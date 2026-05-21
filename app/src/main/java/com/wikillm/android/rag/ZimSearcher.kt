package com.wikillm.android.rag

import android.os.ParcelFileDescriptor
import com.wikillm.android.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import org.kiwix.libzim.FdInput
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
         * Open a ZIM from a SAF-derived ParcelFileDescriptor. libkiwix's plain
         * Archive(FileDescriptor) JNI is sometimes missing from prebuilt .so; the
         * embedded constructor Archive(FdInput[]) is the path actually used by the
         * official Kiwix Android app, so we try that first and fall back if needed.
         */
        suspend fun openFd(pfd: ParcelFileDescriptor, sourceLabel: String): Result<ZimSearcher> =
            withContext(Dispatchers.IO) {
                val size = runCatching { pfd.statSize }.getOrDefault(-1L)
                DiagLog.i(TAG, "openFd: $sourceLabel size=$size")

                val attempts = listOf<Triple<String, () -> Archive, String>>(
                    Triple("Archive(FdInput[])", {
                        val arr = arrayOf(FdInput(pfd.fileDescriptor, 0L, if (size > 0) size else 0L))
                        Archive(arr)
                    }, "FdInput array"),
                    Triple("Archive(FdInput)", {
                        Archive(FdInput(pfd.fileDescriptor, 0L, if (size > 0) size else 0L))
                    }, "single FdInput"),
                    Triple("Archive(FileDescriptor, offset, size)", {
                        Archive(pfd.fileDescriptor, 0L, if (size > 0) size else 0L)
                    }, "embedded FileDescriptor"),
                    Triple("Archive(FileDescriptor)", {
                        Archive(pfd.fileDescriptor)
                    }, "plain FileDescriptor"),
                )

                for ((label, ctor, hint) in attempts) {
                    val r = runCatching {
                        DiagLog.i(TAG, "Trying $label ($hint)")
                        val archive = ctor()
                        val searcher = Searcher(archive)
                        ZimSearcher(sourceLabel, archive, searcher, pfd = pfd)
                    }
                    if (r.isSuccess) {
                        DiagLog.i(TAG, "OK with $label")
                        return@withContext r
                    } else {
                        DiagLog.w(TAG, "$label failed: ${r.exceptionOrNull()?.javaClass?.simpleName} ${r.exceptionOrNull()?.message?.take(200)}")
                    }
                }
                runCatching { pfd.close() }
                Result.failure(RuntimeException("Все 4 варианта Archive(fd) не сработали — смотри Диагностику"))
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
