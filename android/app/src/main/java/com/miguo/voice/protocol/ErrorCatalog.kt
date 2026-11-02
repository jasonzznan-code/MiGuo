package com.miguo.voice.protocol

/** 把服务端错误码 / 握手 HTTP 状态 / WS 关闭码 翻译成可读的中文提示（措辞沿用 iOS ErrorCatalog.swift）。 */
data class ReadableError(
    val code: String,
    val title: String,
    override val message: String,
    val fix: String? = null,
    /** 是否值得重试（Phase 1 不做自动重连，仅用于提示文案） */
    val retryable: Boolean = false,
    /** 服务端 errors{} 文案里的占位符取值：server_message / code / status / reason / description */
    val vars: Map<String, String> = emptyMap(),
    /** message 来自运行时（服务端 error.message、网络异常原文、关闭原因），而不是内置固定文案 */
    val runtimeMessage: Boolean = false,
    /** fix 来自服务端 error 事件本身（优先级最高） */
    val serverFix: Boolean = false,
) : Exception()

private fun String?.nonEmpty(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** 把超时/异常原文收成短中文，避免把 stack trace 甩到界面上 */
fun humanizeRuntime(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return "暂时连不上米果，请检查网络后再试。"
    val low = s.lowercase()
    if ("timeout" in low || "timed out" in low || "SocketTimeout" in s) return "暂时连不上米果，请检查网络后再试。"
    if ("unable to resolve host" in low || "unknownhost" in low) return "暂时连不上米果，请检查网络后再试。"
    if ("connection refused" in low || "failed to connect" in low) return "暂时连不上米果，请检查网络后再试。"
    if ("ssl" in low || "certificate" in low) return "安全连接失败，请稍后再试。"
    if ("\tat " in s || s.lines().size > 3 || "Exception" in s && s.length > 160) {
        return "出了点问题，请稍后再试。"
    }
    val looksEnglish = s.any { it in 'A'..'Z' || it in 'a'..'z' } && !s.any { it.code > 127 }
    if (looksEnglish) return "出了点问题，请稍后再试。"
    return if (s.length > 180) s.take(160) + "…" else s
}
object ErrorCatalog {
    /** 服务端 error{code,message,fix,fatal} */
    fun fromServer(e: ServerError): ReadableError {
        val msg = e.message ?: ""
        return fromServerBuiltin(e, msg).copy(
            vars = mapOf("server_message" to msg, "code" to e.code),
            runtimeMessage = msg.isNotBlank(),
            serverFix = e.fix.nonEmpty() != null,
        )
    }

    private fun fromServerBuiltin(e: ServerError, msg: String): ReadableError {
        return when (e.code) {
            "too_many_sessions" -> ReadableError(
                e.code, "语音会话已满",
                "这个 user_id 已经有 2 个进行中的语音会话（例如 PC 端和另一台设备）。$msg",
                e.fix.nonEmpty() ?: "先结束其他设备上的对话再试；刚断线的旧连接最多约 40 秒后才会被服务器释放。也可以在服务器 .env 调大 MEMORY_VOICE_MAX_SESSIONS_PER_USER。",
                retryable = true,
            )
            "unauthorized" -> ReadableError(
                e.code, "登录已失效", msg.ifEmpty { "access token 无效或已过期。" },
                e.fix.nonEmpty() ?: "App 会自动用 refresh token 续期后重试；仍不行请到设置里退出登录再登录。", retryable = true,
            )
            "bad_user" -> ReadableError(
                e.code, "user_id 不合法",
                msg.ifEmpty { "user_id 只能包含字母、数字、_ - . @，长度 1–64。" },
                e.fix.nonEmpty() ?: "到设置里修改 user_id。",
            )
            "bad_request" -> ReadableError(
                e.code, "请求参数被服务器拒绝", msg,
                e.fix.nonEmpty() ?: "检查设置里的音色/语速/输出格式；或服务器版本与 App 不匹配。",
            )
            "start_timeout" -> ReadableError(
                e.code, "开始超时", msg.ifEmpty { "连接后 15 秒内没有发出 start。" },
                e.fix.nonEmpty() ?: "网络可能过慢，重试一次。", retryable = true,
            )
            "idle_timeout" -> ReadableError(
                e.code, "长时间无数据，服务器已结束会话", msg,
                e.fix.nonEmpty() ?: "点一下重新开始。", retryable = true,
            )
            "session_timeout" -> ReadableError(
                e.code, "单次会话达到最长时长（30 分钟）", msg,
                e.fix.nonEmpty() ?: "点一下继续（同一个记忆会话会延续）。", retryable = true,
            )
            "stt_unavailable" -> ReadableError(
                e.code, "语音识别（faster-whisper）不可用", msg,
                e.fix.nonEmpty() ?: "在服务器上运行 setup_voice.ps1 / verify_voice.ps1 检查 STT 模型。",
            )
            "tts_unavailable" -> ReadableError(
                e.code, "语音合成（edge-tts）不可用", msg,
                e.fix.nonEmpty() ?: "edge-tts 需要服务器能访问外网；文字回复仍会显示。", retryable = true,
            )
            "llm_unavailable" -> ReadableError(
                e.code, "大模型（Ollama）不可用", msg,
                e.fix.nonEmpty() ?: "确认服务器上 Ollama 已启动且有 qwen3:8b。", retryable = true,
            )
            "turn_failed" -> ReadableError(e.code, "这一轮处理失败", msg, e.fix.nonEmpty(), retryable = true)
            "internal" -> ReadableError(e.code, "服务器内部错误", msg, e.fix.nonEmpty() ?: "查看服务器 logs\\server.err.log。")
            else -> ReadableError(e.code, "服务器错误（${e.code}）", msg, e.fix.nonEmpty())
        }
    }

    /** WS 握手被拒（升级前 HTTP 状态）。uvicorn 对 accept 前 close(4401) 回 HTTP 403。 */
    fun handshake(status: Int): ReadableError = handshakeBuiltin(status).copy(vars = mapOf("status" to "$status", "code" to "$status"))

    private fun handshakeBuiltin(status: Int): ReadableError = when (status) {
        403, 401 -> ReadableError(
            "auth_failed", "鉴权失败（HTTP $status）",
            "服务器拒绝了 WebSocket 握手：API Key 错误或未填写（服务器启用了 API Key 校验）。",
            "到设置里填写与服务端配置的 API Key 完全一致的 API Key。",
        )
        404 -> ReadableError(
            "not_found", "找不到语音接口（HTTP 404）",
            "服务器上没有 /api/v1/voice/session。",
            "确认地址指向 memory_core（默认端口 8765），并且服务端版本包含语音层。",
        )
        else -> ReadableError(
            "handshake", "握手失败（HTTP $status）", "WebSocket 升级被拒绝。",
            "检查反向代理是否透传 WebSocket（Upgrade/Connection 头）。", retryable = true,
        )
    }

    /** WS 关闭码（session.py：stop/client_closed→1000，其余→4000；router.py：user_id 非法→4400；4401 实际表现为 HTTP 403） */
    fun close(code: Int, reason: String?): ReadableError? = closeBuiltin(code, reason)?.let {
        if (it.code == "auth_failed") it
        else it.copy(vars = mapOf("code" to "$code", "reason" to (reason ?: "")), runtimeMessage = !reason.isNullOrBlank())
    }

    private fun closeBuiltin(code: Int, reason: String?): ReadableError? = when (code) {
        1000 -> null
        4400 -> ReadableError(
            "close_4400", "user_id 不合法（关闭码 4400）",
            "user_id 只能包含字母、数字、_ - . @，长度 1–64。", "到设置里修改 user_id。",
        )
        4401 -> handshake(403)
        4000 -> ReadableError(
            "closed_4000", "服务器结束了会话（关闭码 4000）",
            reason.nonEmpty() ?: "超时、错误或并发超限。", null, retryable = true,
        )
        else -> ReadableError("closed", "连接已关闭（$code）", reason ?: "", retryable = true)
    }

    /** 网络层错误分类（Android 上对应 java.net 异常）。loopback 地址在真机上必然连不上，给出专门提示。 */
    enum class NetKind { UNREACHABLE, TIMEOUT, UNKNOWN_HOST, CLEARTEXT_BLOCKED, OTHER }

    fun network(description: String, kind: NetKind, endpointIsLoopback: Boolean): ReadableError =
        networkBuiltin(description, kind, endpointIsLoopback).copy(
            vars = mapOf("description" to description, "code" to kind.name.lowercase()),
            runtimeMessage = !endpointIsLoopback && description.isNotBlank(),
        )

    private fun networkBuiltin(description: String, kind: NetKind, endpointIsLoopback: Boolean): ReadableError {
        if (endpointIsLoopback) {
            return ReadableError(
                "loopback", "地址是 127.0.0.1 / localhost",
                "在手机上 127.0.0.1 指向手机自己，不是电脑。",
                "把服务器地址改成电脑的局域网 IP（例如 http://<lan-host>:8765），并让服务端监听 0.0.0.0（见 README）。",
            )
        }
        return when (kind) {
            NetKind.UNREACHABLE, NetKind.TIMEOUT, NetKind.UNKNOWN_HOST -> ReadableError(
                "unreachable", "连不上服务器（${kind.name.lowercase()}）", description,
                "① 电脑上的 MiguoMemoryCore 服务默认只监听 127.0.0.1，需要改成 0.0.0.0 或局域网 IP；② Windows 防火墙放行 TCP 8765（规则「Miguo memory_core 8765」只对“专用”网络生效，电脑当前 Wi-Fi 必须是专用网络）；③ 手机和电脑在同一 Wi-Fi，或都连上 Tailscale。",
                retryable = true,
            )
            NetKind.CLEARTEXT_BLOCKED -> ReadableError(
                "cleartext", "明文 HTTP 被系统拦截", description,
                "App 的 network_security_config 已允许明文；如果仍出现，请确认安装的是本工程构建的 APK。",
            )
            NetKind.OTHER -> ReadableError("network", "网络错误", description, retryable = true)
        }
    }

    fun micDenied() = ReadableError(
        "mic_denied", "没有麦克风权限",
        "米果需要麦克风才能听你说话。", "到 系统设置 → 应用 → 米果 → 权限 → 麦克风 打开。",
    )
}
