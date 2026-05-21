package com.wikillm.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wikillm.android.data.DownloadedZim
import com.wikillm.android.data.KiwixEntry
import com.wikillm.android.data.SelectedZim

private val TABS = listOf("Мои файлы", "Каталог Kiwix")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiScreen(navController: NavController, vm: WikiViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Википедия (ZIM)") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SecondaryTabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> MyFilesTab(vm)
                1 -> CatalogTab(vm)
            }
        }
    }
}

// ---- TAB 1: my files ----

@Composable
private fun MyFilesTab(vm: WikiViewModel) {
    val context = LocalContext.current
    val selected by vm.selected.collectAsState()
    val downloaded by vm.downloaded.collectAsState()
    val scanned by vm.scanned.collectAsState()
    val zimDir by vm.zimDir.collectAsState()
    val scanning by vm.scanning.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.onUriPicked(uri)
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.onZimDirPicked(uri)
        }
    }

    // Hint the picker to start in /Android/media on first launch.
    val initialTreeUri: Uri = remember {
        Uri.parse(
            "content://com.android.externalstorage.documents/document/" +
                    "primary%3AAndroid%2Fmedia"
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        // Directory picker section ------------------------------------------------
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Папка с ZIM-файлами", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (zimDir == null)
                            "Выбери папку один раз — приложение будет автоматически находить в ней все .zim-файлы, включая скрытые от обычного пикера каталоги типа /Android/media/org.kiwix.kiwixmobile/."
                        else
                            "Папка выбрана. Тап «Обновить», если в ней появились/исчезли файлы.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { treePicker.launch(initialTreeUri) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (zimDir == null) "Выбрать папку" else "Сменить папку")
                        }
                        if (zimDir != null) {
                            OutlinedButton(onClick = { vm.rescan() }, enabled = !scanning) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (scanning) "Сканирую…" else "Обновить")
                            }
                            TextButton(onClick = { vm.clearZimDir() }) { Text("Убрать") }
                        }
                    }
                }
            }
        }

        if (scanning && scanned.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        if (scanned.isNotEmpty()) {
            item { SectionHeader("Найдено в выбранной папке (${scanned.size})") }
            items(scanned, key = { "scan:" + it.uriString }) { z ->
                ScannedZimCard(z)
            }
        }

        // Manual file picker --------------------------------------------------
        item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Выбрать отдельный ZIM-файл")
                }
            }
        }

        if (selected.isNotEmpty()) {
            item { SectionHeader("Добавлено вручную (${selected.size})") }
            items(selected, key = { it.uriString }) { z ->
                SelectedZimCard(z, onRemove = { vm.removeSelected(z.uriString) })
            }
        }

        if (downloaded.isNotEmpty()) {
            item { SectionHeader("Скачано в приложение (${downloaded.size})") }
            items(downloaded, key = { it.absolutePath }) { z ->
                DownloadedZimCard(z, onDelete = { vm.deleteDownloaded(z) })
            }
        }

        if (selected.isEmpty() && downloaded.isEmpty() && scanned.isEmpty() && zimDir == null) {
            item {
                Text(
                    "Если у тебя уже есть скачанная Википедия в Kiwix-приложении, выбери папку «Выбрать папку» → «Android/media/org.kiwix.kiwixmobile» (на S26 может потребоваться сначала разрешить просмотр Android/media в самом пикере). Приложение найдёт .zim автоматически.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ScannedZimCard(zim: SelectedZim) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(zim.displayName, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatBytes(zim.sizeBytes)} · найден автоматически",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SelectedZimCard(zim: SelectedZim, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(zim.displayName, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatBytes(zim.sizeBytes)} · добавлен вручную",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Убрать из списка")
            }
        }
    }
}

@Composable
private fun DownloadedZimCard(zim: DownloadedZim, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(zim.filename, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatBytes(zim.sizeBytes)} · во внутренней памяти приложения",
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

// ---- TAB 2: catalog ----

@Composable
private fun CatalogTab(vm: WikiViewModel) {
    val state by vm.catalog.collectAsState()
    val filters by vm.filters.collectAsState()
    val progress by vm.progress.collectAsState()
    val errors by vm.errors.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filters.query,
            onValueChange = vm::setQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("Поиск по имени") },
            singleLine = true,
            trailingIcon = {
                if (filters.query.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить")
                    }
                }
            }
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Язык:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
            listOf(null to "Все", "ru" to "RU", "en" to "EN", "fr" to "FR", "de" to "DE").forEach { (code, label) ->
                FilterChip(
                    selected = filters.language == code,
                    onClick = { vm.setLanguage(code) },
                    label = { Text(label) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Тип:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
            listOf(null to "Все", "mini" to "mini", "nopic" to "nopic", "maxi" to "maxi").forEach { (code, label) ->
                FilterChip(
                    selected = filters.variant == code,
                    onClick = { vm.setVariant(code) },
                    label = { Text(label) },
                )
            }
        }

        when (val s = state) {
            CatalogState.Loading, CatalogState.Idle -> Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            is CatalogState.Error -> Column(Modifier.padding(16.dp)) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.loadCatalog() }) { Text("Повторить") }
            }

            is CatalogState.Loaded -> {
                val filtered = s.entries.filter { e ->
                    (filters.language == null || e.language?.equals(filters.language, true) == true) &&
                    (filters.variant == null || e.variant == filters.variant) &&
                    (filters.query.isBlank() || e.filename.contains(filters.query, ignoreCase = true))
                }
                if (s.entries.isEmpty()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Каталог Kiwix не удалось распарсить — формат страницы видимо изменился. На следующем апдейте поправим. Используй пока вкладку «Мои файлы».", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.loadCatalog() }) { Text("Повторить") }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Text(
                                "Найдено: ${filtered.size} из ${s.entries.size}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(filtered, key = { it.filename }) { entry ->
                            CatalogCard(
                                entry = entry,
                                progress = progress[entry.filename],
                                errorMessage = errors[entry.filename],
                                onDownload = { vm.download(entry) },
                                onCancel = { vm.cancelDownload(entry) },
                            )
                        }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    entry: KiwixEntry,
    progress: DownloadProgress?,
    errorMessage: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.filename, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    entry.language?.let { AssistChip(onClick = {}, label = { Text(it.uppercase()) }) }
                    entry.variant?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                }
                val sizeLine = if (progress != null && progress.totalBytes > 0)
                    "${formatBytes(progress.bytesRead)} / ${formatBytes(progress.totalBytes)} · ${entry.date}"
                else
                    "${formatBytes(entry.sizeBytes)} · ${entry.date}"
                Text(
                    sizeLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.ratio.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            if (progress == null) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Default.Download, contentDescription = "Скачать")
                }
            } else {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, contentDescription = "Отменить")
                }
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

private fun formatBytes(b: Long): String {
    if (b <= 0) return "—"
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
