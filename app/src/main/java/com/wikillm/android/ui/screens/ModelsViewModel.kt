package com.wikillm.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.data.DownloadEvent
import com.wikillm.android.data.HfFile
import com.wikillm.android.data.LocalModel
import com.wikillm.android.data.ModelRepository
import com.wikillm.android.data.RemoteModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Loaded(val results: List<RemoteModel>) : SearchState
    data class Error(val message: String) : SearchState
}

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
) {
    val ratio: Float get() = if (totalBytes > 0) bytesRead.toFloat() / totalBytes else 0f
}

class ModelsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ModelRepository(app.applicationContext)

    val local: StateFlow<List<LocalModel>> = repo.local

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _expanded = MutableStateFlow<Map<String, List<HfFile>>>(emptyMap())
    val expanded: StateFlow<Map<String, List<HfFile>>> = _expanded.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()
    private var searchJob: Job? = null

    init {
        runSearch("") // populate initial catalog
    }

    fun onQueryChange(text: String) {
        _query.value = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            runSearch(text)
        }
    }

    private fun runSearch(text: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = SearchState.Loading
            try {
                _state.value = SearchState.Loaded(repo.search(text))
            } catch (e: Exception) {
                _state.value = SearchState.Error(e.message ?: "Не удалось загрузить каталог")
            }
        }
    }

    fun retrySearch() {
        runSearch(_query.value)
    }

    fun toggleFiles(modelId: String) {
        if (_expanded.value.containsKey(modelId)) {
            _expanded.value = _expanded.value - modelId
            return
        }
        viewModelScope.launch {
            try {
                val files = repo.listGgufFiles(modelId)
                _expanded.value = _expanded.value + (modelId to files)
            } catch (e: Exception) {
                _errors.value = _errors.value + (modelId to (e.message ?: "Не удалось получить список файлов"))
            }
        }
    }

    fun download(modelId: String, file: HfFile) {
        val key = downloadKey(modelId, file)
        if (downloadJobs[key]?.isActive == true) return
        _errors.value = _errors.value - key
        downloadJobs[key] = viewModelScope.launch {
            repo.downloadFlow(modelId, file).collect { ev ->
                when (ev) {
                    is DownloadEvent.Progress -> {
                        _progress.value =
                            _progress.value + (key to DownloadProgress(ev.bytesRead, ev.totalBytes))
                    }
                    is DownloadEvent.Done -> {
                        _progress.value = _progress.value - key
                        repo.refreshLocal()
                    }
                    is DownloadEvent.Failed -> {
                        _progress.value = _progress.value - key
                        _errors.value = _errors.value + (key to ev.message)
                    }
                }
            }
        }
    }

    fun cancelDownload(modelId: String, file: HfFile) {
        val key = downloadKey(modelId, file)
        downloadJobs[key]?.cancel()
        downloadJobs.remove(key)
        _progress.value = _progress.value - key
    }

    fun delete(local: LocalModel) {
        repo.delete(local)
    }

    private fun downloadKey(modelId: String, file: HfFile) = "$modelId#${file.path}"
}
