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
import com.wikillm.android.rag.ListExtractor
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

        // Sprint 3: list-intent already gets a deterministic chain-walker via
        // Sprint 2's InfoboxGraphWalker at single-shot time, so the agentic
        // loop on top of it just burns wall-time re-asking for the same data
        // (documented 4B failure mode — "collapse to user query paraphrase").
        // Skip agentic for list-intent regardless of the Deep Search toggle.
        val listIntent = QueryExtractor.isListIntent(userText)
        // build-94: list questions go through the L3X map-extract pipeline
        // (one short extraction call per candidate biography + deterministic
        // merge) instead of one giant single-shot prompt the 4B can't handle.
        val listExtraction = listIntent &&
            _ragEnabled.value && ZimSearchHolder.searcher() != null
        val agentic = !listIntent &&
            _ragEnabled.value && _deepSearch.value && ZimSearchHolder.searcher() != null

        generationJob = viewModelScope.launch {
            val builder = StringBuilder()
            var stats: GenStats? = null
            val temp = genSettings.currentTemperature()
            val noThink = genSettings.currentNoThink()
            try {
                if (listExtraction) {
                    val res = runListExtraction(
                        question = userText.trim(),
                        onToken = { tokenCount.incrementAndGet() },
                        onFirstToken = { firstTokenMs.compareAndSet(0L, System.currentTimeMillis()) },
                    )
                    builder.append(res.text)
                    _messages.value = _messages.value.map {
                        if (it.id == assistantId) it.copy(text = res.text) else it
                    }
                    stats = GenStats(currentModelName(), System.currentTimeMillis() - startMs, res.genTokens, res.promptTokens)
                } else if (agentic) {
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
     * build-94 — L3X map-extract pipeline for "перечисли всех X" questions.
     * Retrieves candidate biographies (walker + title probes), runs one short
     * extraction call per biography, then merges deterministically in Kotlin.
     * No final LLM synthesis call yet (build-96 may add one) — we format the
     * merged list directly so the 4B can't re-drop names during synthesis.
     */
    private suspend fun runListExtraction(
        question: String,
        onFirstToken: () -> Unit,
        onToken: () -> Unit,
    ): AgenticResult {
        val searcher = ZimSearchHolder.searcher()
            ?: return AgenticResult("ZIM не открыт.", 0, 0)
        val rag = RagPromptBuilder(searcher)
        _searchStep.value = "🔎 Ищу кандидатов…"
        val docs = rag.searchExcerptDocs(
            question = question,
            candidates = _ragCandidates.value,
            topK = 10,
            perDocChars = 1400,
        )
        if (docs.isEmpty()) {
            return AgenticResult("не знаю по приведённым выдержкам", 0, 0)
        }
        var genTokens = 0
        val extractor = ListExtractor { messages, maxTokens, systemPrompt ->
            // Low temp for deterministic extraction; thinking off.
            llmRepo.generateChat(
                messages, maxTokens = maxTokens,
                systemPrompt = systemPrompt, temperature = 0.3f, noThink = true,
            )
        }
        val items = extractor.extract(question, docs) { done, total ->
            _searchStep.value = "📑 Извлекаю $done/$total"
            onFirstToken()
            onToken()
            genTokens += 1
        }
        _searchStep.value = null
        if (items.isEmpty()) {
            return AgenticResult("не знаю по приведённым выдержкам", genTokens, 0)
        }
        val md = buildString {
            items.forEach { append("- **").append(it.name).append("** — ").append(it.years).append("\n") }
        }.trimEnd()
        return AgenticResult(md, genTokens, 0)
    }

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

        // Track what we've already pulled/searched so hops add new info, not loops.
        val usedTitles = LinkedHashSet<String>()
        val triedQueries = HashSet<String>()
        var totalGen = 0
        var totalPrompt = 0
        var lastText = ""

        val seedEx = rag.searchExcerpts(resolved, _ragCandidates.value, topK = 4, budgetChars = 3500)
        usedTitles += seedEx.titles
        triedQueries += normalizeQuery(resolved)
        val seed = seedEx.block
        var trail = "" // excerpts gathered across follow-up hops

        // Keep the seed article plus the most recent hops, bounded to fit nCtx.
        fun context(): String {
            val full = seed + trail
            if (full.length <= AGENTIC_CTX_CAP) return full
            val room = (AGENTIC_CTX_CAP - seed.length).coerceAtLeast(800)
            return seed + "\n…\n" + trail.takeLast(room)
        }

        // For enumeration questions the 4B model hallucinates predecessor names if
        // asked to emit each search query itself (verified on device: it parroted
        // the system-prompt placeholder and invented Mельников/Волков). So we walk
        // the «Предшественник» chain in code and only ask the LLM to format the
        // final list — a task it can't get wrong.
        val holder = OFFICE_HOLDER.find(seed)?.groupValues?.get(1)?.trim()
        val wantsList = LIST_WORDS.any { question.lowercase().contains(it) }
        if (wantsList && !holder.isNullOrBlank()) {
            val chain = walkPredecessorChain(searcher, holder, _ragCandidates.value)
            DiagLog.i(TAG, "Predecessor chain (${chain.size}): " + chain.joinToString(" -> "))
            val factual = chain.joinToString("\n") { "- $it" }
            val formatPrompt =
                "Дан фактологический перечень людей (от текущего к более ранним), занимавших одну и ту же " +
                "должность. Просто перепиши его как аккуратный Markdown-список (маркированный), сохранив порядок. " +
                "НЕ добавляй своих имён, НЕ выдумывай, НЕ комментируй, НЕ переставляй. Только переоформи.\n\n" +
                "Перечень:\n$factual\n\nГотовый ответ:"
            val out = StringBuilder()
            llmRepo.generateChat(
                listOf("user" to formatPrompt),
                maxTokens = maxTokens,
                systemPrompt = "Ты аккуратно форматируешь данный перечень в Markdown. Никаких дополнений или выдумок.",
                temperature = temp,
                noThink = noThink,
            ).collect { ev ->
                when (ev) {
                    is LlmEvent.Token -> { onFirstToken(); onToken(); out.append(ev.piece) }
                    is LlmEvent.Done -> { totalGen += ev.genTokens; totalPrompt += ev.promptTokens }
                }
            }
            val text = stripThinking(out.toString()).trim()
            // If the model misbehaves, fall back to the deterministic list.
            return AgenticResult(text.ifBlank { factual }, totalGen, totalPrompt)
        }

        for (hop in 1..MAX_HOPS) {
            val isLast = hop == MAX_HOPS
            // Intermediate hops only emit a short «ПОИСК: …» directive — cap their
            // tokens so each chain step is fast; the final answer gets full budget.
            val hopTokens = if (isLast) maxTokens else minOf(maxTokens, 96)
            val out = StringBuilder()
            llmRepo.generateChat(
                listOf("user" to buildAgenticPrompt(question, context(), isLast)),
                maxTokens = hopTokens,
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
                // Final answer — drop any stray ПОИСК directive lines (incl. markdown-wrapped).
                val answer = text.lineSequence()
                    .filterNot { isSearchDirectiveLine(it) }
                    .joinToString("\n").trim()
                return AgenticResult(answer.ifBlank { "не знаю по приведённым выдержкам" }, totalGen, totalPrompt)
            }
            if (normalizeQuery(query) in triedQueries) {
                // The model is repeating itself — nudge it to answer instead of looping.
                DiagLog.i(TAG, "Agentic hop $hop → repeat query '$query', skipping")
                trail += "\n\n(Запрос «$query» уже выполнялся. Если данных достаточно — дай финальный ответ.)"
                continue
            }
            triedQueries += normalizeQuery(query)
            _searchStep.value = "🔎 Шаг $hop: ищу «$query»"
            DiagLog.i(TAG, "Agentic hop $hop → search '$query'")
            val more = rag.searchExcerpts(
                query, _ragCandidates.value, topK = 2, budgetChars = 1800, excludeTitles = usedTitles,
            )
            usedTitles += more.titles
            trail += if (more.block.isBlank()) "\n\n--- Доп. поиск «$query»: ничего нового ---"
            else "\n\n--- Доп. поиск «$query» ---\n" + more.block
        }
        return AgenticResult(lastText.ifBlank { "не знаю по приведённым выдержкам" }, totalGen, totalPrompt)
    }

    /** Normalize a search query for dedup (lowercase, strip punctuation/spaces). */
    private fun normalizeQuery(q: String): String =
        q.lowercase().replace(Regex("[\\p{Punct}«»\"]"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Walk the «Предшественник» chain in code, starting from [holder]:
     *   search → first matching person article → read its card → take
     *   «Предшественник» → search → … until empty / duplicate / MAX_HOPS.
     *
     * This is the deterministic core of layer C — the small LLM only gets the
     * resulting list to format, so it can't invent predecessor names.
     */
    private suspend fun walkPredecessorChain(
        searcher: com.wikillm.android.rag.ZimSearcher,
        holder: String,
        candidates: Int,
    ): List<String> {
        val chain = mutableListOf(holder)
        val visited = hashSetOf(normalizeQuery(holder))
        var current = holder
        for (step in 1..MAX_HOPS) {
            _searchStep.value = "🔎 Цепочка: «$current»"
            val hits = searcher.search(current, candidates)
            val hit = pickBestNameHit(hits, current) ?: break
            DiagLog.i(TAG, "Chain step $step: '$current' → '${hit.title}'")
            val html = searcher.readArticleHtml(hit.path) ?: break
            val card = com.wikillm.android.rag.InfoboxExtractor.extract(html, hit.title)
            val pred = card.field("Предшественник") ?: break
            val next = pred.substringBefore("(").substringBefore("[").trim()
            if (next.isBlank()) break
            val key = normalizeQuery(next)
            if (key in visited) break
            visited += key
            chain += next
            current = next
        }
        return chain
    }

    /** Pick the hit whose title shares the most tokens with [name] (handles
     *  "Сухих, Илья Геннадьевич" vs "Илья Геннадьевич Сухих"). */
    private fun pickBestNameHit(
        hits: List<com.wikillm.android.rag.ZimSearcher.Hit>,
        name: String,
    ): com.wikillm.android.rag.ZimSearcher.Hit? {
        if (hits.isEmpty()) return null
        val tokens = name.lowercase().split(Regex("[\\s,.]+")).filter { it.length >= 3 }.toSet()
        if (tokens.isEmpty()) return hits.first()
        return hits.maxByOrNull { hit ->
            val titleTokens = hit.title.lowercase().split(Regex("[\\s,.]+")).toSet()
            tokens.count { it in titleTokens }
        }
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

    /** Returns the search query if the reply contains a `ПОИСК: …` directive on any
     *  line, tolerant of markdown the model wraps it in ("**ПОИСК:**", "ПОИСК — …"). */
    private fun parseSearchDirective(text: String): String? {
        for (raw in text.lineSequence()) {
            if (!isSearchDirectiveLine(raw)) continue
            val line = raw.trim().trim('*', '#', '>', '`', ' ', '"')
            val q = SEARCH_DIRECTIVE.find(line)?.groupValues?.get(1)
                ?.trim()?.trim('«', '»', '"', '*', '`', '_')?.take(80)
            if (!q.isNullOrBlank()) return q
        }
        return null
    }

    /** A line that is (after stripping markdown) a `ПОИСК:` directive. */
    private fun isSearchDirectiveLine(raw: String): Boolean {
        val line = raw.trim().trim('*', '#', '>', '`', ' ', '"')
        return line.lowercase().startsWith("поиск") && SEARCH_DIRECTIVE.containsMatchIn(line)
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
        // Sprint 3: dropped from 5. SOTA agentic-RAG papers for ≤8B models
        // (PRISM 2510.14278, Search-o1 EMNLP-2025) converge on a 3-hop ceiling —
        // beyond that, 4B models start paraphrasing the user query instead of
        // emitting novel sub-questions, and wall-time gets dominated by retries.
        private const val MAX_HOPS = 3

        private const val AGENTIC_CTX_CAP = 4000 // chars of accumulated context (keep within nCtx)

        private const val AGENTIC_SYSTEM =
            "Ты — ассистент с доступом к офлайн-поиску по Википедии. Тебе дают собранные выдержки с карточками статей. " +
            "Чтобы собрать перечень должностных лиц за период, НЕ ищи «список …» — такой статьи нет. " +
            "Вместо этого иди по цепочке через карточки: возьми имя человека, в его персональной статье найди поле " +
            "«Предшественник», затем тем же приёмом найди его предшественника, и так далее вглубь. " +
            "Если нужно дочитать что-то, ответь РОВНО одной строкой и больше ничего: «ПОИСК: <запрос>» " +
            "(имя человека или точное название статьи, без лишних слов и без markdown). Не повторяй уже выполненные запросы. " +
            "Когда цепочка собрана — дай финальный ответ на русском в Markdown маркированным списком, без слова ПОИСК."

        /** Pulls the current office-holder's name out of a seed card line. */
        private val OFFICE_HOLDER =
            Regex("(?im)^(?:Глава/мэр|Мэр|Президент|Губернатор|Глава)\\s*:\\s*(.+)$")

        /** Question asks to enumerate (so we jump-start the predecessor chain). */
        private val LIST_WORDS = listOf("перечисл", "список", "все ", "всех", "назови", "по годам")

        /** A `ПОИСК: <query>` directive (after markdown is stripped from the line). */
        private val SEARCH_DIRECTIVE = Regex("(?i)поиск\\s*[:：．.\\-—]\\s*(.+)")

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
