package com.wikillm.android.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-tunable generation parameters, backed by SharedPreferences.
 *
 * StateFlows drive the Settings UI; the current*() reads pull straight from
 * prefs so ChatViewModel always uses the latest value even though it holds a
 * different instance than the settings screen.
 */
class GenerationSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("wikillm_generation", Context.MODE_PRIVATE)

    private val _systemPrompt = MutableStateFlow(
        prefs.getString(KEY_SYS, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
    )
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    private val _temperature = MutableStateFlow(prefs.getFloat(KEY_TEMP, DEFAULT_TEMPERATURE))
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    /** true = let reasoning models think (<think> blocks); false = direct answers. */
    private val _thinking = MutableStateFlow(prefs.getBoolean(KEY_THINK, false))
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    fun setSystemPrompt(v: String) {
        prefs.edit().putString(KEY_SYS, v).apply(); _systemPrompt.value = v
    }

    fun setTemperature(v: Float) {
        prefs.edit().putFloat(KEY_TEMP, v).apply(); _temperature.value = v
    }

    fun setThinking(v: Boolean) {
        prefs.edit().putBoolean(KEY_THINK, v).apply(); _thinking.value = v
    }

    fun resetSystemPrompt() = setSystemPrompt(DEFAULT_SYSTEM_PROMPT)

    // Fresh reads from prefs (used at generation time).
    fun currentSystemPrompt(): String =
        prefs.getString(KEY_SYS, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
    fun currentTemperature(): Float = prefs.getFloat(KEY_TEMP, DEFAULT_TEMPERATURE)
    /** Value to pass to native: true = suppress <think>. */
    fun currentNoThink(): Boolean = !prefs.getBoolean(KEY_THINK, false)

    companion object {
        private const val KEY_SYS = "system_prompt"
        private const val KEY_TEMP = "temperature"
        private const val KEY_THINK = "thinking_enabled"
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — полезный ассистент. Отвечай кратко и по существу на русском языке. " +
            "Если ответ короткий — не растягивай. Если не знаешь точно — пиши «не знаю» вместо догадок. " +
            "Не повторяй одну и ту же мысль или слово несколько раз."
    }
}
