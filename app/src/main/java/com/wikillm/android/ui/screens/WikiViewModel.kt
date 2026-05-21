package com.wikillm.android.ui.screens

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.data.DownloadEvent
import com.wikillm.android.data.DownloadedZim
import com.wikillm.android.data.KiwixEntry
import com.wikillm.android.data.SelectedZim
import com.wikillm.android.data.ZimRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CatalogState {
    data object Idle : CatalogState
    data object Loading : CatalogState
    data class Loaded(val entries: List<KiwixEntry>) : CatalogState
    data class Error(val message: String) : CatalogState
}

data class CatalogFilters(
    val language: String? = null,
    val variant: String? = null,
    val topic: String? = null,
    val query: String = "",
)

class WikiViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ZimRepository(app.applicationContext)

    val selected: StateFlow<List<SelectedZim>> = repo.selected
    val downloaded: StateFlow<List<DownloadedZim>> = repo.downloaded
    val scanned: StateFlow<List<SelectedZim>> = repo.scanned
    val zimDir: StateFlow<String?> = repo.zimDir

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _catalog = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    private val _filters = MutableStateFlow(CatalogFilters())
    val filters: StateFlow<CatalogFilters> = _filters.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        // If a folder was picked earlier, run a scan on startup.
        if (repo.zimDir.value != null) rescan()
        loadCatalog()
    }

    fun onZimDirPicked(uri: Uri) {
        viewModelScope.launch {
            _scanning.value = true
            try {
                repo.setZimDirectory(uri)
            } finally {
                _scanning.value = false
            }
        }
    }

    fun clearZimDir() {
        repo.clearZimDirectory()
    }

    fun rescan() {
        viewModelScope.launch {
            _scanning.value = true
            try {
                repo.rescanDirectory()
            } finally {
                _scanning.value = false
            }
        }
    }

    fun loadCatalog() {
        viewModelScope.launch {
            _catalog.value = CatalogState.Loading
            try {
                _catalog.value = CatalogState.Loaded(repo.fetchCatalog())
            } catch (e: Exception) {
                _catalog.value = CatalogState.Error(e.message ?: "Не удалось загрузить каталог Kiwix")
            }
        }
    }

    fun setLanguage(lang: String?) { _filters.value = _filters.value.copy(language = lang) }
    fun setVariant(variant: String?) { _filters.value = _filters.value.copy(variant = variant) }
    fun setQuery(q: String) { _filters.value = _filters.value.copy(query = q) }

    fun onUriPicked(uri: Uri) {
        val context = getApplication<Application>().applicationContext
        val cr = context.contentResolver
        var name = uri.lastPathSegment ?: "selected.zim"
        var size = 0L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
        }
        repo.addSelected(SelectedZim(uri.toString(), name, size))
    }

    fun removeSelected(uriString: String) = repo.removeSelected(uriString)
    fun deleteDownloaded(zim: DownloadedZim) = repo.deleteDownloaded(zim)

    fun download(entry: KiwixEntry) {
        val key = entry.filename
        if (downloadJobs[key]?.isActive == true) return
        _errors.value = _errors.value - key
        downloadJobs[key] = viewModelScope.launch {
            repo.downloadFlow(entry).collect { ev ->
                when (ev) {
                    is DownloadEvent.Progress -> {
                        _progress.value =
                            _progress.value + (key to DownloadProgress(ev.bytesRead, ev.totalBytes))
                    }
                    is DownloadEvent.Done -> {
                        _progress.value = _progress.value - key
                        repo.refreshDownloaded()
                    }
                    is DownloadEvent.Failed -> {
                        _progress.value = _progress.value - key
                        _errors.value = _errors.value + (key to ev.message)
                    }
                }
            }
        }
    }

    fun cancelDownload(entry: KiwixEntry) {
        val key = entry.filename
        downloadJobs[key]?.cancel()
        downloadJobs.remove(key)
        _progress.value = _progress.value - key
    }
}
