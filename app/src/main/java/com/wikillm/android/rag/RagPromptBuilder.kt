package com.wikillm.android.rag

import com.wikillm.android.diag.DiagLog

/**
 * Turns a user question into a RAG-augmented prompt by:
 *   1. Searching ZIM for top-N candidates.
 *   2. Reading article bodies for the top-K candidates.
 *   3. Stitching them into a "Контекст:" block in front of the user question.
 *
 * Tries to fit within an approximate character budget so the model's context
 * window isn't blown. We never include the question itself in the context.
 */
class RagPromptBuilder(private val searcher: ZimSearcher) {

    data class Result(
        val prompt: String,
        val sourcesUsed: List<String>, // article titles actually included
        val totalCandidates: Int,
    )

    /** Just the gathered excerpts (reused by both single-shot and agentic RAG). */
    data class Excerpts(
        val block: String,           // "=== Title ===\n...\n\n" sections
        val titles: List<String>,
        val totalCandidates: Int,
    )

    /**
     * Build a RAG prompt.
     * @param question  user's free-text question
     * @param candidates how many candidates to fetch from the index (10/20/50)
     * @param topK      how many articles to actually paste into the prompt
     * @param budgetChars approximate cap on the context section
     */
    suspend fun build(
        question: String,
        candidates: Int,
        topK: Int = 3,
        budgetChars: Int = 4000,
    ): Result {
        // Sprint 19: list questions naturally need to fit more articles in
        // the prompt — the seed + the chain-walker biographies + maybe a
        // list-article. The 4000-char default smothers half of them before
        // they reach the LLM. Stretch the budget for list-intent so the
        // walker hits stay visible. n_ctx=4096 in JNI is the hard cap; ~6000
        // chars of context plus the prompt frame and answer still fit safely.
        val isListBuild = QueryExtractor.isListIntent(question)
        // Sprint 21: n_ctx=6144 (Sprint 20) plus KV q8_0 (Sprint 4) gives us
        // enough room to widen the list-question budget further. 9000 chars
        // (~3600 RU tokens) still leaves comfortable space for the answer.
        val effectiveBudget = if (isListBuild) maxOf(budgetChars, 9000) else budgetChars
        val ex = searchExcerpts(question, candidates, topK, effectiveBudget)
        if (ex.block.isBlank()) {
            return Result(
                prompt = "В Википедии не нашлось статей по запросу. Ответь общим знанием, но кратко.\n\nВопрос: $question",
                sourcesUsed = emptyList(),
                totalCandidates = 0,
            )
        }
        val listIntent = QueryExtractor.isListIntent(question)
        val prompt = buildString {
            if (listIntent) {
                append("Тебе даны выдержки из Википедии. Собери список ВСЕХ имён, ")
                append("которые в выдержках были на соответствующей должности из вопроса ")
                append("(даже если в той же биографии упомянуты и другие, более поздние должности). ")
                append("Формат ответа — Markdown-список «Имя — годы», по пункту в строке. ")
                // Sprint 30: emphasise inclusion over exclusion. Build-92 went
                // too narrow: the model dropped Сергей Андреев (real Тольятти
                // mayor 2012-2017) because his bio also mentioned later roles
                // in Самара. Make explicit that holding *another* role later
                // doesn't disqualify a name — the question is who EVER served
                // in the asked role.
                append("ВАЖНО: если человек был на запрошенной должности (например, мэром Тольятти), ")
                append("включай его в список — даже если потом стал губернатором, министром, ")
                append("депутатом или служил в других городах. Должность позже или роль в другом ")
                append("месте — это НЕ повод исключать. Включай каждого, чья биография подтверждает ")
                append("службу в запрошенной роли в указанный период. ")
                append("Если совсем ничего подходящего нет — скажи «не знаю по приведённым выдержкам». ")
                append("Отвечай на русском.\n\n")
            } else {
                append("Тебе даны выдержки из Википедии. Отвечай на их основе. ")
                append("Извлеки и собери ВСЕ относящиеся к вопросу факты из выдержек, даже частичные ")
                append("(имена, даты, перечни). Если в выдержках совсем нет нужной информации — ")
                append("скажи «не знаю по приведённым выдержкам». Отвечай на русском языке.\n\n")
            }
            append("=== ВЫДЕРЖКИ ИЗ ВИКИ ===\n")
            append(ex.block)
            append("=== КОНЕЦ ВЫДЕРЖЕК ===\n\n")
            append("Вопрос: ").append(question)
        }
        DiagLog.i(TAG, "RAG prompt preview (listIntent=$listIntent): " + prompt.take(500).replace('\n', ' '))
        return Result(prompt = prompt, sourcesUsed = ex.titles, totalCandidates = ex.totalCandidates)
    }

    /**
     * One article's worth of context, kept separate (NOT concatenated) so the
     * list-extraction map phase (build-94) can feed the model exactly one
     * biography per LLM call. `text` is the same "=== Title ===\nКарточка…\nbody"
     * section a single-shot prompt would have inlined.
     */
    data class DocExcerpt(
        val title: String,
        val path: String,
        val text: String,
        val sourceTag: String?,
    )

    /** Search ZIM and return ready excerpt sections for [question]. */
    suspend fun searchExcerpts(
        question: String,
        candidates: Int,
        topK: Int,
        budgetChars: Int,
        excludeTitles: Set<String> = emptySet(),
    ): Excerpts {
        val hits = gatherSortedHits(question, candidates, excludeTitles)
        if (hits.isEmpty()) return Excerpts("", emptyList(), 0)
        val searchTerms = QueryExtractor.extract(question)
            .split(" ").filter { it.length >= 3 }.map { it.lowercase() }
        return buildExcerptsFromHits(question, hits, topK, budgetChars, searchTerms)
    }

    /**
     * build-94 — per-document excerpts for the L3X-style map phase. Same
     * retrieval/sort as [searchExcerpts] but each surviving article is returned
     * as its own [DocExcerpt] instead of being glued into one block. The caller
     * (ListExtractor) runs one short extraction LLM call per item, then merges
     * deterministically — far higher recall on "перечисли всех X" than asking a
     * 4B model to pull every name out of one giant concatenated prompt.
     */
    suspend fun searchExcerptDocs(
        question: String,
        candidates: Int,
        topK: Int,
        perDocChars: Int = 1400,
        excludeTitles: Set<String> = emptySet(),
    ): List<DocExcerpt> {
        val hits = gatherSortedHits(question, candidates, excludeTitles)
        if (hits.isEmpty()) return emptyList()
        val searchTerms = QueryExtractor.extract(question)
            .split(" ").filter { it.length >= 3 }.map { it.lowercase() }
        val isList = QueryExtractor.isListIntent(question)
        val out = mutableListOf<DocExcerpt>()
        for ((idx, hit) in hits.take(topK).withIndex()) {
            val html = searcher.readArticleHtml(hit.path) ?: continue
            val card = InfoboxExtractor.extract(html, hit.title)
            val body = InfoboxExtractor.bodyText(html)
            // Seed article on a list question: prefer the leadership section.
            val chunk = if (idx == 0 && isList) {
                val section = InfoboxExtractor.sectionsByAnchor(html, sectionAnchorsFor(question), perDocChars)
                if (section.isNotBlank()) section else relevantChunk(body, hit.title, searchTerms, perDocChars)
            } else {
                relevantChunk(body, hit.title, searchTerms, perDocChars)
            }
            val text = buildString {
                append("=== ").append(hit.title).append(" ===\n")
                if (!card.isEmpty) append(card.block()).append("\n")
                append(chunk)
            }
            out += DocExcerpt(hit.title, hit.path, text, hit.sourceTag)
        }
        DiagLog.i(TAG, "Map docs: ${out.size} (${out.joinToString { it.title }})")
        return out
    }

    /** Shared retrieval+sort used by both single-shot and per-doc map paths. */
    private suspend fun gatherSortedHits(
        question: String,
        candidates: Int,
        excludeTitles: Set<String>,
    ): List<ZimSearcher.Hit> {
        val searchQuery = QueryExtractor.extract(question)
        DiagLog.i(TAG, "Query: '$question' -> ZIM keywords: '$searchQuery'")
        val tokens = searchQuery.split(" ").filter { it.length >= 3 }

        // --- build-61: List-intent title-probe lane (runs BEFORE Xapian) ---
        // For "перечисли мэров Тольятти" the answer lives in articles like
        // "Главы Тольятти" / "Список глав Тольятти" / "Категория:Главы Тольятти".
        // BM25 buries those under 700 mentions of «Тольятти + мэр». libzim's
        // title index (getEntryByTitle / findByTitle) lets us land directly.
        // Pinned at score 800–1000 so title-boost sort floats them to the top.
        // build-61/68: List-intent title probes — only for "перечисли/список" questions.
        val titleProbeHits = if (QueryExtractor.isListIntent(question)) {
            val entity = QueryExtractor.extractEntity(question)
            val role = QueryExtractor.extractRolePlural(question)
            if (!entity.isNullOrBlank()) {
                listAwareTitleProbes(entity, role).also { probes ->
                    if (probes.isNotEmpty()) {
                        DiagLog.i(TAG, "List probes (entity='$entity', role='$role'): " +
                            probes.joinToString { it.title })
                    } else {
                        DiagLog.i(TAG, "List-intent: no title probes for entity='$entity' role='$role'")
                    }
                }
            } else emptyList()
        } else emptyList()

        // Sprint 6: chain-walker — runs for ANY entity-bearing question, not
        // just lists. For factoid ("кто отец Жилкина?") the walker hits the
        // biography and pulls P22; for chains ("преемник Брежнева") it BFS's
        // by P1366. Bounded to 6 nodes / 4 hops so cost stays low even on
        // single-shot. Disjoint from titleProbeHits (no path overlap by design).
        val walkerProbeHits = run {
            val entity = QueryExtractor.extractEntity(question)
            if (!entity.isNullOrBlank()) {
                // Sprint 16: narrow CHAIN_PROPS by question context. For a
                // mayor/leader question we don't want the walker to drift
                // through P166 (awards) and start dragging in "Заслуженный
                // врач РФ" or P184 (научный руководитель). Each profile
                // contains exactly the properties that are relevant for
                // that kind of chain.
                val props = chainPropsFor(question)
                chainWalkerProbes(entity, props).also { probes ->
                    if (probes.isNotEmpty()) {
                        DiagLog.i(TAG, "Walker probes (entity='$entity', props=${props.size}): " +
                            probes.joinToString { it.title })
                    }
                }
            } else emptyList()
        }

        var hits = searcher.search(searchQuery.ifBlank { question }, candidates)
        if (titleProbeHits.isNotEmpty()) {
            val have = hits.mapTo(HashSet()) { it.path }
            hits = titleProbeHits.filter { it.path !in have } + hits
        }
        if (walkerProbeHits.isNotEmpty()) {
            val have = hits.mapTo(HashSet()) { it.path }
            hits = walkerProbeHits.filter { it.path !in have } + hits
        }
        // Also pull the head-entity article on its own. When the query mixes an
        // attribute with an entity ("мэр Тольятти"), the bare entity page
        // ("Тольятти") gets crowded out of the candidates by "<Entity>ский/ская…"
        // pages — so it never reaches the prompt. Searching the entity term alone
        // guarantees it's a candidate; the title-boost below then floats it to #1.
        val entity = tokens.filter { it.length >= 4 }.maxByOrNull { it.length }
        if (!entity.isNullOrBlank() && !entity.equals(searchQuery, ignoreCase = true)) {
            val have = hits.mapTo(HashSet()) { it.path }
            val extra = searcher.search(entity, candidates).filter { it.path !in have }
            if (extra.isNotEmpty()) {
                DiagLog.i(TAG, "Entity merge '$entity': +${extra.size} candidates")
                hits = hits + extra
            }
        }
        if (hits.isEmpty()) {
            val longest = tokens.maxByOrNull { it.length }
            if (!longest.isNullOrBlank()) {
                DiagLog.i(TAG, "No hits, retrying with longest token: '$longest'")
                hits = searcher.search(longest, candidates)
            }
        }
        val searchTerms = tokens.map { it.lowercase() }
        if (searchTerms.isNotEmpty()) {
            hits = hits.sortedWith(compareByDescending { hit ->
                val title = hit.title.lowercase()
                // Start from the pinned base score (1000 for exact-title probes,
                // 800 for prefix probes, 0/small for Xapian hits) — without this,
                // the list-aware probes get re-buried by an "exact entity" article.
                var score = hit.score
                if (searchTerms.any { it == title }) score += 100
                if (searchTerms.any { title.startsWith(it) }) score += 20
                if (searchTerms.any { title.contains(it) }) score += 10
                score - title.length / 20
            })
        }
        if (excludeTitles.isNotEmpty()) {
            hits = hits.filter { it.title !in excludeTitles }
        }
        DiagLog.i(TAG, "RAG: '$question' candidates=${hits.size}")
        // build-69 diag: top of the candidate list after re-sort, with paths,
        // so we can see when a pinned probe lost the join or its path is wrong.
        if (hits.isNotEmpty()) {
            DiagLog.i(TAG, "Top hits: " + hits.take(5).joinToString(" | ") {
                "${it.title}(s=${it.score})[${it.path}]"
            })
        }
        return hits
    }

    /** Build one concatenated excerpt block from already-sorted [hits]. */
    private suspend fun buildExcerptsFromHits(
        question: String,
        hits: List<ZimSearcher.Hit>,
        topK: Int,
        budgetChars: Int,
        searchTerms: List<String>,
    ): Excerpts {
        // Sprint 18: when the chain-walker found a deep chain (e.g. all
        // historical mayors of a city), keep topK wide enough so the LLM
        // sees every link in the chain, not just the first 2-3. We grow
        // topK to fit `seed + walker_hits` but cap at 12 so the budget
        // doesn't smear thin. relevantChunk + per-article cap still bound
        // the total prompt size.
        val effectiveTopK = if (QueryExtractor.isListIntent(question)) {
            maxOf(topK, 5, hits.size + 2).coerceAtMost(12)
        } else topK
        val isList = QueryExtractor.isListIntent(question)
        val sb = StringBuilder()
        val titles = mutableListOf<String>()
        var used = 0
        // Sprint 12: when the question is a list AND no dedicated list article
        // exists in this ZIM (very common — ru.wiki redirects "Главы X" back to
        // "X"), the city/topic article is where the list actually lives, often
        // in a "Городская власть" / "Главы" section. Give the first (seed) hit
        // a much bigger window so relevantChunk can pull that section in full;
        // share the remaining budget between the chain-walker biographies.
        val perArticle = (budgetChars / effectiveTopK).coerceAtLeast(500)
        // Sprint 23: dial back to 55 %. Sprint 21's 45 % was too tight —
        // ru.wiki's "Городская власть" section in the city seed often holds
        // half the historical mayors, and trimming it dropped names like
        // Жилкин from the prompt. With Sprint 19/21 widening the overall
        // budget to 9000 chars, 55 % of seed is ~4900 chars (≈1900 tokens)
        // — enough for the full leadership section.
        val seedBudget = if (isList) (budgetChars * 55 / 100).coerceAtLeast(1500) else perArticle
        for ((idx, hit) in hits.take(effectiveTopK).withIndex()) {
            val html = searcher.readArticleHtml(hit.path)
            if (html == null) {
                DiagLog.w(TAG, "skip '${hit.title}' — readArticleHtml null for path=${hit.path}")
                continue
            }
            val remaining = budgetChars - used
            if (remaining <= 200) break
            val card = InfoboxExtractor.extract(html, hit.title)
            val body = InfoboxExtractor.bodyText(html)
            val cap = if (idx == 0) minOf(remaining, seedBudget) else minOf(remaining, perArticle)
            // Sprint 17: for the seed article on a list question, try to grab
            // the section whose header matches the user's role ("Городская
            // власть", "Главы города", "Руководство") rather than just the
            // densest text cluster. Falls back to relevantChunk if no section
            // header matches.
            val chunk = if (idx == 0 && isList) {
                val anchors = sectionAnchorsFor(question)
                val section = InfoboxExtractor.sectionsByAnchor(html, anchors, cap)
                if (section.isNotBlank()) section
                else relevantChunk(body, hit.title, searchTerms, cap)
            } else {
                relevantChunk(body, hit.title, searchTerms, cap)
            }
            val section = buildString {
                append("=== ").append(hit.title).append(" ===\n")
                // Sprint 8: chain-walker provenance — lets the LLM see *why*
                // this article is here ("предшественник Сухих", "сын Жилкина")
                // instead of having to guess from text alone.
                if (!hit.sourceTag.isNullOrBlank()) {
                    append("(найдено ").append(hit.sourceTag).append(")\n")
                }
                if (!card.isEmpty) append(card.block()).append("\n")
                append(chunk).append("\n\n")
            }
            sb.append(section)
            titles += hit.title
            used += section.length
        }
        if (sb.isEmpty()) {
            for (hit in hits.take(topK)) {
                val snippet = ZimSearcher.htmlToPlainText(hit.snippet)
                sb.append("=== ").append(hit.title).append(" ===\n").append(snippet).append("\n\n")
                titles += hit.title
                if (sb.length > budgetChars) break
            }
        }
        return Excerpts(sb.toString(), titles, hits.size)
    }

    /**
     * Take a [cap]-char window of [body] over the DENSEST cluster of query-term
     * matches (stem-matched for Russian inflections). For "list all …" questions
     * the answer sits where the relevant word repeats most (e.g. the "Городская
     * власть" section full of "глава"), not at the first mention. The article's
     * own title word is ignored (it's everywhere). Falls back to the start.
     */
    private fun relevantChunk(body: String, title: String, terms: List<String>, cap: Int): String {
        if (body.length <= cap) return body
        val lower = body.lowercase()
        val titleLower = title.lowercase()
        fun stem(t: String) = if (t.length >= 5) t.dropLast(2) else t

        // Stems to anchor on: non-title query terms + leadership synonyms when the
        // question is about city leadership (мэр/глава/руководитель…).
        val stems = terms.map { stem(it) }.filter { !titleLower.contains(it) }.toMutableSet()
        // Sprint 14: anchor synonyms grouped by domain. Each group widens
        // relevantChunk on related role words so dense clusters get picked
        // up even when the user used a near-synonym ("главы" vs "руководители",
        // "лауреаты" vs "обладатели"). Add the canonical stems for each
        // domain that *any* original stem already hit.
        val GROUPS = listOf(
            // Government
            listOf("мэр", "глав", "градонач", "руковод", "губерн") to
                listOf("мэр", "глав", "градонач", "руковод"),
            // Awards
            listOf("лауреат", "обладат", "номинант") to
                listOf("лауреат", "обладат", "награж"),
            // Sport
            listOf("чемпион", "победит", "призёр", "призер", "финалист") to
                listOf("чемпион", "победит", "призёр", "финалист"),
            // Films / books
            listOf("автор", "режисс", "сценарист", "продюсер", "композитор") to
                listOf("автор", "режисс", "сценарист", "продюсер"),
            // Family
            listOf("отец", "мать", "сын", "дочь", "ребён", "ребен", "супруг", "брат", "сестр") to
                listOf("отец", "мать", "сын", "дочь", "супруг"),
        )
        for ((triggers, additions) in GROUPS) {
            if (stems.any { s -> triggers.any { t -> s.startsWith(t) } }) {
                stems += additions
            }
        }
        if (stems.isEmpty()) return body.take(cap)

        val anchors = ArrayList<Int>()
        for (s in stems) {
            var i = lower.indexOf(s)
            while (i >= 0) { anchors.add(i); i = lower.indexOf(s, i + s.length) }
        }
        if (anchors.isEmpty()) return body.take(cap)
        anchors.sort()
        // Pick the window position covering the most anchors.
        var bestStart = anchors[0]
        var bestCount = -1
        for (a in anchors) {
            val count = anchors.count { it in a until (a + cap) }
            if (count > bestCount) { bestCount = count; bestStart = a }
        }
        val start = (bestStart - 100).coerceIn(0, (body.length - cap).coerceAtLeast(0))
        return body.substring(start, (start + cap).coerceAtMost(body.length))
    }

    /**
     * For list-style questions, probe libzim's title index directly for the
     * canonical list/category articles about [entity]. We try exact-title
     * lookups first (cheapest, single dict probe) and a short title-prefix
     * scan for "Категория:Главы …" — these reliably exist in ru.wiki ZIMs and
     * are exactly what answers questions like "перечисли мэров Тольятти".
     *
     * Returned hits carry path/title only — bodies are read later in the
     * normal excerpt-building loop, so this stays cheap (~few µs per probe).
     */
    private suspend fun listAwareTitleProbes(entity: String, role: String?): List<ZimSearcher.Hit> {
        val templates = mutableListOf<String>()
        // Role-specific templates first (more precise).
        if (role != null) {
            val low = role.lowercase()
            templates += "$role $entity"
            templates += "Список $low $entity"
            templates += "Список $low города $entity"
            templates += "Список $low страны $entity"
            templates += "$role $entity по годам"
            // ru.wiki list-articles with various prepositions
            templates += "$role по $entity"          // «Чемпионы по боксу»
            templates += "$role $entity (хронология)"
        }
        // Generic leadership templates — catch list pages we still miss when
        // role detection lands on something close but not perfect.
        templates += listOf(
            "Главы $entity",
            "Список глав $entity",
            "Список глав города $entity",
            "Мэры $entity",
            "Список мэров $entity",
            "Руководители $entity",
            "Градоначальники $entity",
            // Generic "history of"/"timeline" article — often holds chronological
            // chains the LLM can mine for full-list answers.
            "История $entity",
            "Хронология $entity",
        )

        val hits = mutableListOf<ZimSearcher.Hit>()
        val seenPaths = HashSet<String>()
        // Tier A: exact-title lookup for each template.
        for (t in templates.distinct()) {
            val h = searcher.lookupExactTitle(t) ?: continue
            if (h.path.isBlank() || h.path in seenPaths) continue
            hits += h
            seenPaths += h.path
        }
        // Tier B: title-prefix scan for "Категория:" + role + entity (the canonical
        // Wikipedia category page that lists every biography we care about).
        if (role != null) {
            val catPrefix = "Категория:$role $entity"
            for (h in searcher.findByTitlePrefix(catPrefix, limit = 3)) {
                if (h.path.isBlank() || h.path in seenPaths) continue
                hits += h
                seenPaths += h.path
            }
        }
        // Tier C (chain-walker) lives in chainWalkerProbes now — it runs
        // unconditionally for any entity-bearing question, not just lists.
        return hits
    }

    /**
     * Sprint 6: run the deterministic infobox chain-walker for [entity].
     * Used for factoid questions ("кто отец Жилкина?") and chain questions
     * ("преемник Брежнева"), in addition to list questions. Returns the
     * seed article itself (so the prompt always sees its infobox), plus
     * everything the walker discovers via CHAIN_PROPS.
     */
    private suspend fun chainWalkerProbes(
        entity: String,
        props: Set<String> = CHAIN_PROPS,
    ): List<ZimSearcher.Hit> {
        val seed = searcher.lookupExactTitle(entity) ?: return emptyList()
        if (seed.path.isBlank()) return emptyList()
        val hits = mutableListOf<ZimSearcher.Hit>()
        val seenPaths = HashSet<String>()
        hits += seed
        seenPaths += seed.path
        val walked = InfoboxGraphWalker.walk(
            searcher = searcher,
            seedPath = seed.path,
            propertyIds = props,
            // Sprint 29: maxNodes 12 → 8. The previous 12-node cap filled
            // up before depth 6 ever mattered — the city infobox often emits
            // 3-5 outgoing P6 wikilinks (current head + recent ones), and
            // walker BFSs into governor chains for each. Tighten the cap
            // to "seed + ~6 mayors". For a 30-year city this fits the
            // straight predecessor chain (Сухих → Ренц → Анташев → Андреев
            // → Пушков → Азаров) and stops before Самара governors enter.
            maxNodes = 8,
            maxDepth = 6,
        )
        for (w in walked) {
            if (w.path in seenPaths) continue
            // Prefer the link's visible text as title (it's already clean Russian
            // like "Жилкин С. Ф."); fall back to decoding the href.
            val title = w.viaLabel.takeIf { it.isNotBlank() } ?: decodeHrefAsTitle(w.path)
            val fromTitle = decodeHrefAsTitle(w.fromPath)
            val propLabel = PROP_LABELS[w.viaProperty] ?: w.viaProperty
            val tag = "по $propLabel из «$fromTitle»"
            hits += ZimSearcher.Hit(
                title = title,
                path = w.path,
                snippet = "",
                // Deeper nodes get a tiny score nudge down so closer chains
                // dominate when the prompt budget is tight.
                score = 900 - w.depth,
                sourceTag = tag,
            )
            seenPaths += w.path
        }
        return hits
    }

    /** Compact RU labels for the chain-walker source tag — same vocabulary
     *  as InfoboxExtractor's PRIORITY so the prompt stays consistent. */
    private val PROP_LABELS = mapOf(
        "P6" to "P6 (глава)",
        "P1365" to "P1365 (предшественник)",
        "P1366" to "P1366 (преемник)",
        "P39" to "P39 (должность)",
        "P166" to "P166 (награда)",
        "P26" to "P26 (супруг)",
        "P22" to "P22 (отец)",
        "P25" to "P25 (мать)",
        "P40" to "P40 (ребёнок)",
        "P3373" to "P3373 (брат/сестра)",
        "P50" to "P50 (автор)",
        "P175" to "P175 (исполнитель)",
        "P800" to "P800 (заметная работа)",
        "P184" to "P184 (научный руководитель)",
        "P802" to "P802 (студент)",
        "P57" to "P57 (режиссёр)",
        "P58" to "P58 (сценарист)",
        "P162" to "P162 (продюсер)",
        "P36" to "P36 (столица)",
        "P159" to "P159 (штаб-квартира)",
        "P127" to "P127 (владелец)",
        "P112" to "P112 (основатель)",
    )

    /**
     * Wikidata properties walked by Tier C — chosen so a single BFS covers all
     * common chain questions over Russian Wikipedia infoboxes:
     *   P6    глава правительства            (Тольятти → Сухих)
     *   P1365 предшественник                  (мэр → предыдущий мэр)
     *   P1366 преемник                        (мэр → следующий мэр)
     *   P39   занимаемая должность           (биография → должности)
     *   P166  награды                         (биография → лауреаты)
     *   P26   супруг(а)                       (chain про родственные связи)
     *   P40   дети
     *   P22   отец
     *   P25   мать
     *   P3373 брат / сестра
     *   P50   автор                           (книга → автор → другие книги)
     *   P175  исполнитель                     (роль → актёр → другие роли)
     *   P800  заметные работы                 (учёный → работы)
     */
    /**
     * Sprint 16: pick the chain-walker property profile by question keywords.
     * Each profile contains only the properties that are actually relevant
     * for that kind of chain. Default: the broad CHAIN_PROPS set.
     */
    /**
     * Sprint 17: pick section-header anchors for [question]. Used by the
     * seed-article excerpt extractor to grab "Городская власть" / "Главы
     * города" sections from ru.wiki rather than the densest text cluster.
     */
    private fun sectionAnchorsFor(question: String): List<String> {
        val q = question.lowercase()
        return when {
            listOf("мэр", "глав", "руковод", "градонач", "губерн").any { q.contains(it) } ->
                listOf("городская власть", "главы города", "руководство",
                       "руководители", "главы", "мэры", "градоначальники", "власти", "управление")
            listOf("президент", "правительств").any { q.contains(it) } ->
                listOf("президенты", "правительство", "главы государства", "руководство")
            listOf("лауреат", "награ", "обладат", "медал").any { q.contains(it) } ->
                listOf("награды", "лауреаты", "обладатели")
            listOf("чемпион", "победит", "призёр").any { q.contains(it) } ->
                listOf("чемпионы", "победители", "призёры")
            listOf("режиссёр", "режиссер", "сценарист", "продюсер").any { q.contains(it) } ->
                listOf("создатели", "съёмочная группа", "режиссёры")
            else -> emptyList()
        }
    }

    private fun chainPropsFor(question: String): Set<String> {
        val q = question.lowercase()
        // Sprint 27: drop P36/P159/P127/P112 too. Sprint 25's deeper walk
        // (depth 8) made walker hop through P36 (столица) from Тольятти →
        // Самара → mayors/governors of Самара (Тархов, Меркушкин, Федорищев)
        // and through P159 (штаб-квартира) into corporate biographies. For
        // "перечисли мэров X" we only want the strict head-of chain of one
        // city: P6 head, P1365 предшественник, P1366 преемник.
        val governance = setOf("P6", "P1365", "P1366")
        val family = setOf("P26", "P22", "P25", "P40", "P3373", "P1365", "P1366")
        val awards = setOf("P166", "P39", "P1365", "P1366")
        val creative = setOf("P50", "P57", "P58", "P162", "P175", "P800", "P1365", "P1366")
        val academic = setOf("P184", "P802", "P39", "P166", "P1365", "P1366")
        return when {
            // Governance / authority
            listOf("мэр", "глава", "глав", "руковод", "градонач", "губерн",
                   "президент", "министр", "канцлер", "лидер", "патриарх",
                   "царь", "король", "корол", "хан", "султан", "император",
                   "столица", "правлен", "власт").any { q.contains(it) } -> governance
            // Family / heirs
            listOf("отец", "мать", "сын", "дочь", "ребён", "ребен", "супруг",
                   "жена", "муж", "брат", "сестр", "родствен", "семья").any { q.contains(it) } -> family
            // Awards / honours
            listOf("лауреат", "награ", "обладат", "номинант",
                   "премия", "медал", "орден").any { q.contains(it) } -> awards
            // Films / books / music
            listOf("режиссёр", "режиссер", "сценарист", "продюсер",
                   "автор", "исполнит", "композитор", "писател", "поэт",
                   "фильм", "книг", "альбом", "сериал").any { q.contains(it) } -> creative
            // Academic
            listOf("учён", "учен", "научн", "профессор", "академик",
                   "доктор", "диссертац").any { q.contains(it) } -> academic
            else -> CHAIN_PROPS
        }
    }

    private val CHAIN_PROPS = setOf(
        // Government / authority chain
        "P6", "P1365", "P1366", "P39", "P166",
        // Family chain
        "P26", "P40", "P22", "P25", "P3373",
        // Creative works chain
        "P50", "P175", "P800",
        // Sprint 10: academic + creative + place chains
        "P184",  // научный руководитель  → defended-under
        "P802",  // студенты              → mentored
        "P57",   // режиссёр              → film → director
        "P58",   // сценарист             → film → screenwriter
        "P162",  // продюсер              → film → producer
        "P36",   // столица               → country → capital
        "P159",  // штаб-квартира         → organisation → HQ
        "P127",  // владелец              → company → owner
        "P112",  // основатель            → company/team → founder
    )

    /**
     * Decode a ZIM href like "A/Жилкин,_Сергей_Фёдорович" into a human title
     * "Жилкин, Сергей Фёдорович" so logs and the prompt are readable. The
     * actual article body is read later via the path verbatim.
     */
    private fun decodeHrefAsTitle(href: String): String {
        val tail = href.substringAfterLast('/')
        val decoded = try {
            java.net.URLDecoder.decode(tail, Charsets.UTF_8)
        } catch (_: Throwable) { tail }
        return decoded.replace('_', ' ')
    }

    companion object { private const val TAG = "RagPromptBuilder" }
}
