package com.wikillm.android.rag

import com.wikillm.android.diag.DiagLog

/**
 * Fast path for factoid questions ("кто мэр Тольятти", "какое население Казани",
 * "когда основан Новосибирск").
 *
 * For this class of question the answer is literally one field of the article's
 * infobox, which [InfoboxExtractor] already parses into "Метка: значение" pairs.
 * Running the LLM over a 2000-token RAG prompt to restate that field costs ~50 s
 * on-device and can hallucinate; reading the field costs well under a second and
 * cannot.
 *
 * This never replaces RAG — it either produces a confident answer or returns
 * null and the normal pipeline runs. Because a wrong fast answer is far worse
 * than a miss, entity matching is deliberately strict: the article title has to
 * actually be the entity that was asked about, not merely mention it.
 */
object FactoidAnswerer {

    /**
     * A resolved factoid: [value] is the infobox field, [label] the canonical
     * field name and [articleTitle] the source article (shown as attribution).
     */
    data class Answer(
        val label: String,
        val value: String,
        val articleTitle: String,
        val articlePath: String,
    ) {
        /**
         * Rendered reply: the value, then the source, so it reads like an answer.
         * Only bold is used — MarkdownText renders `**bold**` and `code`, not
         * underscore italics, which would otherwise show up as literal `_`.
         */
        fun render(): String = "**$value**\n\n$label · $articleTitle"
    }

    /**
     * Question pattern → the infobox label to read. Labels must match the
     * canonical RU names in [InfoboxExtractor.PRIORITY] exactly, since that is
     * what ends up in Card.lines.
     *
     * Patterns are intentionally narrow: they must look like a single-fact
     * lookup. Anything asking for a list, a comparison or an explanation is left
     * to the normal RAG path.
     */
    private data class Intent(val pattern: Regex, val label: String)

    private val INTENTS = listOf(
        // Who runs the place
        Intent(Regex("(кто|как(ой|ая)|фамилия)\\b.{0,30}\\b(мэр|глав[аеуы]|градоначальник|губернатор)"), "Глава/мэр"),
        Intent(Regex("\\b(мэр|глава|губернатор)\\b.{0,20}\\b(кто|сейчас|нынешн|действующ|текущ)"), "Глава/мэр"),
        // Numbers
        Intent(Regex("(как(ое|ова)|сколько|численность)\\b.{0,30}\\bнаселени"), "Население"),
        Intent(Regex("(как(ая|ова)|сколько)\\b.{0,30}\\bплощад"), "Площадь"),
        // Dates
        Intent(Regex("(когда|в каком году|дата)\\b.{0,30}\\b(основан|образован|founded)"), "Основан"),
        Intent(Regex("(когда|дата)\\b.{0,30}\\bродил"), "Дата рождения"),
        Intent(Regex("(когда|дата)\\b.{0,30}\\b(умер|смерт|скончал)"), "Дата смерти"),
        Intent(Regex("(где|мест[оа])\\b.{0,30}\\bродил"), "Место рождения"),
        // Affiliation / role
        Intent(Regex("(как(ая|ой)|в как(ой|ом))\\b.{0,30}\\bпарти"), "Партия"),
        Intent(Regex("(как(ая|ов)|что за)\\b.{0,30}\\bдолжност"), "Должность"),
        Intent(Regex("(как(ая|ой)|что за)\\b.{0,30}\\b(столиц)"), "Столица"),
        Intent(Regex("(как(ая|ой)|что за)\\b.{0,30}\\b(валют)"), "Валюта"),
        // Works
        Intent(Regex("(кто|как(ая|ой)|фамилия)\\b.{0,30}\\b(автор|написал)"), "Автор"),
        Intent(Regex("(кто|как(ая|ой)|фамилия)\\b.{0,30}\\b(режиссёр|режиссер|снял)"), "Режиссёр"),
    )

    /** Question shapes that are never a single-field lookup, even if a pattern hits. */
    private val NOT_FACTOID = Regex(
        "перечисл|список|все\\s|всех\\s|сравн|почему|зачем|как\\s+работает|" +
            "расскаж|подробн|истори|объясн|за\\s+последн|список"
    )

    /**
     * Try to answer [question] straight from an infobox.
     * Returns null whenever anything is less than certain — the caller then runs
     * the normal RAG pipeline.
     */
    suspend fun tryAnswer(question: String, searcher: ZimSearcher): Answer? {
        val q = question.lowercase()
        if (NOT_FACTOID.containsMatchIn(q)) return null

        val label = INTENTS.firstOrNull { it.pattern.containsMatchIn(q) }?.label ?: return null
        val entity = QueryExtractor.extractEntity(question) ?: return null

        DiagLog.i(TAG, "Factoid candidate: label='$label' entity='$entity'")

        val hit = resolveEntity(entity, searcher) ?: run {
            DiagLog.i(TAG, "Factoid: no confident article for '$entity' — falling back to RAG")
            return null
        }

        val html = searcher.readArticleHtml(hit.path) ?: return null
        val card = InfoboxExtractor.extract(html, hit.title)
        if (card.isEmpty) {
            DiagLog.i(TAG, "Factoid: '${hit.title}' has no infobox — falling back to RAG")
            return null
        }

        val value = card.field(label) ?: run {
            DiagLog.i(TAG, "Factoid: '${hit.title}' card has no '$label' — falling back to RAG")
            return null
        }
        if (!plausible(value)) {
            DiagLog.i(TAG, "Factoid: value '$value' looks unusable — falling back to RAG")
            return null
        }

        DiagLog.i(TAG, "Factoid HIT: $label = '$value' (${hit.title})")
        return Answer(label = label, value = value, articleTitle = hit.title, articlePath = hit.path)
    }

    /**
     * Find the article that *is* [entity], not one that merely mentions it.
     *
     * Every candidate — including the one from lookupExactTitle — is checked
     * against [titleIsEntity]. That check is not optional: lookupExactTitle
     * falls back to a SuggestionSearcher fuzzy match, so it happily answers
     * "Казани" with "Архитектура Казани". Returning that unchecked would let us
     * give a confident sub-second answer out of the wrong article, which is the
     * worst failure this path can have.
     *
     * Questions name entities in an oblique case ("население Казани",
     * "мэр Москвы"), while article titles are nominative, so each declined form
     * is also tried as its likely nominative. Guessing wide is safe precisely
     * because every guess still has to pass the strict check.
     */
    private suspend fun resolveEntity(entity: String, searcher: ZimSearcher): ZimSearcher.Hit? {
        for (form in nominativeForms(entity)) {
            searcher.lookupExactTitle(form)
                ?.takeIf { titleIsEntity(it.title, form) }
                ?.let { return it }
            searcher.findByTitlePrefix(form, limit = 5)
                .firstOrNull { titleIsEntity(it.title, form) }
                ?.let { return it }
        }
        return null
    }

    /**
     * The entity as written, plus plausible nominatives. Shared with
     * [EntityTitleProbe] so the retrieval lane and this one decline words the
     * same way — they had drifted, and the wider rule set there (-ии → -ия,
     * -ого → -ий) covers endings this lane was missing on «Японии», «Бразилии».
     * Indeclinable names ("Тольятти") pass through as the first form.
     */
    private fun nominativeForms(entity: String): List<String> =
        EntityTitleProbe.nominativeForms(entity)

    /** True when [title] names exactly [entity], ignoring a parenthesised qualifier. */
    private fun titleIsEntity(title: String, entity: String): Boolean {
        val base = title.substringBefore('(').trim()
        return base.equals(entity.trim(), ignoreCase = true)
    }

    /**
     * Reject values that would read as a non-answer: empty, a bare punctuation
     * leftover, or a long run of prose that is clearly not a single fact.
     */
    private fun plausible(value: String): Boolean {
        val v = value.trim()
        if (v.length < 2 || v.length > 120) return false
        if (v.none { it.isLetterOrDigit() }) return false
        // A value with several sentences is prose that slipped into the card.
        if (v.count { it == '.' } > 2) return false
        return true
    }

    private const val TAG = "FactoidAnswerer"
}
