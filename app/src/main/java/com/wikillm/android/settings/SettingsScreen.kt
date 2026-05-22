package com.wikillm.android.settings

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.wikillm.android.BuildConfig
import com.wikillm.android.diag.DiagLog
import com.wikillm.android.diag.github.GitHubIssueReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app.applicationContext)
    val githubPat = repo.githubPat
    val autoSend = repo.autoSend
    val bakedInPat: Boolean = BuildConfig.DIAG_PAT.isNotBlank()

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    sealed interface TestState {
        data object Idle : TestState
        data object Sending : TestState
        data class Ok(val url: String) : TestState
        data class Failed(val msg: String) : TestState
    }

    fun setPat(v: String) = repo.setGithubPat(v.trim())
    fun setAutoSend(v: Boolean) = repo.setAutoSend(v)

    fun sendTest() {
        _test.value = TestState.Sending
        viewModelScope.launch {
            val reporter = GitHubIssueReporter(repo)
            val body = reporter.formatBody(
                "Test issue from wiki-llm-android settings screen.\nApp version: ${BuildConfig.VERSION_NAME}\n\n" +
                DiagLog.dump().takeLast(2000)
            )
            val r = reporter.send("Test: settings ping", body)
            _test.value = when (r) {
                is GitHubIssueReporter.Result.Ok -> TestState.Ok(r.url)
                is GitHubIssueReporter.Result.Failed -> TestState.Failed(r.message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, vm: SettingsViewModel = viewModel()) {
    val pat by vm.githubPat.collectAsState()
    val auto by vm.autoSend.collectAsState()
    val test by vm.test.collectAsState()
    val context = LocalContext.current
    var hidden by remember { mutableStateOf(true) }

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
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Авто-отправка диагностики в GitHub Issues", style = MaterialTheme.typography.titleMedium)
            if (vm.bakedInPat) {
                Text(
                    "Встроенный токен из CI — отправка работает из коробки. Можешь оставить поле PAT пустым.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Встроенного токена нет (не передан через секрет CI). Вставь свой fine-grained PAT с правом Issues:Read+Write на этот репо.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = pat,
                onValueChange = vm::setPat,
                label = { Text("GitHub PAT (опционально)") },
                singleLine = true,
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = {
                    TextButton(onClick = { hidden = !hidden }) {
                        Text(if (hidden) "Показать" else "Скрыть")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = auto, onCheckedChange = vm::setAutoSend)
                Spacer(Modifier.width(8.dp))
                Text("Авто-отправлять ошибки в Issues при их появлении")
            }

            HorizontalDivider()

            Text("Тест: создать тестовый issue", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { vm.sendTest() }, enabled = test !is SettingsViewModel.TestState.Sending) {
                Text("Отправить тестовый issue")
            }
            when (val s = test) {
                SettingsViewModel.TestState.Idle -> Unit
                SettingsViewModel.TestState.Sending -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Отправляю…")
                }
                is SettingsViewModel.TestState.Ok -> Text("Issue создан: ${s.url}", color = MaterialTheme.colorScheme.primary)
                is SettingsViewModel.TestState.Failed -> Text("Ошибка: ${s.msg}", color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()

            Text("Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
            Text("Репо: ${SettingsRepository.REPO}", style = MaterialTheme.typography.bodySmall)
        }
    }
}
