package com.wikillm.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wikillm.android.data.LocalModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, vm: ChatViewModel = viewModel()) {
    val downloaded by vm.downloadedModels.collectAsState()
    val loadState by vm.loadState.collectAsState()
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чат") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                },
                actions = {
                    if (loadState is ModelLoadState.Loaded) {
                        TextButton(onClick = { vm.unloadModel() }) { Text("Выгрузить") }
                    }
                    if (messages.isNotEmpty()) {
                        TextButton(onClick = { vm.clear() }) { Text("Очистить") }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ModelPicker(loadState, downloaded, vm::loadModel, vm::refreshModels)

            RagControls(vm)

            val listState = rememberLazyListState()
            LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty() && loadState is ModelLoadState.Loaded) {
                    item {
                        Text(
                            "Введи запрос ниже. На этом этапе чат пока без истории и без поиска по Википедии — это будет в следующей итерации.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(messages, key = { it.id }) { MessageBubble(it) }
            }

            ChatInput(loadState is ModelLoadState.Loaded, generating, vm::send, vm::stop)
        }
    }
}

@Composable
private fun ModelPicker(
    loadState: ModelLoadState,
    downloaded: List<LocalModel>,
    onLoad: (LocalModel) -> Unit,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            when (val s = loadState) {
                is ModelLoadState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Загружается: ${s.name}")
                    }
                }
                is ModelLoadState.Loaded -> {
                    Text("Модель: ${s.name}", fontWeight = FontWeight.Medium)
                }
                is ModelLoadState.Failed -> {
                    Text("Ошибка: ${s.message}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    PickerList(downloaded, onLoad, onRefresh)
                }
                ModelLoadState.NotLoaded -> {
                    if (downloaded.isEmpty()) {
                        Text(
                            "Сначала скачай хотя бы одну модель в разделе «Модели».",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRefresh) { Text("Обновить список") }
                    } else {
                        Text("Выбери модель для загрузки:", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        PickerList(downloaded, onLoad, onRefresh)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerList(
    downloaded: List<LocalModel>,
    onLoad: (LocalModel) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        downloaded.forEach { m ->
            Button(onClick = { onLoad(m) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(m.modelId, fontWeight = FontWeight.Medium)
                    Text("${m.fileName} · ${formatBytes(m.size)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = onRefresh) { Text("Обновить список") }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.USER
    val align = if (isUser) Alignment.End else Alignment.Start
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.align(align).widthIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
                .background(bg).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val text = if (msg.text.isEmpty() && msg.isStreaming) "…" else msg.text
            Text(text, color = fg)
        }
    }
}

@Composable
private fun ChatInput(
    enabled: Boolean, generating: Boolean,
    onSend: (String) -> Unit, onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (enabled) "Сообщение" else "Сначала загрузи модель") },
            enabled = enabled && !generating,
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        if (generating) {
            IconButton(onClick = onStop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.Stop, contentDescription = "Стоп", tint = Color.White)
            }
        } else {
            IconButton(
                onClick = {
                    val t = text
                    if (t.isNotBlank()) { onSend(t); text = "" }
                },
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Отправить", tint = Color.White)
            }
        }
    }
}

private fun formatBytes(b: Long): String {
    if (b <= 0) return "—"
    val units = listOf("Б", "КБ", "МБ", "ГБ")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}


@Composable
private fun RagControls(vm: ChatViewModel) {
    val ragOn by vm.ragEnabled.collectAsState()
    val n by vm.ragCandidates.collectAsState()
    val zimState by vm.zimState.collectAsState()

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = ragOn, onCheckedChange = vm::setRagEnabled)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (ragOn) "Вся вики (RAG)" else "Без вики",
                        fontWeight = FontWeight.Medium,
                    )
                    val zimLabel = when (val s = zimState) {
                        is com.wikillm.android.rag.ZimSearchHolder.State.Ready -> "ZIM: ${s.label}"
                        is com.wikillm.android.rag.ZimSearchHolder.State.Opening -> "ZIM открывается…"
                        is com.wikillm.android.rag.ZimSearchHolder.State.Failed -> "ZIM не открыт: ${s.message}"
                        com.wikillm.android.rag.ZimSearchHolder.State.Empty -> "ZIM не выбран"
                    }
                    Text(zimLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ragOn) {
                Spacer(Modifier.height(8.dp))
                Text("Кандидатов из вики: $n", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 50).forEach { v ->
                        FilterChip(
                            selected = n == v,
                            onClick = { vm.setRagCandidates(v) },
                            label = { Text(v.toString()) },
                        )
                    }
                }
            }
        }
    }
}
