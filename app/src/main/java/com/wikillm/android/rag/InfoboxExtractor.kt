package com.wikillm.android.rag

import org.jsoup.Jsoup

/**
 * Pulls an article's infobox (карточка) out of the raw Wikipedia/ZIM HTML as a
 * clean "Метка: значение" block, so the model gets factoid answers (мэр,
 * население, площадь…) the same way a browsing LLM would — from the structured
 * card, not by guessing from prose.
 *
 * Two tiers, mirroring how production scrapers and the official Wikidata
 * "infobox-export" gadget read these pages:
 *   1. Wikidata property ids embedded on values (`data-wikidata-property-id`),
 *      e.g. P6 = head of government (мэр/глава). Language-agnostic and exact.
 *   2. Fallback: plain `<th>label</th><td>value</td>` rows of the infobox.
 *
 * Tier-1 facts are emitted first (so the answer is line 1); Tier-2 fills in the
 * rest. Footnote refs ("[ 1 ]"), trend arrows (↗↘) and coordinate widgets are
 * stripped.
 */
object InfoboxExtractor {

    /** Curated, high-value properties surfaced first, with canonical RU labels. */
    private val PRIORITY = linkedMapOf(
        "P6" to "Глава/мэр",
        "P1082" to "Население",
        "P2046" to "Площадь",
        "P571" to "Основан",
        "P17" to "Страна",
        "P131" to "Регион",
        "P39" to "Должность",
        "P569" to "Дата рождения",
        "P570" to "Дата смерти",
        "P19" to "Место рождения",
        "P102" to "Партия",
        "P69" to "Образование",
        "P1365" to "Предшественник",
        "P1366" to "Преемник",
    )

    /** th/td rows whose label is noise for QA (icons, codes, coordinates…). */
    private val LABEL_BLOCK = listOf(
        "флаг", "герб", "гимн", "координат", "карта", "индекс", "телефонный",
        "почтов", "окато", "октмо", "идентификатор", "медиафайл", "викисклад",
        "аудио", "логотип", "изображени", "цифровой",
    )

    data class Card(val title: String, val lines: List<String>) {
        val isEmpty get() = lines.isEmpty()
        fun block(): String = if (lines.isEmpty()) "" else "Карточка:\n" + lines.joinToString("\n")
    }

    /** Extract the first infobox as priority-ordered "Метка: значение" lines. */
    fun extract(html: String, title: String, maxLines: Int = 12, maxValueLen: Int = 140): Card {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return Card(title, emptyList())
        val ib = doc.selectFirst("table.infobox") ?: return Card(title, emptyList())

        val byLabel = LinkedHashMap<String, String>() // ordered, deduped by label
        val seenValues = HashSet<String>()

        fun add(label: String, rawValue: String) {
            val value = clean(rawValue).take(maxValueLen).trim()
            if (label.isBlank() || value.isBlank()) return
            if (byLabel.keys.any { it.equals(label, ignoreCase = true) }) return
            if (value.lowercase() in seenValues) return
            byLabel[label] = value
            seenValues += value.lowercase()
        }

        // Tier 1: guaranteed facts via Wikidata property ids.
        for ((pid, label) in PRIORITY) {
            val el = ib.selectFirst("[data-wikidata-property-id=$pid]") ?: continue
            add(label, el.text())
        }

        // Tier 2: human-labelled th/td rows.
        for (tr in ib.select("tr")) {
            val th = tr.selectFirst("th") ?: continue
            val td = tr.selectFirst("td") ?: continue
            val label = clean(th.text())
            if (label.length !in 1..40) continue
            if (LABEL_BLOCK.any { label.lowercase().contains(it) }) continue
            add(label, td.text())
        }

        return Card(title, byLabel.entries.take(maxLines).map { "${it.key}: ${it.value}" })
    }

    /** Article body as plain text, with infobox, references and chrome removed. */
    fun bodyText(html: String): String {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return ""
        doc.select(
            "script, style, table.infobox, sup.reference, .reference, " +
                ".mw-editsection, .noprint, .navbox, .metadata",
        ).remove()
        return clean(doc.body().text())
    }

    private val REF = Regex("\\[\\s*\\d+\\s*]")
    private val ARROWS = Regex("[\\u2190-\\u21FF]")
    private val WS = Regex("[\\s\\u00A0]+")
    private fun clean(s: String): String =
        s.replace(REF, " ").replace(ARROWS, " ").replace(WS, " ").trim()
}
