package com.wikillm.android.settings

import android.content.Context
import com.wikillm.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("wikillm_settings", Context.MODE_PRIVATE)

    private val _githubPat = MutableStateFlow(prefs.getString(KEY_PAT, "") ?: "")
    val githubPat: StateFlow<String> = _githubPat.asStateFlow()

    private val _autoSend = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SEND, false))
    val autoSend: StateFlow<Boolean> = _autoSend.asStateFlow()

    fun setGithubPat(v: String) {
        prefs.edit().putString(KEY_PAT, v).apply()
        _githubPat.value = v
    }

    fun setAutoSend(v: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SEND, v).apply()
        _autoSend.value = v
    }

    /** Effective token: user override if set, else baked-in from CI secret. */
    fun effectivePat(): String =
        _githubPat.value.ifBlank { BuildConfig.DIAG_PAT }

    companion object {
        private const val KEY_PAT = "github_pat"
        private const val KEY_AUTO_SEND = "auto_send_diag"
        const val REPO = "akozit-del/wiki-llm-android"
    }
}
