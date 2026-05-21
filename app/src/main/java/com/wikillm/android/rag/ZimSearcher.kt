package com.wikillm.android.rag

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import java.io.File

/**
 * Wraps libkiwix's Archive + Searcher into a coroutine-friendly facade.
 * One instance binds to one ZIM file; create via [open] and keep around
 * — opening is cheap (libzim just mmaps the file).
 */
class ZimSearcher private constructor(
    val zimPath: String,
    private val archive: Archive,
    private val searcher: Searcher,
) : AutoCloseable {

    data class Hit(
        val title: String,
        val path: String,
        val snippet: String,   // libzim returns an HTML snippet with <b> highlights
        val score: Int,
    )

    suspend fun search(query: String, maxResults: Int): List<Hit> =
        withContext(Dispatchers.IO) {
            val q = Query(query)
            val s = searcher.search(q)
            try {
                val total = s.estimatedMatches
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
     * Reads the full article body for a path returned by [search] and
     * returns it as plain text (HTML tags stripped, whitespace normalised).
     */
    suspend fun readArticleText(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val entry = archive.getEntryByPath(path)
            val item = entry.getItem(true)
            val blob = item.data
            // libkiwix 2.6: Blob.getData() returns byte[] (older versions used String)
            val html = String(blob.data, Charsets.UTF_8)
            htmlToPlainText(html)
        }.onFailure {
            Log.w(TAG, "readArticleText failed for $path: ${it.message}")
        }.getOrNull()
    }

    override fun close() {
        runCatching { searcher.dispose() }
        runCatching { archive.dispose() }
    }

    companion object {
        private const val TAG = "ZimSearcher"

        suspend fun open(zimPath: String): Result<ZimSearcher> = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(zimPath)
                require(f.exists()) { "ZIM не найден: $zimPath" }
                val archive = Archive(zimPath)
                val searcher = Searcher(archive)
                ZimSearcher(zimPath, archive, searcher)
            }
        }

        /**
         * Quick & dirty HTML→text: strip <script>/<style> blocks, drop tags,
         * collapse whitespace, decode a handful of HTML entities. Good enough
         * to feed Wikipedia article text into an LLM context window.
         */
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
