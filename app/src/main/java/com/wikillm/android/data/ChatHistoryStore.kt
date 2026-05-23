package com.wikillm.android.data

import android.content.Context
import com.wikillm.android.diag.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredMessage(val role: String, val text: String)

@Serializable
data class Conversation(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredMessage>,
)

/**
 * Persists chat conversations to a JSON file so they survive app restarts
 * (OpenWebUI-style history). Small text payloads, so reads happen once at
 * construction and writes are debounced onto an IO scope.
 */
class ChatHistoryStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "chat_history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _conversations = MutableStateFlow(load())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private fun load(): List<Conversation> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString<List<Conversation>>(file.readText())
            .sortedByDescending { it.updatedAt }
    }.onFailure { DiagLog.e(TAG, "Failed to load chat history", it) }
        .getOrDefault(emptyList())

    private fun persist(list: List<Conversation>) {
        _conversations.value = list
        ioScope.launch {
            runCatching { file.writeText(json.encodeToString(list)) }
                .onFailure { DiagLog.e(TAG, "Failed to save chat history", it) }
        }
    }

    fun get(id: Long): Conversation? = _conversations.value.firstOrNull { it.id == id }

    /** Insert or replace a conversation, keeping the list sorted by recency. */
    fun upsert(conv: Conversation) {
        val rest = _conversations.value.filter { it.id != conv.id }
        persist((listOf(conv) + rest).sortedByDescending { it.updatedAt })
    }

    fun delete(id: Long) {
        persist(_conversations.value.filter { it.id != id })
    }

    companion object { private const val TAG = "ChatHistory" }
}
