package com.wikillm.android.rag

import com.wikillm.android.diag.DiagLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    /** A node discovered by the walker, with the link metadata that found it. */
    data class WalkedNode(
        val path: String,
        val depth: Int,
        val viaProperty: String,    // wikidata property id
        val viaLabel: String,       // human label of the link ("Жилкин С. Ф.")
        val fromPath: String,       // article that contained the link
    )

    suspend fun walk(
        searcher: ZimSearcher,
        seedPath: String,
        propertyIds: Set<String>,
        maxNodes: Int = 12,
        maxDepth: Int = 5,
    ): List<WalkedNode> = coroutineScope {
        if (seedPath.isBlank() || propertyIds.isEmpty() || maxNodes <= 0) return@coroutineScope emptyList()
        val visited = HashSet<String>()
        visited += seedPath
        val out = mutableListOf<WalkedNode>()
        // Sprint 15: BFS by depth-level, with all reads at a level fired in
        // parallel. ZimSearcher.readArticleHtml is a suspending IO call (a few
        // ms each but they add up — sequentially 12 nodes was ~600 ms). On a
        // single-shot the user is staring at a "Думаю…" spinner, so trimming
        // that wins perceptible time.
        var current: List<String> = listOf(seedPath)
        var depth = 0
        while (current.isNotEmpty() && out.size < maxNodes && depth < maxDepth) {
            val results = current.map { path ->
                async { path to searcher.readArticleHtml(path) }
            }.awaitAll()
            val nextLevel = mutableListOf<String>()
            for ((path, html) in results) {
                if (html == null) {
                    DiagLog.w(TAG, "walk: readArticleHtml null for path=$path (depth=$depth)")
                    continue
                }
                val links = InfoboxExtractor.extractWikilinks(html, propertyIds)
                if (links.isEmpty()) {
                    DiagLog.i(TAG, "walk: 0 wikilinks in '$path' (depth=$depth)")
                    continue
                }
                for (link in links) {
                    if (out.size >= maxNodes) break
                    val href = link.href
                    if (href in visited) continue
                    visited += href
                    out += WalkedNode(
                        path = href,
                        depth = depth + 1,
                        viaProperty = link.propertyId,
                        viaLabel = link.text,
                        fromPath = path,
                    )
                    nextLevel += href
                }
                if (out.size >= maxNodes) break
            }
            current = nextLevel
            depth++
        }
        DiagLog.i(TAG, "walk seed=$seedPath props=$propertyIds → ${out.size} nodes")
        out
    }
}
