package com.wikillm.android.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class RemoteModel(
    val id: String,
    val author: String,
    val name: String,
    val downloads: Long,
    val likes: Long,
)

data class LocalModel(
    val modelId: String,
    val fileName: String,
    val size: Long,
    val file: File,
)

class ModelRepository(private val context: Context) {

    private val api = HfApi()
    private val downloader = ModelDownloader()

    private val _local = MutableStateFlow<List<LocalModel>>(emptyList())
    val local: StateFlow<List<LocalModel>> = _local.asStateFlow()

    init {
        migrateLegacyIfNeeded()
        refreshLocal()
    }

    /** Primary location: visible in MTP, survives app updates. */
    private fun modelsRoot(): File {
        val external = context.getExternalFilesDir(null)
        val root = if (external != null) File(external, "models") else File(context.filesDir, "models")
        root.mkdirs()
        return root
    }

    /** Old internal-only location used in earlier builds. */
    private fun legacyRoot(): File = File(context.filesDir, "models")

    /** Move files from internal storage to external once after upgrade. */
    private fun migrateLegacyIfNeeded() {
        val legacy = legacyRoot()
        val target = modelsRoot()
        if (legacy.absolutePath == target.absolutePath) return
        if (!legacy.exists()) return
        legacy.walkTopDown().filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }.forEach { src ->
            val rel = src.relativeTo(legacy)
            val dst = File(target, rel.path)
            dst.parentFile?.mkdirs()
            if (!dst.exists()) {
                runCatching { src.copyTo(dst, overwrite = false) }.onSuccess { src.delete() }
            } else {
                src.delete()
            }
        }
        // Try to remove now-empty legacy dirs
        legacy.walkBottomUp().filter { it.isDirectory }.forEach { runCatching { it.delete() } }
    }

    fun refreshLocal() {
        val list = mutableListOf<LocalModel>()
        val roots = listOfNotNull(modelsRoot(), legacyRoot().takeIf { it.exists() })
            .distinctBy { it.absolutePath }
        for (root in roots) {
            root.listFiles()?.forEach { authorDir ->
                if (!authorDir.isDirectory) return@forEach
                authorDir.listFiles()?.forEach { modelDir ->
                    if (!modelDir.isDirectory) return@forEach
                    modelDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.endsWith(".gguf", ignoreCase = true)) {
                            list += LocalModel(
                                modelId = "${authorDir.name}/${modelDir.name}",
                                fileName = file.name,
                                size = file.length(),
                                file = file,
                            )
                        }
                    }
                }
            }
        }
        _local.value = list.distinctBy { it.file.absolutePath }.sortedBy { it.modelId }
    }

    fun delete(local: LocalModel) {
        local.file.delete()
        // clean empty parents
        local.file.parentFile?.takeIf { it.listFiles()?.isEmpty() == true }?.delete()
        local.file.parentFile?.parentFile?.takeIf { it.listFiles()?.isEmpty() == true }?.delete()
        refreshLocal()
    }

    suspend fun search(query: String): List<RemoteModel> {
        return api.searchModels(query, limit = 50)
            .map { hf ->
                val id = hf.id
                val parts = id.split('/', limit = 2)
                val author = if (parts.size == 2) parts[0] else "(unknown)"
                val name = if (parts.size == 2) parts[1] else id
                RemoteModel(id, author, name, hf.downloads, hf.likes)
            }
            .filter { matchesSizeFilter(it.name) }
    }

    /** Keeps models whose name suggests 1.0B–2.5B parameters. */
    private fun matchesSizeFilter(name: String): Boolean {
        val matches = SIZE_REGEX.findAll(name)
        for (m in matches) {
            val size = m.groupValues[1].toFloatOrNull() ?: continue
            if (size in 1.0f..2.5f) return true
        }
        return false
    }

    suspend fun listGgufFiles(modelId: String): List<HfFile> {
        return api.listFiles(modelId)
            .filter { it.type == "file" && it.path.endsWith(".gguf", ignoreCase = true) }
            .sortedBy { it.size }
    }

    fun downloadFlow(modelId: String, file: HfFile): Flow<DownloadEvent> {
        val parts = modelId.split('/', limit = 2)
        val author = if (parts.size == 2) parts[0] else "unknown"
        val name = if (parts.size == 2) parts[1] else modelId
        val outFile = File(modelsRoot(), "$author/$name/${file.path.substringAfterLast('/')}")
        val url = api.fileUrl(modelId, file.path)
        return downloader.download(url, outFile)
    }

    companion object {
        // Matches "1B", "1.5B", "2B", "2.5B" but not "10B" or "21B"
        private val SIZE_REGEX = Regex("""(?<![\d.])([12](?:\.\d)?)B(?![\d])""", RegexOption.IGNORE_CASE)
    }
}
