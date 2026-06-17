package com.wikillm.android.rag

import android.os.ParcelFileDescriptor
import com.wikillm.android.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.File

class ZimSearcher private constructor(
    val source: String,
    private val archive: Archive,
    private val searcher: Searcher,
    private val pfd: ParcelFileDescriptor?,
) : AutoCloseable {

    data class Hit(
        val title: String,
        val path: String,
        val snippet: String,
        val score: Int,
        /**
         * Optional provenance for chain-walker results: free-form Russian like
         * "по P1366 (преемник) из «Сухих, Илья Геннадьевич»". When set, the
         * prompt-builder prepends it as a one-line excerpt header so the LLM
         * sees the link between articles, not just their bodies.
         */
        val sourceTag: String? = null,
    )

    suspend fun search(query: String, maxResults: Int): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = Query(query)
            val s = searcher.search(q)
            try {
                val total = s.estimatedMatches
                DiagLog.i(TAG, "search('$query') estimated=$total")
                if (total == 0L) return@withContext emptyList()
                val it = s.getResults(0, maxResults)
                val out = ArrayList<Hit>(maxResults)
                while (it.hasNext()) {
                    out += Hit(
                        title = safe { it.title } ?: "",
                        path = safe { it.path } ?: "",
                        snippet = safe { it.snippet } ?: "",
                        score = safe { it.score } ?: 0,
                    )
                    it.next()
                }
                out
            } finally {
                runCatching { s.dispose() }
                runCatching { q.dispose() }
            }
        }

    /**
     * Exact-title probe via libzim's `getEntryByTitle` — O(1) lookup in the
     * title index (separate from the Xapian full-text one). Lets us land on
     * canonical list articles ("Главы Тольятти", "Список глав Тольятти")
     * directly, without fighting BM25 ranking 700 candidates.
     */
    suspend fun lookupExactTitle(title: String): Hit? = withContext(Dispatchers.IO) {
        // Tier 1: O(1) exact-title lookup.
        val exact = runCatching {
            var entry = archive.getEntryByTitle(title)
            var hops = 0
            while (hops < 3 && (safe { entry.isRedirect } == true)) {
                val next = safe { entry.redirectEntry } ?: break
                entry = next
                hops++
            }
            Hit(
                title = safe { entry.title } ?: title,
                path = safe { entry.path } ?: "",
                snippet = "",
                score = 1000,
            )
        }.getOrNull()
        if (exact != null && exact.path.isNotBlank()) return@withContext exact

        // Tier 2 (build-73): SuggestionSearcher fallback. ru.wiki's title
        // index normalises case + diacritics, so "толятти" / "Тольятти " /
        // "Толятти, Самара" all match. This is the same index Kiwix's
        // search-bar autocomplete uses. Returns the best hit only.
        runCatching {
            val ss = SuggestionSearcher(archive)
            val s = ss.suggest(title)
            try {
                val total = safe { s.estimatedMatches } ?: 0L
                if (total == 0L) return@runCatching null
                val it = s.getResults(0, 1)
                if (safe { it.hasNext() } != true) return@runCatching null
                val item = safe { it.next() } ?: return@runCatching null
                Hit(
                    title = safe { item.title } ?: title,
                    path = safe { item.path } ?: "",
                    snippet = safe { item.snippet } ?: "",
                    // Slightly below exact-title (1000) so a real exact match
                    // always wins, but well above BM25 noise (~20-50).
                    score = 950,
                )
            } finally {
                runCatching { s.dispose() }
                runCatching { ss.dispose() }
            }
        }.getOrNull()
    }

    /** A list article ("Градоначальники X") and whether it's a real article
     *  or a redirect — with its own (un-followed) path and body length. */
    data class TitleProbe(val title: String, val path: String, val isRedirect: Boolean, val bodyLen: Int)

    /**
     * build-103: look up [title] WITHOUT following a redirect, and report
     * whether it's a standalone article (with body) or a redirect stub. Used
     * to detect the dedicated list article ("Градоначальники Тольятти") that
     * lookupExactTitle's redirect-follow was silently collapsing into the city
     * page. If it has its own body, that body IS the full mayor list.
     */
    suspend fun lookupRaw(title: String): TitleProbe? = withContext(Dispatchers.IO) {
        runCatching {
            val entry = archive.getEntryByTitle(title)
            val isRedirect = safe { entry.isRedirect } ?: false
            val path = safe { entry.path } ?: ""
            val bodyLen = if (!isRedirect && path.isNotBlank()) {
                val html = runCatching {
                    val item = entry.getItem(true)
                    String(item.data.data, Charsets.UTF_8)
                }.getOrNull()
                html?.let { InfoboxExtractor.bodyText(it).length } ?: 0
            } else 0
            DiagLog.i(TAG, "lookupRaw '$title' redirect=$isRedirect path='$path' bodyLen=$bodyLen")
            TitleProbe(safe { entry.title } ?: title, path, isRedirect, bodyLen)
        }.getOrNull()
    }

    /**
     * Title-prefix scan via libzim's `findByTitle`. Returns up to [limit]
     * entries whose title starts with [prefix] (case-insensitive), in title
     * order. Used by `ListIntentPipeline` to enumerate "Главы Тольятти…",
     * "Список глав Тольятти…", "Категория:Главы Тольятти" candidates without
     * any LLM planning.
     */
    suspend fun findByTitlePrefix(prefix: String, limit: Int): List<Hit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // EntryIterator is `java.util.Iterator<Entry>` — next() RETURNS
                // the Entry; the iterator itself has no title/path getters.
                val iter = archive.findByTitle(prefix)
                val out = ArrayList<Hit>(limit)
                var n = 0
                while (n < limit && (safe { iter.hasNext() } ?: false)) {
                    val entry = safe { iter.next() } ?: break
                    val t = safe { entry.title } ?: ""
                    val p = safe { entry.path } ?: ""
                    n++
                    if (t.isBlank()) continue
                    // findByTitle returns entries in title order starting >= prefix;
                    // first non-matching title means we've walked past the prefix run.
                    if (!t.startsWith(prefix, ignoreCase = true)) break
                    out += Hit(title = t, path = p, snippet = "", score = 800)
                }
                out
            }.getOrElse {
                DiagLog.w(TAG, "findByTitlePrefix('$prefix') failed", it)
                emptyList()
            }
        }

    suspend fun readArticleText(path: String): String? = withContext(Dispatchers.IO) {
        readArticleHtml(path)?.let { htmlToPlainText(it) }
    }

    /** Raw article HTML — RAG needs it to extract the infobox before flattening. */
    suspend fun readArticleHtml(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val entry = archive.getEntryByPath(path)
            val item = entry.getItem(true)
            String(item.data.data, Charsets.UTF_8)
        }.onFailure { DiagLog.w(TAG, "readArticleHtml failed for $path", it) }
            .getOrNull()
    }

    override fun close() {
        runCatching { searcher.dispose() }
        runCatching { archive.dispose() }
        runCatching { pfd?.close() }
    }

    companion object {
        private const val TAG = "ZimSearcher"

        suspend fun openPath(zimPath: String): Result<ZimSearcher> = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(zimPath)
                require(f.exists()) { "ZIM не найден: $zimPath" }
                DiagLog.i(TAG, "openPath: $zimPath (${f.length()} bytes)")
                val archive = Archive(zimPath)
                val searcher = Searcher(archive)
                ZimSearcher(zimPath, archive, searcher, pfd = null)
            }.onFailure { DiagLog.e(TAG, "openPath failed", it) }
        }

        /**
         * libkiwix 2.6.0 prebuilt .so has NO fd-based JNI methods implemented —
         * confirmed via UnsatisfiedLinkError on all four ctors. Workaround:
         * turn the ParcelFileDescriptor into a /proc/self/fd/<n> path, which is
         * a valid Linux pseudofile that resolves to the same open file. libzim
         * opens that as a plain path through its String ctor, which IS in the .so.
         *
         * The trick relies on the kernel keeping the fd open; we hold onto the
         * PFD until close() so the descriptor isn't GC'd.
         */
        suspend fun openFd(pfd: ParcelFileDescriptor, sourceLabel: String): Result<ZimSearcher> =
            withContext(Dispatchers.IO) {
                val fdNum = pfd.fd
                val size = runCatching { pfd.statSize }.getOrDefault(-1L)
                val procPath = "/proc/self/fd/$fdNum"
                DiagLog.i(TAG, "openFd: $sourceLabel fd=$fdNum size=$size path=$procPath")

                val r = runCatching {
                    val archive = Archive(procPath)
                    val searcher = Searcher(archive)
                    ZimSearcher(sourceLabel, archive, searcher, pfd = pfd)
                }
                if (r.isFailure) {
                    runCatching { pfd.close() }
                    DiagLog.e(TAG, "Archive(/proc/self/fd) failed", r.exceptionOrNull())
                }
                r
            }

        fun htmlToPlainText(html: String): String {
            val noScript = html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            val noTags = noScript.replace(Regex("<[^>]+>"), " ")
            val decoded = noTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("&#(\\d+);")) {
                    val cp = it.groupValues[1].toIntOrNull() ?: return@replace it.value
                    if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else it.value
                }
                .replace(Regex("&[a-zA-Z]+;"), " ")
            return decoded.replace(Regex("[\\s\\u00A0]+"), " ").trim()
        }

        private inline fun <T> safe(block: () -> T): T? =
            try { block() } catch (t: Throwable) { null }
    }
}
