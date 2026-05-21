package com.wikillm.android.rag

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.data.ZimRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class WikiSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val zimRepo = ZimRepository(app.applicationContext)

    sealed interface State {
        data object NoZim : State
        data class Opening(val path: String) : State
        data class Ready(val path: String) : State
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
            val candidate = bestEffortPath() ?: run {
                _state.value = State.NoZim
                return@launch
            }
            _state.value = State.Opening(candidate)
            ZimSearcher.open(candidate).fold(
                onSuccess = {
                    searcher?.close()
                    searcher = it
                    _state.value = State.Ready(candidate)
                    Log.i(TAG, "Opened ZIM: $candidate")
                },
                onFailure = { _state.value = State.Failed(it.message ?: "Не удалось открыть ZIM") },
            )
        }
    }

    fun search(query: String, maxResults: Int = 10) {
        val s = searcher ?: return
        if (query.isBlank()) return
        _searching.value = true
        viewModelScope.launch {
            _results.value = runCatching { s.search(query, maxResults) }
                .onFailure { Log.e(TAG, "search failed", it) }
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

    /**
     * Returns the first openable file path we can find. Priority:
     *   1. downloaded ZIM in app storage
     *   2. scanned ZIM mapped to /storage/emulated/0/Android/media/org.kiwix.kiwixmobile/<name>
     */
    private fun bestEffortPath(): String? {
        zimRepo.refreshDownloaded()
        zimRepo.downloaded.value.firstOrNull()?.absolutePath?.let { return it }

        zimRepo.scanned.value.firstOrNull()?.displayName?.let { name ->
            val candidates = listOf(
                "/storage/emulated/0/Android/media/org.kiwix.kiwixmobile/$name",
                "/storage/emulated/0/Android/media/org.kiwix.kiwixmobile/Custom/$name",
                "/storage/emulated/0/Download/$name",
                "/storage/emulated/0/$name",
            )
            candidates.firstOrNull { File(it).exists() }?.let { return it }
        }
        return null
    }

    companion object { private const val TAG = "WikiSearchVM" }
}
