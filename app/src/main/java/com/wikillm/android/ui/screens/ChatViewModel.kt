package com.wikillm.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.data.LlmRepository
import com.wikillm.android.data.LocalModel
import com.wikillm.android.data.ModelRepository
import com.wikillm.android.llm.LlmEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.wikillm.android.diag.DiagLog
import com.wikillm.android.rag.QueryExtractor
import com.wikillm.android.rag.RagPromptBuilder
import com.wikillm.android.rag.ZimSearchHolder
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface ModelLoadState {
    data object NotLoaded : ModelLoadState
    data class Loading(val name: String) : ModelLoadState
    data class Loaded(val name: String) : ModelLoadState
    data class Failed(val message: String) : ModelLoadState
}

/** Stats shown under a finished assistant reply. */
data class GenStats(
    val model: String,
    val elapsedMs: Long,
    val genTokens: Int,
    val promptTokens: Int,
) {
    val tokensPerSec: Float get() = if (elapsedMs > 0) genTokens * 1000f / elapsedMs else 0f
}

/** Live progress while the model is thinking/generating. */
data class GenProgress(
    val elapsedMs: Long,
    val tokensSoFar: Int,
    val etaMs: Long?, // null = not enough data yet
)

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val isStreaming: Boolean = false,
    val stats: GenStats? = null,
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

    private val _genProgress = MutableStateFlow<GenProgress?>(null)
    val genProgress: StateFlow<GenProgress?> = _genProgress.asStateFlow()

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

    private fun currentModelName(): String =
        (_loadState.value as? ModelLoadState.Loaded)?.name?.removeSuffix(".gguf") ?: "модель"

    /**
     * Safety net for reasoning models (Qwen3.5 etc.): hide any <think>…</think>
     * block so the user sees only the answer. Native side normally pre-closes
     * thinking, so this is usually a no-op; it also handles a still-open block
     * mid-stream (everything up to a future </think> is treated as thinking).
     */
    private fun stripThinking(text: String): String {
        val closed = THINK_BLOCK.replace(text, "")
        val openIdx = closed.indexOf("<think>")
        val cleaned = if (openIdx >= 0) closed.substring(0, openIdx) else closed
        return cleaned.trimStart('\n', ' ', '\t')
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

        val startMs      = System.currentTimeMillis()
        val firstTokenMs = AtomicLong(0L)
        val tokenCount   = AtomicInteger(0)
        _genProgress.value = GenProgress(0, 0, null)

        // Live ticker: refresh elapsed time + ETA a couple of times a second.
        val ticker = viewModelScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val ft = firstTokenMs.get()
                val n = tokenCount.get()
                val eta: Long? = if (ft > 0 && n > 1) {
                    val genElapsed = now - ft
                    val ratePerMs = if (genElapsed > 0) n.toFloat() / genElapsed else 0f
                    if (ratePerMs > 0f) ((MAX_TOKENS - n).coerceAtLeast(0) / ratePerMs).toLong() else null
                } else null
                _genProgress.value = GenProgress(now - startMs, n, eta)
                delay(400)
            }
        }

        generationJob = viewModelScope.launch {
            val builder = StringBuilder()
            var stats: GenStats? = null
            try {
                val history = buildHistory(previousMessages, userText.trim())
                DiagLog.i(TAG, "Generating: ${history.size} msgs in history, maxTokens=$MAX_TOKENS")
                llmRepo.generateChat(history, maxTokens = MAX_TOKENS).collect { ev ->
                    when (ev) {
                        is LlmEvent.Token -> {
                            firstTokenMs.compareAndSet(0L, System.currentTimeMillis())
                            tokenCount.incrementAndGet()
                            builder.append(ev.piece)
                            val shown = stripThinking(builder.toString())
                            _messages.value = _messages.value.map {
                                if (it.id == assistantId) it.copy(text = shown) else it
                            }
                        }
                        is LlmEvent.Done -> {
                            stats = GenStats(
                                model = currentModelName(),
                                elapsedMs = System.currentTimeMillis() - startMs,
                                genTokens = ev.genTokens,
                                promptTokens = ev.promptTokens,
                            )
                        }
                    }
                }
            } finally {
                ticker.cancel()
                _genProgress.value = null
                val finalText = stripThinking(builder.toString())
                val finalStats = stats ?: GenStats(
                    model = currentModelName(),
                    elapsedMs = System.currentTimeMillis() - startMs,
                    genTokens = tokenCount.get(),
                    promptTokens = 0,
                )
                DiagLog.i(TAG, "Reply (${finalText.length} chars, ${finalStats.genTokens} tok, " +
                        "${finalStats.elapsedMs}ms): ${finalText.take(200).replace('\n', ' ')}")
                _messages.value = _messages.value.map {
                    if (it.id == assistantId) {
                        it.copy(text = finalText, isStreaming = false, stats = finalStats)
                    } else it
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
        result += Pair("user", buildRagContent(currentUserText, previous))
        return result
    }

    private suspend fun buildRagContent(userText: String, previous: List<ChatMessage>): String {
        if (!_ragEnabled.value) return userText
        val searcher = ZimSearchHolder.searcher()
        if (searcher == null) {
            DiagLog.w(TAG, "RAG on, but ZIM not open — sending raw question")
            return userText
        }
        val question = resolveCoreference(userText, previous)
        return runCatching {
            val r = RagPromptBuilder(searcher).build(
                question = question,
                candidates = _ragCandidates.value,
                topK = 5,
                budgetChars = 2000,
            )
            DiagLog.i(TAG, "RAG ctx: ${r.sourcesUsed.size} articles from ${r.totalCandidates} candidates")
            r.prompt
        }.onFailure { DiagLog.e(TAG, "RAG prompt build failed, sending raw question", it) }
            .getOrDefault(userText)
    }

    /**
     * Follow-up questions often refer to the previous topic via pronouns
     * («Какой автозавод *там* расположен?»). The ZIM search can't resolve those,
     * so when the current question is short or pronoun-laden we append the
     * previous user question's keywords to give the search its missing referent.
     */
    private fun resolveCoreference(userText: String, previous: List<ChatMessage>): String {
        val lastUser = previous.lastOrNull { it.role == ChatMessage.Role.USER }?.text ?: return userText
        val tokens = userText.lowercase()
            .replace(Regex("[\\p{Punct}«»“”\"]"), " ")
            .split(Regex("\\s+"))
        val hasPronoun = tokens.any { it in PRONOUNS }
        if (userText.length >= 50 && !hasPronoun) return userText

        val topic = QueryExtractor.extract(lastUser)
        if (topic.isBlank()) return userText
        // Drop topic words already present in the current question to avoid noise.
        val present = tokens.toSet()
        val added = topic.split(" ").filter { it.isNotBlank() && it.lowercase() !in present }
        if (added.isEmpty()) return userText

        val augmented = "$userText ${added.joinToString(" ")}"
        DiagLog.i(TAG, "Coreference: '$userText' + topic '${added.joinToString(" ")}'")
        return augmented
    }

    fun stop() { generationJob?.cancel() }
    fun clear() { generationJob?.cancel(); _messages.value = emptyList() }

    companion object {
        private const val TAG = "ChatVM"
        private const val MAX_TOKENS = 512

        /** Matches a complete <think>…</think> block (DOTALL). */
        private val THINK_BLOCK = Regex("(?s)<think>.*?</think>")

        /** Russian pronouns/locatives that signal the question refers to a prior topic. */
        private val PRONOUNS = setOf(
            "там", "тут", "здесь",
            "он", "она", "оно", "они",
            "его", "её", "ее", "их", "ему", "ей", "им",
            "этот", "эта", "это", "эти", "тот", "та", "те",
            "который", "которая", "которое", "которые",
        )
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        llmRepo.unload()
    }
}
