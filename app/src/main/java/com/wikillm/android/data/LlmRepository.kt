package com.wikillm.android.data

import com.wikillm.android.llm.LlamaContext
import com.wikillm.android.llm.LlmEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

class LlmRepository {

    data class LoadedModel(val filePath: String, val displayName: String)

    private val _loaded = MutableStateFlow<LoadedModel?>(null)
    val loaded: StateFlow<LoadedModel?> = _loaded.asStateFlow()

    private var current: LlamaContext? = null
    private val mutex = Object()

    suspend fun load(file: File): Result<Unit> {
        unload()
        return try {
            val ctx = LlamaContext.load(file.absolutePath)
            synchronized(mutex) {
                current = ctx
                _loaded.value = LoadedModel(file.absolutePath, file.name)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun unload() {
        synchronized(mutex) {
            current?.close()
            current = null
            _loaded.value = null
        }
    }

    fun generateChat(
        messages: List<Pair<String, String>>,
        maxTokens: Int = 512,
        systemPrompt: String,
        temperature: Float,
        noThink: Boolean,
    ): Flow<LlmEvent> {
        val ctx = synchronized(mutex) { current } ?: return emptyFlow()
        return ctx.generateChat(messages, maxTokens, systemPrompt, temperature, noThink)
    }

    fun isLoaded(): Boolean = synchronized(mutex) { current != null }
}
