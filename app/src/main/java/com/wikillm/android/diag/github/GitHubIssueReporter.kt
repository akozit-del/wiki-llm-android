package com.wikillm.android.diag.github

import com.wikillm.android.diag.DiagLog
import com.wikillm.android.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class GitHubIssueReporter(private val settings: SettingsRepository) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Ok(val url: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun send(title: String, body: String): Result = withContext(Dispatchers.IO) {
        val pat = settings.effectivePat()
        if (pat.isBlank()) {
            return@withContext Result.Failed("GitHub PAT не задан (ни в настройках, ни в сборке)")
        }
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body.take(60_000)) // GitHub Issue body limit ~65k chars
            put("labels", org.json.JSONArray().put("diag"))
        }.toString()
        val req = Request.Builder()
            .url("https://api.github.com/repos/${SettingsRepository.REPO}/issues")
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "wiki-llm-android")
            .post(payload.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { r ->
                val respText = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    DiagLog.e(TAG, "GitHub issue create HTTP ${r.code}: ${respText.take(300)}")
                    return@withContext Result.Failed("HTTP ${r.code}: ${respText.take(200)}")
                }
                val url = runCatching { JSONObject(respText).optString("html_url") }.getOrNull().orEmpty()
                DiagLog.i(TAG, "GitHub issue created: $url")
                Result.Ok(url)
            }
        } catch (t: Throwable) {
            DiagLog.e(TAG, "GitHub issue create threw", t)
            Result.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    fun defaultTitle(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return "diag: $ts"
    }

    fun formatBody(diagDump: String): String {
        val limit = 50_000
        if (diagDump.length <= limit) {
            return "Автоматический отчёт из приложения wiki-llm-android.\n\n```\n$diagDump\n```"
        }
        // Keep both ends: first 12 KB (load library, init) + last 35 KB (latest errors).
        val head = diagDump.take(12_000)
        val tail = diagDump.takeLast(35_000)
        return "Автоматический отчёт из приложения wiki-llm-android. Лог обрезан в середине.\n\n" +
                "```\n$head\n\n... (skipped ${diagDump.length - head.length - tail.length} chars) ...\n\n$tail\n```"
    }

    companion object { private const val TAG = "GitHubReporter" }
}
