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
        val searchQuery = QueryExtractor.extract(question)
        DiagLog.i(TAG, "Query: '$question' -> ZIM keywords: '$searchQuery'")
        var hits = searcher.search(searchQuery.ifBlank { question }, candidates)
        if (hits.isEmpty()) {
            // Fallback: try the longest single token (most likely a proper noun).
            val longest = searchQuery.split(" ").filter { it.length >= 3 }.maxByOrNull { it.length }
            if (!longest.isNullOrBlank()) {
                DiagLog.i(TAG, "No hits, retrying with longest token: '$longest'")
                hits = searcher.search(longest, candidates)
                DiagLog.i(TAG, "Retry hits: ${hits.size}")
            }
        }
        // Title-boost: rank the primary article first. Prefer an exact title match,
        // then title-starts-with, then contains; shorter titles win ties (the
        // central "Тольятти" beats "Памятники Тольятти").
        val searchTerms = searchQuery.split(" ").filter { it.length >= 3 }.map { it.lowercase() }
        if (searchTerms.isNotEmpty()) {
            hits = hits.sortedWith(compareByDescending { hit ->
                val title = hit.title.lowercase()
                var score = 0
                if (searchTerms.any { it == title }) score += 100
                if (searchTerms.any { title.startsWith(it) }) score += 20
                if (searchTerms.any { title.contains(it) }) score += 10
                score - title.length / 20 // prefer shorter (more central) titles
            })
        }

        DiagLog.i(TAG, "RAG: '$question' candidates=${hits.size}")
        if (hits.isEmpty()) {
            return Result(
                prompt = "В Википедии не нашлось статей по запросу. Ответь общим знанием, но кратко.\n\nВопрос: $question",
                sourcesUsed = emptyList(),
                totalCandidates = 0,
            )
        }

        val sb = StringBuilder()
        val titles = mutableListOf<String>()
        var used = 0
        // Cap each article so several fit (breadth) instead of one filling the budget.
        val perArticle = (budgetChars / topK).coerceAtLeast(500)
        for (hit in hits.take(topK)) {
            val body = searcher.readArticleText(hit.path) ?: continue
            val remaining = budgetChars - used
            if (remaining <= 200) break
            val chunk = body.take(minOf(remaining, perArticle))
            sb.append("=== ").append(hit.title).append(" ===\n")
                .append(chunk)
                .append("\n\n")
            titles += hit.title
            used += chunk.length + 40
        }

        if (sb.isEmpty()) {
            // Nothing readable — fall back to snippets only.
            for (hit in hits.take(topK)) {
                val snippet = ZimSearcher.htmlToPlainText(hit.snippet)
                sb.append("=== ").append(hit.title).append(" ===\n")
                    .append(snippet)
                    .append("\n\n")
                titles += hit.title
                if (sb.length > budgetChars) break
            }
        }

        val prompt = buildString {
            append("Тебе даны выдержки из Википедии. Отвечай на их основе. ")
            append("Извлеки и собери ВСЕ относящиеся к вопросу факты из выдержек, даже частичные ")
            append("(имена, даты, перечни). Если в выдержках совсем нет нужной информации — ")
            append("скажи «не знаю по приведённым выдержкам». Отвечай на русском языке.\n\n")
            append("=== ВЫДЕРЖКИ ИЗ ВИКИ ===\n")
            append(sb)
            append("=== КОНЕЦ ВЫДЕРЖЕК ===\n\n")
            append("Вопрос: ").append(question)
        }
        DiagLog.i(TAG, "RAG prompt preview: " + prompt.take(500).replace('\n', ' '))
        return Result(prompt = prompt, sourcesUsed = titles, totalCandidates = hits.size)
    }

    companion object { private const val TAG = "RagPromptBuilder" }
}
