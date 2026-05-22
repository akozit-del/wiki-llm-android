package com.wikillm.android.rag

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import com.wikillm.android.data.ZimRepository
import com.wikillm.android.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-singleton that owns the currently open ZimSearcher. Both WikiSearch
 * and ChatViewModel rely on it so the ZIM stays open across screens and we
 * don't pay the open-cost twice (and don't burn an extra fd from SAF).
 */
object ZimSearchHolder {

    sealed interface State {
        data object Empty : State
        data class Opening(val label: String) : State
        data class Ready(val label: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var current: ZimSearcher? = null

    fun searcher(): ZimSearcher? = current

    suspend fun ensureOpen(context: Context) {
        if (current != null) return
        val zimRepo = ZimRepository(context.applicationContext)
        zimRepo.refreshDownloaded()
        withContext(Dispatchers.IO) { runCatching { zimRepo.rescanDirectory() } }

        zimRepo.downloaded.value.firstOrNull()?.absolutePath?.let { p ->
            _state.value = State.Opening(File(p).name)
            ZimSearcher.openPath(p).fold(
                onSuccess = { onOpened(it, File(p).name) },
                onFailure = { _state.value = State.Failed(it.message ?: "openPath failed") },
            )
            return
        }
        val sz = zimRepo.scanned.value.firstOrNull() ?: zimRepo.selected.value.firstOrNull()
        if (sz == null) {
            _state.value = State.Empty
            return
        }
        _state.value = State.Opening(sz.displayName)
        val direct = directFilePathFor(sz.displayName)
        if (direct != null) {
            DiagLog.i(TAG, "Using direct file: $direct")
            ZimSearcher.openPath(direct).fold(
                onSuccess = { onOpened(it, sz.displayName) },
                onFailure = { fail ->
                    DiagLog.w(TAG, "openPath failed, trying SAF fd", fail)
                    openByUri(context, Uri.parse(sz.uriString), sz.displayName)
                },
            )
            return
        }
        openByUri(context, Uri.parse(sz.uriString), sz.displayName)
    }

    private suspend fun openByUri(context: Context, uri: Uri, label: String) {
        val docUri = resolveDocumentUri(context, uri, label) ?: run {
            _state.value = State.Failed("Не удалось получить URI документа для $label")
            return
        }
        val pfd: ParcelFileDescriptor = runCatching {
            context.contentResolver.openFileDescriptor(docUri, "r")
        }.getOrNull() ?: run {
            _state.value = State.Failed("openFileDescriptor вернул null")
            return
        }
        ZimSearcher.openFd(pfd, label).fold(
            onSuccess = { onOpened(it, label) },
            onFailure = { _state.value = State.Failed(it.message ?: "openFd failed") },
        )
    }

    private fun onOpened(s: ZimSearcher, label: String) {
        current?.close()
        current = s
        _state.value = State.Ready(label)
        DiagLog.i(TAG, "ZimSearchHolder ready: $label")
    }

    fun closeAll() {
        current?.close()
        current = null
        _state.value = State.Empty
    }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private suspend fun resolveDocumentUri(context: Context, uri: Uri, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            runCatching {
                val direct = DocumentFile.fromSingleUri(context, uri)
                if (direct?.isFile == true) return@runCatching uri
                val tree = DocumentFile.fromTreeUri(context, uri) ?: return@runCatching null
                walk(tree, displayName)?.uri
            }.onFailure { DiagLog.w(TAG, "resolveDocumentUri failed: ${it.message}") }.getOrNull()
        }

    private fun walk(dir: DocumentFile, name: String): DocumentFile? {
        if (!dir.isDirectory) return null
        for (child in dir.listFiles()) {
            if (child.isFile && child.name == name) return child
            if (child.isDirectory) walk(child, name)?.let { return it }
        }
        return null
    }

    private fun directFilePathFor(displayName: String): String? {
        val candidates = listOf(
            "/storage/emulated/0/Android/media/org.kiwix.kiwixmobile/$displayName",
            "/storage/emulated/0/Android/media/org.kiwix.kiwixmobile/Custom/$displayName",
            "/storage/emulated/0/Download/$displayName",
            "/storage/emulated/0/Documents/$displayName",
            "/storage/emulated/0/Kiwix/$displayName",
            "/storage/emulated/0/$displayName",
        )
        for (p in candidates) {
            val f = File(p)
            val ex = f.exists()
            val rd = if (ex) f.canRead() else false
            DiagLog.i(TAG, "probe $p exists=$ex canRead=$rd")
            if (ex && rd) return p
        }
        return null
    }

    private const val TAG = "ZimSearchHolder"
}
