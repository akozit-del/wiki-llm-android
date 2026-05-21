package com.wikillm.android.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

interface TokenCallback {
    fun onToken(piece: String): Boolean
}

class LlamaContext private constructor(private val handle: Long) : AutoCloseable {

    @Volatile private var closed = false

    fun generate(prompt: String, maxTokens: Int = 256): Flow<String> = channelFlow {
        if (closed) {
            close()
            return@channelFlow
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        val cb = object : TokenCallback {
            override fun onToken(piece: String): Boolean {
                val r = trySend(piece)
                if (r.isClosed) {
                    cancelled.set(true)
                    return false
                }
                return !cancelled.get()
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

        suspend fun load(path: String, nCtx: Int = 2048): LlamaContext? =
            withContext(Dispatchers.IO) {
                val h = nativeLoad(path, nCtx)
                if (h == 0L) null else LlamaContext(h)
            }

        @JvmStatic external fun nativeLoad(path: String, nCtx: Int): Long
        @JvmStatic external fun nativeFree(handle: Long)
        @JvmStatic external fun nativeGenerate(
            handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback
        )
    }
}
