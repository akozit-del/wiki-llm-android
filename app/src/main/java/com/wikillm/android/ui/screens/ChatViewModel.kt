package com.wikillm.android.ui.screens

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.data.ChatHistoryStore
import com.wikillm.android.data.Conversation
import com.wikillm.android.data.LlmRepository
import com.wikillm.android.data.LocalModel
import com.wikillm.android.data.ModelRepository
import com.wikillm.android.data.StoredMessage
import com.wikillm.android.llm.LlmEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.wikillm.android.diag.DiagLog
import com.wikillm.android.rag.QueryExtractor
import com.wikillm.android.rag.RagPromptBuilder
import com.wikillm.android.rag.ZimSearchHolder
import com.wikillm.android.settings.GenerationSettings
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
    private val genSettings = GenerationSettings(app.applicationContext)
    private val historyStore = ChatHistoryStore(app.applicationContext)
    private val prefs = app.getSharedPreferences("wikillm_chat", Context.MODE_PRIVATE)
    private val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** Free device RAM in bytes, refreshed periodically for the top bar. */
    private val _freeMemBytes = MutableStateFlow(0L)
    val freeMemBytes: StateFlow<Long> = _freeMemBytes.asStateFlow()

    /** Saved conversations (OpenWebUI-style history), newest first. */
    val conversations: StateFlow<List<Conversation>> = historyStore.conversations

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

    // Agentic multi-hop RAG: the model issues follow-up searches itself.
    private val _deepSearch = MutableStateFlow(false)
    val deepSearch: StateFlow<Boolean> = _deepSearch.asStateFlow()

    // Live "🔎 ищу …" status shown during agentic hops (null = not searching).
    private val _searchStep = MutableStateFlow<String?>(null)
    val searchStep: StateFlow<String?> = _searchStep.asStateFlow()

    val zimState: StateFlow<ZimSearchHolder.State> = ZimSearchHolder.state

    private var generationJob: Job? = null
    private var nextMessageId = 0L
    private var currentConvId = System.currentTimeMillis()

    init {
        modelRepo.refreshLocal()
        // ZIM is opened lazily only when RAG is switched on (see setRagEnabled),
        // so its ~14 GB mmap doesn't compete with the model when RAG is off.
        autoLoadLastModel()
        viewModelScope.launch {
            while (isActive) {
                _freeMemBytes.value = readFreeMem()
                delay(2000)
            }
        }
    }

    private fun readFreeMem(): Long {
        val mi = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mi)
        return mi.availMem
    }

    /** Re-load the model used last session so the user doesn't pick it every launch. */
    private fun autoLoadLastModel() {
        val path = prefs.getString(KEY_LAST_MODEL, null) ?: return
        val model = modelRepo.local.value.firstOrNull { it.file.absolutePath == path } ?: return
        DiagLog.i(TAG, "Auto-loading last model: ${model.fileName}")
        loadModel(model)
    }

    fun setRagEnabled(v: Boolean) {
        _ragEnabled.value = v
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            if (v) runCatching { ZimSearchHolder.ensureOpen(ctx) }
            else ZimSearchHolder.closeAll() // free the ZIM mmap when RAG is off
        }
    }
    fun setRagCandidates(v: Int) { _ragCandidates.value = v }
    fun setDeepSearch(v: Boolean) { _deepSearch.value = v }

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
                    prefs.edit().putString(KEY_LAST_MODEL, model.file.absolutePath).apply()
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
        // Fast path: no thinking marker (the usual case) — avoid the regex entirely.
        if (!text.contains("<think>")) return text
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

        val convId       = currentConvId
        val maxTokens    = genSettings.currentMaxTokens()
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
                    if (ratePerMs > 0f) ((maxTokens - n).coerceAtLeast(0) / ratePerMs).toLong() else null
                } else null
                _genProgress.value = GenProgress(now - startMs, n, eta)
                delay(400)
            }
        }

        val agentic = _ragEnabled.value && _deepSearch.value && ZimSearchHolder.searcher() != null

        generationJob = viewModelScope.launch {
            val builder = StringBuilder()
            var stats: GenStats? = null
            val temp = genSettings.currentTemperature()
            val noThink = genSettings.currentNoThink()
            try {
                if (agentic) {
                    val res = runAgentic(
                        question = userText.trim(),
                        previous = previousMessages,
                        temp = temp, noThink = noThink, maxTokens = maxTokens,
                        onFirstToken = { firstTokenMs.compareAndSet(0L, System.currentTimeMillis()) },
                        onToken = { tokenCount.incrementAndGet() },
                    )
                    builder.append(res.text)
                    stats = GenStats(currentModelName(), System.currentTimeMillis() - startMs, res.genTokens, res.promptTokens)
                } else {
                    val history = buildHistory(previousMessages, userText.trim())
                    val sysPrompt = genSettings.effectiveSystemPrompt()
                    DiagLog.i(TAG, "Generating: ${history.size} msgs, maxTokens=$maxTokens, temp=$temp, noThink=$noThink")
                    llmRepo.generateChat(
                        history, maxTokens = maxTokens,
                        systemPrompt = sysPrompt, temperature = temp, noThink = noThink,
                    ).collect { ev ->
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
                }
            } finally {
                ticker.cancel()
                _searchStep.value = null
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
                persistConversation(convId)
            }
        }
    }

    /** Save the current (non-streaming) messages as a conversation under [convId]. */
    private fun persistConversation(convId: Long) {
        val msgs = _messages.value.filter { !it.isStreaming && it.text.isNotBlank() }
        if (msgs.isEmpty()) return
        val title = msgs.firstOrNull { it.role == ChatMessage.Role.USER }?.text
            ?.replace('\n', ' ')?.trim()?.take(50)?.ifBlank { null } ?: "Чат"
        val stored = msgs.map {
            StoredMessage(
                role = if (it.role == ChatMessage.Role.USER) "user" else "assistant",
                text = it.text,
            )
        }
        historyStore.upsert(Conversation(convId, title, System.currentTimeMillis(), stored))
    }

    /** Open a saved conversation, replacing the current messages. */
    fun openConversation(id: Long) {
        generationJob?.cancel()
        val conv = historyStore.get(id) ?: return
        currentConvId = id
        var idc = nextMessageId
        _messages.value = conv.messages.map { sm ->
            ChatMessage(
                id = idc++,
                role = if (sm.role == "user") ChatMessage.Role.USER else ChatMessage.Role.ASSISTANT,
                text = sm.text,
            )
        }
        nextMessageId = idc
    }

    fun deleteConversation(id: Long) {
        historyStore.delete(id)
        if (id == currentConvId) clear()
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
        // ZIM is opened lazily when RAG turns on; make sure it's ready before the first query.
        if (ZimSearchHolder.searcher() == null) {
            runCatching { ZimSearchHolder.ensureOpen(getApplication<Application>().applicationContext) }
        }
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
                topK = 4,
                budgetChars = 4500,
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
        // Only borrow the previous topic for genuinely context-dependent follow-ups:
        // a pronoun/locative, or a very short question. Self-contained questions
        // (like "Перечисли всех мэров города…") must not be polluted.
        if (userText.length >= 25 && !hasPronoun) return userText

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

    private data class AgenticResult(val text: String, val genTokens: Int, val promptTokens: Int)

    /**
     * Agentic multi-hop RAG: the model gathers facts, and when they're not enough
     * it emits a `ПОИСК: <query>` directive; we run that search, append the new
     * excerpts and ask again — up to MAX_HOPS. Lets it follow chains (mayor →
     * predecessor → …) for any question, without hardcoded logic.
     */
    private suspend fun runAgentic(
        question: String,
        previous: List<ChatMessage>,
        temp: Float,
        noThink: Boolean,
        maxTokens: Int,
        onFirstToken: () -> Unit,
        onToken: () -> Unit,
    ): AgenticResult {
        val searcher = ZimSearchHolder.searcher() ?: return AgenticResult("ZIM не открыт.", 0, 0)
        val rag = RagPromptBuilder(searcher)
        val resolved = resolveCoreference(question, previous)
        var context = rag.searchExcerpts(resolved, _ragCandidates.value, topK = 4, budgetChars = 4500).block
        var totalGen = 0
        var totalPrompt = 0
        var lastText = ""

        for (hop in 1..MAX_HOPS) {
            val isLast = hop == MAX_HOPS
            val out = StringBuilder()
            llmRepo.generateChat(
                listOf("user" to buildAgenticPrompt(question, context, isLast)),
                maxTokens = maxTokens,
                systemPrompt = AGENTIC_SYSTEM,
                temperature = temp,
                noThink = noThink,
            ).collect { ev ->
                when (ev) {
                    is LlmEvent.Token -> { onFirstToken(); onToken(); out.append(ev.piece) }
                    is LlmEvent.Done -> { totalGen += ev.genTokens; totalPrompt += ev.promptTokens }
                }
            }
            val text = stripThinking(out.toString()).trim()
            lastText = text
            val query = if (isLast) null else parseSearchDirective(text)
            if (query == null) {
                return AgenticResult(text.ifBlank { "не знаю по приведённым выдержкам" }, totalGen, totalPrompt)
            }
            _searchStep.value = "🔎 Шаг $hop: ищу «$query»"
            DiagLog.i(TAG, "Agentic hop $hop → search '$query'")
            val more = rag.searchExcerpts(query, _ragCandidates.value, topK = 3, budgetChars = 2500).block
            context += "\n\n--- Доп. поиск «$query» ---\n" + (more.ifBlank { "(ничего не найдено)" })
        }
        return AgenticResult(lastText.ifBlank { "не знаю по приведённым выдержкам" }, totalGen, totalPrompt)
    }

    private fun buildAgenticPrompt(question: String, context: String, isLast: Boolean): String = buildString {
        append("Вопрос: ").append(question).append("\n\n")
        append("=== СОБРАННЫЕ ВЫДЕРЖКИ ИЗ ВИКИПЕДИИ ===\n").append(context).append("\n=== КОНЕЦ ===\n\n")
        if (isLast) {
            append("Дай финальный ответ на русском (Markdown) строго по выдержкам. Новых поисков больше нет.")
        } else {
            append("Если фактов достаточно — дай полный ответ. Если нет — выдай РОВНО одну строку «ПОИСК: <запрос>».")
        }
    }

    /** Returns the search query if the model's reply is a `ПОИСК:` directive. */
    private fun parseSearchDirective(text: String): String? {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (!first.startsWith("ПОИСК:", ignoreCase = true)) return null
        return first.substringAfter(":").trim().trim('«', '»', '"').take(80).ifBlank { null }
    }

    fun stop() { generationJob?.cancel() }

    /** Start a fresh chat. The previous one is already saved after each reply. */
    fun clear() {
        generationJob?.cancel()
        _messages.value = emptyList()
        currentConvId = System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "ChatVM"
        private const val KEY_LAST_MODEL = "last_model_path"
        private const val MAX_HOPS = 3 // agentic search depth

        private const val AGENTIC_SYSTEM =
            "Ты — ассистент с доступом к офлайн-поиску по Википедии. Тебе дают собранные выдержки. " +
            "Если для полного ответа не хватает фактов, ответь РОВНО одной строкой и больше ничего: " +
            "«ПОИСК: <короткий запрос>». Когда фактов достаточно — дай полный ответ на русском в Markdown без слова ПОИСК. " +
            "Чтобы собрать перечень за период, иди по цепочке: найди текущего, затем его предшественника, и так далее."

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
