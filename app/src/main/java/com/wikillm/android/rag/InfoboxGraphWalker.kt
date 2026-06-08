package com.wikillm.android.rag

import com.wikillm.android.diag.DiagLog

/**
 * Programmatic BFS over Wikipedia's infobox link graph — no LLM involved.
 *
 * Wikipedia infoboxes carry Wikidata property IDs on their value cells
 * (`data-wikidata-property-id="P6"` = head of government, `P1365` =
 * predecessor, `P1366` = successor, …). Following those links across a few
 * hops lets us assemble entity chains the LLM would otherwise have to plan
 * with text — for example: city → P6 → current mayor → P1365 → predecessor
 * → P1365 → predecessor → … building the full historical list of mayors,
 * deterministically.
 *
 * The walker reads articles via [ZimSearcher.readArticleHtml] and parses
 * link sets via [InfoboxExtractor.extractWikilinks]. It dedupes by path and
 * caps the total set so a runaway graph (e.g. P131 = is-in or P361 = part-of
 * in a federated state) can't explode.
 */
object InfoboxGraphWalker {

    private const val TAG = "GraphWalker"

    /**
     * Run BFS starting from [seedPath], following infobox wikilinks whose
     * Wikidata property id is in [propertyIds]. Returns the visited paths in
     * BFS order (the seed is NOT included), up to [maxNodes].
     */
    suspend fun walk(
        searcher: ZimSearcher,
        seedPath: String,
        propertyIds: Set<String>,
        maxNodes: Int = 8,
        maxDepth: Int = 4,
    ): List<String> {
        if (seedPath.isBlank() || propertyIds.isEmpty() || maxNodes <= 0) return emptyList()
        val visited = HashSet<String>()
        visited += seedPath
        val out = mutableListOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>() // (path, depth)
        queue += seedPath to 0
        while (queue.isNotEmpty() && out.size < maxNodes) {
            val (path, depth) = queue.removeFirst()
            if (depth >= maxDepth) continue
            val html = searcher.readArticleHtml(path) ?: continue
            val links = InfoboxExtractor.extractWikilinks(html, propertyIds)
            if (links.isEmpty()) continue
            for (link in links) {
                if (out.size >= maxNodes) break
                val href = link.href
                if (href in visited) continue
                visited += href
                out += href
                queue += href to (depth + 1)
            }
        }
        DiagLog.i(TAG, "walk seed=$seedPath props=$propertyIds → ${out.size} nodes")
        return out
    }
}
