package com.miguo.voice.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// v0.2.0：账号 / 登录（memory_core v2 api/auth.py：/api/v1/auth/{status,register,login,refresh,logout,me}）。
// 纯 Kotlin，无 Android 依赖，方便 JVM 单元测试。

/** 已登录的会话（加密存进 SecureStore；toString 不打印 token） */
data class AuthSession(
    val userId: String,
    val username: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    /** access token 过期时刻（epoch ms；0 = 未知） */
    val accessExpiresAtMs: Long = 0,
    val refreshExpiresAt: String? = null,
) {
    fun accessExpired(nowMs: Long, slackMs: Long = 60_000): Boolean = accessExpiresAtMs in 1..(nowMs + slackMs)

    fun encode(): String = buildJsonObject {
        put("user_id", userId); put("username", username); put("display_name", displayName)
        put("access_token", accessToken); put("refresh_token", refreshToken)
        put("access_expires_at_ms", accessExpiresAtMs)
        refreshExpiresAt?.let { put("refresh_expires_at", it) }
    }.toString()

    override fun toString(): String = "AuthSession(userId=$userId, username=$username, displayName=$displayName, tokens=***)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 解析本地存储的 JSON；坏数据 → null */
        fun decode(s: String?): AuthSession? {
            if (s.isNullOrBlank()) return null
            return try {
                val o = json.parseToJsonElement(s).jsonObject
                AuthSession(
                    userId = o.str("user_id") ?: return null,
                    username = o.str("username") ?: o.str("user_id") ?: return null,
                    displayName = o.str("display_name") ?: o.str("username") ?: "",
                    accessToken = o.str("access_token") ?: return null,
                    refreshToken = o.str("refresh_token") ?: return null,
                    accessExpiresAtMs = o.dbl("access_expires_at_ms")?.toLong() ?: 0,
                    refreshExpiresAt = o.str("refresh_expires_at"),
                )
            } catch (e: Exception) {
                null
            }
        }

        /** 解析 /auth/login | /auth/register | /auth/refresh 的响应 */
        fun fromTokenResponse(o: JsonObject, nowMs: Long): AuthSession? {
            val access = o.str("access_token") ?: return null
            val refresh = o.str("refresh_token") ?: return null
            val uid = o.str("user_id") ?: return null
            val user = o.str("username") ?: uid
            val exp = o.dbl("expires_in")?.let { nowMs + (it * 1000).toLong() } ?: 0L
            return AuthSession(uid, user, o.str("display_name") ?: user, access, refresh, exp, o.str("refresh_expires_at"))
        }

        fun parse(text: String): JsonObject? = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }

        /** nano relay POST /v1/auth/verify|login：{"api_key","account":{"id","hint",...}} */
        fun fromNanoTokenResponse(o: JsonObject): AuthSession? {
            val access = o.str("api_key") ?: return null
            val account = o["account"] as? JsonObject
            val uid = account?.str("id") ?: return null
            val hint = account?.str("hint") ?: uid
            return AuthSession(uid, hint, hint, access, access, 0, null)
        }

        /** nano api_key 形如 nm_…；memory_core JWT/随机串不会以此开头 */
        fun isNanoApiKey(token: String?): Boolean = !token.isNullOrEmpty() && token.startsWith("nm_")
    }
}

/** GET /auth/me */
data class AuthUser(val userId: String, val username: String, val displayName: String) {
    companion object {
        fun from(o: JsonObject): AuthUser? {
            val uid = o.str("user_id") ?: return null
            val u = o.str("username") ?: uid
            return AuthUser(uid, u, o.str("display_name") ?: u)
        }

        /** GET /v1/me：账户在 account 子对象里 */
        fun fromNanoMe(o: JsonObject): AuthUser? {
            val account = (o["account"] as? JsonObject) ?: o
            val uid = account.str("id") ?: account.str("user_id") ?: return null
            val hint = account.str("hint") ?: account.str("username") ?: uid
            return AuthUser(uid, hint, hint)
        }
    }
}

/** GET /auth/status */
data class AuthServerStatus(val enabled: Boolean, val required: Boolean, val allowRegister: Boolean, val minPassword: Int) {
    companion object {
        fun from(o: JsonObject) = AuthServerStatus(
            enabled = o.bool("enabled") ?: true,
            required = o.bool("required") ?: false,
            allowRegister = o.bool("allow_register") ?: true,
            minPassword = o.int("min_password") ?: 6,
        )

        /** GET /v1/config（nano public config） */
        fun fromNanoConfig(o: JsonObject) = AuthServerStatus(
            enabled = true,
            required = false,
            allowRegister = o.bool("signup_open") ?: true,
            minPassword = o.int("password_min_len") ?: 8,
        )
    }
}

/** HTTP 结果分类（AuthApi / TextChatApi 共用） */
sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    /** 401：token 无效/过期，或用户名密码错误 */
    data class Unauthorized(val message: String) : ApiResult<Nothing>()
    /** 404/405：服务器没有这个接口（memory_core 未装 v2 补丁） */
    object NotSupported : ApiResult<Nothing>()
    /** 其它 HTTP 错误（400/403/409/422/429/5xx），message 为服务端 detail */
    data class HttpError(val status: Int, val message: String, val retryAfterS: Int? = null) : ApiResult<Nothing>()
    /** 网络错误（连不上/超时） */
    data class Network(val message: String) : ApiResult<Nothing>()
}

object AuthJson {
    /** FastAPI 错误体：{"detail": "..."} 或 {"detail": {...}} 或 {"detail":[{msg:...}]} */
    fun detail(body: String?, status: Int, retryAfterS: Int? = null): String {
        val o = body?.let { AuthSession.parse(it) }
        // nano error.code -> Chinese only; never surface English message
        val err = o?.get("error")
        if (err is JsonObject) {
            val code = err.str("code")?.trim().orEmpty()
            if (code.isNotEmpty()) {
                val zh = AuthLoginMessages.zhForServerCode(code, status, retryAfterS)
                return AuthLoginMessages.tagged(code, zh)
            }
        }
        val d = o?.get("detail")
        val msg = when {
            d == null -> null
            d is kotlinx.serialization.json.JsonPrimitive -> d.content
            d is JsonObject -> d.str("code")?.let { c ->
                AuthLoginMessages.tagged(c, AuthLoginMessages.zhForServerCode(c, status, retryAfterS))
            } ?: d.str("message") ?: d.str("title") ?: d.toString()
            d is kotlinx.serialization.json.JsonArray -> (d.firstOrNull() as? JsonObject)?.str("msg") ?: d.toString()
            else -> d.toString()
        }
        if (msg.isNullOrBlank()) {
            return if (status == 429) AuthLoginMessages.tagged(
                AuthLoginMessages.CODE_RATE_LIMITED,
                AuthLoginMessages.zhForServerCode(AuthLoginMessages.CODE_RATE_LIMITED, status, retryAfterS),
            ) else AuthLoginMessages.tagged("http_$status", AuthLoginMessages.BUBBLE_LOGIN_FAIL_LINE)
        }
        val looksEnglish = msg.any { it in 'A'..'Z' || it in 'a'..'z' } && !msg.contains('|')
        return if (looksEnglish) AuthLoginMessages.tagged(
            if (status == 429) AuthLoginMessages.CODE_RATE_LIMITED else "http_$status",
            AuthLoginMessages.zhForServerCode(if (status == 429) AuthLoginMessages.CODE_RATE_LIMITED else null, status, retryAfterS),
        ) else msg
    }

    fun loginBody(username: String, password: String) = buildJsonObject {
        put("username", username); put("password", password); put("client", "android")
    }.toString()

    fun registerBody(username: String, password: String, displayName: String?, claimUserId: String?) = buildJsonObject {
        put("username", username); put("password", password); put("client", "android")
        displayName?.takeIf { it.isNotBlank() }?.let { put("display_name", it) }
        claimUserId?.takeIf { it.isNotBlank() }?.let { put("user_id", it) }
    }.toString()

    fun refreshBody(refreshToken: String) = buildJsonObject {
        put("refresh_token", refreshToken); put("client", "android")
    }.toString()

    fun logoutBody(refreshToken: String?, everywhere: Boolean = false) = buildJsonObject {
        refreshToken?.let { put("refresh_token", it) }
        if (everywhere) put("everywhere", true)
    }.toString()

    fun changePasswordBody(oldPassword: String, newPassword: String) = buildJsonObject {
        put("old_password", oldPassword); put("new_password", newPassword)
    }.toString()

    fun deleteAccountBody(password: String) = buildJsonObject {
        put("password", password)
    }.toString()
}
