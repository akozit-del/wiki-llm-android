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

    private val mapSystem =
        "Ты извлекаешь имена из выдержки Википедии под конкретный вопрос. " +
            "Выпиши КАЖДОГО человека, который по тексту выдержки подходит под вопрос " +
            "(например, был мэром/главой названного города) — даже если он упомянут кратко " +
            "или позже занимал другие посты. Сама статья тоже может быть про такого человека. " +
            "Формат строго: каждая строка «Имя Фамилия — годы». Годы неизвестны → «Имя Фамилия — ?». " +
            "Никаких пояснений и Markdown. Если в выдержке вообще нет подходящего человека — одно слово: НЕТ.\n" +
            "Пример ответа:\n" +
            "Сергей Жилкин — 1996–2000\n" +
            "Николай Уткин — 2000–2008"

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
            val prompt = buildString {
                append("Вопрос пользователя: ").append(question).append("\n\n")
                append(doc.text).append("\n\n")
                append("Кого из этой выдержки нужно включить в ответ на вопрос? ")
                append("Список «Имя — годы» или «НЕТ».")
            }
            val out = StringBuilder()
            generate(listOf("user" to prompt), 128, mapSystem).collect { ev ->
                if (ev is LlmEvent.Token) out.append(ev.piece)
            }
            val raw = stripThinking(out.toString())
            DiagLog.i(TAG, "Map[${idx + 1}] raw: ${raw.take(160).replace('\n', '|')}")
            val items = parseItems(raw)
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
            val line = line0.trim().removePrefix("*").removePrefix("-").removePrefix("•").trim()
            if (line.isBlank()) continue
            if (line.equals("НЕТ", ignoreCase = true)) continue
            // Split on the first em/en dash or hyphen surrounded by spaces, or a colon.
            val m = Regex("^(.+?)\\s*[—–:-]\\s*(.+)$").find(line)
            if (m != null) {
                val name = m.groupValues[1].trim().trim('*', '«', '»', '"').trim()
                val years = m.groupValues[2].trim().trim('*', '.', '«', '»').trim()
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
