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

        // Two ways to know which field is being asked for.
        //
        // The curated intent table is precise: it knows "кто мэр" means P6 even
        // though the card labels that row «Мэр», «Глава» or «Градоначальник»
        // depending on the article. But it only covers 13 properties, and a
        // scan of 5000 random articles put that at 18.6% of all tagged fields —
        // 520 distinct properties are in use, and the top 200 cover 96%.
        //
        // So when no intent matches, fall through to matching the question
        // against the card's own labels. That needs no table at all: the labels
        // already say what a question says («Дата рождения», «Население»,
        // «Место смерти»), and coverage then grows with Wikipedia rather than
        // with how many regexes we have written.
        val intentLabel = INTENTS.firstOrNull { it.pattern.containsMatchIn(q) }?.label
        if (intentLabel == null && !looksLikeFieldQuestion(q)) return null
        val entity = QueryExtractor.extractEntity(question) ?: return null

        DiagLog.i(TAG, "Factoid candidate: label='${intentLabel ?: "(by card label)"}' entity='$entity'")

        val candidates = resolveCandidates(question, entity, searcher)
        if (candidates.isEmpty()) {
            DiagLog.i(TAG, "Factoid: no confident article for '$entity' — falling back to RAG")
            return null
        }
        DiagLog.i(TAG, "Factoid candidates: " + candidates.joinToString { it.title })

        for (hit in candidates) {
            val html = searcher.readArticleHtml(hit.path) ?: continue
            val card = InfoboxExtractor.extract(html, hit.title)
            if (card.isEmpty) {
                DiagLog.i(TAG, "Factoid: '${hit.title}' has no infobox — next candidate")
                continue
            }
            val hitLabel: String
            val value: String
            val direct = intentLabel?.let { card.field(it) }
            if (direct != null) {
                hitLabel = intentLabel
                value = direct
            } else {
                val m = matchByLabel(question, entity, card)
                if (m == null) {
                    DiagLog.i(TAG, "Factoid: '${hit.title}' card has no matching field — next candidate")
                    continue
                }
                hitLabel = m.label
                value = m.value
            }
            if (!plausible(value)) {
                DiagLog.i(TAG, "Factoid: value '$value' looks unusable — next candidate")
                continue
            }
            DiagLog.i(TAG, "Factoid HIT: $hitLabel = '$value' (${hit.title})")
            return Answer(label = hitLabel, value = value, articleTitle = hit.title, articlePath = hit.path)
        }
        DiagLog.i(TAG, "Factoid: no candidate carried the field — falling back to RAG")
        return null
    }

    /**
     * Whether the question is shaped like a request for one field, so it is
     * worth reading a card even though no curated intent matched.
     *
     * Deliberately narrow: an interrogative, and short. Length is the useful
     * signal — a single-fact question is a handful of words, while anything
     * longer is asking for prose the card can't give.
     */
    private fun looksLikeFieldQuestion(q: String): Boolean {
        if (!INTERROGATIVE.containsMatchIn(q)) return false
        return q.split(Regex("\\s+")).count { it.isNotBlank() } <= 7
    }

    /**
     * The card row whose label the question is asking about, or null when the
     * match is weak or ambiguous.
     *
     * Matching is on word stems rather than whole words, because the question
     * declines what the label states in the nominative («какое **население**
     * Казани» against the label «Население», but also «какова **численность
     * населения**»). Ambiguity is fatal rather than resolved by score: two
     * plausible rows mean we don't actually know which fact was asked for, and
     * answering the wrong field confidently is the failure this whole path is
     * built to avoid.
     */
    private fun matchByLabel(
        question: String,
        entity: String,
        card: InfoboxExtractor.Card,
    ): InfoboxExtractor.Field? {
        val entityStems = stems(entity)
        val asked = (stems(question) + synonymStems(question))
            .filter { it !in entityStems && it !in QUESTION_STEMS }
            .distinct()
        if (asked.isEmpty()) return null

        val scored = card.fields.mapNotNull { f ->
            val labelStems = stems(f.label)
            if (labelStems.isEmpty()) return@mapNotNull null
            val hits = labelStems.count { ls -> asked.any { it.startsWith(ls) || ls.startsWith(it) } }
            if (hits == 0) null else f to hits.toDouble() / labelStems.size
        }.sortedByDescending { it.second }

        val best = scored.firstOrNull()
        // Every word of the label has to be accounted for: «Площадь» may match
        // «какая площадь Байкала», but «Площадь водосбора» must not.
        if (best == null || best.second < 1.0) {
            // The miss is the interesting case, and the two halves of it are
            // invisible from the outside: what the question was reduced to, and
            // what the card actually offered. «какой рост у Юрия Гагарина» hit
            // this line with the field seemingly present in the card.
            DiagLog.i(TAG, "Factoid: no label covers $asked in " +
                "[${card.fields.joinToString { it.label }}]")
            return null
        }
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.second >= 1.0) {
            DiagLog.i(TAG, "Factoid: ambiguous label match " +
                "('${best.first.label}' vs '${runnerUp.first.label}') — falling back to RAG")
            return null
        }
        return best.first
    }

    /** Lowercased word stems, long enough to be discriminating. */
    private fun stems(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[\\p{Punct}«»“”\"]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 4 }
            .map { it.take(STEM_LEN) }

    /**
     * Extra label stems implied by the question's wording rather than spelled
     * out in it. The card says «Место смерти»; the question says «где умер».
     * Stemming cannot bridge that — the two share no prefix — and the
     * all-words-covered rule in [matchByLabel] means even a correct «смерть»
     * leaves «место» uncovered, so both halves have to be supplied.
     *
     * Tokenised without the length filter of [stems], because the interrogative
     * that carries half the meaning («где») is three letters long.
     *
     * Deliberately tiny: every pair here is one a real question needed. Guessing
     * pairs "for later" widens matching with no evidence behind it, and a
     * confident answer out of the wrong field is the failure this path exists to
     * avoid.
     */
    private fun synonymStems(question: String): List<String> {
        val words = question.lowercase()
            .replace(Regex("[\\p{Punct}«»“”\"]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return words.flatMap { w -> LABEL_SYNONYMS.filter { it.first.containsMatchIn(w) }.map { it.second } }
    }

    /** Question wording → the label stem it stands for. Anchored at the word
     *  start so «умер» does not fire on «умеренный». */
    private val LABEL_SYNONYMS: List<Pair<Regex, String>> = listOf(
        Regex("^где$") to "место",
        Regex("^(умер|сконча|погиб)") to "смерти",
        Regex("^родил") to "рожден",
        Regex("^(основа|учрежд)") to "основа",
        Regex("^(снял|снима)") to "режисс",
        Regex("^(написа|сочинил)") to "автор",
        // Person cards label citizenship «Страна», not «Гражданство» — measured
        // on «Бауман, Людвиг», whose card reads
        // [Дата рождения, Дата смерти, Место рождения, Место смерти,
        //  Образование, Страна, Учёба].
        Regex("^(гражданств|подданств)") to "страна",
    )

    /** Interrogatives that can introduce a single-field question. */
    private val INTERROGATIVE =
        Regex("\\b(кто|что|как(ой|ая|ое|ов[аоы]?)|когда|где|сколько|чему|каков)\\b")

    /**
     * Stems of question words themselves — never a field being asked for.
     *
     * Built through [stems] rather than hand-written, because the hand-written
     * list was four characters wide while [stems] emits six: «какой» became
     * "какой" and never equalled the "како" in the set, so every interrogative
     * but the literally-four-letter «чему»/«чего» sailed through into `asked`.
     *
     * «имя»/«название» are deliberately absent — those are real card labels.
     */
    private val QUESTION_STEMS: Set<String> = stems(
        "какой какая какое какие каков какова каковы который которая которое " +
            "когда сколько чему чего нынешний нынешняя сейчас текущий текущая " +
            "действующий действующая назовите скажите",
    ).toSet()

    /**
     * Prefix length used for stem comparison. Six characters keeps
     * «населени»/«население» together while still separating «площадь» from
     * «плотность».
     */
    private const val STEM_LEN = 6

    /**
     * Articles that could carry the asked-for field, best guess first.
     *
     * The old code resolved exactly one article and gave up if it had no
     * infobox — which is how «кто режиссёр фильма Сталкер» died on the
     * disambiguation page «Сталкер» while «Сталкер (фильм)» sat one rank below
     * in the very same candidate list, and how «кто автор романа Война и мир»
     * ended up on «Война». Retrieval had already solved this (recall@3 97%);
     * the fast path just never got to look past its first guess.
     *
     * Widening is safe because the caller still requires the *requested label*
     * to be present. That check is a type check in disguise: a settlement card
     * has no «Режиссёр», a disambiguation page has no card at all, so a wrong
     * candidate falls through instead of answering. This is what keeps
     * false-fast-rate at 0 while coverage goes up.
     */
    private suspend fun resolveCandidates(
        question: String,
        entity: String,
        searcher: ZimSearcher,
    ): List<ZimSearcher.Hit> {
        val out = LinkedHashMap<String, ZimSearcher.Hit>()
        fun add(hit: ZimSearcher.Hit) {
            if (hit.path.isNotBlank()) out.putIfAbsent(hit.path, hit)
        }
        // 1. The strict exact-title resolve, unchanged — it produced 11 of the
        //    14 infobox hits on the baseline, so it stays the first guess.
        resolveEntity(entity, searcher)?.let { add(it) }
        // 2. The retrieval lane's own entity resolver. It reads multi-word
        //    n-grams, so it lands «Война и мир» where a single longest-token
        //    entity can only ever see «Война».
        EntityTitleProbe.probe(question, searcher, limit = 3).forEach { add(it) }
        // 3. Sibling titles under the same prefix: the inverted person form
        //    («Толстой, Лев Николаевич») and parenthesised qualifiers
        //    («Сталкер (фильм)»). Both are only accepted when the question
        //    itself supplies the disambiguating word.
        //    Two indexes are asked, because they disagree: the raw title-order
        //    scan finds the inverted person form but walks past the
        //    parenthesised run («Сталкер (фильм)» never came back for
        //    «Сталкер»), while the suggestion index returns exactly those.
        val siblings = (
            searcher.findByTitlePrefix(entity, limit = SIBLING_LOOKUPS) +
                searcher.suggestTitles(entity, limit = SIBLING_LOOKUPS)
            ).distinctBy { it.path }
        siblings.filter { personTitleMatches(it.title, entity, question) }.forEach { add(it) }
        siblings
            .mapNotNull { h -> qualifierGap(h.title, entity, question)?.let { it to h } }
            // Fewer uncovered qualifier words first: «Сталкер (фильм)» before
            // «Сталкер (фильм, 2023)», whose "2023" the question never mentions.
            .sortedBy { it.first }
            .forEach { add(it.second) }
        return out.values.take(MAX_ARTICLE_READS)
    }

    /**
     * True when [title] is the inverted person form of [entity] and the given
     * names in it are the ones [question] asked for.
     *
     * «Толстой» alone is a disambiguation page; the article is «Толстой, Лев
     * Николаевич». Requiring every other proper noun of the question to appear
     * in the tail is what stops «Толстой, Алексей Константинович» from
     * answering a question about Лев.
     */
    private fun personTitleMatches(title: String, entity: String, question: String): Boolean {
        val surname = title.substringBefore(',').trim()
        if (!surname.equals(entity.trim(), ignoreCase = true)) return false
        val tail = title.substringAfter(',', "").lowercase()
        if (tail.isBlank()) return false
        val given = properTokens(question).filter { !it.equals(entity, ignoreCase = true) }
        // Without a given name in the question there is nothing to disambiguate
        // on, and picking a namesake at random is exactly the false-fast answer
        // this path must never give.
        return given.isNotEmpty() && given.all { g -> tail.contains(g.lowercase()) }
    }

    /**
     * How many words of [title]'s parenthesised qualifier the [question] does
     * *not* mention, or null when it mentions none of them (so the qualifier is
     * about something else entirely and the title must not be considered).
     */
    private fun qualifierGap(title: String, entity: String, question: String): Int? {
        if (!title.substringBefore('(').trim().equals(entity.trim(), ignoreCase = true)) return null
        val qualifier = title.substringAfter('(', "").substringBefore(')')
        val parts = qualifier.split(Regex("[,\\s]+")).filter { it.length >= 3 }
        if (parts.isEmpty()) return null
        val q = question.lowercase()
        // Compare on a stem, not the whole word: the question says «фильма»,
        // the qualifier says «фильм».
        val covered = parts.count { q.contains(it.lowercase().take(5)) }
        return if (covered == 0) null else parts.size - covered
    }

    /** Proper nouns of the question — capitalised mid-sentence words, minus the
     *  question shells a user may have capitalised at the start. */
    private fun properTokens(question: String): List<String> =
        question
            .replace(Regex("[\\p{Punct}«»“”\"]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it.first().isUpperCase() && it.lowercase() !in EntityTitleProbe.EDGE_STOP }

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

    /** Each extra candidate costs one article read + jsoup parse (~50 ms on an
     *  S23). Four is still two orders of magnitude under the ~50 s RAG path we
     *  are trying to avoid, and the candidate list rarely holds more. */
    private const val MAX_ARTICLE_READS = 4

    /**
     * Siblings come back in title order, not in relevance order, so the window
     * has to be wide enough to reach the qualifier the question asked for:
     * «Сталкер» has 2/2154/(группа)/(замок)/(игра)/(кинофестиваль)/(программа)
     * ahead of «(фильм)», and a window of 8 stopped one short of it. Widening
     * costs only list filtering — MAX_ARTICLE_READS still caps how many of
     * these are actually opened and parsed.
     */
    private const val SIBLING_LOOKUPS = 32

    private const val TAG = "FactoidAnswerer"
}
