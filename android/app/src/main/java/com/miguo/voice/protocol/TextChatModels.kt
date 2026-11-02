package com.miguo.voice.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** v0.2.0：POST /api/v1/chat/text（memory_core v2 api/text_chat.py）。stream=true → NDJSON：start / reply_text / audio / done / error */
data class TextChatRequest(
    val text: String,
    /** null → [resume] 决定续接最近对话还是新建 */
    val conversationId: String?,
    val resume: Boolean = true,
    val speak: Boolean = false,
    val useMemory: Boolean = true,
    val voice: String? = null,
    /** edge-tts 韵律（与 StartMessage / tts.validate_prosody 一致） */
    val rate: String = "+0%",
    val volume: String = "+0%",
    val pitch: String = "+0Hz",
    val stream: Boolean = true,
    /** v0.2.0：用户当前位置（null = 不发） */
    val location: WireLocation? = null,
) {
    fun encode(): String = buildJsonObject {
        put("text", text)
        conversationId?.let { put("conversation_id", it) }
        put("resume", resume)
        put("speak", speak)
        put("use_memory", useMemory)
        voice?.let { put("voice", it) }
        put("rate", rate)
        put("volume", volume)
        put("pitch", pitch)
        put("stream", stream)
        put("client", "android")
        location?.takeIf { it.isValid }?.let { l -> put("location", l.toJson()) }
    }.toString()
}

sealed class TextChatEvent {
    data class Start(val conversationId: String?) : TextChatEvent()
    data class Reply(val seq: Int, val text: String) : TextChatEvent()
    class Audio(val seq: Int, val mp3: ByteArray) : TextChatEvent()
    data class Done(val conversationId: String?, val reply: String, val llmError: String?) : TextChatEvent()
    data class Error(val code: String, val message: String) : TextChatEvent()
}

/** 一次文字对话的最终结果 */
class TextChatResult(val conversationId: String?, val reply: String, val llmError: String?, val audioMp3: ByteArray?)

/** GET /conversations 列表项（list_conversations：id/title/turns/updated_at/last_message…） */
data class ConversationSummary(
    val id: String,
    val title: String?,
    val turns: Int?,
    val updatedAt: String?,
    val preview: String?,
)

object TextChatProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析 NDJSON 的一行；空行/坏行 → null */
    fun parseLine(line: String): TextChatEvent? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val o = try { json.parseToJsonElement(t) as? JsonObject } catch (e: Exception) { null } ?: return null
        return when (o.str("type")) {
            "start" -> TextChatEvent.Start(o.str("conversation_id") ?: o.str("session_id"))
            "reply_text" -> TextChatEvent.Reply(o.int("seq") ?: 0, o.str("text") ?: "")
            "audio" -> o.str("data")?.let { d ->
                try { TextChatEvent.Audio(o.int("seq") ?: 0, java.util.Base64.getDecoder().decode(d)) } catch (e: IllegalArgumentException) { null }
            }
            "done" -> TextChatEvent.Done(o.str("conversation_id") ?: o.str("session_id"), o.str("reply") ?: "", o.str("llm_error"))
            "error" -> TextChatEvent.Error(o.str("code") ?: "chat_failed", o.str("message") ?: "文字对话失败")
            else -> null
        }
    }

    /** 非流式响应（stream=false）：{conversation_id, reply, llm_error, audio_base64?} */
    fun parseFull(body: String): TextChatResult? {
        val o = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null } ?: return null
        val audio = o.str("audio_base64")?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        return TextChatResult(o.str("conversation_id"), o.str("reply") ?: "", o.str("llm_error"), audio)
    }

    /** GET /conversations/{id}/messages → messages */
    fun parseMessages(body: String): List<HistoryMessage>? {
        val o = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null } ?: return null
        return ServerEventDecoder.decodeHistory(o.arr("messages"))
    }

    /** GET /conversations → latest（可能为 null） */
    fun parseNewConversationId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val o = try { json.parseToJsonElement(raw) as? JsonObject } catch (_: Exception) { null } ?: return null
        return o.str("conversation_id") ?: o.str("id") ?: o.str("session_id")
    }

    fun parseLatest(body: String): String? {
        val o = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null } ?: return null
        return o.str("latest")
    }

    /** GET /conversations → conversations[] */
    fun parseConversations(body: String): List<ConversationSummary>? {
        val o = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null } ?: return null
        val arr = o.arr("conversations") ?: return emptyList()
        return arr.mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            val id = c.str("id") ?: c.str("conversation_id") ?: return@mapNotNull null
            ConversationSummary(
                id = id,
                title = c.str("title"),
                turns = c.int("turns"),
                updatedAt = c.str("updated_at"),
                preview = c.str("last_message") ?: c.str("preview") ?: c.str("title"),
            )
        }
    }
}
