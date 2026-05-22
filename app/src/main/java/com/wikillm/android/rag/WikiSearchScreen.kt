package com.wikillm.android.rag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WikiSearchScreen(navController: NavController, vm: WikiSearchViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val results by vm.results.collectAsState()
    val searching by vm.searching.collectAsState()
    val article by vm.articleText.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поиск в вики") },
                navigationIcon = {
                    TextButton(onClick = {
                        if (article != null) vm.closeArticle()
                        else navController.popBackStack()
                    }) { Text("Назад") }
                },
                actions = {
                    TextButton(onClick = { vm.tryOpenBestEffort() }) { Text("Переоткрыть") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusCard(state)
            if (state is WikiSearchViewModel.State.NoZim ||
                state is WikiSearchViewModel.State.Failed) {
                AllFilesAccessCard(vm, LocalContext.current)
            }

            if (article != null) {
                ArticleView(article!!)
                return@Column
            }

            if (state is WikiSearchViewModel.State.Ready) {
                SearchBar(searching, vm::search)
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (results.isEmpty() && !searching) {
                        item {
                            Text(
                                "Введи запрос — посмотрим, что libkiwix умеет искать в твоей вики.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    items(results, key = { it.path.ifEmpty { it.title } }) { hit ->
                        HitCard(hit) { vm.openArticle(hit.path) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: WikiSearchViewModel.State) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            when (state) {
                WikiSearchViewModel.State.NoZim -> {
                    Text("ZIM не найден", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Скачай ZIM в «Каталоге Kiwix» внутри приложения или скопируй .zim в /Android/media/org.kiwix.kiwixmobile/.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is WikiSearchViewModel.State.Opening ->
                    Text("Открываю ${state.label.substringAfterLast('/')}…")
                is WikiSearchViewModel.State.Ready ->
                    Text("ZIM открыт: ${state.label.substringAfterLast('/')}", fontWeight = FontWeight.Medium)
                is WikiSearchViewModel.State.Failed ->
                    Text("Ошибка: ${state.message}", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SearchBar(searching: Boolean, onSearch: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Запрос") },
            singleLine = true,
            enabled = !searching,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { if (q.isNotBlank()) onSearch(q) },
            enabled = !searching && q.isNotBlank(),
        ) {
            if (searching) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
            }
        }
    }
}

@Composable
private fun HitCard(hit: ZimSearcher.Hit, onOpen: () -> Unit) {
    val plainSnippet = remember(hit.snippet) {
        ZimSearcher.htmlToPlainText(hit.snippet)
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp)) {
            Text(hit.title.ifBlank { hit.path }, fontWeight = FontWeight.Medium)
            if (plainSnippet.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    plainSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArticleView(text: String) {
    val scroll = rememberScrollState()
    Column(Modifier.padding(12.dp).verticalScroll(scroll)) {
        Text(text)
    }
}


@Composable
private fun AllFilesAccessCard(vm: WikiSearchViewModel, context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    if (Environment.isExternalStorageManager()) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Доступ ко всем файлам не выдан",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(6.dp))
            Text(
                "Чтобы открыть ZIM из /Android/media/org.kiwix.kiwixmobile/, Android требует разрешения «All files access». " +
                "После выдачи разрешения вернись и нажми «Переоткрыть».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(fallback) }
                    }
            }) {
                Text("Открыть настройки разрешений")
            }
        }
    }
}
