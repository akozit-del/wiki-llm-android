package com.wikillm.android.diag.github

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wikillm.android.diag.DiagLog
import com.wikillm.android.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GitHubReporterViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app.applicationContext)
    private val reporter = GitHubIssueReporter(settings)

    sealed interface State {
        data object Idle : State
        data object Sending : State
        data class Ok(val url: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun send() {
        if (_state.value is State.Sending) return
        _state.value = State.Sending
        viewModelScope.launch {
            val dump = DiagLog.dump()
            val title = reporter.defaultTitle()
            val body = reporter.formatBody(dump)
            val r = reporter.send(title, body)
            _state.value = when (r) {
                is GitHubIssueReporter.Result.Ok -> State.Ok(r.url)
                is GitHubIssueReporter.Result.Failed -> State.Failed(r.message)
            }
        }
    }
}
