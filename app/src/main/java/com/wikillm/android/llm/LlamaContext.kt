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

interface TokenCallback {
    /**
     * Called for each complete-UTF-8 chunk of generated text, as raw bytes.
     * Native side buffers partial multi-byte chars, so [utf8] always decodes
     * cleanly. Return false to stop generation.
     */
    fun onToken(utf8: ByteArray): Boolean
    /** Called once at the very end with exact token counts. */
    fun onComplete(promptTokens: Int, genTokens: Int)
}

/** Streamed output of a generation: token pieces followed by a final [Done] with stats. */
sealed interface LlmEvent {
    data class Token(val piece: String) : LlmEvent
    data class Done(val promptTokens: Int, val genTokens: Int) : LlmEvent
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
            val cb = object : TokenCallback {
                override fun onToken(utf8: ByteArray): Boolean {
                    val r = trySend(LlmEvent.Token(String(utf8, Charsets.UTF_8)))
                    if (r.isClosed) { cancelled.set(true); return false }
                    return !cancelled.get()
                }
                override fun onComplete(promptTokens: Int, genTokens: Int) {
                    promptTok.set(promptTokens); genTok.set(genTokens)
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
            trySend(LlmEvent.Done(promptTok.get(), genTok.get()))
        }.flowOn(Dispatchers.Default).buffer(Channel.UNLIMITED)

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
        suspend fun load(path: String, nCtx: Int = 6144): LlamaContext =
            withContext(Dispatchers.IO) {
                val h = nativeLoad(path, nCtx)
                if (h == 0L) {
                    val reason = nativeLastError().ifBlank { "Не удалось загрузить модель" }
                    throw LoadException(reason)
                }
                LlamaContext(h)
            }

        @JvmStatic external fun nativeLoad(path: String, nCtx: Int): Long
        @JvmStatic external fun nativeFree(handle: Long)
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
    }
}
