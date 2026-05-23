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
    /** Called for each generated token piece. Return false to stop generation. */
    fun onToken(piece: String): Boolean
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
    fun generateChat(messages: List<Pair<String, String>>, maxTokens: Int = 512): Flow<LlmEvent> =
        channelFlow {
            if (closed) { close(); return@channelFlow }
            val cancelled  = AtomicBoolean(false)
            val promptTok  = AtomicInteger(0)
            val genTok     = AtomicInteger(0)
            val cb = object : TokenCallback {
                override fun onToken(piece: String): Boolean {
                    val r = trySend(LlmEvent.Token(piece))
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
                    nativeGenerateChat(handle, roles, contents, maxTokens, cb)
                } catch (t: Throwable) { cancelled.set(true); throw t }
            }
            trySend(LlmEvent.Done(promptTok.get(), genTok.get()))
        }.flowOn(Dispatchers.Default).buffer(Channel.UNLIMITED)

    /** Single-turn generation (legacy; chat path is preferred). */
    fun generate(prompt: String, maxTokens: Int = 512): Flow<LlmEvent> = channelFlow {
        if (closed) { close(); return@channelFlow }
        val cancelled = AtomicBoolean(false)
        val promptTok = AtomicInteger(0)
        val genTok    = AtomicInteger(0)
        val cb = object : TokenCallback {
            override fun onToken(piece: String): Boolean {
                val r = trySend(LlmEvent.Token(piece))
                if (r.isClosed) { cancelled.set(true); return false }
                return !cancelled.get()
            }
            override fun onComplete(promptTokens: Int, genTokens: Int) {
                promptTok.set(promptTokens); genTok.set(genTokens)
            }
        }
        withContext(Dispatchers.IO) {
            try {
                nativeGenerate(handle, prompt, maxTokens, cb)
            } catch (t: Throwable) {
                cancelled.set(true)
                throw t
            }
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

        suspend fun load(path: String, nCtx: Int = 4096): LlamaContext =
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
        @JvmStatic external fun nativeGenerate(
            handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback
        )
        @JvmStatic external fun nativeGenerateChat(
            handle: Long,
            roles: Array<String>,
            contents: Array<String>,
            maxTokens: Int,
            callback: TokenCallback,
        )
        @JvmStatic external fun nativeLastError(): String
    }
}
