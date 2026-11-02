package com.miguo.voice.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

// 客户端 → 服务端 的 JSON 控制消息。字段名与 memory_core voice/session.py::_configure / _loop 完全一致
// （对应 iOS MiguoProtocol/ClientMessages.swift）。只依赖现有协议，不发送任何提议字段。

/**
 * v0.2.0：用户当前位置（设备定位，WGS-84），放在 start.location / {type:"location"} / 文字对话 location 里。
 * 服务端（memory_core v2 voice/location.py）逆地理后写进系统提示词，修“你在北京朝阳区”。
 * 字段：lat, lon, accuracy_m, provider, ts（毫秒）。
 */
data class WireLocation(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val provider: String = "unknown",
    val tsMs: Long = 0,
) {
    val isValid: Boolean
        get() = !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    /** 坐标保留 6 位小数（约 0.1 m）；provider 只留 [A-Za-z0-9_-.]，最长 32 */
    fun fields(): Map<String, Any?> = linkedMapOf(
        "lat" to round6(lat), "lon" to round6(lon),
        "accuracy_m" to accuracyM?.takeIf { !it.isNaN() && it >= 0f }?.let { Math.round(it * 10f) / 10.0 },
        "provider" to cleanProvider(provider),
        "ts" to tsMs.takeIf { it > 0 },
    )

    fun toJson(): JsonObject = buildJsonObject { putFields(this@WireLocation) }

    companion object {
        fun round6(v: Double): Double = Math.round(v * 1_000_000.0) / 1_000_000.0
        fun cleanProvider(p: String): String =
            p.filter { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '_' || it == '-' || it == '.' }
                .take(32).ifEmpty { "unknown" }
    }
}

internal fun kotlinx.serialization.json.JsonObjectBuilder.putFields(l: WireLocation) {
    for ((k, v) in l.fields()) when (v) {
        null -> {}
        is Double -> put(k, v)
        is Long -> put(k, v)
        is String -> put(k, v)
    }
}

/** 下行音频格式（session.py OUT_FORMATS：mp3 | pcm_s16le，均为 24 kHz 单声道）。未知值保留原字符串。 */
sealed class AudioPayloadFormat(val rawValue: String) {
    object Mp3 : AudioPayloadFormat("mp3")
    object PcmS16LE : AudioPayloadFormat("pcm_s16le")
    class Other(raw: String) : AudioPayloadFormat(raw)

    override fun toString() = rawValue
    override fun equals(other: Any?) = other is AudioPayloadFormat && other.rawValue == rawValue
    override fun hashCode() = rawValue.hashCode()

    companion object {
        fun of(raw: String): AudioPayloadFormat = when (raw) {
            "mp3" -> Mp3
            "pcm_s16le" -> PcmS16LE
            else -> Other(raw)
        }
    }
}

/** start.vad（session.py：threshold / min_speech_ms / min_silence_ms） */
data class VADParams(val threshold: Double = 0.5, val minSpeechMs: Int = 250, val minSilenceMs: Int = 700) {
    fun toJson(): JsonObject = buildJsonObject {
        put("threshold", threshold)
        put("min_speech_ms", minSpeechMs)
        put("min_silence_ms", minSilenceMs)
    }

    companion object {
        /** 设置页“稳/快”两档：700 默认；500 为 A/B 候选 */
        val STEADY = VADParams(minSilenceMs = 700)
        val FAST = VADParams(minSilenceMs = 500)
    }
}

/** 第一条消息：start。服务端要求连接后 15 s 内发出（session_start_timeout_s）。 */
data class StartMessage(
    /** = v2 conversation_id（稳定对话 ID）；null = 不发，由 [resume] 决定续接最近对话还是服务器新建 */
    val sessionId: String?,
    /** null = 不发送，服务端用默认音色 */
    val voice: String? = null,
    val rate: String = "+0%",
    val volume: String = "+0%",
    val pitch: String = "+0Hz",
    val language: String = "zh",
    val useMemory: Boolean = true,
    /** 上行 PCM 采样率；推荐 16000（=服务端 VAD/STT 采样率，免重采样） */
    val sampleRate: Int = 16_000,
    val outputFormat: AudioPayloadFormat = AudioPayloadFormat.Mp3,
    /** "binary"（音频头 JSON + 紧随其后的二进制帧）| "base64" */
    val audioTransport: String = "binary",
    /** v0.2.0：全双工打断（默认跟随服务器 /client/config voice.barge_in；android 默认 true） */
    val bargeIn: Boolean = false,
    val vad: VADParams = VADParams.STEADY,
    /** v2：没给 sessionId 时 true = 续接最近一次对话（旧服务器忽略） */
    val resume: Boolean = false,
    /** v2：access token（同时也放在 Authorization 头；旧服务器忽略）。toString 不打印 */
    val token: String? = null,
    val client: String = "android",
    /** v0.2.0：用户当前位置；null = 不发（服务端提示词会写“不知道用户位置，别猜”） */
    val location: WireLocation? = null,
) {
    override fun toString(): String =
        "StartMessage(sessionId=$sessionId, voice=$voice, useMemory=$useMemory, sampleRate=$sampleRate, " +
            "outputFormat=$outputFormat, bargeIn=$bargeIn, vad=$vad, resume=$resume, token=${if (token == null) "null" else "***"}, location=${if (location == null) "null" else "set"})"

    fun toJson(): JsonObject = buildJsonObject {
        put("type", "start")
        sessionId?.let { put("session_id", it); put("conversation_id", it) }
        if (sessionId == null && resume) put("resume", true)
        token?.let { put("token", it) }
        put("client", client)
        voice?.let { put("voice", it) }
        put("rate", rate)
        put("volume", volume)
        put("pitch", pitch)
        put("language", language)
        put("use_memory", useMemory)
        put("sample_rate", sampleRate)
        put("output_format", outputFormat.rawValue)
        put("audio_transport", audioTransport)
        put("barge_in", bargeIn)
        location?.takeIf { it.isValid }?.let { l -> putJsonObject("location") { putFields(l) } }
        putJsonObject("vad") {
            put("threshold", vad.threshold)
            put("min_speech_ms", vad.minSpeechMs)
            put("min_silence_ms", vad.minSilenceMs)
        }
    }

    fun encode(): String = toJson().toString()

    companion object {
        val ALLOWED_SAMPLE_RATES = setOf(8000, 16000, 22050, 24000, 32000, 44100, 48000)
    }
}

/** 会话中的控制消息：stop / interrupt / text / ping（session.py _loop） */
sealed class ClientControl {
    data class Stop(val waitForReply: Boolean) : ClientControl()
    object Interrupt : ClientControl()
    data class Text(val text: String) : ClientControl()
    data class Ping(val t: Long) : ClientControl()
    /** v0.2.0：会话中更新位置 → 服务端回 location_ack */
    data class Location(val location: WireLocation) : ClientControl()

    fun toJson(): JsonObject = buildJsonObject {
        when (val c = this@ClientControl) {
            is Stop -> { put("type", "stop"); put("wait_for_reply", c.waitForReply) }
            Interrupt -> put("type", "interrupt")
            is Text -> { put("type", "text"); put("text", c.text) }
            is Ping -> { put("type", "ping"); put("t", c.t) }
            is Location -> { put("type", "location"); putFields(c.location) }
        }
    }

    fun encode(): String = toJson().toString()
}

/** session_id：服务端正则 ^[\w\-.:]{1,64}$。格式 android-YYYYMMDD-HHmmss-xxxx。 */
object SessionIdFactory {
    // fix1（确认的崩溃根因）：原来是 Regex("(?U)^[\\w\\-.:]{1,64}$")。Android 的 ICU 正则不支持 (?U)
    // (UNICODE_CHARACTER_CLASS)，类初始化时抛 PatternSyntaxException → ExceptionInInitializerError，点开始必崩；
    // 桌面 JDK 支持 (?U)，所以 JVM 单元测试没发现。现在完全不用正则，按字符判断（语义同 Python 的 \w）。

    fun make(now: Date = Date(), suffix: String? = null): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val sfx = suffix ?: UUID.randomUUID().toString().replace("-", "").take(4).lowercase()
        return "android-$ts-$sfx"
    }

    fun isValid(s: String) = IdChars.matches(s, ".:")
}

/** user_id：服务端 router.py USER_RE = ^[\w\-.@]{1,64}$ */
object UserIdValidator {
    // fix1：同上，去掉 (?U) 正则（Android ICU 不支持），改为按字符判断，语义与服务端 Python ^[\w\-.@]{1,64}$ 一致
    fun isValid(s: String) = IdChars.matches(s, ".@")

    /** 能否放进 HTTP 头（OkHttp 只接受 ASCII 可见字符；中文 user_id 服务端合法，但头里带不过去） */
    fun isHeaderSafe(s: String) = HeaderValues.isSafe(s)
}

/**
 * Python 3 str 正则 \w 的等价判断（Unicode 字母/数字 + 下划线），再加上额外允许的标点和 "-"，长度 1–64。
 * 不用正则：Android 的 ICU 正则和桌面 JDK 正则支持的语法不同，按字符判断两边结果一定一致。
 */
internal object IdChars {
    fun isWordChar(cp: Int): Boolean {
        if (cp == '_'.code) return true
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
    }

    fun matches(s: String, extra: String): Boolean {
        val n = s.codePointCount(0, s.length)
        if (n < 1 || n > 64) return false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (!(isWordChar(cp) || cp == '-'.code || (cp < 0x80 && extra.indexOf(cp.toChar()) >= 0))) return false
        }
        return true
    }
}

/** HTTP 头取值校验/清洗（fix1：OkHttp 遇到非 ASCII 会抛 IllegalArgumentException "Unexpected char"） */
object HeaderValues {
    /** 只允许可见 ASCII（0x21–0x7E）；空串不算安全 */
    fun isSafe(s: String): Boolean = s.isNotEmpty() && s.all { it.code in 0x21..0x7E }

    /** 第一个不能放进 HTTP 头的字符（用于提示），没有则 null */
    fun firstBadChar(s: String): Char? = s.firstOrNull { it.code !in 0x21..0x7E }

    /**
     * API Key 清洗：去首尾空白、零宽字符、BOM，全角 ASCII（中文输入法常见）转半角。
     * 返回 null = 空；清洗后仍含非 ASCII → 由调用方报错（不能放进 X-API-Key 头）。
     */
    fun cleanApiKey(raw: String?): String? {
        if (raw == null) return null
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when (cp) {
                in 0xFF01..0xFF5E -> sb.append((cp - 0xFEE0).toChar())
                0x200B, 0x200C, 0x200D, 0xFEFF -> {}
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }
}
