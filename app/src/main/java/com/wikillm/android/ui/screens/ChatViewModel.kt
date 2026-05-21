package com.wikillm.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.data.LlmRepository
import com.wikillm.android.data.LocalModel
import com.wikillm.android.data.ModelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ModelLoadState {
    data object NotLoaded : ModelLoadState
    data class Loading(val name: String) : ModelLoadState
    data class Loaded(val name: String) : ModelLoadState
    data class Failed(val message: String) : ModelLoadState
}

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val isStreaming: Boolean = false,
) {
    enum class Role { USER, ASSISTANT }
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val modelRepo = ModelRepository(app.applicationContext)
    private val llmRepo = LlmRepository()

    val downloadedModels: StateFlow<List<LocalModel>> = modelRepo.local

    private val _loadState = MutableStateFlow<ModelLoadState>(ModelLoadState.NotLoaded)
    val loadState: StateFlow<ModelLoadState> = _loadState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private var generationJob: Job? = null
    private var nextMessageId = 0L

    init { modelRepo.refreshLocal() }

    fun refreshModels() = modelRepo.refreshLocal()

    fun loadModel(model: LocalModel) {
        if (_loadState.value is ModelLoadState.Loading) return
        viewModelScope.launch {
            _loadState.value = ModelLoadState.Loading(model.fileName)
            val r = llmRepo.load(model.file)
            _loadState.value = r.fold(
                onSuccess = { ModelLoadState.Loaded(model.fileName) },
                onFailure = { ModelLoadState.Failed(it.message ?: "Ошибка загрузки модели") },
            )
        }
    }

    fun unloadModel() {
        generationJob?.cancel()
        llmRepo.unload()
        _loadState.value = ModelLoadState.NotLoaded
    }

    fun send(userText: String) {
        if (userText.isBlank() || !llmRepo.isLoaded() || _generating.value) return

        val userMsg = ChatMessage(nextMessageId++, ChatMessage.Role.USER, userText.trim())
        val assistantId = nextMessageId++
        val assistantMsg = ChatMessage(assistantId, ChatMessage.Role.ASSISTANT, "", isStreaming = true)
        _messages.value = _messages.value + userMsg + assistantMsg

        _generating.value = true
        generationJob = viewModelScope.launch {
            val builder = StringBuilder()
            try {
                llmRepo.generate(userMsg.text, maxTokens = 256).collect { piece ->
                    builder.append(piece)
                    _messages.value = _messages.value.map {
                        if (it.id == assistantId) it.copy(text = builder.toString()) else it
                    }
                }
            } finally {
                _messages.value = _messages.value.map {
                    if (it.id == assistantId) it.copy(text = builder.toString(), isStreaming = false) else it
                }
                _generating.value = false
            }
        }
    }

    fun stop() { generationJob?.cancel() }
    fun clear() { generationJob?.cancel(); _messages.value = emptyList() }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        llmRepo.unload()
    }
}
