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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.wikillm.android.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val gen = remember { GenerationSettings(context.applicationContext) }
    val sysPrompt by gen.systemPrompt.collectAsState()
    val temp by gen.temperature.collectAsState()
    val thinking by gen.thinking.collectAsState()

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

            Text(
                "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
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
