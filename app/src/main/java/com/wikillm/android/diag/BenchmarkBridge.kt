package com.wikillm.android.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.wikillm.android.rag.FactoidAnswerer
import com.wikillm.android.rag.InfoboxExtractor
import com.wikillm.android.rag.RagPromptBuilder
import com.wikillm.android.rag.ZimSearchHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Retrieval-only probe: runs the fast path and the ZIM candidate search for
     * [question], logs both, and never touches the LLM.
     *
     * Scoring the 32-question reference set through the chat path means paying
     * a full decode (~60 s) per question just to learn whether the right
     * article was retrieved — 30+ minutes for one baseline, which is why
     * recall@k had never been measured. Everything recall@k, fast-path
     * hit-rate and false-fast-rate need is decided before the first token is
     * generated, so this stops there. One line per fact, prefixed [PROBE], so
     * `benchmark/score.py` can parse diag.log without guessing.
     */
    fun probe(context: Context, question: String, k: Int) {
        if (question.isBlank()) return
        probeScope.launch {
            DiagLog.i(TAG, "[PROBE] q=$question")
            runCatching { ZimSearchHolder.ensureOpen(context.applicationContext) }
            val searcher = ZimSearchHolder.searcher()
            if (searcher == null) {
                DiagLog.i(TAG, "[PROBE] error=zim-not-open")
                return@launch
            }
            val t0 = SystemClock.elapsedRealtime()
            val fast = runCatching { FactoidAnswerer.tryAnswer(question, searcher) }.getOrNull()
            val fastMs = SystemClock.elapsedRealtime() - t0
            if (fast != null) {
                DiagLog.i(TAG, "[PROBE] fast=hit ms=$fastMs article=${fast.articleTitle} " +
                    "field=${fast.label} value=${fast.value}")
            } else {
                DiagLog.i(TAG, "[PROBE] fast=miss ms=$fastMs")
            }
            val t1 = SystemClock.elapsedRealtime()
            val hits = runCatching { RagPromptBuilder(searcher).probeCandidates(question, k) }
                .getOrElse {
                    DiagLog.i(TAG, "[PROBE] error=${it.message}")
                    emptyList()
                }
            val searchMs = SystemClock.elapsedRealtime() - t1
            // Full ranked list, not the top-5 "Top hits" line: recall@20 cannot
            // be computed from a truncated list.
            DiagLog.i(TAG, "[PROBE] cand=${hits.size} ms=$searchMs titles=" +
                hits.take(k).joinToString(" | ") { it.title })
            DiagLog.i(TAG, "[PROBE] done")
        }
    }

    /**
     * Walks a random sample of articles and logs which infobox fields they
     * carry, so field coverage can be argued from the corpus instead of from
     * our own configuration.
     *
     * The question this answers: we wire question patterns to infobox fields
     * one at a time, and had no idea whether the useful field set is ~50 or
     * ~2000, nor which ones we're missing. [InfoboxExtractor.extract] can't
     * tell us — it only reports fields we already decided to look for — so
     * this uses [InfoboxExtractor.rawLabels] instead.
     *
     * Emits one `[FIELDS]` line per article plus a summary, so
     * `benchmark/score_fields.py` can build the histogram off-device.
     */
    fun fieldScan(context: Context, sample: Int, seed: Long) {
        probeScope.launch {
            DiagLog.i(TAG, "[FIELDS] begin sample=$sample seed=$seed")
            runCatching { ZimSearchHolder.ensureOpen(context.applicationContext) }
            val searcher = ZimSearchHolder.searcher()
            if (searcher == null) {
                DiagLog.i(TAG, "[FIELDS] error=zim-not-open")
                return@launch
            }
            val t0 = SystemClock.elapsedRealtime()
            val articles = searcher.sampleArticles(sample, seed)
            DiagLog.i(TAG, "[FIELDS] sampled=${articles.size} ms=${SystemClock.elapsedRealtime() - t0}")
            var withCard = 0
            articles.forEachIndexed { i, hit ->
                val html = searcher.readArticleHtml(hit.path)
                val labels = if (html == null) emptyList() else InfoboxExtractor.rawLabels(html)
                if (labels.isNotEmpty()) withCard++
                // Tab-separated so labels containing spaces survive parsing.
                DiagLog.i(TAG, "[FIELDS] a=${i + 1}\tcard=${labels.size}\t" +
                    "title=${hit.title}\tlabels=${labels.joinToString("\t")}")
            }
            DiagLog.i(TAG, "[FIELDS] done articles=${articles.size} withCard=$withCard " +
                "ms=${SystemClock.elapsedRealtime() - t0}")
        }
    }

    const val ACTION = "com.wikillm.android.ASK"
    const val EXTRA_QUESTION = "q"
    /**
     * "search" = retrieval-only probe, "fields" = infobox field scan over a
     * random article sample; anything else = full chat answer.
     */
    const val EXTRA_MODE = "m"
    /** Probe: candidates to request. Field scan: articles to sample. */
    const val EXTRA_K = "k"
    /** Field scan: RNG seed, so a sample can be reproduced. */
    const val EXTRA_SEED = "seed"
    private const val TAG = "BenchmarkBridge"
}

/** Receives [BenchmarkBridge.ACTION] and forwards the question to the chat. */
class BenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BenchmarkBridge.ACTION) return
        val q = intent.getStringExtra(BenchmarkBridge.EXTRA_QUESTION).orEmpty()
        when (intent.getStringExtra(BenchmarkBridge.EXTRA_MODE)) {
            "search" -> BenchmarkBridge.probe(context, q, intent.getIntExtra(BenchmarkBridge.EXTRA_K, 20))
            "fields" -> BenchmarkBridge.fieldScan(
                context,
                sample = intent.getIntExtra(BenchmarkBridge.EXTRA_K, 2000),
                seed = intent.getLongExtra(BenchmarkBridge.EXTRA_SEED, 1L),
            )
            else -> BenchmarkBridge.post(q)
        }
    }
}
