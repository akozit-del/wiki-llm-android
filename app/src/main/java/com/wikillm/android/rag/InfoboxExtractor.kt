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
        // Places — administrative + geographic basics
        "P6" to "Глава/мэр",
        "P1082" to "Население",
        "P2046" to "Площадь",
        "P571" to "Основан",
        "P17" to "Страна",
        "P131" to "Регион",
        "P36" to "Столица",
        "P30" to "Континент",
        "P38" to "Валюта",
        "P122" to "Форма правления",
        "P194" to "Законодательный орган",
        "P159" to "Штаб-квартира",
        "P421" to "Часовой пояс",
        // Person — political/career basics
        "P39" to "Должность",
        "P569" to "Дата рождения",
        "P570" to "Дата смерти",
        "P19" to "Место рождения",
        "P20" to "Место смерти",
        "P102" to "Партия",
        "P69" to "Образование",
        "P106" to "Род занятий",
        "P166" to "Награды",
        "P184" to "Научный руководитель",
        "P1365" to "Предшественник",
        "P1366" to "Преемник",
        // Person — family
        "P26" to "Супруг(а)",
        "P22" to "Отец",
        "P25" to "Мать",
        "P40" to "Дети",
        // Works — books / films / music
        "P50" to "Автор",
        "P57" to "Режиссёр",
        "P58" to "Сценарист",
        "P175" to "Исполнитель",
        "P162" to "Продюсер",
        "P136" to "Жанр",
        "P577" to "Дата публикации",
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
        // Sprint 22: ru.wiki uses several variant classes for infoboxes.
        // Cover the most common ones explicitly — picks up cards on
        // persons, organisations, films, books, etc.
        val ib = doc.selectFirst(INFOBOX_SELECTOR) ?: return Card(title, emptyList())

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
    /**
     * Russian labels mapped to the Wikidata property they correspond to —
     * used by the Tier-2 fallback in extractWikilinks when an infobox has
     * no `data-wikidata-property-id` attributes at all (lots of older or
     * hand-rolled cards in ru.wiki land this way). Keep keys lowercase.
     */
    private val LABEL_TO_PROP: Map<String, String> = mapOf(
        "глава" to "P6", "мэр" to "P6", "градоначальник" to "P6",
        "предшественник" to "P1365", "предыдущий" to "P1365",
        "преемник" to "P1366", "следующий" to "P1366",
        "супруг" to "P26", "супруга" to "P26", "жена" to "P26", "муж" to "P26",
        "отец" to "P22",
        "мать" to "P25",
        "дети" to "P40", "ребёнок" to "P40", "ребенок" to "P40",
        "автор" to "P50", "авторы" to "P50",
        "режиссёр" to "P57", "режиссер" to "P57",
        "сценарист" to "P58",
        "продюсер" to "P162",
        "исполнитель" to "P175",
        "научный руководитель" to "P184",
        "должность" to "P39",
        "столица" to "P36",
        "штаб-квартира" to "P159",
        "владелец" to "P127",
        "основатель" to "P112",
    )

    fun extractWikilinks(html: String, propertyIds: Set<String>): List<WikiLink> {
        if (propertyIds.isEmpty()) return emptyList()
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val ib = doc.selectFirst("table.infobox") ?: return emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<WikiLink>()
        for (pid in propertyIds) {
            // Some infoboxes wrap the value cell with `data-wikidata-property-id`
            // and put `<a>` inside; others put `data-wikidata-property-id`
            // directly on the `<a>`; others still put it on a `<span>` wrapping
            // the link. Walk both: for each property-tagged element, treat the
            // element itself as a link if it's an `<a>`, otherwise scan its
            // descendant `<a>` tags. Same dedup either way.
            val cells = ib.select("[data-wikidata-property-id=$pid]")
            for (cell in cells) {
                val anchors: List<org.jsoup.nodes.Element> =
                    if (cell.tagName().equals("a", ignoreCase = true) && cell.hasAttr("href")) {
                        listOf(cell)
                    } else {
                        cell.select("a[href]").toList()
                    }
                for (a in anchors) {
                    val raw = a.attr("href").trim()
                    if (raw.startsWith("http")) continue
                    val href = normaliseZimHref(raw)
                    if (href.isEmpty()) continue
                    val key = "$pid|$href"
                    if (!seen.add(key)) continue
                    out += WikiLink(propertyId = pid, text = clean(a.text()), href = href)
                }
            }
        }

        // Sprint 13: Tier-2 fallback — many ru.wiki infoboxes (especially
        // person-bios that were updated by editors rather than re-rendered from
        // Wikidata) have NO data-wikidata-property-id at all. Walk th/td rows
        // and match the label text against LABEL_TO_PROP. Only emit if the
        // mapped property is in the requested set, so we don't pollute with
        // links the caller didn't ask for.
        for (tr in ib.select("tr")) {
            val th = tr.selectFirst("th") ?: continue
            val td = tr.selectFirst("td") ?: continue
            val label = clean(th.text()).lowercase()
            if (label.isBlank()) continue
            val pid = LABEL_TO_PROP[label]
                ?: LABEL_TO_PROP.entries.firstOrNull { label.contains(it.key) }?.value
                ?: continue
            if (pid !in propertyIds) continue
            for (a in td.select("a[href]")) {
                val raw = a.attr("href").trim()
                if (raw.startsWith("http")) continue
                val href = normaliseZimHref(raw)
                if (href.isEmpty()) continue
                val key = "$pid|$href"
                if (!seen.add(key)) continue
                out += WikiLink(propertyId = pid, text = clean(a.text()), href = href)
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

    /**
     * Sprint 17: pull text from sections whose H2/H3 heading text contains
     * any of [anchors]. Returns text from "Городская власть", "Главы города",
     * "Руководство" etc. — the sections where ru.wiki actually keeps lists
     * of mayors / governors / heads-of-government — concatenated in order,
     * capped at [maxChars]. Returns empty if no matching section was found,
     * so the caller can fall back to bodyText().
     */
    fun sectionsByAnchor(html: String, anchors: List<String>, maxChars: Int = 4000): String {
        if (anchors.isEmpty()) return ""
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return ""
        // Drop the same chrome we drop from bodyText.
        doc.select(
            "script, style, table.infobox, sup.reference, .reference, " +
                ".mw-editsection, .noprint, .navbox, .metadata",
        ).remove()

        val anchorLowered = anchors.map { it.lowercase() }
        val sb = StringBuilder()
        // Walk H2/H3 headings in document order. For each match, collect
        // following siblings until the next H2/H3 (sections are flat in
        // mw-output HTML, not nested).
        for (h in doc.select("h2, h3")) {
            val headText = clean(h.text()).lowercase()
            if (headText.isBlank()) continue
            if (anchorLowered.none { headText.contains(it) }) continue
            sb.append("[").append(clean(h.text())).append("] ")
            var sib = h.nextElementSibling()
            while (sib != null && sib.tagName().lowercase() !in setOf("h2", "h3")) {
                val t = clean(sib.text())
                if (t.isNotBlank()) {
                    sb.append(t).append(" ")
                    if (sb.length >= maxChars) break
                }
                sib = sib.nextElementSibling()
            }
            sb.append("\n\n")
            if (sb.length >= maxChars) break
        }
        return clean(sb.toString()).take(maxChars)
    }

    /**
     * Normalise a ZIM href as emitted by jsoup into the form libzim expects
     * for `Archive.getEntryByPath`:
     *   • drop "./" / "../" Parsoid prefixes
     *   • drop legacy "A/" namespace prefix (modern ZIM7+ stores entries
     *     without it; passing it makes getEntryByPath return null)
     *   • URL-decode percent-escapes — jsoup gives us
     *     "%D0%A1%D1%83%D1%85%D0%B8%D1%85", libzim expects "Сухих" verbatim
     *   • split off any fragment ("#section") — libzim ignores it but it
     *     skews equality checks for dedup
     */
    fun normaliseZimHref(raw: String): String {
        var h = raw.trim()
        if (h.isEmpty()) return ""
        if (h.startsWith("./")) h = h.drop(2)
        if (h.startsWith("../")) h = h.drop(3)
        // Strip fragment if present
        val hashIdx = h.indexOf('#')
        if (hashIdx >= 0) h = h.substring(0, hashIdx)
        h = try { java.net.URLDecoder.decode(h, Charsets.UTF_8) } catch (_: Throwable) { h }
        if (h.startsWith("A/")) h = h.drop(2)
        return h.trim()
    }

    // Sprint 22: cover ru.wiki's main infobox variants. "infobox" is the
    // canonical class, but Parsoid emits "infobox-vcard" for person bios,
    // and a few cards use "infobox-mw" / "cards" / "wikitable infobox".
    private const val INFOBOX_SELECTOR =
        "table.infobox, table.infobox-vcard, table.infobox-mw, " +
            "table.cards, table.wikitable.infobox, .infobox"

    private val REF = Regex("\\[\\s*\\d+\\s*]")
    private val ARROWS = Regex("[\\u2190-\\u21FF]")
    private val WS = Regex("[\\s\\u00A0]+")
    private fun clean(s: String): String =
        s.replace(REF, " ").replace(ARROWS, " ").replace(WS, " ").trim()
}
