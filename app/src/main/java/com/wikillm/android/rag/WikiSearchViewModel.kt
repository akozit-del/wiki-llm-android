package com.wikillm.android.rag

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.diag.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thin VM for the libkiwix test screen. All ZIM open/close logic lives in the
 * shared [ZimSearchHolder]; this just mirrors its state and runs search/read
 * against the single shared searcher (no second mmap, no duplicated SAF code).
 */
class WikiSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication<Application>().applicationContext

    sealed interface State {
        data object NoZim : State
        data class Opening(val label: String) : State
        data class Ready(val label: String) : State
        data class Failed(val message: String) : State
    }

    val state: StateFlow<State> = ZimSearchHolder.state
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State.NoZim)

    private val _results = MutableStateFlow<List<ZimSearcher.Hit>>(emptyList())
    val results: StateFlow<List<ZimSearcher.Hit>> = _results.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _articleText = MutableStateFlow<String?>(null)
    val articleText: StateFlow<String?> = _articleText.asStateFlow()

    init {
        viewModelScope.launch { runCatching { ZimSearchHolder.ensureOpen(context) } }
    }

    /** "Переоткрыть": force a fresh open of the ZIM. */
    fun tryOpenBestEffort() {
        _results.value = emptyList()
        viewModelScope.launch {
            ZimSearchHolder.closeAll()
            runCatching { ZimSearchHolder.ensureOpen(context) }
        }
    }

    fun search(query: String, maxResults: Int = 10) {
        val s = ZimSearchHolder.searcher() ?: return
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
        val s = ZimSearchHolder.searcher() ?: return
        _articleText.value = null
        viewModelScope.launch {
            _articleText.value = s.readArticleText(path) ?: "Не удалось прочитать статью"
        }
    }

    fun closeArticle() { _articleText.value = null }

    /** True iff the user has granted MANAGE_EXTERNAL_STORAGE (Android 11+). */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private fun ZimSearchHolder.State.toUiState(): State = when (this) {
        ZimSearchHolder.State.Empty -> State.NoZim
        is ZimSearchHolder.State.Opening -> State.Opening(label)
        is ZimSearchHolder.State.Ready -> State.Ready(label)
        is ZimSearchHolder.State.Failed -> State.Failed(message)
    }

    companion object { private const val TAG = "WikiSearchVM" }
}
