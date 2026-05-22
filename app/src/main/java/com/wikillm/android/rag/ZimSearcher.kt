package com.wikillm.android.rag

import android.os.ParcelFileDescriptor
import com.wikillm.android.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
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

    suspend fun readArticleText(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val entry = archive.getEntryByPath(path)
            val item = entry.getItem(true)
            val blob = item.data
            val html = String(blob.data, Charsets.UTF_8)
            htmlToPlainText(html)
        }.onFailure { DiagLog.w(TAG, "readArticleText failed for $path", it) }
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
