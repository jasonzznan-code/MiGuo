package com.miguo.voice.conversation

import com.miguo.voice.protocol.AudioPayloadFormat
import com.miguo.voice.protocol.AuthSession
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.HeaderValues
import com.miguo.voice.protocol.ReadableError
import com.miguo.voice.protocol.ServerEndpoint
import com.miguo.voice.protocol.SessionIdFactory
import com.miguo.voice.protocol.StartMessage
import com.miguo.voice.protocol.UserIdValidator
import com.miguo.voice.protocol.VADParams
import com.miguo.voice.protocol.WireLocation

/** “说完判定”档位（对应 iOS VADPreset） */
enum class VADPreset(val params: VADParams, val label: String) {
    STEADY(VADParams.STEADY, "稳（静音 700 ms）"),
    FAST(VADParams.FAST, "快（静音 500 ms）"),
}


/**
 * edge-tts 韵律参数（与 memory_core voice/tts.py validate_prosody 一致）：
 * rate/volume = `^[+-]\d{1,3}%$`，pitch = `^[+-]\d{1,3}Hz$`（服务端只校验格式）。
 * 客户端 UX 夹紧：rate/volume ±50%，pitch ±50Hz；始终带显式符号。
 */
object TtsProsody {
    const val RATE_MIN = -50
    const val RATE_MAX = 50
    const val VOLUME_MIN = -50
    const val VOLUME_MAX = 50
    const val PITCH_MIN = -50
    const val PITCH_MAX = 50

    fun clampRate(v: Int): Int = v.coerceIn(RATE_MIN, RATE_MAX)
    fun clampVolume(v: Int): Int = v.coerceIn(VOLUME_MIN, VOLUME_MAX)
    fun clampPitch(v: Int): Int = v.coerceIn(PITCH_MIN, PITCH_MAX)

    /** 0 → "+0%"；10 → "+10%"；-20 → "-20%" */
    fun formatPercent(v: Int): String {
        val n = v.coerceIn(-50, 50)
        return if (n >= 0) "+$n%" else "$n%"
    }

    /** 0 → "+0Hz"；5 → "+5Hz"；-10 → "-10Hz" */
    fun formatPitchHz(v: Int): String {
        val n = v.coerceIn(PITCH_MIN, PITCH_MAX)
        return if (n >= 0) "+${n}Hz" else "${n}Hz"
    }

    /** 常用中文 Neural 音色预设（空 = 跟随服务器） */
    val VOICE_PRESETS: List<Pair<String, String>> = listOf(
        "" to "跟随服务器",
        "zh-CN-XiaoxiaoNeural" to "晓晓",
        "zh-CN-XiaoyiNeural" to "晓伊",
        "zh-CN-YunxiNeural" to "云希",
        "zh-CN-YunyangNeural" to "云扬",
        "zh-CN-XiaochenNeural" to "晓辰",
        "zh-CN-XiaohanNeural" to "晓涵",
    )
}

/**
 * 本地设置（SharedPreferences；API Key 单独存 Keystore 加密）。
 * 语音参数字段为 null = “跟随服务器”（/api/v1/client/config 的 voice.*），只有用户在设置里明确改过才覆盖。
 */
data class AppSettings(
    /** 对话/语音基址（memory_core，默认 3010） */
    val address: String = ServerEndpoint.DEFAULT_ADDRESS,
    /** 登录基址（nano relay，默认 8787）；与 address 分离 */
    val authAddress: String = ServerEndpoint.DEFAULT_AUTH_ADDRESS,
    val userId: String = "jason",
    /** null = 跟随服务器；"mp3" | "pcm_s16le" = 用户明确选择 */
    val outputFormat: String? = null,
    /** null = 跟随服务器 */
    val vadPreset: VADPreset? = null,
    /** null = 跟随服务器；20 | 40 */
    val frameMs: Int? = null,
    /** 空 = 跟随服务器 */
    val voice: String = "",
    /** edge-tts 语速相对偏移（百分比整数，夹紧 ±50）；0 = "+0%" */
    val ttsRatePercent: Int = 0,
    /** edge-tts 音高相对偏移（Hz 整数，夹紧 ±50）；0 = "+0Hz" */
    val ttsPitchHz: Int = 0,
    /** edge-tts 音量相对偏移（百分比整数，夹紧 ±50）；0 = "+0%" */
    val ttsVolumePercent: Int = 0,
    /** null = 跟随服务器 */
    val useMemory: Boolean? = null,
    val showMetrics: Boolean = true,
    /** v0.2.0：null = 跟随服务器 voice.barge_in（android 默认 true）；true/false = 用户明确选择 */
    val bargeIn: Boolean? = null,
    /** v0.2.0：不在语音会话里发文字时，回复也朗读出来（/chat/text speak=true） */
    val speakTextReplies: Boolean = false,
    /** v0.2.0：用户选择“不登录，用 user_id” */
    val authSkipped: Boolean = false,
)

/** 一次会话需要的全部参数 */
data class SessionParams(
    val endpoint: ServerEndpoint,
    val userId: String,
    val apiKey: String?,
    val start: StartMessage,
    val frameMs: Int,
    val config: ClientConfig,
    /** v0.2.0：access token（登录后）；放 Authorization 头 */
    val bearer: String? = null,
)

/**
 * 校验设置并生成会话参数。
 * v0.2.0：sessionId = 稳定的对话 ID（每个账号一个，重启 App 续接）；null + resume = 让服务器续接最近一次对话。
 * 登录后 [auth] 非空：user_id 取自账号，token 放 Authorization 头 + start.token。
 */
@Throws(ReadableError::class)
fun AppSettings.makeParams(
    apiKey: String?, config: ClientConfig, sessionId: String? = SessionIdFactory.make(),
    auth: AuthSession? = null, resume: Boolean = sessionId == null,
    location: WireLocation? = null,
): SessionParams {
    val ep = try {
        ServerEndpoint.parse(address)
    } catch (e: ServerEndpoint.ParseError) {
        throw ReadableError("bad_address", "服务器地址不对", e.message ?: "", "格式例如 https://ai.njzyb.com:3010（请使用 HTTPS 公网地址）")
    }
    val uid = auth?.userId ?: userId.trim()
    if (auth == null) validateIdentity(uid, apiKey) else validateApiKey(apiKey)
    val v = config.voice
    val frame = (frameMs ?: v.frameMs).let { if (it in 10..100) it else 20 }
    val start = StartMessage(
        sessionId = sessionId?.takeIf { SessionIdFactory.isValid(it) },
        voice = voice.trim().ifEmpty { null } ?: v.voice,
        rate = TtsProsody.formatPercent(ttsRatePercent),
        volume = TtsProsody.formatPercent(ttsVolumePercent),
        pitch = TtsProsody.formatPitchHz(ttsPitchHz),
        useMemory = useMemory ?: v.useMemory,
        sampleRate = v.sampleRate,
        outputFormat = AudioPayloadFormat.of(outputFormat ?: v.outputFormat),
        audioTransport = "binary",
        bargeIn = bargeIn ?: v.bargeIn,     // v0.2.0：全双工打断，默认跟随服务器
        vad = vadPreset?.params ?: v.vad,
        resume = resume,
        token = auth?.accessToken,
        location = location?.takeIf { it.isValid },
    )
    return SessionParams(ep, uid, HeaderValues.cleanApiKey(apiKey), start, frame, config, auth?.accessToken)
}

/**
 * fix1：校验 user_id / API Key 能否放进 HTTP 头。OkHttp 的 header() 遇到中文/全角/表情会直接抛
 * IllegalArgumentException("Unexpected char ...")，以前在没 try 的协程里会让 App 闪退。
 */
@Throws(ReadableError::class)
fun validateIdentity(userId: String, apiKey: String?) {
    val uid = userId.trim()
    if (!UserIdValidator.isValid(uid)) {
        throw ReadableError("invalid_user_id", "user_id 不合法", "只能包含字母、数字、_ - . @，长度 1–64。", "到设置里修改 user_id。")
    }
    if (!HeaderValues.isSafe(uid)) {
        throw ReadableError("invalid_user_id", "user_id 不能含中文或全角字符",
            "user_id 要放进 HTTP 请求头 X-User-Id，只能用英文字母、数字和 _ - . @（发现字符「${HeaderValues.firstBadChar(uid)}」）。",
            "到设置里把 user_id 改成英文/数字，例如 jason。")
    }
    validateApiKey(apiKey)
}

@Throws(ReadableError::class)
fun validateApiKey(apiKey: String?) {
    val k = HeaderValues.cleanApiKey(apiKey) ?: return
    if (!HeaderValues.isSafe(k)) {
        throw ReadableError("invalid_api_key", "API Key 含有非法字符",
            "API Key 里有中文、全角或空格等字符（「${HeaderValues.firstBadChar(k)}」），无法放进 HTTP 请求头。",
            "到设置里重新粘贴 memory_core 服务端配置的 API Key（只含英文字母/数字/符号）。")
    }
}
