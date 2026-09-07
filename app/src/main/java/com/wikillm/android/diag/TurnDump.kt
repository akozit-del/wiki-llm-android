package com.wikillm.android.diag

import android.content.Context
import android.util.Log
import com.wikillm.android.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Verbatim record of what actually went into the model, and what came back.
 *
 * Why not [DiagLog]: diag.log is a 256 KB rolling file that every scorer parses
 * (`[PHASE]`, `Top hits`, `[TURN] end`). One full prompt is ~7 KB, so a 13-turn
 * pass would push ~90 KB through that window and evict the very lines the run is
 * being scored on. This keeps the bulky text in its own file with its own cap.
 *
 * Why it exists at all: since 2026-09-02 the open question is why three of the
 * thirteen model questions produce garbage («почему Байкал самое глубокое озеро»
 * — 3 of 5 turns) while the other ten produce none in ~85 answers. Both earlier
 * hypotheses — sampler drift, long-run state — assumed the defect was spread
 * evenly across turns, and the per-question tally disproved that. What is left
 * is something inside the context of those specific articles, and it cannot be
 * examined while the log keeps only `prompt.take(500)`.
 *
 * The dump is the WHOLE model input — system prompt and every history message —
 * not just the RAG excerpt string. History replay was itself a defect this month
 * (`2cbb286`), so a record that shows only the excerpt could not have caught it.
 *
 * Debug builds only: this is a benchmarking instrument, like [BenchmarkBridge],
 * and a release user has no reason to carry megabytes of prompt text.
 */
object TurnDump {

    private const val MAX_FILE_BYTES = 4 * 1024 * 1024L  // ~500 turns, rolling
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    /** Call from Application.onCreate, next to [DiagLog.attach]. No-op in release. */
    fun attach(context: Context) {
        if (!BuildConfig.DEBUG || file != null) return
        file = File(context.filesDir, "turns.log")
    }

    /**
     * The exact model input for one turn. [messages] is the history as handed to
     * `generateChat`, in order, as (role, content) pairs.
     */
    fun prompt(question: String, systemPrompt: String, messages: List<Pair<String, String>>) {
        val f = file ?: return
        append(f, buildString {
            append("\n===== PROMPT ").append(df.format(Date()))
            append(" q=").append(question.trim().replace('\n', ' ')).append(" =====\n")
            append("--- system (").append(systemPrompt.length).append(" chars) ---\n")
            append(systemPrompt).append('\n')
            messages.forEachIndexed { i, (role, content) ->
                append("--- msg[").append(i).append("] ").append(role)
                append(" (").append(content.length).append(" chars) ---\n")
                append(content).append('\n')
            }
        })
    }

    /** The reply in full — the `Reply (…)` line in diag.log keeps only 200 chars. */
    fun reply(text: String, meta: String) {
        val f = file ?: return
        append(f, "===== REPLY ${df.format(Date())} $meta =====\n$text\n")
    }

    private fun append(f: File, s: String) {
        synchronized(this) {
            runCatching {
                f.appendText(s)
                if (f.length() > MAX_FILE_BYTES) {
                    // Drop the oldest half. A truncated first record is fine —
                    // records are read newest-first anyway.
                    f.writeText(f.readText().takeLast((MAX_FILE_BYTES / 2).toInt()))
                }
            }.onFailure { Log.w("TurnDump", "append failed: ${it.message}") }
        }
    }
}
