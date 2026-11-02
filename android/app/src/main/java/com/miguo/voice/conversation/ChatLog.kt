package com.miguo.voice.conversation

import com.miguo.voice.protocol.HistoryMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 聊天气泡的一行：用户一句 + 米果的回复（语音轮、文字轮、历史记录统一用这个） */
data class ChatEntry(
    val key: String,
    val user: String = "",
    val assistant: String = "",
    /** "text" = 文字输入；"voice" / null = 语音 */
    val userSource: String? = null,
    val interrupted: Boolean = false,
    val failed: String? = null,
    /** 文字轮等待回复中 */
    val pending: Boolean = false,
    /** 来自服务器历史记录（上次对话） */
    val history: Boolean = false,
)

/**
 * v0.2.0：可滚动的聊天记录。语音（ConversationEngine）和文字（TextChatController）共用一份，按出现顺序排列。
 * 只在主线程修改。
 */
class ChatLog(private val cap: Int = 300) {
    private val _entries = MutableStateFlow<List<ChatEntry>>(emptyList())
    val entries: StateFlow<List<ChatEntry>> = _entries

    fun clear() { _entries.value = emptyList() }

    fun contains(key: String) = _entries.value.any { it.key == key }

    fun ensure(key: String, init: (ChatEntry) -> ChatEntry = { it }) {
        if (contains(key)) return
        _entries.value = (_entries.value + init(ChatEntry(key))).takeLast(cap)
    }

    fun update(key: String, f: (ChatEntry) -> ChatEntry) {
        _entries.value = _entries.value.map { if (it.key == key) f(it) else it }
    }

    fun remove(key: String) { _entries.value = _entries.value.filterNot { it.key == key } }

    fun removeIf(p: (ChatEntry) -> Boolean) { _entries.value = _entries.value.filterNot(p) }

    /** 用服务器的历史消息替换全部内容（服务端历史是权威：语音轮和文字轮都在里面） */
    fun setHistory(msgs: List<HistoryMessage>) {
        _entries.value = pairUp(msgs).takeLast(cap)
    }

    companion object {
        fun pairUp(msgs: List<HistoryMessage>): List<ChatEntry> {
            val out = ArrayList<ChatEntry>()
            for ((i, m) in msgs.withIndex()) {
                val last = out.lastOrNull()
                if (m.role == "assistant") {
                    if (last != null && last.user.isNotEmpty() && last.assistant.isEmpty()) {
                        out[out.size - 1] = last.copy(assistant = m.content)
                    } else {
                        out += ChatEntry("h$i", assistant = m.content, history = true)
                    }
                } else if (m.role == "user") {
                    out += ChatEntry("h$i", user = m.content, history = true)
                }
            }
            return out
        }
    }
}
