package com.miguo.voice.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 服务端下发的客户端配置：GET /api/v1/client/config?platform=android&version=<versionName>
 * （memory_core 是唯一的“真相来源”，以后改参数/文案只改服务端）。
 *
 * 合并规则：内置默认值 ← 上次成功拉取的缓存 ← 本次拉取结果；任何字段缺失/类型不对/未知字段一律忽略，
 * 接口 404（老版本服务端）时完全使用内置默认值 + 缓存。
 */
data class ClientConfig(
    val configVersion: Int = 0,
    val minClientVersion: String? = null,
    val updateUrl: String? = null,
    val voice: VoiceDefaults = VoiceDefaults(),
    val playback: PlaybackTuning = PlaybackTuning(),
    /** 状态文案：key 见 [LABEL_KEYS] */
    val labels: Map<String, String> = DEFAULT_LABELS,
    /** 错误文案覆盖：code → (title, fix, message) */
    val errors: Map<String, ErrorText> = emptyMap(),
    /** 服务端公告（横幅），null = 无 */
    val notice: String? = null,
    /** 服务端 latest_version（按平台） */
    val latestVersion: String? = null,
    /** 服务端算好的 update_required（以本地比较为准，两者任一为 true 即提示） */
    val updateRequiredFlag: Boolean = false,
    /** 服务端给了 errors.<code>.android 的错误码（这些即使是手机端特有错误也完整采用服务端文案） */
    val platformErrors: Set<String> = emptySet(),
    /**
     * 服务端对模型提供方的不含密钥通告。缺省关闭。
     * 不改变 `/api/v1/chat/text`、登录或中继的请求地址；DeepSeek Key 不会出现在这里。
     */
    val llm: LlmProviderNotice = LlmProviderNotice.DISABLED,
    /** 来源："builtin" | "cache" | "server"，诊断用 */
    val source: String = "builtin",
    /** v2 服务端能力（旧服务器没有 features → 全部 null = 未知，客户端按 404 自动降级） */
    val features: Features = Features(),
) {
    data class Features(
        val auth: Boolean? = null,
        val textChat: Boolean? = null,
        val conversations: Boolean? = null,
        val resumeConversation: Boolean? = null,
    )

    data class VoiceDefaults(
        val outputFormat: String = "mp3",
        val sampleRate: Int = 16_000,
        val frameMs: Int = 20,
        val bargeIn: Boolean = false,
        val vad: VADParams = VADParams.STEADY,
        val voice: String? = null,
        val useMemory: Boolean = true,
        val resumeListenDelayMs: Int = 200,
    )

    data class PlaybackTuning(
        val watchdogIntervalMs: Long = 500,
        val stallTimeoutS: Double = 5.0,
        val statusLogIntervalS: Double = 2.0,
    )

    data class ErrorText(val title: String?, val fix: String?, val message: String?)

    fun label(key: String): String = labels[key] ?: DEFAULT_LABELS[key] ?: key

    /** 带占位符的文案，例如 "重连中（第 {n} 次）"、"有新版本 {latest}" */
    fun label(key: String, vars: Map<String, String>): String = fill(label(key), vars)

    /**
     * 用服务端 errors{code:{title,message,fix}} 覆盖本地 ErrorCatalog 的结果（规则与服务端 client_config.py 注释一致）：
     * - title：直接替换（代入占位符）；
     * - message：含占位符 → 代入（代入后为空则用内置文案）；不含占位符 → 只在没有运行时消息时使用；
     * - fix：服务端 error 事件自带 fix 时优先用事件里的，否则用这里的；null / 空 = 用内置文案。
     * 占位符：{server_message} {code} {status} {reason} {description}；没有取值的占位符代入空串。
     * 手机端特有的错误（麦克风权限、127.0.0.1、明文拦截）服务端文案是 iOS 措辞，只取标题，正文保留 Android 内置文案；
     * 服务端可以用 errors.<code>.android = {...} 单独给 Android 文案（此时完整生效）。
     */
    fun apply(e: ReadableError): ReadableError {
        val o = errors[e.code] ?: return e
        val androidSpecific = e.code in PLATFORM_SPECIFIC_ERRORS && !platformErrors.contains(e.code)
        val title = o.title?.takeIf { it.isNotBlank() }?.let { fill(it, e.vars) }?.takeIf { it.isNotBlank() } ?: e.title
        if (androidSpecific) return e.copy(title = title)
        val message = o.message?.takeIf { it.isNotBlank() }?.let { m ->
            if (PLACEHOLDER.containsMatchIn(m)) fill(m, e.vars).trim().ifEmpty { e.message }
            else if (e.runtimeMessage && e.message.isNotBlank()) e.message else m
        } ?: e.message
        val fix = if (e.serverFix) e.fix
            else o.fix?.takeIf { it.isNotBlank() }?.let { fill(it, e.vars) } ?: e.fix
        return e.copy(title = title, message = message, fix = fix)
    }

    /** 当前版本是否低于服务端要求的最低版本 */
    fun needsUpdate(currentVersion: String): Boolean {
        // 以本地比较为准（缓存里的 update_required 是按旧版本号算的，升级后不能再用）
        val min = minClientVersion ?: return updateRequiredFlag
        return compareVersions(currentVersion, min) < 0
    }

    /** 有更新的版本但不强制 */
    fun updateAvailable(currentVersion: String): Boolean {
        val latest = latestVersion ?: return false
        return compareVersions(currentVersion, latest) < 0
    }

    /** 把一份服务端 JSON 合并到当前配置之上（未给的字段保持不变） */
    fun mergedWith(o: JsonObject, platform: String = "android", source: String = "server"): ClientConfig {
        var c = this
        o.int("config_version")?.let { c = c.copy(configVersion = it) }
        platformValue(o, "min_client_version", platform)?.let { c = c.copy(minClientVersion = it) }
        if (o.containsKey("update_url")) c = c.copy(updateUrl = platformValue(o, "update_url", platform))
        platformValue(o, "latest_version", platform)?.let { c = c.copy(latestVersion = it) }
        o.bool("update_required")?.let { c = c.copy(updateRequiredFlag = it) }
        o.obj("voice")?.let { v -> c = c.copy(voice = mergeVoice(c.voice, v)) }
        // 原始 client_config.json 里的 voice_by_platform.<platform>（服务端 platform_view 已展开时不会出现）
        o.obj("voice_by_platform")?.obj(platform)?.let { v -> c = c.copy(voice = mergeVoice(c.voice, v)) }
        o.obj("features")?.let { f ->
            c = c.copy(features = Features(
                auth = f.bool("auth") ?: c.features.auth,
                textChat = f.bool("text_chat") ?: c.features.textChat,
                conversations = f.bool("conversations") ?: c.features.conversations,
                resumeConversation = f.bool("resume_conversation") ?: c.features.resumeConversation,
            ))
        }
        o.obj("playback")?.let { p ->
            var d = c.playback
            p.int("watchdog_interval_ms")?.takeIf { it in 100..10_000 }?.let { d = d.copy(watchdogIntervalMs = it.toLong()) }
            p.dbl("stall_timeout_s")?.takeIf { it in 0.5..120.0 }?.let { d = d.copy(stallTimeoutS = it) }
            p.dbl("status_log_interval_s")?.takeIf { it in 0.2..120.0 }?.let { d = d.copy(statusLogIntervalS = it) }
            c = c.copy(playback = d)
        }
        o.obj("labels")?.let { l ->
            val m = c.labels.toMutableMap()
            for ((k, _) in l) l.str(k)?.takeIf { it.isNotBlank() }?.let { m[k] = it }
            c = c.copy(labels = m)
        }
        o.obj("errors")?.let { es ->
            val m = c.errors.toMutableMap()
            val plat = c.platformErrors.toMutableSet()
            for ((k, v) in es) {
                val outer = v as? JsonObject ?: continue
                val eo = outer.obj(platform)?.also { plat += k } ?: outer
                m[k] = ErrorText(eo.str("title"), eo.str("fix"), eo.str("message"))
            }
            c = c.copy(errors = m, platformErrors = plat)
        }
        if (o.containsKey("notice")) {
            // notice：null | "文字" | {"text"/"message"/"title"/"body": "..."}
            val n = o.obj("notice")?.let { no -> no.str("text") ?: no.str("message") ?: no.str("title") ?: no.str("body") }
                ?: o.str("notice")
            c = c.copy(notice = n?.takeIf { it.isNotBlank() })
        }
        // llm 只接受不含密钥的通告。没有这个对象时保持默认关闭，聊天路径不变。
        // base_url / api_key 即使出现也不读取：地址和密钥只属于服务端环境变量。
        o.obj("llm")?.let { c = c.copy(llm = LlmProviderNotice.fromJson(it)) }
        return c.copy(source = source)
    }

    private fun mergeVoice(d0: VoiceDefaults, v: JsonObject): VoiceDefaults {
        var d = d0
        v.str("output_format")?.let { d = d.copy(outputFormat = it) }
        v.int("sample_rate")?.takeIf { it in StartMessage.ALLOWED_SAMPLE_RATES }?.let { d = d.copy(sampleRate = it) }
        v.int("frame_ms")?.takeIf { it in 10..100 }?.let { d = d.copy(frameMs = it) }
        v.bool("barge_in")?.let { d = d.copy(bargeIn = it) }
        v.obj("vad")?.let { vad ->
            d = d.copy(
                vad = VADParams(
                    threshold = vad.dbl("threshold") ?: d.vad.threshold,
                    minSpeechMs = vad.int("min_speech_ms") ?: d.vad.minSpeechMs,
                    minSilenceMs = vad.int("min_silence_ms") ?: d.vad.minSilenceMs,
                )
            )
        }
        if (v.containsKey("voice")) d = d.copy(voice = v.str("voice")?.takeIf { it.isNotBlank() })
        v.bool("use_memory")?.let { d = d.copy(useMemory = it) }
        v.int("resume_listen_delay_ms")?.takeIf { it in 0..5000 }?.let { d = d.copy(resumeListenDelayMs = it) }
        return d
    }

    companion object {
        const val PATH = "/api/v1/client/config"

        /** 内置状态文案（与 iOS ConversationState.title 一致）；服务端 labels 同名 key 覆盖 */
        val DEFAULT_LABELS: Map<String, String> = linkedMapOf(
            "idle" to "点一下开始",
            "starting" to "连接中…",
            "listening" to "正在听",
            "user_speaking" to "你在说话",
            "recognizing" to "识别中",
            "thinking" to "思考中",
            "synthesizing" to "合成中",
            "speaking" to "米果在说",
            "suspended" to "已暂停",
            "reconnecting" to "重连中（第 {n} 次）",
            "stopping" to "正在结束…",
            // 界面文案（与服务端 client_config.json labels 同名）
            "app_title" to "米果",
            "caption_empty" to "点下方按钮开始，像打电话一样直接说话。\n米果说完后会自动继续听。",
            "interrupted_suffix" to " …（被打断）",
            "failed_prefix" to "出错：",
            "fix_prefix" to "怎么办：",
            "retry_hint" to "点按钮重试",
            "retry" to "重试",
            "degraded_prefix" to "服务器降级：",
            "a11y_start" to "开始对话",
            "a11y_stop" to "结束对话",
            "update_required" to "App 版本过低（最低 {min}），请更新后再用",
            "update_available" to "有新版本 {latest}",
            "update_open" to "去更新",
        )

        /** 服务端文案是 iOS 措辞的手机端特有错误 */
        val PLATFORM_SPECIFIC_ERRORS = setOf("mic_denied", "loopback", "ats", "cleartext")
        private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")

        /** 代入 {name} 占位符；没有取值的代入空串 */
        fun fill(template: String, vars: Map<String, String>): String =
            PLACEHOLDER.replace(template) { m -> vars[m.groupValues[1]] ?: "" }
        val LABEL_KEYS: Set<String> get() = DEFAULT_LABELS.keys

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parseJson(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

        /** 字段可以是字符串，也可以是 {"android":"..","ios":".."} */
        private fun platformValue(o: JsonObject, key: String, platform: String): String? {
            o.obj(key)?.let { return it.str(platform)?.takeIf { s -> s.isNotBlank() } }
            return o.str(key)?.takeIf { it.isNotBlank() }
        }

        /** 比较 "0.1.0" / "0.1.0-phase1" 这类版本号（只比较数字段） */
        fun compareVersions(a: String, b: String): Int {
            fun parts(s: String) = s.substringBefore('-').split('.').map { it.trim().toIntOrNull() ?: 0 }
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
