package com.wikillm.android.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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

    private val _scanned = MutableStateFlow<List<SelectedZim>>(emptyList())
    val scanned: StateFlow<List<SelectedZim>> = _scanned.asStateFlow()

    private val _zimDir = MutableStateFlow(loadZimDir())
    val zimDir: StateFlow<String?> = _zimDir.asStateFlow()

    init {
        refreshDownloaded()
    }

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

    /** Persist the picked tree URI and refresh the scan. */
    suspend fun setZimDirectory(treeUri: Uri) {
        prefs.edit().putString(KEY_ZIM_TREE_URI, treeUri.toString()).apply()
        _zimDir.value = treeUri.toString()
        rescanDirectory()
    }

    /** Remove the picked directory (does not touch the files on disk). */
    fun clearZimDirectory() {
        val current = prefs.getString(KEY_ZIM_TREE_URI, null) ?: return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(current),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        prefs.edit().remove(KEY_ZIM_TREE_URI).apply()
        _zimDir.value = null
        _scanned.value = emptyList()
    }

    /** Walks the saved tree and replaces the scanned list with the discovered .zim files. */
    suspend fun rescanDirectory() = withContext(Dispatchers.IO) {
        val treeUriStr = prefs.getString(KEY_ZIM_TREE_URI, null)
        if (treeUriStr == null) {
            _scanned.value = emptyList()
            return@withContext
        }
        val treeUri = Uri.parse(treeUriStr)
        val root = DocumentFile.fromTreeUri(context, treeUri)
        if (root == null || !root.canRead()) {
            _scanned.value = emptyList()
            return@withContext
        }
        val found = mutableListOf<SelectedZim>()
        walkForZims(root, found)
        _scanned.value = found.sortedBy { it.displayName }
    }

    private fun walkForZims(dir: DocumentFile, out: MutableList<SelectedZim>) {
        val children = runCatching { dir.listFiles() }.getOrElse { return }
        for (c in children) {
            if (c.isDirectory) {
                walkForZims(c, out)
            } else if (c.isFile) {
                val name = c.name ?: continue
                if (name.endsWith(".zim", ignoreCase = true)) {
                    out += SelectedZim(
                        uriString = c.uri.toString(),
                        displayName = name,
                        sizeBytes = runCatching { c.length() }.getOrDefault(0L),
                    )
                }
            }
        }
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

    private fun loadZimDir(): String? = prefs.getString(KEY_ZIM_TREE_URI, null)

    companion object {
        private const val PREFS_NAME = "wiki_llm_zim_prefs"
        private const val KEY_SELECTED = "selected_zims_json"
        private const val KEY_ZIM_TREE_URI = "zim_tree_uri"
    }
}
