package com.wikillm.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ZimRepository(private val context: Context) {

    private val api = KiwixApi()
    private val downloader = ModelDownloader()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _selected = MutableStateFlow(loadSelected())
    val selected: StateFlow<List<SelectedZim>> = _selected.asStateFlow()

    private val _downloaded = MutableStateFlow<List<DownloadedZim>>(emptyList())
    val downloaded: StateFlow<List<DownloadedZim>> = _downloaded.asStateFlow()

    init { refreshDownloaded() }

    private fun zimRoot(): File =
        File(context.filesDir, "zim").apply { mkdirs() }

    fun refreshDownloaded() {
        val list = zimRoot().listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zim", ignoreCase = true) }
            ?.map { DownloadedZim(it.name, it.absolutePath, it.length()) }
            ?.sortedBy { it.filename }
            ?: emptyList()
        _downloaded.value = list
    }

    fun deleteDownloaded(zim: DownloadedZim): Boolean {
        val ok = File(zim.absolutePath).delete()
        if (ok) refreshDownloaded()
        return ok
    }

    fun addSelected(zim: SelectedZim) {
        val current = _selected.value
        if (current.any { it.uriString == zim.uriString }) return
        _selected.value = current + zim
        saveSelected(_selected.value)
    }

    fun removeSelected(uriString: String) {
        _selected.value = _selected.value.filterNot { it.uriString == uriString }
        saveSelected(_selected.value)
    }

    suspend fun fetchCatalog(): List<KiwixEntry> = api.fetchCatalog()

    fun downloadFlow(entry: KiwixEntry): Flow<DownloadEvent> {
        val outFile = File(zimRoot(), entry.filename)
        return downloader.download(entry.downloadUrl, outFile)
    }

    // --- persistence ---

    @Serializable
    private data class StoredZim(val uri: String, val name: String, val size: Long)

    private fun loadSelected(): List<SelectedZim> {
        val raw = prefs.getString(KEY_SELECTED, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredZim>>(raw)
                .map { SelectedZim(it.uri, it.name, it.size) }
        }.getOrDefault(emptyList())
    }

    private fun saveSelected(list: List<SelectedZim>) {
        val raw = json.encodeToString(list.map { StoredZim(it.uriString, it.displayName, it.sizeBytes) })
        prefs.edit().putString(KEY_SELECTED, raw).apply()
    }

    companion object {
        private const val PREFS_NAME = "wiki_llm_zim_prefs"
        private const val KEY_SELECTED = "selected_zims_json"
    }
}
