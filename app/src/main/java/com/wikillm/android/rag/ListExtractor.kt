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
            "Выпиши КАЖДОГО человека, который по тексту выдержки подходит под вопрос " +
            "(например, был мэром/главой названного города) — даже если он упомянут кратко. " +
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
        docs.forEachIndexed { idx, doc ->
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
                DiagLog.i(TAG, "Map[${idx + 1}] negative — skip"); return@forEachIndexed
            }
            // Bio doc where the model just said "ДА" (no name): the subject IS
            // valid → use the article title as the name.
            if (!doc.isSeed && raw.trimEnd('.', '!', ' ').equals("ДА", ignoreCase = true)) {
                val key = normalise(doc.title)
                if (key.length >= 4 && seen[key] == null) seen[key] = Item(doc.title, "?")
                DiagLog.i(TAG, "Map[${idx + 1}] bare-ДА → ${doc.title}"); return@forEachIndexed
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
                val existing = seen[key]
                if (existing == null) {
                    seen[key] = it
                } else if (existing.years == "?" && it.years != "?") {
                    seen[key] = it // upgrade with a real year span
                }
            }
            DiagLog.i(TAG, "Map[${idx + 1}/${docs.size}] '${doc.title}': ${items.size} items")
        }
        onProgress(docs.size, docs.size)
        val result = seen.values.toList()
        DiagLog.i(TAG, "Map merged: ${result.size} unique — ${result.joinToString { it.name }}")
        return result
    }

    /** Parse "Имя — годы" lines; tolerant of «-», «—», «:» separators. */
    private fun parseItems(raw: String): List<Item> {
        val out = mutableListOf<Item>()
        for (line0 in raw.lineSequence()) {
            var line = line0.trim().removePrefix("*").removePrefix("-").removePrefix("•").trim()
            if (line.isBlank()) continue
            // Strip leading verdict words the model sometimes prepends.
            line = line.replace(Regex("^(ДА|YES|Да)[\\s,:-]+"), "").trim()
            if (line.equals("НЕТ", ignoreCase = true) || line.equals("NO", ignoreCase = true)) continue
            // Split on the first em/en dash or hyphen surrounded by spaces, or a colon.
            val m = Regex("^(.+?)\\s*[—–:-]\\s*(.+)$").find(line)
            if (m != null) {
                val name = m.groupValues[1].trim().trim('*', '«', '»', '"').trim()
                var years = m.groupValues[2].trim().trim('*', '.', '«', '»').trim()
                // "годы" / "год" / "?" are placeholders, not real year spans.
                if (years.equals("годы", ignoreCase = true) || years.equals("год", ignoreCase = true) ||
                    !years.any { it.isDigit() }) {
                    years = "?"
                }
                if (name.length in 4..60 && looksLikeName(name)) {
                    out += Item(name, years.ifBlank { "?" })
                }
            } else if (line.length in 4..60 && looksLikeName(line)) {
                out += Item(line.trim('*', '«', '»', '"').trim(), "?")
            }
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
