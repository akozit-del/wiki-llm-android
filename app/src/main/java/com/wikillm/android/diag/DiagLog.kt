package com.wikillm.android.diag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Crash-resilient in-app log. Writes every entry both to Logcat and to a plain
 * text file in the app's internal storage. Survives crashes — the next launch
 * picks up where the previous one left off so users can still grab the trace.
 */
object DiagLog {

    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val MAX_ENTRIES_IN_MEMORY = 500
    private const val MAX_FILE_BYTES = 256 * 1024L  // 256 KB rolling

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val nextId = AtomicLong(0)
    @Volatile private var logFile: File? = null
    private val fileLock = Any()
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        add(Level.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        add(Level.WARN, tag, message + (throwable?.let { "\n" + stackTrace(it) } ?: ""))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        add(Level.ERROR, tag, message + (throwable?.let { "\n" + stackTrace(it) } ?: ""))
    }

    fun clear() {
        _entries.value = emptyList()
        synchronized(fileLock) { logFile?.writeText("") }
    }

    fun dump(): String {
        return _entries.value.joinToString("\n") { e ->
            val lvl = lvlChar(e.level)
            "${df.format(Date(e.timestamp))} $lvl/${e.tag}: ${e.message}"
        }
    }

    /** Call once from Application.onCreate / Activity.onCreate so we can persist log. */
    fun attach(context: Context) {
        if (logFile != null) return
        val f = File(context.filesDir, "diag.log")
        logFile = f
        // Read what previous sessions left behind so it stays visible after a crash.
        runCatching {
            if (f.exists() && f.length() > 0) {
                val existing = f.readText()
                val parsed = existing.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                    parseLine(line)
                }
                _entries.value = parsed.takeLast(MAX_ENTRIES_IN_MEMORY)
                nextId.set((_entries.value.lastOrNull()?.id ?: 0L) + 1)
                Log.i("DiagLog", "Restored ${parsed.size} entries from $f")
            }
        }.onFailure { Log.w("DiagLog", "Couldn't restore log: ${it.message}") }
    }

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

    private fun add(level: Level, tag: String, message: String) {
        val now = System.currentTimeMillis()
        val entry = Entry(nextId.getAndIncrement(), now, level, tag, message)
        // Update in-memory list.
        val current = _entries.value
        val next = if (current.size >= MAX_ENTRIES_IN_MEMORY)
            current.drop(current.size - MAX_ENTRIES_IN_MEMORY + 1) + entry
        else current + entry
        _entries.value = next

        // Persist to file (best-effort).
        val f = logFile ?: return
        synchronized(fileLock) {
            runCatching {
                val line = "${df.format(Date(now))} ${lvlChar(level)}/$tag: ${message.replace('\n', '')}\n"
                f.appendText(line)
                if (f.length() > MAX_FILE_BYTES) {
                    // Rotate: keep the last half.
                    val keep = f.readText().takeLast((MAX_FILE_BYTES / 2).toInt())
                    f.writeText(keep)
                }
            }
        }
    }

    private fun parseLine(line: String): Entry? {
        // "yyyy-MM-dd HH:mm:ss.SSS L/TAG: message"
        val m = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([IWE])/([^:]+): (.*)$""").matchEntire(line)
            ?: return null
        val (ts, lvl, tag, msg) = m.destructured
        val timestamp = runCatching { df.parse(ts)?.time ?: 0L }.getOrDefault(0L)
        val level = when (lvl) { "E" -> Level.ERROR; "W" -> Level.WARN; else -> Level.INFO }
        val restored = msg.replace('\u0001', '\n')
        return Entry(nextId.getAndIncrement(), timestamp, level, tag, restored)
    }

    private fun lvlChar(level: Level) = when (level) { Level.ERROR -> "E"; Level.WARN -> "W"; Level.INFO -> "I" }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
