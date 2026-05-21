package com.wikillm.android.rag

import android.app.Application
import android.content.Context
import android.net.Uri
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

            // 2) Scanned ZIM via SAF tree URI -> FileDescriptor.
            zimRepo.scanned.value.firstOrNull()?.let { sz ->
                _state.value = State.Opening(sz.displayName)
                openByUri(Uri.parse(sz.uriString), sz.displayName)
                return@launch
            }

            // 3) Manually selected ZIM (file picker).
            zimRepo.selected.value.firstOrNull()?.let { sz ->
                _state.value = State.Opening(sz.displayName)
                openByUri(Uri.parse(sz.uriString), sz.displayName)
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

    companion object { private const val TAG = "WikiSearchVM" }
}
