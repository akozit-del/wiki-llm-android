package com.wikillm.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.wikillm.android.data.ModelRepository
import com.wikillm.android.data.ZimRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    val modelRepo = remember { ModelRepository(context.applicationContext) }
    val zimRepo = remember { ZimRepository(context.applicationContext) }

    LaunchedEffect(Unit) {
        modelRepo.refreshLocal()
        zimRepo.refreshDownloaded()
        if (zimRepo.zimDir.value != null) zimRepo.rescanDirectory()
    }

    val models by modelRepo.local.collectAsState()
    val zimSelected by zimRepo.selected.collectAsState()
    val zimDownloaded by zimRepo.downloaded.collectAsState()
    val zimScanned by zimRepo.scanned.collectAsState()
    val zimAll = zimSelected.map { it.displayName } +
            zimDownloaded.map { it.filename } +
            zimScanned.map { it.displayName }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wiki LLM") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Локальная нейросеть + Википедия офлайн",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Скачай GGUF-модель, выбери ZIM-файл с Википедией — и можно задавать вопросы офлайн.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            MenuCard(
                icon = Icons.Default.Memory,
                title = "Модели",
                subtitle = when {
                    models.isEmpty() -> "Ничего не скачано"
                    models.size == 1 -> "Готова: ${models[0].fileName}"
                    else -> "Скачано: ${models.size} · последняя ${models.last().fileName}"
                },
                onClick = { navController.navigate("models") }
            )

            MenuCard(
                icon = Icons.Default.MenuBook,
                title = "Википедия",
                subtitle = when {
                    zimAll.isEmpty() -> "Не выбрано"
                    zimAll.size == 1 -> "Готов: ${zimAll[0]}"
                    else -> "Доступно: ${zimAll.size} ZIM-файлов · ${zimAll[0]}"
                },
                onClick = { navController.navigate("wiki") }
            )

            MenuCard(
                icon = Icons.Default.Chat,
                title = "Чат",
                subtitle = if (models.isEmpty())
                    "Сначала скачай модель в разделе «Модели»"
                else
                    "Загрузи модель и начни задавать вопросы",
                onClick = { navController.navigate("chat") }
            )
        }
    }
}

@Composable
private fun MenuCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
