package com.wikillm.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HfApi(
    private val client: OkHttpClient = defaultClient,
    private val json: Json = lenientJson,
) {
    companion object {
        private const val BASE = "https://huggingface.co"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        val lenientJson: Json by lazy {
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            }
        }
    }

    suspend fun searchModels(query: String, limit: Int = 40): List<HfModel> =
        withContext(Dispatchers.IO) {
            val effectiveQuery = if (query.isBlank()) "gguf" else "$query gguf"
            val url = buildString {
                append(BASE).append("/api/models")
                append("?search=").append(encode(effectiveQuery))
                append("&filter=gguf")
                append("&sort=downloads&direction=-1")
                append("&limit=").append(limit)
            }
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HF search HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("HF: empty body")
                json.decodeFromString<List<HfModel>>(body)
            }
        }

    suspend fun listFiles(modelId: String): List<HfFile> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/api/models/${encodePath(modelId)}/tree/main?recursive=true"
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HF tree HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("HF: empty body")
                json.decodeFromString<List<HfFile>>(body)
            }
        }

    fun fileUrl(modelId: String, path: String): String =
        "$BASE/${encodePath(modelId)}/resolve/main/${encodePath(path)}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun encodePath(s: String): String =
        s.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
}
