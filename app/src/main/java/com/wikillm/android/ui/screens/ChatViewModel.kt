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
import com.wikillm.android.diag.DiagLog
import com.wikillm.android.rag.RagPromptBuilder
import com.wikillm.android.rag.ZimSearchHolder
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

    private val _ragEnabled = MutableStateFlow(false)
    val ragEnabled: StateFlow<Boolean> = _ragEnabled.asStateFlow()

    private val _ragCandidates = MutableStateFlow(20)
    val ragCandidates: StateFlow<Int> = _ragCandidates.asStateFlow()

    val zimState: StateFlow<ZimSearchHolder.State> = ZimSearchHolder.state

    private var generationJob: Job? = null
    private var nextMessageId = 0L

    init {
        modelRepo.refreshLocal()
        viewModelScope.launch {
            runCatching { ZimSearchHolder.ensureOpen(getApplication<Application>().applicationContext) }
        }
    }

    fun setRagEnabled(v: Boolean) { _ragEnabled.value = v }
    fun setRagCandidates(v: Int) { _ragCandidates.value = v }

    fun refreshModels() = modelRepo.refreshLocal()

    fun loadModel(model: LocalModel) {
        if (_loadState.value is ModelLoadState.Loading) return
        viewModelScope.launch {
            _loadState.value = ModelLoadState.Loading(model.fileName)
            DiagLog.i(TAG, "Loading model: ${model.fileName} (${model.file.absolutePath})")
            val r = llmRepo.load(model.file)
            _loadState.value = r.fold(
                onSuccess = {
                    DiagLog.i(TAG, "Model loaded: ${model.fileName}")
                    ModelLoadState.Loaded(model.fileName)
                },
                onFailure = {
                    DiagLog.e(TAG, "Model load failed: ${model.fileName}", it)
                    val msg = when {
                        it.message?.contains("unknown model architecture") == true -> {
                            val arch = Regex("unknown model architecture: '([^']+)'")
                                .find(it.message!!)?.groupValues?.get(1) ?: "?"
                            "Архитектура '$arch' не поддерживается. Нужна новая версия llama.cpp."
                        }
                        else -> it.message?.substringBefore('\n') ?: "Ошибка загрузки модели"
                    }
                    ModelLoadState.Failed(msg)
                },
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

        // Capture completed history BEFORE adding the new exchange.
        val previousMessages = _messages.value.filter { !it.isStreaming }

        val userMsg    = ChatMessage(nextMessageId++, ChatMessage.Role.USER,      userText.trim())
        val assistantId = nextMessageId++
        val assistantMsg = ChatMessage(assistantId, ChatMessage.Role.ASSISTANT, "", isStreaming = true)
        _messages.value = previousMessages + userMsg + assistantMsg

        _generating.value = true
        generationJob = viewModelScope.launch {
            val builder = StringBuilder()
            try {
                val history = buildHistory(previousMessages, userText.trim())
                DiagLog.i(TAG, "Generating: ${history.size} msgs in history, maxTokens=512")
                llmRepo.generateChat(history, maxTokens = 512).collect { piece ->
                    builder.append(piece)
                    _messages.value = _messages.value.map {
                        if (it.id == assistantId) it.copy(text = builder.toString()) else it
                    }
                }
            } finally {
                val finalText = builder.toString()
                DiagLog.i(TAG, "Reply (${finalText.length} chars): ${finalText.take(200).replace('\n', ' ')}")
                _messages.value = _messages.value.map {
                    if (it.id == assistantId) it.copy(text = finalText, isStreaming = false) else it
                }
                _generating.value = false
            }
        }
    }

    /** Builds the full message list to pass to the model: previous turns + current user (with RAG). */
    private suspend fun buildHistory(
        previous: List<ChatMessage>,
        currentUserText: String,
    ): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (msg in previous) {
            if (msg.text.isNotBlank()) {
                result += Pair(
                    if (msg.role == ChatMessage.Role.USER) "user" else "assistant",
                    msg.text,
                )
            }
        }
        result += Pair("user", buildRagContent(currentUserText))
        return result
    }

    private suspend fun buildRagContent(userText: String): String {
        if (!_ragEnabled.value) return userText
        val searcher = ZimSearchHolder.searcher()
        if (searcher == null) {
            DiagLog.w(TAG, "RAG on, but ZIM not open — sending raw question")
            return userText
        }
        return runCatching {
            val r = RagPromptBuilder(searcher).build(
                question = userText,
                candidates = _ragCandidates.value,
                topK = 5,
                budgetChars = 2000,
            )
            DiagLog.i(TAG, "RAG ctx: ${r.sourcesUsed.size} articles from ${r.totalCandidates} candidates")
            r.prompt
        }.onFailure { DiagLog.e(TAG, "RAG prompt build failed, sending raw question", it) }
            .getOrDefault(userText)
    }

    fun stop() { generationJob?.cancel() }
    fun clear() { generationJob?.cancel(); _messages.value = emptyList() }

    companion object { private const val TAG = "ChatVM" }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        llmRepo.unload()
    }
}
