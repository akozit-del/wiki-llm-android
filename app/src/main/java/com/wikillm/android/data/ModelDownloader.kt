package com.wikillm.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface DownloadEvent {
    data class Progress(val bytesRead: Long, val totalBytes: Long) : DownloadEvent
    data class Done(val file: File) : DownloadEvent
    data class Failed(val message: String) : DownloadEvent
}

class ModelDownloader(
    private val client: OkHttpClient = HfApi.defaultClient,
) {
    fun download(url: String, outputFile: File): Flow<DownloadEvent> = flow {
        outputFile.parentFile?.mkdirs()
        val tmp = File(outputFile.parentFile, outputFile.name + ".part")
        val req = Request.Builder().url(url).build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(DownloadEvent.Failed("HTTP ${resp.code}"))
                    return@use
                }
                val total = resp.body?.contentLength() ?: -1L
                val stream = resp.body?.byteStream()
                if (stream == null) {
                    emit(DownloadEvent.Failed("Empty body"))
                    return@use
                }
                stream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 200) {
                                emit(DownloadEvent.Progress(read, total))
                                lastEmit = now
                            }
                        }
                        emit(DownloadEvent.Progress(read, total))
                    }
                }
                if (!tmp.renameTo(outputFile)) {
                    tmp.copyTo(outputFile, overwrite = true)
                    tmp.delete()
                }
                emit(DownloadEvent.Done(outputFile))
            }
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: IOException) {
            tmp.delete()
            emit(DownloadEvent.Failed(e.message ?: "IO error"))
        }
    }.flowOn(Dispatchers.IO)
}
