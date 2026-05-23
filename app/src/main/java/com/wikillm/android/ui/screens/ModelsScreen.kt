package com.wikillm.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wikillm.android.data.HfFile
import com.wikillm.android.data.LocalModel
import com.wikillm.android.data.RemoteModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(navController: NavController, vm: ModelsViewModel = viewModel()) {
    val query by vm.query.collectAsState()
    val state by vm.state.collectAsState()
    val local by vm.local.collectAsState()
    val expanded by vm.expanded.collectAsState()
    val progress by vm.progress.collectAsState()
    val errors by vm.errors.collectAsState()
    val partials by vm.partials.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Модели") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Поиск GGUF · фильтр 1B–2B") },
                singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Очистить")
                        }
                    }
                }
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (local.isNotEmpty()) {
                    item { SectionHeader("Скачано (${local.size})") }
                    items(local, key = { it.file.absolutePath }) { m ->
                        LocalModelCard(m, onDelete = { vm.delete(m) })
                    }
                }

                item { SectionHeader("Каталог Hugging Face") }

                when (val s = state) {
                    SearchState.Loading -> item {
                        Box(
                            Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is SearchState.Error -> item {
                        Column(Modifier.padding(16.dp)) {
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { vm.retrySearch() }) { Text("Повторить") }
                        }
                    }
                    is SearchState.Loaded -> {
                        if (s.results.isEmpty()) {
                            item {
                                Text(
                                    "Ничего не нашлось. Попробуй другой запрос.",
                                    Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(s.results, key = { it.id }) { m ->
                                RemoteModelCard(
                                    model = m,
                                    files = expanded[m.id],
                                    progress = progress,
                                    partials = partials,
                                    errorMessage = errors[m.id],
                                    onToggle = { vm.toggleFiles(m.id) },
                                    onDownload = { f -> vm.download(m.id, f) },
                                    onCancel = { f -> vm.cancelDownload(m.id, f) },
                                )
                            }
                        }
                    }
                    SearchState.Idle -> Unit
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun LocalModelCard(model: LocalModel, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    model.modelId,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${model.fileName} · ${formatBytes(model.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Удалить")
            }
        }
    }
}

@Composable
private fun RemoteModelCard(
    model: RemoteModel,
    files: List<HfFile>?,
    progress: Map<String, DownloadProgress>,
    partials: Map<String, Long>,
    errorMessage: String?,
    onToggle: () -> Unit,
    onDownload: (HfFile) -> Unit,
    onCancel: (HfFile) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.name,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${model.author} · ↓ ${formatCount(model.downloads)} · ♥ ${formatCount(model.likes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onToggle) {
                    Text(if (files == null) "Файлы" else "Скрыть")
                }
            }
            if (errorMessage != null) {
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (files != null) {
                Spacer(Modifier.height(8.dp))
                if (files.isEmpty()) {
                    Text(
                        "В этом репозитории нет .gguf файлов",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    files.forEach { f ->
                        val key = "${model.id}#${f.path}"
                        FileRow(
                            file = f,
                            progress = progress[key],
                            partialBytes = partials[key] ?: 0L,
                            onDownload = { onDownload(f) },
                            onCancel = { onCancel(f) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: HfFile,
    progress: DownloadProgress?,
    partialBytes: Long,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.path.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Effective ratio for the resume hint when not actively downloading.
            val partialRatio = if (file.size > 0) partialBytes.toFloat() / file.size else 0f
            val sizeLine = when {
                progress != null && progress.totalBytes > 0 ->
                    "${formatBytes(progress.bytesRead)} / ${formatBytes(progress.totalBytes)} · ${percent(progress.ratio)}"
                partialBytes > 0 ->
                    "Загружено ${formatBytes(partialBytes)} / ${formatBytes(file.size)} · ${percent(partialRatio)} — пауза"
                else -> formatBytes(file.size)
            }
            Text(
                sizeLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.ratio.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            } else if (partialBytes > 0) {
                LinearProgressIndicator(
                    progress = { partialRatio.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
        if (progress == null) {
            IconButton(onClick = onDownload) {
                Icon(
                    if (partialBytes > 0) Icons.Default.PlayArrow else Icons.Default.Download,
                    contentDescription = if (partialBytes > 0) "Продолжить" else "Скачать",
                )
            }
        } else {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Cancel, contentDescription = "Отменить")
            }
        }
    }
}

private fun percent(ratio: Float): String = "${(ratio.coerceIn(0f, 1f) * 100).toInt()}%"

private fun formatBytes(b: Long): String {
    if (b <= 0) return "—"
    val units = listOf("Б", "КБ", "МБ", "ГБ")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format("%.1f %s", v, units[i])
}

private fun formatCount(n: Long): String {
    return when {
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1_000 -> "${n / 1_000}k"
        else -> n.toString()
    }
}
