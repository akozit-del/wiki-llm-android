package com.wikillm.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide theme preference. Process singleton so MainActivity (applies it) and
 * the Settings screen (changes it) share one source of truth. SYSTEM follows the
 * phone's light/dark setting.
 */
object ThemePrefs {

    enum class Mode { SYSTEM, LIGHT, DARK }

    private var prefs: android.content.SharedPreferences? = null

    private val _mode = MutableStateFlow(Mode.SYSTEM)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("wikillm_app", Context.MODE_PRIVATE)
        prefs = p
        _mode.value = runCatching { Mode.valueOf(p.getString(KEY, Mode.SYSTEM.name)!!) }
            .getOrDefault(Mode.SYSTEM)
    }

    fun set(mode: Mode) {
        _mode.value = mode
        prefs?.edit()?.putString(KEY, mode.name)?.apply()
    }

    private const val KEY = "theme_mode"
}
