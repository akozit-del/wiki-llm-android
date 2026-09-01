package com.wikillm.android.rag

import android.os.SystemClock
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
                // Sprint 31: "собери ВСЕ факты" is the list branch's instruction
                // and it had leaked into every non-list question. On the phase
                // baseline «почему Юпитер называют газовым гигантом» came back
                // as 1053 tokens — 213 s of decode out of a 228 s turn, 93 %.
                // Decode is the whole cost of a RAG answer and its length is set
                // right here, not by the model's speed: the same 4.9 tok/s spent
                // on 150 tokens is a 30 s turn. Ask for the answer, not for a
                // digest of the excerpts.
                append("Тебе даны выдержки из Википедии. Отвечай на их основе. ")
                append("Ответь коротко и по существу: 1–3 предложения, только то, ")
                append("о чём спрашивают. Не пересказывай выдержки, не добавляй ")
                append("посторонние факты и не нумеруй пункты, если вопрос не про перечень. ")
                append("Если вопрос требует сравнения двух и более вещей — до 5 предложений. ")
                append("Если в выдержках совсем нет нужной информации — ")
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
        /** First hit (city/topic seed) holds a leadership SECTION with many
         *  names → open extraction. The rest are single-person biographies →
         *  the only name we trust is the article's own subject (the title). */
        val isSeed: Boolean = false,
        /** True when this is the DEDICATED list article ("Градоначальники X",
         *  score 2000). It contains the full answer, so if it yields a good
         *  list the map phase can short-circuit and skip the bio docs. */
        val isListArticle: Boolean = false,
    )

    /** Search ZIM and return ready excerpt sections for [question]. */
    suspend fun searchExcerpts(
        question: String,
        candidates: Int,
        topK: Int,
        budgetChars: Int,
        excludeTitles: Set<String> = emptySet(),
    ): Excerpts {
        // Index work and article reading are billed apart on purpose. They are
        // optimised by different levers — the first by ranking, the second by
        // how much body we decompress and keep — and until now both hid inside
        // one opaque "everything before the first token". `ctx` is the number
        // the context-trimming work has to move.
        val tSearch = SystemClock.elapsedRealtime()
        val hits = gatherSortedHits(question, candidates, excludeTitles)
        val searchMs = SystemClock.elapsedRealtime() - tSearch
        if (hits.isEmpty()) {
            DiagLog.i(TAG, "[PHASE] search=${searchMs}ms read=0ms docs=0 ctx=0")
            return Excerpts("", emptyList(), 0)
        }
        val searchTerms = QueryExtractor.extract(question)
            .split(" ").filter { it.length >= 3 }.map { it.lowercase() }
        val tRead = SystemClock.elapsedRealtime()
        val ex = buildExcerptsFromHits(question, hits, topK, budgetChars, searchTerms)
        DiagLog.i(TAG, "[PHASE] search=${searchMs}ms " +
            "read=${SystemClock.elapsedRealtime() - tRead}ms " +
            "docs=${ex.titles.size} ctx=${ex.block.length}")
        return ex
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
        val allHits = gatherSortedHits(question, candidates, excludeTitles)
        if (allHits.isEmpty()) return emptyList()
        val searchTerms = QueryExtractor.extract(question)
            .split(" ").filter { it.length >= 3 }.map { it.lowercase() }
        val isList = QueryExtractor.isListIntent(question)
        // build-95: for list questions, map ONLY over the deterministic
        // probes (title-probe ≥800, walker ≥900, exact-title 1000) — those are
        // the actual biographies. BM25 noise (s≤100: "Тольятти 24",
        // "театр кукол", "станция") just wastes a slow LLM call each and never
        // contains a mayor. Falls back to all hits if no probe survived.
        val hits = if (isList) {
            allHits.filter { it.score >= 800 }.ifEmpty { allHits }
        } else allHits
        val out = mutableListOf<DocExcerpt>()
        for ((idx, hit) in hits.take(topK).withIndex()) {
            val html = searcher.readArticleHtml(hit.path) ?: continue
            val card = InfoboxExtractor.extract(html, hit.title)
            val body = InfoboxExtractor.bodyText(html)
            // build-101: revert to the focused leadership section. build-100's
            // 9000-char whole-article window made the 4B extract WORSE (seed →
            // НЕТ), confirming the long-context weakness from the research: a
            // small focused section ("Городская власть", ~2700 chars) is the
            // sweet spot. Falls back to a density-anchored window when the
            // article has no matching section header.
            // build-103: a dedicated list article (score 2000, e.g.
            // "Градоначальники Тольятти") IS the list — feed its whole body,
            // not a section window. A normal city seed uses the focused
            // leadership section (build-101).
            val isListArticle = idx == 0 && isList && hit.score >= 2000
            val chunk = when {
                // The list article is a long chronological table (imperial era →
                // today). "Last 30 years" sits at the modern end, so anchor on
                // the мэр/глав density cluster (relevantChunk) rather than the
                // head, with a big cap so the whole modern run fits.
                isListArticle -> relevantChunk(body, hit.title, searchTerms, perDocChars * 6)
                idx == 0 && isList -> {
                    val seedCap = perDocChars * 3
                    val section = InfoboxExtractor.sectionsByAnchor(html, sectionAnchorsFor(question), seedCap)
                    if (section.isNotBlank()) section else relevantChunk(body, hit.title, searchTerms, seedCap)
                }
                else -> relevantChunk(body, hit.title, searchTerms, perDocChars)
            }
            val text = buildString {
                append("=== ").append(hit.title).append(" ===\n")
                if (!card.isEmpty) append(card.block()).append("\n")
                append(chunk)
            }
            out += DocExcerpt(
                hit.title, hit.path, text, hit.sourceTag,
                isSeed = (idx == 0 && isList),
                isListArticle = isListArticle,
            )
        }
        DiagLog.i(TAG, "Map docs: ${out.size} (${out.joinToString { it.title }})")
        return out
    }

    /**
     * Retrieval only — no article reading, no prompt, no LLM. Exists so the
     * reference set (benchmark/questions.json) can be scored for recall@k in
     * about a second per question instead of the ~60 s a full RAG answer costs;
     * measuring retrieval was otherwise gated on generating an answer we throw
     * away.
     */
    suspend fun probeCandidates(question: String, candidates: Int): List<ZimSearcher.Hit> =
        gatherSortedHits(question, candidates, emptySet())

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
                listAwareTitleProbes(entity, role, EntityTitleProbe.listPhrase(question)).also { probes ->
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

        // Sprint 2026-08-25: head-entity title probes, for EVERY question.
        // Xapian has no Russian stemmer in this index, so a question in the
        // genitive («площадь Байкала») never reaches the nominative title
        // («Байкал») — 8 of the 11 articles lost outside top-20 on the first
        // recall baseline failed exactly this way. Probing the title index with
        // guessed nominatives is exact and O(1), so a wrong guess costs nothing
        // and a right one lands the article at rank 1 instead of nowhere.
        val entityProbeHits = EntityTitleProbe.probe(question, searcher)

        val bm25 = searcher.search(searchQuery.ifBlank { question }, candidates)
        // build-103 fix: probes FIRST, then dedup by path keeping the first
        // (highest-score) version. The old code filtered probes OUT when their
        // path also appeared in BM25 — which silently demoted a pinned
        // score-2000 list article ("Градоначальники Тольятти") to its BM25
        // duplicate at ~50, then the score≥800 doc filter dropped it entirely.
        // ...and keep the HIGHEST-scoring copy of each path, not the first one.
        // The concatenation order stopped matching descending score once the
        // chain walker moved ahead of EntityTitleProbe: the walker pins its seed
        // at whatever lookupExactTitle returned (1000), while the probe scores
        // the very same article 1090 because it matched the full n-gram
        // «Гагарин, Юрий Алексеевич». distinctBy keeps the first, so the article
        // reached the sort at 1000 and lost rank 1 to the bare «Юрий» (1070).
        // That is why 829b7ff (boost gate, reverted) measured no change: the
        // probe's ranking was already gone before the sort ran.
        var hits = (titleProbeHits + walkerProbeHits + entityProbeHits + bm25)
            .groupBy { it.path }
            .map { (_, dupes) -> dupes.maxByOrNull { it.score }!! }
        hits = promoteQualifiedSiblings(question, hits)
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
                // Boost only the unpinned Xapian tail. A pinned hit already
                // carries a deliberate ordering from EntityTitleProbe (n-gram
                // length, proper-noun bonus, attribute penalty) and those gaps
                // are 20–90 points wide, so a ±130 title boost on top decides
                // rank 1 by itself — wrongly, because all three bonuses fire
                // together exactly when the title *is* one query word. That is
                // how «Дмитрий» beat «Менделеев, Дмитрий Иванович» and «Юрий»
                // beat «Гагарин, Юрий Алексеевич». Boosted BM25 tops out near
                // 200, well under the floor, so pins still sit above the tail —
                // only their internal order is left alone.
                if (hit.score < PINNED_SCORE_FLOOR) {
                    if (searchTerms.any { it == title }) score += 100
                    if (searchTerms.any { title.startsWith(it) }) score += 20
                    if (searchTerms.any { title.contains(it) }) score += 10
                }
                score - title.length / 20
            })
        }
        if (excludeTitles.isNotEmpty()) {
            hits = hits.filter { it.title !in excludeTitles }
        }
        // Variant 3: semantic rerank of the BM25 tail (no-op unless the mE5
        // embedder is loaded). Pinned probes stay on top; the tail is reordered
        // by RRF(bm25-rank, cosine-to-question) so the most on-topic passages
        // enter topK even when Xapian ranked them low.
        hits = semanticRerank(question, hits)
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

    /**
     * Lift «Сталкер (фильм)» above the pinned «Сталкер» when the question itself
     * said «фильма».
     *
     * A bare pinned title is only the right answer while nothing disambiguates
     * it. «кто режиссёр фильма Сталкер» pinned «Сталкер» — a disambiguation page
     * with no card and no body — at 1070, while «Сталкер (фильм)» sat one rank
     * below at BM25 s=99, in the very same candidate list. The fast path already
     * refuses to stop at the bare page ([EntityTitleProbe.qualifierGap] picked
     * the film and answered «Андрей Тарковский» in 108 ms); retrieval kept
     * handing the LLM the disambiguation stub.
     *
     * No lookup is added: the qualified sibling is already a candidate — BM25
     * finds it, since the question's own words are in its title. Only its score
     * changes, so this costs nothing in latency.
     */
    private fun promoteQualifiedSiblings(
        question: String,
        hits: List<ZimSearcher.Hit>,
    ): List<ZimSearcher.Hit> {
        // Only a *pinned* bare title is worth overriding: below the floor the
        // title boost and the rerank already decide the order, and a BM25 hit
        // carries no claim that it is the entity the question names.
        val pinned = hits
            .filter { it.score >= PINNED_SCORE_FLOOR && '(' !in it.title }
            .associateBy { it.title.trim().lowercase() }
        if (pinned.isEmpty()) return hits
        var promoted = 0
        val out = hits.map { hit ->
            if ('(' !in hit.title) return@map hit
            val base = pinned[hit.title.substringBefore('(').trim().lowercase()] ?: return@map hit
            val gap = EntityTitleProbe.qualifierGap(hit.title, base.title, question) ?: return@map hit
            promoted++
            // Above its own base, and ordered by how much of the qualifier the
            // question left uncovered: «Сталкер (фильм)» before «Сталкер (фильм,
            // 2023)», whose "2023" nobody asked about.
            hit.copy(score = base.score + QUALIFIER_PROMOTION - gap)
        }
        if (promoted > 0) {
            DiagLog.i(TAG, "Qualified siblings promoted: " + out
                .filter { '(' in it.title && it.score >= PINNED_SCORE_FLOOR }
                .joinToString { "${it.title}(${it.score})" })
        }
        return out
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
     * Variant 3 — hybrid rerank. Keeps the pinned probe hits (list article,
     * chain-walker, title probes) in place at the top, then reorders the BM25
     * tail by Reciprocal-Rank Fusion of its original BM25 order and cosine
     * similarity of (title + Xapian snippet) to the natural-language question.
     *
     * This is the "глобально, не только по мэрам" lever: for questions with NO
     * dedicated list article, the tail IS the whole candidate set, so semantic
     * ordering decides what the model actually reads. No-op when the embedder
     * isn't loaded, so the deterministic list path is never disturbed.
     */
    private suspend fun semanticRerank(
        question: String,
        hits: List<ZimSearcher.Hit>,
    ): List<ZimSearcher.Hit> {
        if (!EmbeddingHolder.isReady() || hits.size < 3) return hits
        // Probes are pinned at score ≥ 800; BM25/Xapian hits sit well below.
        val pinned = hits.filter { it.score >= PINNED_SCORE_FLOOR }
        val tail = hits.filter { it.score < PINNED_SCORE_FLOOR }
        if (tail.size < 3) return hits

        val qv = EmbeddingHolder.embedQuery(question) ?: return hits
        val sims = FloatArray(tail.size) { -1f }
        for (i in tail.indices) {
            val h = tail[i]
            val passage = (h.title + ". " + stripHtml(h.snippet)).trim().take(600)
            val pv = EmbeddingHolder.embedPassage(passage) ?: continue
            sims[i] = EmbeddingHolder.cosine(qv, pv)
        }
        // Semantic rank per tail index (0 = most similar).
        val semRank = IntArray(tail.size)
        tail.indices.sortedByDescending { sims[it] }
            .forEachIndexed { rank, idx -> semRank[idx] = rank }
        // RRF fuse: bm25 rank == the tail index (already BM25-ordered).
        val k = 60f
        val fusedOrder = tail.indices.sortedByDescending { i ->
            1f / (k + i) + 1f / (k + semRank[i])
        }
        val rerankedTail = fusedOrder.map { tail[it] }
        DiagLog.i(TAG, "Semantic rerank: tail ${tail.size}, top → " +
            fusedOrder.take(3).joinToString { i ->
                "${tail[i].title}(cos=%.2f,bm25#$i)".format(sims[i])
            })
        return pinned + rerankedTail
    }

    /** Strip Xapian snippet markup ("<b>…</b>") and collapse whitespace. */
    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Pick up to [cap] chars of [body] that actually bear on the question:
     * derive stems from the query terms (stem-matched for Russian inflections,
     * the article's own title word ignored because it is everywhere) and hand
     * them to [packSentences]. Falls back to the article's lead when no stem
     * matches at all.
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

        // No stem occurs anywhere: nothing to select on, so the lead is the best
        // guess (it is what defines the article).
        if (stems.none { lower.contains(it) }) return body.take(cap)
        return packSentences(body, stems, cap)
    }

    /**
     * Split [body] on sentence boundaries and keep only the sentences that carry
     * an anchor stem, in document order, until [cap] is spent.
     *
     * The window this replaced was contiguous: it found the densest cluster and
     * then paid for every filler sentence *between* the matches, and cut both
     * ends mid-sentence. Two consequences, both measurable on the reference set:
     * the prompt carried text the question never asked about (prefill is ~23 %
     * of a RAG turn and scales with it), and matches outside the one window —
     * mayors listed in a later section, a birth date in the lead — were dropped
     * even when the budget had room.
     *
     * The lead sentence is always kept: it is what says *what the article is*,
     * and without it a pile of matched sentences has no referent.
     */
    private fun packSentences(body: String, stems: Set<String>, cap: Int): String {
        val sentences = splitSentences(body)
        if (sentences.size <= 1) return body.take(cap)

        // Score = how many DISTINCT stems the sentence carries. Distinct, not
        // total: a sentence repeating «глава» six times is one fact, a sentence
        // holding «глава» and «Тольятти» is the answer.
        data class Scored(val idx: Int, val text: String, val score: Int)
        val scored = sentences.mapIndexed { i, s ->
            val low = s.lowercase()
            Scored(i, s, stems.count { low.contains(it) })
        }

        val keep = sortedSetOf<Int>()
        var used = 0
        fun take(s: Scored): Boolean {
            if (s.idx in keep) return true
            if (used + s.text.length + 1 > cap) return false
            keep += s.idx
            used += s.text.length + 1
            return true
        }
        // The lead first, then by descending score; ties by document order so
        // the article's own narrative sequence survives inside a score band.
        take(scored[0])
        for (s in scored.drop(1).filter { it.score > 0 }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.idx })) {
            if (!take(s)) break
        }
        // Budget left over (short article, few matches): backfill in document
        // order rather than returning a prompt smaller than we're allowed.
        if (used < cap) for (s in scored) { if (!take(s)) break }

        val sb = StringBuilder()
        var prev = -1
        for (i in keep) {
            // Mark elisions: without it the model reads two distant sentences as
            // consecutive and invents the connection between them.
            if (prev >= 0 && i != prev + 1) sb.append("… ")
            sb.append(sentences[i]).append(' ')
            prev = i
        }
        return sb.toString().trim()
    }

    /**
     * Sentence boundaries for Russian article text. Jsoup's `.text()` gives one
     * whitespace-joined blob, so punctuation is all we have: split after . ! ? …
     * only when the next token starts with a capital. That keeps «1985 г. в
     * Москве» and «им. Ленина» whole — Russian prose is dense with those
     * abbreviations and splitting on a bare dot shreds it.
     */
    private fun splitSentences(body: String): List<String> =
        body.split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

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
    private suspend fun listAwareTitleProbes(
        entity: String,
        role: String?,
        questionPhrase: String? = null,
    ): List<ZimSearcher.Hit> {
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
        // Tier 0 (build-103): the DEDICATED list article. ru.wiki has
        // "Градоначальники Тольятти" — a standalone article with the full mayor
        // list. lookupExactTitle's redirect-follow was collapsing it into the
        // city page and losing it. lookupRaw checks it WITHOUT following the
        // redirect: if it's a real article with a real body, pin it at score
        // 2000 so it becomes candidate #1 → the open-extraction seed → the
        // whole list reaches the model in one focused call.
        val listTitles = buildList {
            // The question's own phrase first: it is direct evidence of what the
            // user is enumerating, where the templates below are guesses. Only
            // the leadership templates existed, so «Спутники Юпитера» and its
            // kind had no tier-0 route at all.
            if (questionPhrase != null) add(questionPhrase)
            if (role != null) { add("$role $entity"); add("Список ${role.lowercase()} $entity") }
            add("Градоначальники $entity"); add("Главы $entity"); add("Список глав $entity")
            add("Мэры $entity"); add("Список мэров $entity")
        }.distinct()
        for (t in listTitles) {
            val probe = searcher.lookupRaw(t) ?: continue
            if (!probe.isRedirect && probe.bodyLen >= 400 && probe.path !in seenPaths) {
                DiagLog.i(TAG, "List article FOUND: '${probe.title}' (bodyLen=${probe.bodyLen})")
                hits += ZimSearcher.Hit(probe.title, probe.path, "", score = 2000)
                seenPaths += probe.path
                break // one real list article is enough
            }
        }
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
        // lookupExactTitle is not exact — tier 2 is a fuzzy SuggestionSearcher —
        // so the seed can be anything that merely starts with the entity, and it
        // used to enter the candidate list at that tier's own 950: «Москвы имени
        // канал» for «Москвы», «Администрация Екатеринбурга» for «Екатеринбурга»
        // (which took rank 1 away from «Екатеринбург» itself). The walker's job
        // is the chain, not the head article, so the seed enters at the walker's
        // own tier. Costs nothing when the seed is right: EntityTitleProbe hands
        // in the same path at 1070/1090 and the max-score dedup above keeps that.
        hits += seed.copy(score = minOf(seed.score, WALKER_SEED_SCORE))
        seenPaths += seed.path
        val walked = InfoboxGraphWalker.walk(
            searcher = searcher,
            seedPath = seed.path,
            propertyIds = props,
            // build-98: back to 6 nodes / depth 4. build-97's wide walk (14)
            // didn't improve recall — the Тольятти predecessor chain isn't in
            // this ZIM's infobox wikidata links, so the walker just pulled the
            // Samara political cluster (governors), each costing a ~4-min map
            // call to reject. 40-min runs are unusable. The real list lives in
            // the SEED article's "Городская власть" section; the walker bios
            // are a bounded bonus.
            maxNodes = 6,
            maxDepth = 4,
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

    companion object {
        private const val TAG = "RagPromptBuilder"

        /** See [splitSentences]: break only where a capital actually follows. */
        private val SENTENCE_BREAK = Regex("(?<=[.!?…])\\s+(?=[А-ЯЁA-Z«\"])")

        /**
         * Where the pinned probes end and the Xapian tail begins. Shared by the
         * title-boost gate and [semanticRerank] on purpose: the two split the
         * same list, and if the thresholds ever drift, one of them reorders what
         * the other pinned.
         */
        private const val PINNED_SCORE_FLOOR = 500

        /** Walker tier: the seed sits with its own chain nodes (900 - depth). */
        private const val WALKER_SEED_SCORE = 900

        /**
         * How far a question-disambiguated sibling sits above its bare base
         * title. Wide enough to clear the sort's length tiebreak
         * (`title.length / 20`, ~1-2 points), narrow enough that it never
         * reorders two different pinned entities: the probe's own gaps between
         * n-gram tiers are 20 points. */
        private const val QUALIFIER_PROMOTION = 15
    }
}
