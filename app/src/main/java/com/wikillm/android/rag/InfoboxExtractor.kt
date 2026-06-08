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
        /** Value for the first line whose label equals [label] (case-insensitive). */
        fun field(label: String): String? = lines
            .firstOrNull { it.substringBefore(":").trim().equals(label, ignoreCase = true) }
            ?.substringAfter(":")?.trim()
            ?.takeIf { it.isNotBlank() }
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

    /**
     * Wikilink returned by [extractWikilinks]: the property the link came from
     * (P6/P1365/…), the visible text of the link, and the ZIM-relative href
     * (e.g. `A/Жилкин,_Сергей_Фёдорович`). The href is what we feed back into
     * `Archive.getEntryByPath`.
     */
    data class WikiLink(val propertyId: String, val text: String, val href: String)

    /**
     * Extract wikilinks from the first infobox for the specified Wikidata
     * property ids. Used by chain-walker to follow P1365 (предшественник) /
     * P1366 (преемник) / P6 (глава) / P39 (должность) and build a list of
     * "next-hop" article paths without involving the LLM at all.
     *
     * Returns links in the order they appear in the infobox. Duplicates are
     * de-duplicated by href.
     */
    fun extractWikilinks(html: String, propertyIds: Set<String>): List<WikiLink> {
        if (propertyIds.isEmpty()) return emptyList()
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val ib = doc.selectFirst("table.infobox") ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<WikiLink>()
        for (pid in propertyIds) {
            // Some infoboxes wrap the value cell with `data-wikidata-property-id`;
            // others put it on the <a> directly. Cover both.
            val cells = ib.select(
                "td[data-wikidata-property-id=$pid], " +
                    "tr td[data-wikidata-property-id=$pid], " +
                    "[data-wikidata-property-id=$pid]",
            )
            for (cell in cells) {
                for (a in cell.select("a[href]")) {
                    var href = a.attr("href").trim()
                    // ZIM/mwoffliner emits relative hrefs like "Иванов" or "A/Иванов".
                    // Normalise: strip leading "./" or "../". Skip external links.
                    if (href.startsWith("http")) continue
                    if (href.startsWith("./")) href = href.drop(2)
                    if (href.startsWith("../")) href = href.drop(3)
                    if (href.isEmpty()) continue
                    val key = "$pid|$href"
                    if (!seen.add(key)) continue
                    out += WikiLink(propertyId = pid, text = clean(a.text()), href = href)
                }
            }
        }
        return out
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
