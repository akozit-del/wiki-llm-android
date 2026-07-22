package com.wikillm.android.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.wikillm.android.BuildConfig
import com.wikillm.android.data.DownloadEvent
import com.wikillm.android.data.ModelDownloader
import com.wikillm.android.diag.DiagLog
import com.wikillm.android.rag.EmbeddingHolder
import com.wikillm.android.ui.theme.ThemePrefs
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val gen = remember { GenerationSettings(context.applicationContext) }
    val sysPrompt by gen.systemPrompt.collectAsState()
    val temp by gen.temperature.collectAsState()
    val thinking by gen.thinking.collectAsState()
    val words by gen.responseWords.collectAsState()
    val themeMode by ThemePrefs.mode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Разделы")

            SettingsNavItem(
                icon = Icons.Default.Memory,
                title = "Модели",
                subtitle = "Скачать и выбрать GGUF-модели",
            ) { navController.navigate("models") }

            SettingsNavItem(
                icon = Icons.Default.MenuBook,
                title = "Википедия",
                subtitle = "Выбрать ZIM-файл для офлайн-поиска (RAG)",
            ) { navController.navigate("wiki") }

            SettingsNavItem(
                icon = Icons.Default.Search,
                title = "Поиск в вики (тест)",
                subtitle = "Проверка libkiwix: поиск и чтение статей",
            ) { navController.navigate("wikisearch") }

            SettingsNavItem(
                icon = Icons.Default.BugReport,
                title = "Диагностика",
                subtitle = "Логи и ошибки приложения",
            ) { navController.navigate("diag") }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            SectionLabel("Оформление")
            Text("Тема", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePrefs.Mode.entries.forEach { m ->
                    FilterChip(
                        selected = themeMode == m,
                        onClick = { ThemePrefs.set(m) },
                        label = { Text(themeLabel(m)) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            SectionLabel("Параметры генерации")

            // System prompt
            OutlinedTextField(
                value = sysPrompt,
                onValueChange = gen::setSystemPrompt,
                label = { Text("Системный промпт") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
            TextButton(onClick = { gen.resetSystemPrompt() }) {
                Text("Сбросить к стандартному")
            }

            // Temperature
            Text(
                "Температура: ${String.format("%.1f", temp)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = temp,
                onValueChange = { gen.setTemperature(it) },
                valueRange = 0.1f..1.5f,
                steps = 13, // 0.1 increments
            )
            Text(
                "Ниже — точнее и предсказуемее, выше — разнообразнее и креативнее.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // Response length
            Text(
                "Длина ответа: примерно $words слов",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = words.toFloat(),
                onValueChange = { gen.setResponseWords(it.roundToInt()) },
                valueRange = 50f..600f,
                steps = 10, // 50-word increments
            )
            Text(
                "Целевой объём ответа. Добавляется в системный промпт и масштабирует лимит токенов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            // Thinking mode (Qwen3.5 etc.)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = thinking, onCheckedChange = gen::setThinking)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (thinking) "Режим: Думать" else "Режим: Отвечать сразу",
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Для reasoning-моделей (Qwen3.5). «Думать» точнее на сложных вопросах, но медленнее и тратит больше токенов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            SectionLabel("Семантический поиск (mE5)")
            RerankSection(gen)

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Text(
                "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * Variant 3 UI: download the ~126 MB mE5-small reranker, toggle semantic
 * reranking, and a smoke-test button that embeds two probes and logs their
 * cosine similarity so the native embedding path can be validated on-device.
 */
@Composable
private fun RerankSection(gen: GenerationSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rerankOn by gen.rerank.collectAsState()

    var downloaded by remember { mutableStateOf(EmbeddingHolder.isDownloaded(context)) }
    var progress by remember { mutableStateOf(-1f) } // -1 = idle
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { downloaded = EmbeddingHolder.isDownloaded(context) }

    Text(
        "Реранжирует кандидатов из Википедии по смыслу (эмбеддинги multilingual-e5-small). " +
            "Помогает вопросам без готовой статьи-списка. Требует загрузки модели (~126 МБ).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    Spacer(Modifier.height(6.dp))

    if (!downloaded) {
        if (progress in 0f..1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Загрузка: ${(progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Button(
                onClick = {
                    progress = 0f
                    status = ""
                    scope.launch {
                        val out = EmbeddingHolder.modelFile(context)
                        ModelDownloader().download(EmbeddingHolder.DOWNLOAD_URL, out).collect { ev ->
                            when (ev) {
                                is DownloadEvent.Progress ->
                                    progress = if (ev.totalBytes > 0)
                                        (ev.bytesRead.toFloat() / ev.totalBytes) else 0f
                                is DownloadEvent.Done -> {
                                    progress = -1f
                                    downloaded = true
                                    EmbeddingHolder.refreshPresence(context)
                                    status = "Модель загружена."
                                }
                                is DownloadEvent.Failed -> {
                                    progress = -1f
                                    status = "Ошибка: ${ev.message}"
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Скачать реранкер (~126 МБ)") }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = rerankOn, onCheckedChange = gen::setRerank)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (rerankOn) "Реранк включён" else "Реранк выключен",
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "Добавляет ~1–2 c на запрос (эмбеддинг кандидатов на CPU).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                status = "Тест…"
                scope.launch {
                    val ok = EmbeddingHolder.ensureLoaded(context)
                    if (!ok) { status = "Не удалось загрузить эмбеддер (см. лог)."; return@launch }
                    val q = EmbeddingHolder.embedQuery("кто мэр города Тольятти")
                    val good = EmbeddingHolder.embedPassage("Градоначальники Тольятти — список глав города")
                    val bad = EmbeddingHolder.embedPassage("Рецепт классического борща со свёклой")
                    if (q == null || good == null || bad == null) {
                        status = "Эмбеддинг вернул null (см. лог)."; return@launch
                    }
                    val cGood = EmbeddingHolder.cosine(q, good)
                    val cBad = EmbeddingHolder.cosine(q, bad)
                    val verdict = if (cGood > cBad) "OK" else "ПОДОЗРИТЕЛЬНО"
                    status = "cos(релевант)=%.3f  cos(шум)=%.3f  → %s".format(cGood, cBad, verdict)
                    DiagLog.i("RerankTest", status)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Проверить эмбеддер") }
    }

    if (status.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

private fun themeLabel(m: ThemePrefs.Mode): String = when (m) {
    ThemePrefs.Mode.SYSTEM -> "Системная"
    ThemePrefs.Mode.LIGHT -> "Светлая"
    ThemePrefs.Mode.DARK -> "Тёмная"
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
