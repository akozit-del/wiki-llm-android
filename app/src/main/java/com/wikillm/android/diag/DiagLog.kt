package com.wikillm.android.diag

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log buffer. Captures the last N entries (errors, warnings, info) and
 * surfaces them through a StateFlow so a Compose screen can show them and let
 * the user copy everything into the clipboard.
 *
 * Anything important the app catches (load failures, search exceptions, crashes)
 * gets fanned out to both Logcat and this buffer. The user pastes the contents
 * into chat and I read the real stack trace.
 */
object DiagLog {

    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 300

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun i(tag: String, message: String) = add(Level.INFO, tag, message).also { Log.i(tag, message) }
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        add(Level.WARN, tag, message + (throwable?.let { "\n" + stackTrace(it) } ?: ""))
    }
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        add(Level.ERROR, tag, message + (throwable?.let { "\n" + stackTrace(it) } ?: ""))
    }

    fun clear() { _entries.value = emptyList() }

    /** Formats the whole buffer as a single text block for clipboard / share. */
    fun dump(): String {
        val df = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return _entries.value.joinToString("\n") { e ->
            val lvl = when (e.level) { Level.INFO -> "I"; Level.WARN -> "W"; Level.ERROR -> "E" }
            "${df.format(Date(e.timestamp))} $lvl/${e.tag}: ${e.message}"
        }
    }

    private fun add(level: Level, tag: String, message: String) {
        val now = System.currentTimeMillis()
        _entries.update { old ->
            val next = old + Entry(now, level, tag, message)
            if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
        }
    }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /** Installs a global UncaughtExceptionHandler so even unexpected crashes land here. */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                e("CRASH", "Uncaught on ${thread.name}", throwable)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    // tiny helper to keep MutableStateFlow.update inline without importing kotlinx.coroutines.flow.update everywhere
    private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) { value = transform(value) }
}
