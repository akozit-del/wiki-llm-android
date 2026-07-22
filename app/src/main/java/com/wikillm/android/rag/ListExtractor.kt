package com.wikillm.android.rag

import com.wikillm.android.diag.DiagLog
import com.wikillm.android.llm.LlmEvent
import kotlinx.coroutines.flow.Flow

/**
 * build-94 — L3X-style list extraction for "перечисли всех X" questions.
 *
 * A 4B model asked to pull every name out of one giant concatenated prompt
 * reliably returns only the first 1-3 (documented long-list failure mode,
 * arxiv 2405.02732 "Recall Them All"). Instead we run the **map phase**: one
 * short extraction call per candidate article, each producing 0-N
 * `Имя — годы` lines, then **reduce deterministically in Kotlin** (dedupe by
 * normalised name, keep the longest year span). No second LLM merge call, so
 * the model can't hallucinate during reduce.
 *
 * Cost: N short calls (N = number of candidate biographies, ~6-8) instead of
 * one big call. Each map call is tiny (one biography in, ~60 tokens out), so
 * the wall-time overhead over a single-shot is modest and the recall jump is
 * large.
 */
class ListExtractor(
    private val generate: (messages: List<Pair<String, String>>, maxTokens: Int, systemPrompt: String) -> Flow<LlmEvent>,
) {

    data class Item(val name: String, val years: String)

    // Seed (city/topic) article: open extraction over the leadership section,
    // which legitimately names several people.
    private val seedSystem =
        "Ты извлекаешь имена из выдержки Википедии под конкретный вопрос. " +
            "Внимательно прочитай ВЕСЬ текст (и карточку, и разделы про власть, и историю) " +
            "и выпиши КАЖДОГО человека, который по тексту подходит под вопрос " +
            "(например, был мэром/главой названного города) — и нынешних, и бывших, " +
            "даже если упомянут одной фразой в любом месте текста. " +
            "Формат строго: каждая строка «Имя Фамилия — годы». Годы неизвестны → «Имя Фамилия — ?». " +
            "Никаких пояснений и Markdown. Если подходящих людей нет — одно слово: НЕТ.\n" +
            "Пример:\nСергей Жилкин — 1996–2000\nНиколай Уткин — 2000–2008"

    // Biography article: trust ONLY the article's subject. His bio may mention
    // many other politicians (a Samara governor's page lists dozens) — those are
    // NOT answers. This merges L3X map + verify into one focused call.
    private fun bioSystem(subject: String): String =
        "Эта выдержка — статья Википедии про человека по имени «$subject». " +
            "Реши строго по тексту выдержки: подходит ли ИМЕННО $subject под вопрос пользователя " +
            "(например, был ли он мэром/главой именно того города, о котором спрашивают). " +
            "Если ДА — верни ровно одну строку «$subject — годы» (годы из текста, или ?). " +
            "Если НЕТ или неясно — одно слово: НЕТ. " +
            "НЕ выписывай других людей, упомянутых в тексте — только самого $subject."

    /**
     * Run the map phase over [docs] for [question]. Returns deduped items in
     * the order first seen. [onProgress] is called with (done, total) so the
     * UI can show "проверяю 3/8".
     */
    suspend fun extract(
        question: String,
        docs: List<RagPromptBuilder.DocExcerpt>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Item> {
        val seen = LinkedHashMap<String, Item>() // key = normalised name
        // Words from the question (e.g. "тольятти") — the entity name itself is
        // not a person; drop it if the model emits it as a "name".
        val questionWords = normalise(question).split(" ").filter { it.length >= 4 }.toSet()
        for ((idx, doc) in docs.withIndex()) {
            onProgress(idx, docs.size)
            val system = if (doc.isSeed) seedSystem else bioSystem(doc.title)
            val prompt = buildString {
                append("Вопрос пользователя: ").append(question).append("\n\n")
                append(doc.text).append("\n\n")
                if (doc.isSeed) append("Кого из этой выдержки нужно включить? «Имя — годы» построчно или «НЕТ».")
                else append("Подходит ли «${doc.title}» под вопрос? «${doc.title} — годы» или «НЕТ».")
            }
            val out = StringBuilder()
            generate(listOf("user" to prompt), 96, system).collect { ev ->
                if (ev is LlmEvent.Token) out.append(ev.piece)
            }
            val raw = stripThinking(out.toString()).trim()
            DiagLog.i(TAG, "Map[${idx + 1}] raw: ${raw.take(160).replace('\n', '|')}")
            // Negative sentence ("X не подходит", "не является мэром") = reject.
            val low = raw.lowercase()
            if (low.contains("не подход") || low.contains("не явля") || low.contains("нет данных")) {
                DiagLog.i(TAG, "Map[${idx + 1}] negative — skip"); continue
            }
            // Bio doc where the model just said "ДА" (no name): the subject IS
            // valid → use the article title as the name.
            if (!doc.isSeed && raw.trimEnd('.', '!', ' ').equals("ДА", ignoreCase = true)) {
                val key = normalise(doc.title)
                if (key.length >= 4 && seen[key] == null) seen[key] = Item(doc.title, "?")
                DiagLog.i(TAG, "Map[${idx + 1}] bare-ДА → ${doc.title}"); continue
            }
            var items = parseItems(raw)
            // Safety net: a biography doc must only contribute its own subject.
            // If the model ignored that and dumped other politicians (a Samara
            // governor's page lists dozens), keep only names overlapping the
            // article title.
            if (!doc.isSeed && items.size > 1) {
                val titleWords = normalise(doc.title).split(" ").filter { it.length >= 4 }.toSet()
                items = items.filter { item ->
                    val nameWords = normalise(item.name).split(" ").toSet()
                    titleWords.any { it in nameWords }
                }
                if (items.isEmpty()) DiagLog.i(TAG, "Map[${idx + 1}] dropped — no subject match")
            }
            for (it in items) {
                val key = normalise(it.name)
                if (key.length < 4) continue // junk
                // Drop the entity itself ("Тольятти") — it's a place, not a mayor.
                if (key in questionWords || (key.split(" ").size == 1 && key in questionWords)) continue
                val existing = seen[key]
                if (existing == null) {
                    seen[key] = it
                } else if (existing.years == "?" && it.years != "?") {
                    seen[key] = it // upgrade with a real year span
                }
            }
            DiagLog.i(TAG, "Map[${idx + 1}/${docs.size}] '${doc.title}': ${items.size} items")

            // build-106 short-circuit: the dedicated list article
            // ("Градоначальники X") holds the WHOLE answer. If it just yielded a
            // solid list (≥3 names), the remaining docs are the city article
            // (dupes) and bio-verify calls (mostly НЕТ) — ~15 min of no new
            // info. Stop here. This is the option-1 speedup: 6 calls → 1-2,
            // ~4.5 min → ~1.5 min, zero quality loss.
            if (doc.isListArticle && seen.size >= 3) {
                DiagLog.i(TAG, "Short-circuit: list article gave ${seen.size} names, skipping ${docs.size - idx - 1} docs")
                break
            }
        }
        onProgress(docs.size, docs.size)
        val result = seen.values.toList()
        DiagLog.i(TAG, "Map merged: ${result.size} unique — ${result.joinToString { it.name }}")
        return result
    }

    /**
     * Parse "Имя — годы" lines. Handles BOTH model output styles:
     *  - Qwen: "Илья Сухих — ?", "Сергей Жилкин — 1996–2000"
     *  - Gemma: "**Исполнительная власть:**", "1. Уткин, Николай Дмитриевич
     *    (род.1949) — 1992 год – 1994 год" (markdown bold, numbering,
     *    comma-form names, parenthetical birth years, "год" words).
     */
    private fun parseItems(raw: String): List<Item> {
        val out = mutableListOf<Item>()
        for (line0 in raw.lineSequence()) {
            var line = line0.trim()
            if (line.isBlank()) continue
            // Strip markdown bold/italic and leading bullets/numbering ("1. ", "- ", "2) ").
            line = line.replace("**", "").replace("__", "")
            line = line.replace(Regex("^[\\-*•\\u2013\\u2014\\d]+[.)]?\\s+"), "").trim()
            line = line.replace(Regex("^(ДА|YES|Да)[\\s,:-]+"), "").trim()
            if (line.isBlank()) continue
            if (line.equals("НЕТ", ignoreCase = true) || line.equals("NO", ignoreCase = true)) continue
            // Section header ("Исполнительная власть:") — ends with ":" and no digits.
            if (line.endsWith(":") && !line.any { it.isDigit() }) continue
            // Split name — years on the FIRST dash that is surrounded by spaces
            // (so commas/dashes inside the name or "(1960—2008)" don't split it).
            val m = Regex("^(.+?)\\s+[—–-]\\s+(.+)$").find(line)
            var name = (m?.groupValues?.get(1) ?: line).trim()
            var years = (m?.groupValues?.get(2) ?: "?").trim()
            // Drop parentheticals from the name: "(род.1949)", "(1960—2008)".
            name = name.replace(Regex("\\(.*?\\)"), "").trim().trim('*', '«', '»', '"', '.', ',').trim()
            // Years: must contain a digit else "?"; strip the "год/года" words.
            years = if (years.any { it.isDigit() })
                years.replace(Regex("\\s*год[а-я]*"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "?" }
            else "?"
            if (name.length in 4..70 && looksLikeName(name)) out += Item(name, years)
        }
        return out
    }

    /** Heuristic: a person name has at least one capitalised Cyrillic word. */
    private fun looksLikeName(s: String): Boolean {
        if (s.any { it.isDigit() } && !s.any { it.isLetter() }) return false
        return Regex("[А-ЯЁ][а-яё]+").containsMatchIn(s)
    }

    private fun normalise(name: String): String =
        name.lowercase().replace(Regex("[^а-яёa-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun stripThinking(text: String): String {
        if (!text.contains("<think>") && !text.contains("</think>")) return text
        return text.substringAfterLast("</think>").ifBlank { text.substringBefore("<think>") }.trim()
    }

    companion object {
        private const val TAG = "ListExtractor"
    }
}
