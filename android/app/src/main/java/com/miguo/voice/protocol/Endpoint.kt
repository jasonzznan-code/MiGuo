package com.miguo.voice.protocol

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * 把用户在设置里填写的服务器地址规范化成 HTTP 基址和 WS 地址（对应 iOS MiguoProtocol/Endpoint.swift）。
 * 可填：`https://ai.njzyb.com:3010`（外网 HTTPS 默认）、`http://<lan-host>:8765`（局域网，仅 internal）、
 * `ws://host:8765`、`wss://host/prefix`（反代子路径会保留）。https → wss，http → ws。
 */
data class ServerEndpoint(val httpBase: String, val wsBase: String) {

    sealed class ParseError(message: String) : Exception(message) {
        object Empty : ParseError("服务器地址为空")
        class Invalid(raw: String) : ParseError("服务器地址无法解析：$raw")
        class UnsupportedScheme(s: String) : ParseError("不支持的协议 $s（只支持 http/https/ws/wss）")
    }

    val sessionUrl: String get() = wsBase + SESSION_PATH
    val healthUrl: String get() = httpBase + HEALTH_PATH

    // Auth health: nano relay uses /healthz; memory_core uses /api/v1/health.
    fun authHealthUrl(): String =
        if (ServerEndpoint.looksLikeNanoRelay(this)) httpBase + ServerEndpoint.NANO_HEALTH_PATH
        else httpBase + HEALTH_PATH


    fun voiceHealthUrl(loadStt: Boolean = false, probeTts: Boolean = false): String {
        val q = buildList {
            if (loadStt) add("load_stt=true")
            if (probeTts) add("probe_tts=true")
        }
        return httpBase + VOICE_HEALTH_PATH + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    val host: String get() = runCatching { URI(httpBase).host ?: "" }.getOrDefault("")

    val isLoopback: Boolean get() = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"

    companion object {
        const val SESSION_PATH = "/api/v1/voice/session"
        const val VOICE_HEALTH_PATH = "/api/v1/voice/health"
        const val HEALTH_PATH = "/api/v1/health"
        /** 对话/语音（memory_core）默认基址 */
        const val DEFAULT_ADDRESS = "https://ai.njzyb.com:3010"
        /** 登录（nano relay）默认基址；可由 BuildConfig 覆盖 */
        val DEFAULT_AUTH_ADDRESS: String get() = FlavorEndpoints.DEFAULT_AUTH_ADDRESS
        const val NANO_HEALTH_PATH = "/healthz"
        const val NANO_CONFIG_PATH = "/v1/config"
        const val NANO_AUTH_CODE_PATH = "/v1/auth/code"
        const val NANO_AUTH_VERIFY_PATH = "/v1/auth/verify"
        const val NANO_AUTH_LOGIN_PATH = "/v1/auth/login"
        const val NANO_AUTH_PASSWORD_PATH = "/v1/auth/password"
        const val NANO_AUTH_DELETE_PATH = "/v1/auth/delete"
        const val NANO_SIGN_OUT_PATH = "/v1/auth/sign-out"
        const val NANO_SIGN_OUT_ALL_PATH = "/v1/auth/sign-out-all"
        const val NANO_ME_PATH = "/v1/me"

        // Port 8787 / DEFAULT_AUTH_ADDRESS / BuildConfig.DEFAULT_AUTH_BASE select nano relay.
        // Public AUTH may not use :8787 — also match BuildConfig auth base and /v1 path marker.
        fun looksLikeNanoRelay(ep: ServerEndpoint): Boolean {
            val port = runCatching { java.net.URI(ep.httpBase).port }.getOrDefault(-1)
            if (port == 8787) return true
            val base = ep.httpBase.trimEnd('/')
            if (base == DEFAULT_AUTH_ADDRESS.trimEnd('/') || base.endsWith(":8787")) return true
            val buildAuth = runCatching {
                Class.forName("com.miguo.voice.BuildConfig")
                    .getField("DEFAULT_AUTH_BASE").get(null) as? String
            }.getOrNull()?.trim()?.trimEnd('/')
            if (!buildAuth.isNullOrEmpty() && base == buildAuth) return true
            // Single-host AUTH path proxy: .../nm -> cloud-relay (keep nano /healthz,/v1/auth/*)
            if (base.endsWith("/nm")) return true
            if (base.contains("/v1")) return true
            return false
        }

        @Throws(ParseError::class)
        fun parse(raw: String): ServerEndpoint {
            var s = normalizeInput(raw)
            if (s.isEmpty()) throw ParseError.Empty
            val sep = s.indexOf("://")
            if (sep >= 0 && s.substring(sep + 3).isEmpty()) throw ParseError.Empty
            if (sep < 0) s = "http://$s"
            val uri = try { URI(s) } catch (e: Exception) { throw ParseError.Invalid(raw) }
            val scheme = uri.scheme?.lowercase() ?: throw ParseError.Invalid(raw)
            val secure = when (scheme) {
                "http", "ws" -> false
                "https", "wss" -> true
                else -> throw ParseError.UnsupportedScheme(scheme)
            }
            val host = uri.host
            if (host.isNullOrEmpty()) throw ParseError.Invalid(raw)
            // 去掉用户误填的 /api/v1/... 尾巴和多余斜杠，只保留反代前缀
            var path = uri.rawPath ?: ""
            val api = path.indexOf("/api/v1")
            if (api >= 0) path = path.substring(0, api)
            path = path.trimEnd('/')
            val hostPart = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            val authority = hostPart + portPart + path
            // fix1：java.net.URI 能解析不代表 OkHttp 能用（例如主机名含特殊字符），否则后面 Request.url() 会抛 IllegalArgumentException
            if (((if (secure) "https://" else "http://") + authority + HEALTH_PATH).toHttpUrlOrNull() == null) throw ParseError.Invalid(raw)
            return ServerEndpoint(
                httpBase = (if (secure) "https://" else "http://") + authority,
                wsBase = (if (secure) "wss://" else "ws://") + authority,
            )
        }

        /** 中文输入法下常见的全角字符（：．／。　和全角数字字母）转成半角，并去掉首尾空白和中间的空格（同 iOS normalizeInput）。 */
        fun normalizeInput(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                i += Character.charCount(cp)
                when (cp) {
                    in 0xFF01..0xFF5E -> sb.append((cp - 0xFEE0).toChar())   // 全角 ASCII → 半角
                    0x3002, 0xFF61 -> sb.append('.')                          // 。 ｡
                    0x3000 -> sb.append(' ')                                  // 全角空格
                    0x200B, 0x200C, 0x200D, 0xFEFF -> {}                      // 零宽字符
                    else -> sb.appendCodePoint(cp)
                }
            }
            return sb.toString().trim().replace(" ", "")
        }

        /** 请求头：X-User-Id 必填；X-API-Key 仅在填写时发送（不用查询参数，避免进访问日志）。 */
        fun headers(userId: String, apiKey: String?): Map<String, String> = headers(userId, apiKey, null)

        /**
         * v0.2.0：登录后多带 Authorization: Bearer（服务端以 token 为准，X-User-Id 被忽略）。
         * token 只放请求头，绝不放 URL 查询参数。userId 为空或不能放进头时不发 X-User-Id。
         */
        fun headers(userId: String?, apiKey: String?, bearer: String?): Map<String, String> {
            val h = linkedMapOf<String, String>()
            if (!userId.isNullOrEmpty() && HeaderValues.isSafe(userId)) h["X-User-Id"] = userId
            val k = HeaderValues.cleanApiKey(apiKey)
            if (!k.isNullOrEmpty()) h["X-API-Key"] = k
            if (!bearer.isNullOrEmpty() && HeaderValues.isSafe(bearer)) h["Authorization"] = "Bearer $bearer"
            return h
        }

        const val AUTH_PATH = "/api/v1/auth"
        const val TEXT_CHAT_PATH = "/api/v1/chat/text"
        const val CONVERSATIONS_PATH = "/api/v1/conversations"
    }
}
