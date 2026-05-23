package com.wikillm.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface DownloadEvent {
    data class Progress(val bytesRead: Long, val totalBytes: Long) : DownloadEvent
    data class Done(val file: File) : DownloadEvent
    data class Failed(val message: String) : DownloadEvent
}

class ModelDownloader(
    private val client: OkHttpClient = HfApi.defaultClient,
) {
    /**
     * Downloads [url] to [outputFile], resuming from a partial `.part` file when one
     * exists. We never delete the `.part` on cancel/IO error, so re-invoking this
     * continues from where it stopped via an HTTP Range request.
     */
    fun download(url: String, outputFile: File): Flow<DownloadEvent> = flow {
        outputFile.parentFile?.mkdirs()
        val tmp = File(outputFile.parentFile, outputFile.name + ".part")
        val existing = if (tmp.exists()) tmp.length() else 0L

        val reqBuilder = Request.Builder().url(url)
        if (existing > 0) reqBuilder.header("Range", "bytes=$existing-")

        try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                // 206 = server honoured our Range (resume). 200 = full body (start over).
                // 416 = range past EOF, which means the .part is already complete.
                when {
                    resp.code == 416 && existing > 0 -> {
                        moveIntoPlace(tmp, outputFile)
                        emit(DownloadEvent.Done(outputFile))
                        return@use
                    }
                    !resp.isSuccessful -> {
                        emit(DownloadEvent.Failed("HTTP ${resp.code}"))
                        return@use
                    }
                }

                val body = resp.body
                if (body == null) {
                    emit(DownloadEvent.Failed("Empty body"))
                    return@use
                }

                val resuming = resp.code == 206 && existing > 0
                val contentLen = body.contentLength() // remaining bytes if 206, full if 200
                val total = if (resuming && contentLen > 0) existing + contentLen else contentLen
                val startBytes = if (resuming) existing else 0L

                body.byteStream().use { input ->
                    FileOutputStream(tmp, /*append=*/ resuming).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = startBytes
                        var lastEmit = 0L
                        emit(DownloadEvent.Progress(read, total))
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
                        output.flush()
                        emit(DownloadEvent.Progress(read, total))
                    }
                }

                moveIntoPlace(tmp, outputFile)
                emit(DownloadEvent.Done(outputFile))
            }
        } catch (e: CancellationException) {
            // Keep the .part file so the next attempt resumes.
            throw e
        } catch (e: IOException) {
            // Keep the .part file so the next attempt resumes.
            emit(DownloadEvent.Failed(e.message ?: "IO error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun moveIntoPlace(tmp: File, outputFile: File) {
        if (!tmp.renameTo(outputFile)) {
            tmp.copyTo(outputFile, overwrite = true)
            tmp.delete()
        }
    }
}
