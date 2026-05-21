package com.wikillm.android.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Build 11: native llama.cpp engine временно отключён, чтобы остальной UI собрался.
 * Чат-движок будет включён обратно в следующем билде, после починки JNI-моста.
 */
class LlmRepository {

    data class LoadedModel(val filePath: String, val displayName: String)

    private val _loaded = MutableStateFlow<LoadedModel?>(null)
    val loaded: StateFlow<LoadedModel?> = _loaded.asStateFlow()

    suspend fun load(file: File): Result<Unit> {
        return Result.failure(
            UnsupportedOperationException(
                "Чат-движок отключён в этой сборке. Жди build-12."
            )
        )
    }

    fun unload() {
        _loaded.value = null
    }

    fun generate(prompt: String, maxTokens: Int = 256): Flow<String> = emptyFlow()

    fun isLoaded(): Boolean = false
}
