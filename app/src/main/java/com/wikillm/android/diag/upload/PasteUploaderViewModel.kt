package com.wikillm.android.diag.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Uploads the diag dump to a public anonymous pastebin so the user can share
 * just the URL instead of trying to copy-paste long logs through a phone
 * keyboard. We try 0x0.st first (file upload), then ix.io (raw text) as a
 * fallback. Both return a plain-text URL in the response body.
 *
 * The log gets a public URL — fine for app logs, but obviously do not include
 * secrets or PII here.
 */
class PasteUploaderViewModel : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Uploading : State
        data class Done(val url: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun upload(payload: String) {
        if (_state.value is State.Uploading) return
        if (payload.isBlank()) {
            _state.value = State.Failed("Лог пустой — нечего загружать")
            return
        }
        _state.value = State.Uploading
        viewModelScope.launch {
            val url = tryProviders(payload)
            _state.value = url.fold(
                onSuccess = { State.Done(it) },
                onFailure = { State.Failed(it.message ?: "Не получилось загрузить") },
            )
        }
    }

    private suspend fun tryProviders(payload: String): Result<String> {
        return runCatching { uploadToZeroX(payload) }
            .recoverCatching { firstError ->
                DiagLog.w(TAG, "0x0.st upload failed, trying ix.io: ${firstError.message}")
                uploadToIxIo(payload)
            }
    }

    private suspend fun uploadToZeroX(payload: String): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = "wikillm-diag.txt",
                body = payload.toByteArray(Charsets.UTF_8).toRequestBody("text/plain".toMediaType()),
            )
            .addFormDataPart("expires", "168") // delete after 7 days
            .build()
        val req = Request.Builder()
            .url("https://0x0.st")
            .header("User-Agent", "wiki-llm-android (anonymous diag upload)")
            .post(body)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("0x0.st HTTP ${r.code}")
            val text = r.body?.string()?.trim() ?: throw RuntimeException("Пустой ответ 0x0.st")
            require(text.startsWith("http")) { "0x0.st вернул не URL: ${text.take(80)}" }
            DiagLog.i(TAG, "0x0.st upload OK: $text")
            text
        }
    }

    private suspend fun uploadToIxIo(payload: String): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("f:1", payload)
            .build()
        val req = Request.Builder()
            .url("http://ix.io")
            .header("User-Agent", "wiki-llm-android (anonymous diag upload)")
            .post(body)
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("ix.io HTTP ${r.code}")
            val text = r.body?.string()?.trim() ?: throw RuntimeException("Пустой ответ ix.io")
            require(text.startsWith("http")) { "ix.io вернул не URL: ${text.take(80)}" }
            DiagLog.i(TAG, "ix.io upload OK: $text")
            text
        }
    }

    companion object { private const val TAG = "PasteUploader" }
}
