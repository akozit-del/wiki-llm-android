package com.wikillm.android.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

interface TokenCallback {
    /**
     * Called for each complete-UTF-8 chunk of generated text, as raw bytes.
     * Native side buffers partial multi-byte chars, so [utf8] always decodes
     * cleanly. Return false to stop generation.
     */
    fun onToken(utf8: ByteArray): Boolean
    /**
     * Called once at the very end with exact token counts and per-phase timing.
     * [prefillMs] = time to process the prompt (prefill), [decodeMs] = time to
     * generate [genTokens] tokens — split so RAG's big-prompt prefill throughput
     * is visible separately from decode speed.
     */
    fun onComplete(promptTokens: Int, genTokens: Int, prefillMs: Long, decodeMs: Long)
}

/** Streamed output of a generation: token pieces followed by a final [Done] with stats. */
sealed interface LlmEvent {
    data class Token(val piece: String) : LlmEvent
    data class Done(
        val promptTokens: Int,
        val genTokens: Int,
        val prefillMs: Long,
        val decodeMs: Long,
    ) : LlmEvent
}

class LlamaContext private constructor(private val handle: Long) : AutoCloseable {

    @Volatile private var closed = false

    /** Multi-turn chat: messages is a list of (role, content) pairs. */
    fun generateChat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512,
        systemPrompt: String,
        temperature: Float,
        noThink: Boolean,
    ): Flow<LlmEvent> =
        channelFlow {
            if (closed) { close(); return@channelFlow }
            val cancelled  = AtomicBoolean(false)
            val promptTok  = AtomicInteger(0)
            val genTok     = AtomicInteger(0)
            val prefillMs  = AtomicLong(0)
            val decodeMs   = AtomicLong(0)
            val cb = object : TokenCallback {
                override fun onToken(utf8: ByteArray): Boolean {
                    val r = trySend(LlmEvent.Token(String(utf8, Charsets.UTF_8)))
                    if (r.isClosed) { cancelled.set(true); return false }
                    return !cancelled.get()
                }
                override fun onComplete(promptTokens: Int, genTokens: Int, prefill: Long, decode: Long) {
                    promptTok.set(promptTokens); genTok.set(genTokens)
                    prefillMs.set(prefill); decodeMs.set(decode)
                }
            }
            val roles    = messages.map { it.first  }.toTypedArray()
            val contents = messages.map { it.second }.toTypedArray()
            withContext(Dispatchers.IO) {
                try {
                    nativeGenerateChat(handle, roles, contents, maxTokens,
                        systemPrompt, temperature, noThink, cb)
                } catch (t: Throwable) { cancelled.set(true); throw t }
            }
            trySend(LlmEvent.Done(promptTok.get(), genTok.get(), prefillMs.get(), decodeMs.get()))
        }.flowOn(Dispatchers.Default).buffer(Channel.UNLIMITED)

    /**
     * Ask a running [generateChat] to stop ASAP. The coroutine's channel-close
     * path alone can't do this — the producer coroutine is blocked inside the
     * native call, so its channel never closes until the call returns. This flips
     * an atomic flag the native loop checks every token (and between prefill
     * batches), breaking that deadlock.
     */
    fun requestStop() {
        if (closed) return
        nativeStopGeneration(handle)
    }

    override fun close() {
        if (closed) return
        closed = true
        nativeFree(handle)
    }

    companion object {
        init {
            System.loadLibrary("llm")
        }

        class LoadException(message: String) : RuntimeException(message)

        // Sprint 20: bump default context from 4096 → 6144. KV q8_0 (Sprint 4)
        // halved KV-cache memory, so 6k tokens fits inside the same 3.7-4.0 GB
        // free RAM on S23. With Sprint 19's wider 6500-char prompt budget for
        // list questions, 4096 was getting truncated; 6144 gives the model
        // enough room for the chain + a full answer. n_ctx is still a hard
        // ceiling — the JNI guard truncates from the head on overshoot.
        // device: 0=auto, 1=NPU/HTP0, 2=GPU/OpenCL, 3=CPU (see GenerationSettings).
        suspend fun load(path: String, nCtx: Int = 6144, device: Int = 0): LlamaContext =
            withContext(Dispatchers.IO) {
                val h = nativeLoad(path, nCtx, device)
                if (h == 0L) {
                    val reason = nativeLastError().ifBlank { "Не удалось загрузить модель" }
                    throw LoadException(reason)
                }
                LlamaContext(h)
            }

        @JvmStatic external fun nativeLoad(path: String, nCtx: Int, device: Int): Long
        @JvmStatic external fun nativeFree(handle: Long)
        @JvmStatic external fun nativeStopGeneration(handle: Long)
        @JvmStatic external fun nativeGenerateChat(
            handle: Long,
            roles: Array<String>,
            contents: Array<String>,
            maxTokens: Int,
            systemPrompt: String,
            temperature: Float,
            noThink: Boolean,
            callback: TokenCallback,
        )
        @JvmStatic external fun nativeLastError(): String

        // Variant 3: embedding model (multilingual-e5-small GGUF). Separate
        // handle configured for mean-pooled embeddings; independent lifecycle
        // from the chat model so both can be resident at once.
        @JvmStatic external fun nativeLoadEmbed(path: String): Long
        @JvmStatic external fun nativeEmbed(handle: Long, text: String): FloatArray?
    }
}

/**
 * Wraps an embedding-mode llama.cpp handle. [embed] returns an L2-normalised
 * vector (so cosine similarity is a plain dot product), or null on failure.
 */
class EmbeddingContext private constructor(private val handle: Long) : AutoCloseable {

    class LoadException(message: String) : RuntimeException(message)

    @Volatile private var closed = false

    suspend fun embed(text: String): FloatArray? =
        withContext(Dispatchers.IO) {
            if (closed) null else LlamaContext.nativeEmbed(handle, text)
        }

    override fun close() {
        if (closed) return
        closed = true
        LlamaContext.nativeFree(handle)
    }

    companion object {
        suspend fun load(path: String): EmbeddingContext =
            withContext(Dispatchers.IO) {
                val h = LlamaContext.nativeLoadEmbed(path)
                if (h == 0L) {
                    val reason = LlamaContext.nativeLastError()
                        .ifBlank { "Не удалось загрузить эмбеддер" }
                    throw LoadException(reason)
                }
                EmbeddingContext(h)
            }
    }
}
