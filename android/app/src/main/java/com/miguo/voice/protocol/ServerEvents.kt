package com.miguo.voice.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// 服务端 → 客户端 的事件（voice/session.py 实际发送的字段；对应 iOS MiguoProtocol/ServerEvents.swift）。
// 解码策略：绝大多数字段可选（服务端在不同分支会省略或给 null，例如被打断的 response_end 没有 reply、
// 出错的 response_end 没有 metrics、bad_user 的 error 没有 fix）。

data class AudioOut(val format: AudioPayloadFormat, val contentType: String?, val sampleRate: Int, val transport: String?)

data class Ready(
    val sessionId: String,
    val userId: String?,
    val bargeIn: Boolean?,
    val audioInSampleRate: Int?,
    val audioOut: AudioOut,
    val vadEngine: String?,
    val idleTimeoutS: Double?,
    val maxSessionS: Double?,
    /** v2：稳定对话信息（旧服务器没有 → null） */
    val conversation: ConversationInfo? = null,
)

/** v2 ready.conversation：{id, resumed, turns, persistent, history:[{role, content, timestamp}]} */
data class ConversationInfo(
    val id: String,
    val resumed: Boolean,
    val turns: Int,
    val persistent: Boolean?,
    val history: List<HistoryMessage>,
)

/** 一条历史消息（short_term Turn.to_dict：role = user | assistant） */
data class HistoryMessage(val role: String, val content: String, val timestamp: String?)

data class VadStart(val turnId: Int, val tMs: Double?)
data class VadEnd(val turnId: Int, val speechMs: Double?, val audioMs: Double?, val forced: Boolean?, val tMs: Double?)
data class Transcript(val turnId: Int, val text: String, val language: String?, val sttMs: Double?, val source: String?)
/** v0.3.0：识别过程中的临时字幕（服务端 transcript_partial）；最终仍以 transcript 为准 */
data class TranscriptPartial(val turnId: Int, val text: String, val partial: Boolean = true)
data class NoSpeech(val turnId: Int, val text: String?, val sttMs: Double?)
data class ReplyText(val turnId: Int, val seq: Int, val text: String)

/**
 * `audio` JSON 头。binary 模式下紧接着的下一个二进制帧就是音频（服务端 send_lock 保证相邻）；
 * base64 模式下 data 字段携带音频。同一 seq 可以有多块（mp3 流式），服务端不发“本句结束”标记。
 */
data class AudioHeader(
    val turnId: Int,
    val seq: Int,
    val format: AudioPayloadFormat,
    val sampleRate: Int,
    val bytes: Int,
    val data: String? = null,
)

/** metrics 事件 / response_end.metrics（session.py _turn 的 m 字典） */
data class TurnMetrics(
    val turnId: Int? = null,
    val sttMs: Double? = null,
    val sttDevice: String? = null,
    val audioS: Double? = null,
    val llmSource: String? = null,
    val llmModel: String? = null,
    val llmError: String? = null,
    val llmFirstTokenMs: Double? = null,
    val llmMs: Double? = null,
    val ttsMs: Double? = null,
    val audioBytes: Int? = null,
    val firstAudioMs: Double? = null,
    val sentences: Int? = null,
    val memoryMs: Double? = null,
    val memoryUsed: Boolean? = null,
    val totalMs: Double? = null,
)

data class ResponseEnd(
    val turnId: Int,
    val transcript: String?,
    val reply: String?,
    val interrupted: Boolean?,
    val error: String?,
    val metrics: TurnMetrics?,
)

/** v2：reason = vad | playback | client_interrupt | text；active = 是否真的取消了进行中的生成 */
data class BargeIn(val turnId: Int?, val message: String?, val reason: String? = null, val active: Boolean? = null)

data class ServerError(
    val code: String,
    val message: String?,
    val fix: String? = null,
    val fatal: Boolean? = null,
    val turnId: Int? = null,
) {
    val isFatal: Boolean get() = fatal ?: false
}

/** reason: stop / client_closed / idle_timeout / max_duration / start_timeout / bad_request / too_many_sessions / error */
data class SessionEnd(val reason: String, val sessionId: String?, val turns: Int?, val durationS: Double?)

/** 原样回传 ping.t；客户端发的是毫秒整数。 */
data class Pong(val t: Double?)

sealed class ServerEvent(val typeName: String) {
    data class ReadyEv(val ready: Ready) : ServerEvent("ready")
    data class VadStartEv(val v: VadStart) : ServerEvent("vad_start")
    data class VadEndEv(val v: VadEnd) : ServerEvent("vad_end")
    data class TranscriptEv(val t: Transcript) : ServerEvent("transcript")
    data class TranscriptPartialEv(val t: TranscriptPartial) : ServerEvent("transcript_partial")
    data class NoSpeechEv(val n: NoSpeech) : ServerEvent("no_speech")
    data class ResponseStart(val turnId: Int) : ServerEvent("response_start")
    data class ReplyTextEv(val r: ReplyText) : ServerEvent("reply_text")
    data class AudioHeaderEv(val h: AudioHeader) : ServerEvent("audio")
    data class ResponseEndEv(val r: ResponseEnd) : ServerEvent("response_end")
    data class MetricsEv(val m: TurnMetrics) : ServerEvent("metrics")
    data class BargeInEv(val b: BargeIn) : ServerEvent("barge_in")
    data class ErrorEv(val e: ServerError) : ServerEvent("error")
    data class SessionEndEv(val s: SessionEnd) : ServerEvent("session_end")
    data class PongEv(val p: Pong) : ServerEvent("pong")
    /** 向前兼容：未知事件忽略 */
    data class Unknown(val type: String) : ServerEvent(type)
}

class ProtocolDecodeException(msg: String) : Exception(msg)

object ServerEventDecoder {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun decode(text: String): ServerEvent {
        val o = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProtocolDecodeException("不是 JSON 对象：${e.message}")
        }
        val type = o.str("type") ?: throw ProtocolDecodeException("缺少 type 字段")
        return when (type) {
            "ready" -> ServerEvent.ReadyEv(decodeReady(o))
            "vad_start" -> ServerEvent.VadStartEv(VadStart(o.reqInt("turn_id"), o.dbl("t_ms")))
            "vad_end" -> ServerEvent.VadEndEv(
                VadEnd(o.reqInt("turn_id"), o.dbl("speech_ms"), o.dbl("audio_ms"), o.bool("forced"), o.dbl("t_ms"))
            )
            "transcript" -> ServerEvent.TranscriptEv(
                Transcript(o.reqInt("turn_id"), o.str("text") ?: "", o.str("language"), o.dbl("stt_ms"), o.str("source"))
            )
            "transcript_partial" -> ServerEvent.TranscriptPartialEv(
                TranscriptPartial(o.reqInt("turn_id"), o.str("text") ?: "", o.bool("partial") ?: true)
            )
            "no_speech" -> ServerEvent.NoSpeechEv(NoSpeech(o.reqInt("turn_id"), o.str("text"), o.dbl("stt_ms")))
            "response_start" -> ServerEvent.ResponseStart(o.int("turn_id") ?: 0)
            "reply_text" -> ServerEvent.ReplyTextEv(ReplyText(o.reqInt("turn_id"), o.int("seq") ?: 0, o.str("text") ?: ""))
            "audio" -> ServerEvent.AudioHeaderEv(
                AudioHeader(
                    turnId = o.reqInt("turn_id"),
                    seq = o.int("seq") ?: 0,
                    format = AudioPayloadFormat.of(o.str("format") ?: "mp3"),
                    sampleRate = o.int("sample_rate") ?: 24_000,
                    bytes = o.int("bytes") ?: -1,
                    data = o.str("data"),
                )
            )
            "response_end" -> ServerEvent.ResponseEndEv(
                ResponseEnd(
                    turnId = o.reqInt("turn_id"),
                    transcript = o.str("transcript"),
                    reply = o.str("reply"),
                    interrupted = o.bool("interrupted"),
                    error = o.str("error"),
                    metrics = o.obj("metrics")?.let { decodeMetrics(it) },
                )
            )
            "metrics" -> ServerEvent.MetricsEv(decodeMetrics(o))
            "barge_in" -> ServerEvent.BargeInEv(BargeIn(o.int("turn_id"), o.str("message"), o.str("reason"), o.bool("active")))
            "error" -> ServerEvent.ErrorEv(
                ServerError(o.str("code") ?: "unknown", o.str("message"), o.str("fix"), o.bool("fatal"), o.int("turn_id"))
            )
            "session_end" -> ServerEvent.SessionEndEv(
                SessionEnd(o.str("reason") ?: "unknown", o.str("session_id"), o.int("turns"), o.dbl("duration_s"))
            )
            "pong" -> ServerEvent.PongEv(Pong(o.dbl("t")))
            else -> ServerEvent.Unknown(type)
        }
    }

    private fun decodeReady(o: JsonObject): Ready {
        val out = o.obj("audio_out") ?: throw ProtocolDecodeException("ready 缺少 audio_out")
        val cfg = o.obj("config")
        val limits = o.obj("limits")
        return Ready(
            sessionId = o.str("session_id") ?: throw ProtocolDecodeException("ready 缺少 session_id"),
            userId = o.str("user_id"),
            bargeIn = cfg?.bool("barge_in"),
            audioInSampleRate = o.obj("audio_in")?.int("sample_rate"),
            audioOut = AudioOut(
                format = AudioPayloadFormat.of(out.str("format") ?: "mp3"),
                contentType = out.str("content_type"),
                sampleRate = out.int("sample_rate") ?: 24_000,
                transport = out.str("transport"),
            ),
            vadEngine = o.str("vad_engine"),
            idleTimeoutS = limits?.dbl("idle_timeout_s"),
            maxSessionS = limits?.dbl("max_session_s"),
            conversation = o.obj("conversation")?.let { decodeConversation(it) },
        )
    }

    fun decodeConversation(c: JsonObject): ConversationInfo? {
        val id = c.str("id") ?: return null
        return ConversationInfo(id, c.bool("resumed") ?: false, c.int("turns") ?: 0, c.bool("persistent"),
            decodeHistory(c.arr("history")))
    }

    /** 宽松解析历史消息数组；坏元素跳过 */
    fun decodeHistory(a: JsonArray?): List<HistoryMessage> {
        if (a == null) return emptyList()
        return a.mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val role = m.str("role") ?: return@mapNotNull null
            val content = m.str("content") ?: m.str("text") ?: return@mapNotNull null
            HistoryMessage(role, content, m.str("timestamp"))
        }
    }

    fun decodeMetrics(o: JsonObject) = TurnMetrics(
        turnId = o.int("turn_id"),
        sttMs = o.dbl("stt_ms"),
        sttDevice = o.str("stt_device"),
        audioS = o.dbl("audio_s"),
        llmSource = o.str("llm_source"),
        llmModel = o.str("llm_model"),
        llmError = o.str("llm_error"),
        llmFirstTokenMs = o.dbl("llm_first_token_ms"),
        llmMs = o.dbl("llm_ms"),
        ttsMs = o.dbl("tts_ms"),
        audioBytes = o.int("audio_bytes"),
        firstAudioMs = o.dbl("first_audio_ms"),
        sentences = o.int("sentences"),
        memoryMs = o.dbl("memory_ms"),
        memoryUsed = o.bool("memory_used"),
        totalMs = o.dbl("total_ms"),
    )
}

// ---- 宽松取值小工具（null / 缺失 / 类型不对 一律返回 null）

internal fun JsonObject.prim(k: String): JsonPrimitive? {
    val e = this[k] ?: return null
    if (e is JsonNull || e !is JsonPrimitive) return null
    return e
}

internal fun JsonObject.str(k: String): String? {
    val p = prim(k) ?: return null
    return if (p.isString) p.content else p.content
}

internal fun JsonObject.dbl(k: String): Double? = prim(k)?.doubleOrNull
internal fun JsonObject.int(k: String): Int? = prim(k)?.doubleOrNull?.toInt()
internal fun JsonObject.bool(k: String): Boolean? = prim(k)?.booleanOrNull
internal fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
internal fun JsonObject.reqInt(k: String): Int = int(k) ?: throw ProtocolDecodeException("缺少 $k")
