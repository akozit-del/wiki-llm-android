package com.wikillm.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    val genProgress by vm.genProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                title = {
                    ModelSelector(
                        loadState = loadState,
                        downloaded = downloaded,
                        onLoad = vm::loadModel,
                        onRefresh = vm::refreshModels,
                    )
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { vm.clear() }) {
                            Icon(Icons.Default.Add, contentDescription = "Новый чат")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            (loadState as? ModelLoadState.Failed)?.let { s ->
                ErrorBanner(s.message)
            }

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
                if (messages.isEmpty()) {
                    item { EmptyHint(loaded = loadState is ModelLoadState.Loaded) }
                }
                items(messages, key = { it.id }) { MessageBubble(it) }
            }

            if (generating) ThinkingBar(genProgress)

            ChatInput(loadState is ModelLoadState.Loaded, generating, vm::send, vm::stop)
        }
    }
}

@Composable
private fun ModelSelector(
    loadState: ModelLoadState,
    downloaded: List<LocalModel>,
    onLoad: (LocalModel) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (val s = loadState) {
        is ModelLoadState.Loaded -> s.name.removeSuffix(".gguf")
        is ModelLoadState.Loading -> "Загрузка…"
        else -> "Выбрать модель"
    }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onRefresh(); expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Выбрать модель")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (downloaded.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Нет моделей — открой Настройки → Модели") },
                    onClick = { expanded = false },
                )
            } else {
                downloaded.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(m.fileName.removeSuffix(".gguf"), fontWeight = FontWeight.Medium)
                                Text(
                                    "${m.modelId} · ${formatBytes(m.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { onLoad(m); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun EmptyHint(loaded: Boolean) {
    Text(
        if (loaded)
            "Введи запрос ниже. История чата сохраняется в течение сессии. При включённом RAG ответы основаны на Википедии. Нажми на сообщение, чтобы скопировать."
        else
            "Выбери модель сверху, чтобы начать. Скачать модели можно в Настройках → «Модели».",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(8.dp),
    )
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.USER
    val align = if (isUser) Alignment.End else Alignment.Start
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.align(align).widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp))
                .background(bg)
                .clickable {
                    if (msg.text.isNotBlank()) {
                        clipboard.setText(AnnotatedString(msg.text))
                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            val text = if (msg.text.isEmpty() && msg.isStreaming) "…" else msg.text
            Text(text, color = fg)
        }
        msg.stats?.let { s ->
            Text(
                statsLine(s),
                modifier = Modifier.align(align).padding(top = 2.dp, start = 4.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Fixed status row above the input while the model is thinking/generating. */
@Composable
private fun ThinkingBar(progress: GenProgress?) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                liveStatus(progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            placeholder = { Text(if (enabled) "Сообщение" else "Сначала выбери модель") },
            shape = RoundedCornerShape(20.dp),
            enabled = enabled && !generating,
            maxLines = 4,
        )
        Spacer(Modifier.width(8.dp))
        if (generating) {
            IconButton(onClick = onStop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
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
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(
                        if (enabled && text.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Отправить",
                    tint = if (enabled && text.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "⏱ 8 с · осталось ~12 с" while generating; "Думаю…" before the first token. */
private fun liveStatus(p: GenProgress?): String {
    if (p == null) return "Думаю…"
    val sb = StringBuilder("⏱ ${secs(p.elapsedMs)} с")
    val eta = p.etaMs
    if (eta != null && eta > 0) sb.append(" · осталось ~${secs(eta)} с")
    return sb.toString()
}

/** "qwen2.5-1.5b · 12 с · 187 ток · 15.6 ток/с" under a finished reply. */
private fun statsLine(s: GenStats): String {
    val rate = String.format("%.1f", s.tokensPerSec)
    return "${s.model} · ${secs(s.elapsedMs)} с · ${s.genTokens} ток · $rate ток/с"
}

private fun secs(ms: Long): Long = (ms + 500) / 1000

@Composable
private fun RagControls(vm: ChatViewModel) {
    val ragOn by vm.ragEnabled.collectAsState()
    val n by vm.ragCandidates.collectAsState()
    val zimState by vm.zimState.collectAsState()

    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
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

private fun formatBytes(b: Long): String {
    if (b <= 0) return "—"
    val units = listOf("Б", "КБ", "МБ", "ГБ")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
