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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
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
import com.wikillm.android.data.Conversation
import com.wikillm.android.data.LocalModel
import com.wikillm.android.settings.GenerationSettings
import com.wikillm.android.ui.MarkdownText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navController: NavController, vm: ChatViewModel = viewModel()) {
    val downloaded by vm.downloadedModels.collectAsState()
    val loadState by vm.loadState.collectAsState()
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()
    val genProgress by vm.genProgress.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val freeMem by vm.freeMemBytes.collectAsState()
    val searchStep by vm.searchStep.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun closeDrawer() = scope.launch { drawerState.close() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                conversations = conversations,
                onNewChat = { vm.clear(); closeDrawer() },
                onOpen = { id -> vm.openConversation(id); closeDrawer() },
                onDelete = { id -> vm.deleteConversation(id) },
                onSettings = { closeDrawer(); navController.navigate("settings") },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "История")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ModelSelector(
                                loadState = loadState,
                                downloaded = downloaded,
                                onLoad = vm::loadModel,
                                onRefresh = vm::refreshModels,
                            )
                            if (freeMem > 0) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "free ram ${String.format("%.1f", freeMem / 1073741824.0)} GB",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.clear() }) {
                            Icon(Icons.Default.Add, contentDescription = "Новый чат")
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                (loadState as? ModelLoadState.Failed)?.let { s -> ErrorBanner(s.message) }

                QuickToggles(vm.settings)
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

                if (generating) ThinkingBar(genProgress, searchStep)

                ChatInput(loadState is ModelLoadState.Loaded, generating, vm::send, vm::stop)
            }
        }
    }
}

@Composable
private fun HistoryDrawer(
    conversations: List<Conversation>,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Spacer(Modifier.height(16.dp))
        Text(
            "Wiki LLM",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            label = { Text("Новый чат") },
            selected = false,
            onClick = onNewChat,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            "История",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 28.dp, bottom = 4.dp),
        )
        if (conversations.isEmpty()) {
            Text(
                "Пока пусто. Задай вопрос — диалоги будут сохраняться здесь.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(conversations, key = { it.id }) { c ->
                NavigationDrawerItem(
                    label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = false,
                    onClick = { onOpen(c.id) },
                    badge = {
                        IconButton(onClick = { onDelete(c.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", modifier = Modifier.size(20.dp))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text("Настройки") },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
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
                modifier = Modifier.widthIn(max = 150.dp),
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
            "Введи запрос ниже. История чатов — в меню слева (☰). Нажми на сообщение, чтобы скопировать."
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
        if (msg.thinking.isNotBlank()) {
            ThinkingBlock(msg.thinking, streaming = msg.isStreaming && msg.text.isBlank())
        }
        // With a reasoning model the answer can still be empty while thinking
        // streams — don't show an empty bubble on top of the reasoning block.
        if (msg.text.isNotBlank() || msg.thinking.isBlank()) {
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
                when {
                    msg.text.isEmpty() && msg.isStreaming -> Text("…", color = fg)
                    isUser -> Text(msg.text, color = fg)
                    else -> MarkdownText(msg.text, color = fg) // render assistant Markdown
                }
            }
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

/**
 * The model's <think> content, shown as a collapsible block above the answer.
 * Auto-expands while it is the only thing streaming (so a reasoning model that
 * spends its whole budget thinking still looks alive), and collapses once the
 * answer arrives — tap the header to toggle.
 */
@Composable
private fun ThinkingBlock(thinking: String, streaming: Boolean) {
    // `streaming` is the initial state per message; the user's tap wins after.
    var expanded by remember(streaming) { mutableStateOf(streaming) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (streaming) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (streaming) "Размышляю…" else "💭 Размышления модели",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${thinking.length} симв. ${if (expanded) "▲" else "▼"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp),
                )
            }
        }
    }
}

/** Fixed status row above the input while the model is thinking/generating. */
@Composable
private fun ThinkingBar(progress: GenProgress?, searchStep: String?) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                searchStep ?: liveStatus(progress), // show the agentic search step when active
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

/**
 * "qwen2.5-3b · 12 с · 187 ток · 15.6 ток/с · префилл 1500 ток @ 320 т/с".
 * The prefill segment (prompt-processing throughput) appears only when native
 * timing is available — it's the number that jumps on the NPU for big RAG
 * prompts, so it's worth surfacing right under the reply.
 */
private fun statsLine(s: GenStats): String {
    // Prefer the precise decode rate (excludes prefill + retrieval); fall back
    // to the whole-turn rate when per-phase timing is unavailable.
    val rate = String.format("%.1f", if (s.decodeMs > 0) s.decodeTokensPerSec else s.tokensPerSec)
    val base = "${s.model} · ${secs(s.elapsedMs)} с · ${s.genTokens} ток · $rate ток/с"
    return if (s.prefillMs > 0 && s.promptTokens > 0) {
        base + " · префилл ${s.promptTokens} ток @ ${String.format("%.0f", s.prefillTokensPerSec)} т/с"
    } else base
}

private fun secs(ms: Long): Long = (ms + 500) / 1000

/**
 * Compact single-row chip strip above the RAG card: mirrors the three most
 * common Settings toggles (thinking mode, MTP, compute device) so the user
 * doesn't have to open Settings mid-chat just to flip them. Bound to the same
 * GenerationSettings StateFlows — changes here take effect on the next model
 * load / next generation (identical semantics to the Settings screen).
 */
@Composable
private fun QuickToggles(settings: GenerationSettings) {
    val thinking by settings.thinking.collectAsState()
    val mtp by settings.mtp.collectAsState()
    val device by settings.device.collectAsState()
    var deviceExpanded by remember { mutableStateOf(false) }
    val deviceLabel = when (device) {
        GenerationSettings.DEVICE_NPU -> "NPU"
        GenerationSettings.DEVICE_GPU -> "GPU"
        GenerationSettings.DEVICE_CPU -> "CPU"
        else -> "Авто"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = thinking,
            onClick = { settings.setThinking(!thinking) },
            label = { Text("Думать", style = MaterialTheme.typography.labelSmall) },
        )
        FilterChip(
            selected = mtp,
            onClick = { settings.setMtp(!mtp) },
            label = { Text("MTP", style = MaterialTheme.typography.labelSmall) },
        )
        Spacer(Modifier.weight(1f))
        Box {
            AssistChip(
                onClick = { deviceExpanded = true },
                label = { Text(deviceLabel, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Устройство") },
            )
            DropdownMenu(expanded = deviceExpanded, onDismissRequest = { deviceExpanded = false }) {
                listOf(
                    GenerationSettings.DEVICE_AUTO to "Авто",
                    GenerationSettings.DEVICE_NPU to "NPU",
                    GenerationSettings.DEVICE_GPU to "GPU",
                    GenerationSettings.DEVICE_CPU to "CPU",
                ).forEach { (v, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { settings.setDevice(v); deviceExpanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun RagControls(vm: ChatViewModel) {
    val ragOn by vm.ragEnabled.collectAsState()
    val n by vm.ragCandidates.collectAsState()
    val zimState by vm.zimState.collectAsState()
    val deep by vm.deepSearch.collectAsState()

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
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = deep, onCheckedChange = vm::setDeepSearch)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Глубокий поиск", fontWeight = FontWeight.Medium)
                        Text(
                            "Модель сама ищет по цепочке (несколько шагов). Точнее для перечней, но медленнее.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
