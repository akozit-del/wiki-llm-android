package com.wikillm.android.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Lets the benchmark be driven from adb.
 *
 * `adb shell input text` cannot type Cyrillic on Samsung firmware — it throws
 * NullPointerException — which meant every Russian question in
 * `benchmark/questions.json` had to be typed by hand, so the reference set
 * could never be run unattended. Intent extras do carry UTF-8 intact, so a
 * question can be delivered as a broadcast instead:
 *
 * ```
 * adb shell "am broadcast -n com.wikillm.android.debug/com.wikillm.android.diag.BenchmarkReceiver \
 *            -a com.wikillm.android.ASK --es q 'кто мэр Тольятти'"
 * ```
 *
 * (The nested quoting matters: `adb shell` hands the line to a remote shell,
 * so without the inner quotes it splits on spaces and `am` mis-parses it.)
 *
 * The receiver is declared only in `src/debug/AndroidManifest.xml`, so it does
 * not exist in a release build — an exported receiver that injects prompts is
 * a debug-only affordance and must not ship.
 */
object BenchmarkBridge {

    private val _questions = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Questions pushed in from adb; the chat screen collects and sends them. */
    val questions: SharedFlow<String> = _questions.asSharedFlow()

    fun post(question: String) {
        if (question.isBlank()) return
        DiagLog.i(TAG, "Benchmark question via intent: $question")
        _questions.tryEmit(question)
    }

    const val ACTION = "com.wikillm.android.ASK"
    const val EXTRA_QUESTION = "q"
    private const val TAG = "BenchmarkBridge"
}

/** Receives [BenchmarkBridge.ACTION] and forwards the question to the chat. */
class BenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BenchmarkBridge.ACTION) return
        val q = intent.getStringExtra(BenchmarkBridge.EXTRA_QUESTION).orEmpty()
        BenchmarkBridge.post(q)
    }
}
