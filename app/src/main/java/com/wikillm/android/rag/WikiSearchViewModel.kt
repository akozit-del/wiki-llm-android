package com.wikillm.android.rag

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.wikillm.android.diag.DiagLog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.data.ZimRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WikiSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val zimRepo = ZimRepository(app.applicationContext)
    private val context: Context get() = getApplication<Application>().applicationContext

    sealed interface State {
        data object NoZim : State
        data class Opening(val label: String) : State
        data class Ready(val label: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NoZim)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _results = MutableStateFlow<List<ZimSearcher.Hit>>(emptyList())
    val results: StateFlow<List<ZimSearcher.Hit>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _articleText = MutableStateFlow<String?>(null)
    val articleText: StateFlow<String?> = _articleText.asStateFlow()

    private var searcher: ZimSearcher? = null

    init { tryOpenBestEffort() }

    fun tryOpenBestEffort() {
        viewModelScope.launch {
            searcher?.close()
            searcher = null
            _results.value = emptyList()

            // Refresh both downloaded and scanned snapshots before picking.
            zimRepo.refreshDownloaded()
            withContext(Dispatchers.IO) { runCatching { zimRepo.rescanDirectory() } }

            // 1) Downloaded ZIM (regular file path).
            zimRepo.downloaded.value.firstOrNull()?.absolutePath?.let { path ->
                _state.value = State.Opening(File(path).name)
                ZimSearcher.openPath(path).fold(
                    onSuccess = { searcher = it; _state.value = State.Ready(File(path).name); DiagLog.i(TAG, "openPath OK: $path") },
                    onFailure = { _state.value = State.Failed(it.message ?: "Не удалось открыть ZIM"); DiagLog.e(TAG, "openPath FAILED: $path", it) },
                )
                return@launch
            }

            // 2/3) Scanned or selected ZIM — first try direct File API at standard places.
            //       libzim mmap'ing a SAF /proc/self/fd handle fails because it's a FUSE pipe,
            //       so a real file path is the only reliable open source.
            val candidate = zimRepo.scanned.value.firstOrNull() ?: zimRepo.selected.value.firstOrNull()
            candidate?.let { sz ->
                _state.value = State.Opening(sz.displayName)
                val direct = directFilePathFor(sz.displayName)
                if (direct != null) {
                    DiagLog.i(TAG, "Using direct file: $direct")
                    ZimSearcher.openPath(direct).fold(
                        onSuccess = { searcher = it; _state.value = State.Ready(sz.displayName); DiagLog.i(TAG, "openPath OK") },
                        onFailure = { fail ->
                            DiagLog.w(TAG, "openPath failed, trying SAF fd as last resort", fail)
                            openByUri(Uri.parse(sz.uriString), sz.displayName)
                        },
                    )
                } else {
                    DiagLog.w(TAG, "no readable direct file for ${sz.displayName}, falling back to SAF fd")
                    openByUri(Uri.parse(sz.uriString), sz.displayName)
                }
                return@launch
            }

            _state.value = State.NoZim
        }
    }

    private suspend fun openByUri(uri: Uri, label: String) {
        // For a SAF tree URI we need to walk to the actual document URI of the file.
        val docUri = resolveDocumentUri(uri, label) ?: run {
            _state.value = State.Failed("Не удалось получить URI документа для $label")
            return
        }
        val pfd = runCatching { context.contentResolver.openFileDescriptor(docUri, "r") }
            .getOrNull()
        if (pfd == null) {
            _state.value = State.Failed("openFileDescriptor вернул null для $label")
            return
        }
        ZimSearcher.openFd(pfd, label).fold(
            onSuccess = { searcher = it; _state.value = State.Ready(label); DiagLog.i(TAG, "openFd OK: $label") },
            onFailure = { _state.value = State.Failed(it.message ?: "Archive(fd) кинул исключение"); DiagLog.e(TAG, "openFd FAILED: $label", it) },
        )
    }

    /**
     * Some scanned entries store a tree URI rather than a document URI. Walk the tree
     * to find the matching file by display name; otherwise the URI is already a document URI.
     */
    private suspend fun resolveDocumentUri(uri: Uri, displayName: String): Uri? =
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

    fun search(query: String, maxResults: Int = 10) {
        val s = searcher ?: return
        if (query.isBlank()) return
        _searching.value = true
        viewModelScope.launch {
            _results.value = runCatching { s.search(query, maxResults) }
                .onFailure { DiagLog.e(TAG, "search failed", it) }
                .getOrDefault(emptyList())
            _searching.value = false
        }
    }

    fun openArticle(path: String) {
        val s = searcher ?: return
        _articleText.value = null
        viewModelScope.launch {
            _articleText.value = s.readArticleText(path) ?: "Не удалось прочитать статью"
        }
    }

    fun closeArticle() { _articleText.value = null }

    override fun onCleared() {
        super.onCleared()
        searcher?.close()
    }



    /** True iff the user has granted MANAGE_EXTERNAL_STORAGE (Android 11+). */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    /**
     * Probes the standard places where Kiwix-related apps drop their .zim files.
     * Returns the first existing readable path so libzim can mmap it directly.
     * On Android 11+ /Android/media/<other_package>/ is readable without SAF.
     */
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
            DiagLog.i(TAG, "probe $p exists=$ex canRead=$rd size=${if (ex) f.length() else 0L}")
            if (ex && rd) return p
        }
        return null
    }
    companion object { private const val TAG = "WikiSearchVM" }
}
