package com.wikillm.android.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(navController: NavController) {
    val entries by DiagLog.entries.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                },
                actions = {
                    TextButton(onClick = {
                        copyToClipboard(context, DiagLog.dump())
                        Toast.makeText(context, "Скопировано в буфер", Toast.LENGTH_SHORT).show()
                    }) { Text("Копировать") }
                    TextButton(onClick = { DiagLog.clear() }) { Text("Очистить") }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        "Лог пуст. Запиши действие в приложении (грузи модель, ищи в вики) и вернись сюда — здесь будут логи и ошибки.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@Column
            }
            val df = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(entries, key = { it.timestamp.toString() + it.tag + it.message.take(20) }) { e ->
                    val color = when (e.level) {
                        DiagLog.Level.ERROR -> MaterialTheme.colorScheme.error
                        DiagLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                        DiagLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
                    }
                    val lvl = when (e.level) {
                        DiagLog.Level.ERROR -> "E"; DiagLog.Level.WARN -> "W"; DiagLog.Level.INFO -> "I"
                    }
                    Text(
                        "${df.format(Date(e.timestamp))} $lvl/${e.tag}: ${e.message}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("wiki-llm diag", text))
}
