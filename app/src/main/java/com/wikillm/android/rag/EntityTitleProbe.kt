package com.wikillm.android.rag

import com.wikillm.android.diag.DiagLog

/**
 * Lands the article the question is *about* by looking its title up directly,
 * instead of hoping BM25 ranks it.
 *
 * The first recall baseline (benchmark/baseline-2026-08-25.md) lost 11 of 32
 * articles outside the top 20 — unrecoverable, since nothing downstream can
 * read an article that never became a candidate. Eight of those eleven fail the
 * same way: the question names the entity in an oblique case, article titles are
 * nominative, and Xapian has no Russian stemmer here, so «Байкала» matches
 * "Ветры Байкала" and "Площадь Свободы" but never «Байкал».
 *
 * [FactoidAnswerer] already solved this for its own lane (79% hit-rate, zero
 * false answers) — the retrieval lane simply never got the same treatment. This
 * object is that logic, widened and shared:
 *
 *  1. take contiguous n-grams of the question, in the case the user typed them;
 *  2. guess nominatives for each token («Японии» → «Япония»);
 *  3. probe every guess against libzim's title index, which is exact and O(1).
 *
 * Guessing wide is safe *because* the probe is exact: a wrong guess simply finds
 * no entry. That is the whole reason this can afford dozens of candidates per
 * question rather than a real morphological analyser.
 */
object EntityTitleProbe {

    /** Pinned scores sit far above BM25 (~20-50) and above the tail cut (500). */
    private const val BASE_SCORE = 1000

    /** An n-gram naming an attribute is a worse title guess than a bare entity. */
    private const val ATTRIBUTE_PENALTY = 200

    /** Outweighs one n-gram length step, so a proper noun beats a longer
     *  common-noun phrase but not a longer phrase containing it. */
    private const val PROPER_NOUN_BONUS = 50

    /**
     * A guess whose title came back unchanged names the article outright; one
     * that arrived through a redirect only points at it, and the redirect may
     * well be about something else — «Океаны» lands on «Мировой океан» and
     * «Океана» on the asteroid «(224) Океана», while «Океан» is the article the
     * question is about.
     *
     * Deliberately smaller than one n-gram step (20), so this decides *only*
     * ties — between forms of one n-gram, which all carry the same score — and
     * never lets a shorter or common-noun guess overtake a longer or proper-noun
     * one. Otherwise the city «Гагарин» would outrank «Гагарин, Юрий
     * Алексеевич», which the probe reaches through exactly such a redirect.
     */
    private const val TITLE_IDENTITY_BONUS = 10

    /** Cost ceiling. Each probe is an exact index lookup, but a miss costs a
     *  thrown JNI exception, so the candidate list stays bounded. */
    private const val MAX_LOOKUPS = 48

    private const val MAX_NGRAM = 4

    private const val TAG = "EntityTitleProbe"

    /**
     * Tokens that name the *attribute* being asked about rather than the entity
     * that carries it. «Мэр Москвы» is a real ru.wiki article, so a phrase probe
     * finds it — but the infobox field the question needs lives in «Москва».
     * Matching is by stem, which covers the whole declension paradigm.
     */
    private val ATTRIBUTE_STEMS = listOf(
        "мэр", "глав", "градонач", "губернат", "президент", "министр", "руковод",
        "насел", "площад", "столиц", "валют", "числен",
        "автор", "написа", "режисс", "сня", "произвед", "роман", "фильм",
        "основа", "образова", "родил", "умер", "смерт", "рожден",
        "предшеств", "преемник", "парти", "должност", "спутник",
        "открыл", "состои", "производ", "называ", "знаменит", "связан",
        "начал", "расположен", "находи", "атмосфер",
    )

    /** Question shells: never part of a title, so an n-gram may not start or end
     *  with one. Trimming these is what turns «кто мэр Москвы» into «Москвы». */
    internal val EDGE_STOP = setOf(
        "кто", "что", "где", "когда", "почему", "зачем", "как", "какой", "какая",
        "какое", "какие", "каком", "какого", "сколько", "чем", "чего", "чему",
        "это", "такое", "такой", "есть", "был", "была", "было", "были",
        "и", "а", "но", "или", "же", "ли", "бы", "не", "ни", "да", "нет",
        "в", "во", "на", "с", "со", "к", "ко", "у", "о", "об", "от", "из", "за",
        "по", "до", "для", "при", "про", "над", "под",
        "перечисли", "перечислите", "назови", "назовите", "список", "спиши",
        "расскажи", "опиши", "напиши", "сравни", "сравните", "дай", "покажи",
        "все", "всех", "весь", "вся", "всё", "нынешнего", "нынешний",
    )

    /** One title guess plus how good a guess it is. */
    internal data class Candidate(val title: String, val score: Int)

    /**
     * Probe the title index for the articles [question] names. Returns at most
     * [limit] hits, pinned high enough to survive the title-boost sort and the
     * semantic rerank, most specific first.
     */
    suspend fun probe(question: String, searcher: ZimSearcher, limit: Int = 4): List<ZimSearcher.Hit> {
        val candidates = candidates(question)
        if (candidates.isEmpty()) return emptyList()

        val out = ArrayList<ZimSearcher.Hit>(limit)
        val seenPaths = HashSet<String>()
        var lookups = 0
        for (c in candidates) {
            if (out.size >= limit || lookups >= MAX_LOOKUPS) break
            lookups++
            val hit = searcher.lookupTitleExact(c.title) ?: continue
            if (hit.path.isBlank() || !seenPaths.add(hit.path)) continue
            val itself = hit.title.equals(c.title, ignoreCase = true)
            out += hit.copy(score = c.score + if (itself) TITLE_IDENTITY_BONUS else 0)
        }
        if (out.isNotEmpty()) {
            DiagLog.i(TAG, "Title probes ($lookups lookups): " +
                out.joinToString { "${it.title}(${it.score})" })
        }
        // Probing runs in candidate order, which is score order only until the
        // identity bonus fires; re-sorting keeps "most specific first" true for
        // callers that do not sort themselves (FactoidAnswerer reads this list
        // in order and answers from the first card that carries the field).
        return out.sortedByDescending { it.score }
    }

    /**
     * Title guesses for [question], best first. Ordering is what decides rank 1
     * when several guesses all resolve, so it encodes two preferences: a longer
     * phrase is more specific than a shorter one («Война и мир» over «Война»),
     * and a phrase free of attribute words is more likely to be the article that
     * *carries* the fact than one that describes it.
     */
    internal fun candidates(question: String): List<Candidate> {
        val tokens = question
            .replace(Regex("[\\p{Punct}«»“”\"]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, Candidate>()
        for (n in MAX_NGRAM downTo 1) {
            for (start in 0..tokens.size - n) {
                val slice = tokens.subList(start, start + n)
                // A title neither starts nor ends with a question shell or a
                // preposition, so these n-grams cannot be one.
                if (slice.first().lowercase() in EDGE_STOP) continue
                if (slice.last().lowercase() in EDGE_STOP) continue
                if (n == 1 && slice[0].length < 4) continue

                val attribute = slice.any { t -> ATTRIBUTE_STEMS.any { t.lowercase().startsWith(it) } }
                // A phrase that is *only* attribute words names no entity at all.
                if (attribute && n == 1) continue
                // Russian capitalises mid-sentence only for proper nouns, so a
                // capital here is the strongest available "this is the entity"
                // signal. Without it «Байкал» in "почему Байкал самое глубокое
                // озеро" loses its slot to the longer, realer «Глубокое озеро».
                val proper = slice.any { it.first().isUpperCase() }
                val score = BASE_SCORE + n * 20 +
                    (if (proper) PROPER_NOUN_BONUS else 0) -
                    (if (attribute) ATTRIBUTE_PENALTY else 0)

                for (phrase in phraseForms(slice)) {
                    // Longest/highest-scoring form wins: n descends, so the first
                    // time a string appears it already carries its best score.
                    out.putIfAbsent(phrase, Candidate(phrase, score))
                }
            }
        }
        return out.values.sortedByDescending { it.score }
    }

    /**
     * Written form of [slice] plus its plausible nominatives.
     *
     * Short phrases get the full cross-product of per-token guesses, because a
     * two-word title can need *both* words normalised («Солнечной системы» →
     * «Солнечная система»). Longer phrases only get all-verbatim and
     * all-normalised: three-plus-word titles are nominative as written in
     * practice («Большое красное пятно», «Вторая мировая война»), and the
     * cross-product would grow to 81 lookups for one n-gram.
     */
    private fun phraseForms(slice: List<String>): List<String> {
        val perToken = slice.map { listOf(it) + nominativeForms(it).drop(1) }
        val combos: List<List<String>> = if (slice.size <= 2) {
            perToken.fold(listOf(emptyList<String>())) { acc, opts ->
                acc.flatMap { prefix -> opts.map { prefix + it } }
            }
        } else {
            listOf(slice.toList(), perToken.map { it.last() })
        }
        return combos.map { capitalized(it.joinToString(" ")) }.distinct()
    }

    /**
     * The question's own noun phrase as a title guess: «перечисли спутники
     * Юпитера» → «Спутники Юпитера».
     *
     * ru.wiki names its enumeration articles the way the question asks for
     * them, so once the instruction shell is trimmed off a list question what
     * remains often *is* the dedicated list article, verbatim. That article is
     * unreachable any other way: [ATTRIBUTE_STEMS] penalises «спутники» — the
     * rule that keeps «Мэр Москвы» from outranking «Москва» — so the n-gram
     * probe scores «Спутники Юпитера» below the bare entity «Юпитер».
     *
     * Null unless the trimmed phrase is 2-4 tokens with no stop word left
     * inside it: «Океаны на Земле» is a sentence, not a title.
     */
    fun listPhrase(question: String): String? {
        val tokens = question
            .replace(Regex("[\\p{Punct}«»“”\"]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val phrase = tokens
            .dropWhile { it.lowercase() in EDGE_STOP }
            .dropLastWhile { it.lowercase() in EDGE_STOP }
        if (phrase.size !in 2..4) return null
        if (phrase.any { it.lowercase() in EDGE_STOP }) return null
        return capitalized(phrase.joinToString(" "))
    }

    /**
     * How many words of [title]'s parenthesised qualifier the [question] does
     * *not* mention, or null when it mentions none of them (so the qualifier is
     * about something else entirely and the title must not be considered).
     *
     * Shared with [FactoidAnswerer] and [RagPromptBuilder]: both lanes face the
     * same ru.wiki convention, where the bare title is a disambiguation page and
     * the article is «Сталкер (фильм)». Requiring the question to supply the
     * qualifier is what keeps «Сталкер (игра)» out of a question about a film.
     */
    fun qualifierGap(title: String, entity: String, question: String): Int? {
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

    /** ru.wiki titles start with a capital; the rest of the phrase is left as
     *  typed, so «Солнечной системы» keeps its inner casing. */
    private fun capitalized(phrase: String): String =
        if (phrase.isEmpty()) phrase
        else phrase[0].uppercaseChar() + phrase.substring(1)

    /**
     * The word as written, followed by plausible nominatives for the common
     * Russian oblique endings:
     *   Японии → Япония, Бразилии → Бразилия  (-ии → -ия)
     *   Казани → Казань, Перми → Пермь        (3rd declension, -и → -ь)
     *   Москвы → Москва, океаны → океан       (-ы → -а, or plural -ы → ∅)
     *   Байкала → Байкал, Марса → Марс        (2nd declension, drop -а)
     *   Достоевского → Достоевский            (adjectival -ого → -ий)
     *   Солнечной → Солнечная                 (adjectival -ой → -ая)
     *
     * Shared with [FactoidAnswerer], which filters every guess through a strict
     * title check; here the exactness of the title index plays the same role.
     * Both callers make over-guessing free, so the rules stay crude on purpose —
     * a real morphological analyser would cost megabytes for the same recall.
     */
    fun nominativeForms(word: String): List<String> {
        val w = word.trim()
        if (w.length < 4) return listOf(w)
        val lower = w.lowercase()
        fun cut(n: Int) = w.dropLast(n)
        val guesses = when {
            lower.endsWith("ого") -> listOf(cut(3) + "ий", cut(3) + "ый", cut(3) + "ое")
            lower.endsWith("его") -> listOf(cut(3) + "ий", cut(3) + "ее")
            lower.endsWith("ии") -> listOf(cut(2) + "ия", cut(1) + "й")
            lower.endsWith("ой") -> listOf(cut(2) + "ая", cut(2))
            lower.endsWith("ей") -> listOf(cut(2) + "я", cut(2) + "ь")
            lower.endsWith("ом") || lower.endsWith("ем") -> listOf(cut(2))
            lower.endsWith("ов") || lower.endsWith("ев") -> listOf(cut(2))
            lower.endsWith("ые") || lower.endsWith("ие") -> listOf(cut(2) + "ый", cut(2) + "ий")
            else -> when (lower.last()) {
                'и' -> listOf(cut(1) + "ь", cut(1) + "я", cut(1) + "а")
                'ы' -> listOf(cut(1) + "а", cut(1))
                'а' -> listOf(cut(1))
                'я' -> listOf(cut(1) + "ь")
                'е' -> listOf(cut(1) + "а", cut(1))
                'у' -> listOf(cut(1) + "а", cut(1))
                'ю' -> listOf(cut(1) + "я", cut(1) + "а")
                else -> emptyList()
            }
        }
        return (listOf(w) + guesses.filter { it.length >= 3 }).distinct()
    }
}
