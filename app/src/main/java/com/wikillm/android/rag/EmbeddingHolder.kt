package com.wikillm.android.rag

import android.content.Context
import com.wikillm.android.diag.DiagLog
import com.wikillm.android.llm.EmbeddingContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Variant 3 — process-singleton owning the mE5-small embedding model used for
 * semantic reranking of ZIM candidates. Separate from the chat model so both
 * stay resident; ~126 MB q8_0 loads in a couple of seconds and adds ~150 MB RAM.
 *
 * mE5 was trained with instruction prefixes: a search query MUST be embedded as
 * "query: …" and a document as "passage: …". Skipping the prefixes measurably
 * degrades retrieval, so [embedQuery]/[embedPassage] add them — callers pass
 * raw text.
 */
object EmbeddingHolder {

    /** Canonical on-disk location; see [modelFile]. Kept out of the chat-model
     *  catalog (ModelRepository skips the `rerank/` dir) so it can't be loaded
     *  as a chat model by mistake. */
    const val FILE_NAME = "multilingual-e5-small-q8_0.gguf"
    const val DOWNLOAD_URL =
        "https://huggingface.co/cstr/multilingual-e5-small-GGUF/resolve/main/multilingual-e5-small-q8_0.gguf"
    const val APPROX_BYTES = 126L * 1024 * 1024

    sealed interface State {
        data object Absent : State      // model file not downloaded
        data object Idle : State        // file present, not yet loaded
        data object Loading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Absent)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var ctx: EmbeddingContext? = null
    private val loadMutex = Mutex()

    fun modelFile(context: Context): File {
        val external = context.getExternalFilesDir(null)
        val root = if (external != null) File(external, "models") else File(context.filesDir, "models")
        return File(root, "rerank/$FILE_NAME")
    }

    fun isDownloaded(context: Context): Boolean = modelFile(context).let { it.exists() && it.length() > 0 }

    fun refreshPresence(context: Context) {
        if (ctx != null) return
        _state.value = if (isDownloaded(context)) State.Idle else State.Absent
    }

    /** True once the native context is loaded and [embedQuery]/[embedPassage] work. */
    fun isReady(): Boolean = ctx != null

    /**
     * Lazily load the embedder if its file is present. Idempotent and
     * concurrency-safe; returns true when [ctx] is ready afterwards.
     */
    suspend fun ensureLoaded(context: Context): Boolean {
        if (ctx != null) return true
        val file = modelFile(context)
        if (!file.exists() || file.length() == 0L) { _state.value = State.Absent; return false }
        return loadMutex.withLock {
            if (ctx != null) return@withLock true
            _state.value = State.Loading
            try {
                val c = EmbeddingContext.load(file.absolutePath)
                ctx = c
                _state.value = State.Ready
                DiagLog.i(TAG, "Embedder loaded: ${file.name}")
                true
            } catch (t: Throwable) {
                _state.value = State.Failed(t.message ?: "load failed")
                DiagLog.w(TAG, "Embedder load failed", t)
                false
            }
        }
    }

    suspend fun embedQuery(text: String): FloatArray? = embed("query: " + text.trim())

    suspend fun embedPassage(text: String): FloatArray? = embed("passage: " + text.trim())

    private suspend fun embed(prefixed: String): FloatArray? {
        val c = ctx ?: return null
        return withContext(Dispatchers.Default) { c.embed(prefixed) }
    }

    fun unload() {
        ctx?.close()
        ctx = null
        _state.value = State.Idle
    }

    /** Cosine similarity of two L2-normalised vectors == dot product. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private const val TAG = "EmbeddingHolder"
}
