package com.wikillm.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class KiwixApi(
    private val client: OkHttpClient = defaultClient,
) {
    companion object {
        const val INDEX_URL = "https://download.kiwix.org/zim/wikipedia/"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        // Captures: filename, date, size
        // Apache row: <a href="filename.zim">filename.zim</a></td><td align="right">2024-05-15 10:23  </td><td align="right">110G</td>
        private val ROW_REGEX = Regex(
            """<a\s+href="([^"]+\.zim)"[^>]*>[^<]+</a>\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
            RegexOption.IGNORE_CASE
        )

        // Parse filename like: wikipedia_en_all_maxi_2024-05.zim
        private val FILENAME_REGEX = Regex(
            """^wikipedia_([a-z]{2,3})_([a-z][a-z0-9]*)_(maxi|mini|nopic)_(\d{4}-\d{2})\.zim$""",
            RegexOption.IGNORE_CASE
        )
    }

    suspend fun fetchCatalog(): List<KiwixEntry> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(INDEX_URL)
            .header("Accept", "text/html")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Kiwix HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("Kiwix: empty body")
            parseIndex(body)
        }
    }

    internal fun parseIndex(html: String): List<KiwixEntry> {
        val entries = mutableListOf<KiwixEntry>()
        for (m in ROW_REGEX.findAll(html)) {
            val name = m.groupValues[1]
            val date = m.groupValues[2]
            val sizeStr = m.groupValues[3]
            val sizeBytes = parseSize(sizeStr) ?: continue

            val fm = FILENAME_REGEX.matchEntire(name)
            val lang = fm?.groupValues?.getOrNull(1)
            val topic = fm?.groupValues?.getOrNull(2)
            val variant = fm?.groupValues?.getOrNull(3)?.lowercase()

            entries += KiwixEntry(
                filename = name,
                sizeBytes = sizeBytes,
                date = date,
                downloadUrl = INDEX_URL + name,
                language = lang,
                topic = topic,
                variant = variant,
            )
        }
        return entries.sortedWith(
            compareBy<KiwixEntry> { it.language ?: "zz" }
                .thenBy { it.topic ?: "zzz" }
                .thenByDescending { it.date }
        )
    }

    /** Apache-style size: "5.2M", "110G", "850K", "1024" */
    private fun parseSize(s: String): Long? {
        val cleaned = s.trim()
        if (cleaned.isEmpty() || cleaned == "-") return null
        val suffix = cleaned.last()
        val (number, mult) = when (suffix.uppercaseChar()) {
            'K' -> cleaned.dropLast(1) to 1024L
            'M' -> cleaned.dropLast(1) to 1024L * 1024
            'G' -> cleaned.dropLast(1) to 1024L * 1024 * 1024
            'T' -> cleaned.dropLast(1) to 1024L * 1024 * 1024 * 1024
            else -> cleaned to 1L
        }
        val v = number.trim().toDoubleOrNull() ?: return null
        return (v * mult).toLong()
    }
}
