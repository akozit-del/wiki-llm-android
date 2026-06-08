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
        val ex = searchExcerpts(question, candidates, topK, budgetChars)
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
                append("Тебе даны выдержки из Википедии, содержащие списки/перечни. ")
                append("Извлеки ВСЕ имена/пункты из этих списков, относящиеся к вопросу, ")
                append("даже короткие упоминания. Формат ответа — Markdown-список «Имя — годы», ")
                append("по одному пункту в строке. НЕ пропускай ни одного кандидата из выдержек. ")
                append("Если совсем ничего нет — скажи «не знаю по приведённым выдержкам». ")
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

    /** Search ZIM and return ready excerpt sections for [question]. */
    suspend fun searchExcerpts(
        question: String,
        candidates: Int,
        topK: Int,
        budgetChars: Int,
        excludeTitles: Set<String> = emptySet(),
    ): Excerpts {
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
                chainWalkerProbes(entity).also { probes ->
                    if (probes.isNotEmpty()) {
                        DiagLog.i(TAG, "Walker probes (entity='$entity'): " +
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
        if (hits.isEmpty()) return Excerpts("", emptyList(), 0)

        // build-69: list questions need a wider window (city article + list article
        // + biographies). Bump topK and per-article cap when intent is list.
        val effectiveTopK = if (QueryExtractor.isListIntent(question)) maxOf(topK, 5) else topK
        val sb = StringBuilder()
        val titles = mutableListOf<String>()
        var used = 0
        val perArticle = (budgetChars / effectiveTopK).coerceAtLeast(500)
        for (hit in hits.take(effectiveTopK)) {
            val html = searcher.readArticleHtml(hit.path)
            if (html == null) {
                DiagLog.w(TAG, "skip '${hit.title}' — readArticleHtml null for path=${hit.path}")
                continue
            }
            val remaining = budgetChars - used
            if (remaining <= 200) break
            val card = InfoboxExtractor.extract(html, hit.title)
            val body = InfoboxExtractor.bodyText(html)
            val chunk = relevantChunk(body, hit.title, searchTerms, minOf(remaining, perArticle))
            val section = buildString {
                append("=== ").append(hit.title).append(" ===\n")
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
        if (stems.any { it.startsWith("мэр") || it.startsWith("глав") || it.startsWith("руковод") || it.startsWith("градонач") || it.startsWith("губерн") }) {
            stems += listOf("мэр", "глав", "градонач", "руковод")
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
            templates += "$role $entity"
            templates += "Список ${role.lowercase()} $entity"
            templates += "Список ${role.lowercase()} города $entity"
        }
        // Generic leadership templates (cover the common ru.wiki naming patterns).
        templates += listOf(
            "Главы $entity",
            "Список глав $entity",
            "Список глав города $entity",
            "Мэры $entity",
            "Список мэров $entity",
            "Руководители $entity",
            "Градоначальники $entity",
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
    private suspend fun chainWalkerProbes(entity: String): List<ZimSearcher.Hit> {
        val seed = searcher.lookupExactTitle(entity) ?: return emptyList()
        if (seed.path.isBlank()) return emptyList()
        val hits = mutableListOf<ZimSearcher.Hit>()
        val seenPaths = HashSet<String>()
        hits += seed
        seenPaths += seed.path
        val walked = InfoboxGraphWalker.walk(
            searcher = searcher,
            seedPath = seed.path,
            propertyIds = CHAIN_PROPS,
            maxNodes = 12,
            maxDepth = 5,
        )
        for (w in walked) {
            if (w.path in seenPaths) continue
            // Prefer the link's visible text as title (it's already clean Russian
            // like "Жилкин С. Ф."); fall back to decoding the href.
            val title = w.viaLabel.takeIf { it.isNotBlank() } ?: decodeHrefAsTitle(w.path)
            hits += ZimSearcher.Hit(
                title = title,
                path = w.path,
                snippet = "",
                // Deeper nodes get a tiny score nudge down so closer chains
                // dominate when the prompt budget is tight.
                score = 900 - w.depth,
            )
            seenPaths += w.path
        }
        return hits
    }

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
    private val CHAIN_PROPS = setOf(
        "P6", "P1365", "P1366", "P39", "P166",
        "P26", "P40", "P22", "P25", "P3373",
        "P50", "P175", "P800",
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
