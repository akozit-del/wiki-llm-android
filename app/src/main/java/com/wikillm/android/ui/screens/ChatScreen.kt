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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
            ModelPicker(
                loadState = loadState,
                downloaded = downloaded,
                onLoad = vm::loadModel,
                onRefresh = vm::refreshModels,
            )

            val listState = rememberLazyListState()
            LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.lastIndex)
                }
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
                            "Введи запрос ниже. На этом этапе чат пока без истории: каждое сообщение обрабатывается само по себе.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(messages, key = { it.id }) { MessageBubble(it) }
            }

            ChatInput(
                enabled = loadState is ModelLoadState.Loaded,
                generating = generating,
                onSend = vm::send,
                onStop = vm::stop,
            )
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
                    Text(
                        "${m.fileName} · ${formatBytes(m.size)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
    val bg = if (isUser)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .align(align)
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val text = if (msg.text.isEmpty() && msg.isStreaming) "…" else msg.text
            Text(text, color = fg)
        }
    }
}

@Composable
private fun ChatInput(
    enabled: Boolean,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
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
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Стоп", tint = Color.White)
            }
        } else {
            IconButton(
                onClick = {
                    val t = text
                    if (t.isNotBlank()) {
                        onSend(t)
                        text = ""
                    }
                },
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
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
