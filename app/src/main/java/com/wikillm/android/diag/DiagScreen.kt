package com.wikillm.android.diag

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wikillm.android.diag.upload.PasteUploaderViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(navController: NavController,
               vm: PasteUploaderViewModel = viewModel()) {
    val entries by DiagLog.entries.collectAsState()
    val uploadState by vm.state.collectAsState()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Диагностика") },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("Назад") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Копировать в буфер") },
                                onClick = {
                                    menuOpen = false
                                    copyToClipboard(context, DiagLog.dump())
                                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Поделиться…") },
                                onClick = {
                                    menuOpen = false
                                    shareText(context, DiagLog.dump())
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Загрузить (получить ссылку)") },
                                onClick = {
                                    menuOpen = false
                                    vm.upload(DiagLog.dump())
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Очистить лог") },
                                onClick = { menuOpen = false; DiagLog.clear() },
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            UploadBanner(uploadState, onCopyUrl = { url ->
                copyToClipboard(context, url)
                Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
            })

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Лог пуст. Запиши действие в приложении (грузи модель, ищи в вики) и вернись сюда.",
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
                items(entries, key = { it.id }) { e ->
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

@Composable
private fun UploadBanner(state: PasteUploaderViewModel.State, onCopyUrl: (String) -> Unit) {
    when (state) {
        PasteUploaderViewModel.State.Idle -> Unit
        PasteUploaderViewModel.State.Uploading -> Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Загружаю лог…")
            }
        }
        is PasteUploaderViewModel.State.Done -> Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Готово. Скопируй ссылку и пришли её Клоду:")
                Spacer(Modifier.height(6.dp))
                Text(state.url, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Row {
                    OutlinedButton(onClick = { onCopyUrl(state.url) }) { Text("Копировать ссылку") }
                }
            }
        }
        is PasteUploaderViewModel.State.Failed -> Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Загрузка не получилась.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                Text(state.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("wiki-llm diag", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "wiki-llm diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, "Отправить лог").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
