package com.miguo.voice.transport

import com.miguo.voice.conversation.AuthBackend
import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.AuthServerStatus
import com.miguo.voice.protocol.AuthSession
import com.miguo.voice.protocol.AuthUser
import com.miguo.voice.protocol.ServerEndpoint
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// nano relay auth adapter (clean-room): /healthz, /v1/config, /v1/auth/code|verify|login, /v1/me, sign-out.
// Does not call memory_core /api/v1/auth endpoints.
class RelayAuthApi(
    private val endpoint: () -> ServerEndpoint?,
    private val timeoutMs: Long = 8000,
) : AuthBackend {
    private fun base(): String? = endpoint()?.httpBase
    private fun url(path: String): String? = base()?.let { it.trimEnd('/') + path }
    private fun headers(bearer: String? = null): Map<String, String> {
        val h = linkedMapOf("Accept" to "application/json")
        if (!bearer.isNullOrEmpty()) h["Authorization"] = "Bearer $bearer"
        return h
    }
    private val noEndpoint = ApiResult.Network("登录服务器地址不对")

    private fun parseSession(raw: HttpJson.Raw): ApiResult<AuthSession> {
        val o = AuthSession.parse(raw.body) ?: return ApiResult.HttpError(raw.status, "服务器返回的不是 JSON")
        return AuthSession.fromNanoTokenResponse(o)?.let { ApiResult.Ok(it) }
            ?: ApiResult.HttpError(raw.status, "服务器返回缺少 api_key")
    }

    override suspend fun status(): ApiResult<AuthServerStatus> {
        val u = url(ServerEndpoint.NANO_CONFIG_PATH) ?: return noEndpoint
        return HttpJson.call("GET", u, headers(), null, timeoutMs) { raw ->
            val o = AuthSession.parse(raw.body) ?: return@call ApiResult.HttpError(raw.status, "服务器返回的不是 JSON")
            ApiResult.Ok(AuthServerStatus.fromNanoConfig(o))
        }
    }

    override suspend fun requestCode(identifier: String): ApiResult<Unit> {
        val u = url(ServerEndpoint.NANO_AUTH_CODE_PATH) ?: return noEndpoint
        val body = buildJsonObject { put("identifier", identifier.trim()) }.toString()
        return HttpJson.call("POST", u, headers(), body, timeoutMs) { ApiResult.Ok(Unit) }
    }

    override suspend fun verifyCode(identifier: String, code: String): ApiResult<AuthSession> {
        val u = url(ServerEndpoint.NANO_AUTH_VERIFY_PATH) ?: return noEndpoint
        val body = buildJsonObject {
            put("identifier", identifier.trim())
            put("code", code.trim())
        }.toString()
        return HttpJson.call("POST", u, headers(), body, timeoutMs, parse = ::parseSession)
    }

    override suspend fun login(username: String, password: String): ApiResult<AuthSession> {
        val u = url(ServerEndpoint.NANO_AUTH_LOGIN_PATH) ?: return noEndpoint
        val body = buildJsonObject {
            put("identifier", username.trim())
            put("password", password)
        }.toString()
        return HttpJson.call("POST", u, headers(), body, timeoutMs, parse = ::parseSession)
    }

    override suspend fun register(
        username: String, password: String, displayName: String?, claimUserId: String?,
    ): ApiResult<AuthSession> = ApiResult.NotSupported

    override suspend fun refresh(refreshToken: String): ApiResult<AuthSession> {
        // nano has no refresh token; re-validate api_key via /v1/me and keep the same key.
        return when (val r = me(refreshToken)) {
            is ApiResult.Ok -> ApiResult.Ok(
                AuthSession(
                    userId = r.value.userId,
                    username = r.value.username,
                    displayName = r.value.displayName,
                    accessToken = refreshToken,
                    refreshToken = refreshToken,
                    accessExpiresAtMs = 0,
                )
            )
            is ApiResult.Unauthorized -> ApiResult.Unauthorized(r.message)
            ApiResult.NotSupported -> ApiResult.NotSupported
            is ApiResult.HttpError -> ApiResult.HttpError(r.status, r.message)
            is ApiResult.Network -> ApiResult.Network(r.message)
        }
    }

    override suspend fun me(accessToken: String): ApiResult<AuthUser> {
        val u = url(ServerEndpoint.NANO_ME_PATH) ?: return noEndpoint
        return HttpJson.call("GET", u, headers(accessToken), null, timeoutMs) { raw ->
            val o = AuthSession.parse(raw.body) ?: return@call ApiResult.HttpError(raw.status, "服务器返回的不是 JSON")
            AuthUser.fromNanoMe(o)?.let { ApiResult.Ok(it) }
                ?: ApiResult.HttpError(raw.status, "服务器返回格式不对")
        }
    }

    override suspend fun logout(accessToken: String, refreshToken: String?, everywhere: Boolean): ApiResult<Unit> {
        val path = if (everywhere) ServerEndpoint.NANO_SIGN_OUT_ALL_PATH else ServerEndpoint.NANO_SIGN_OUT_PATH
        val u = url(path) ?: return noEndpoint
        val body = if (everywhere) """{"all":true}""" else "{}"
        return HttpJson.call("POST", u, headers(accessToken), body, timeoutMs) { ApiResult.Ok(Unit) }
    }

    override suspend fun changePassword(accessToken: String, oldPassword: String, newPassword: String): ApiResult<Unit> {
        val u = url(ServerEndpoint.NANO_AUTH_PASSWORD_PATH) ?: return noEndpoint
        val body = buildJsonObject {
            if (oldPassword.isNotEmpty()) put("current", oldPassword)
            put("password", newPassword)
        }.toString()
        return HttpJson.call("POST", u, headers(accessToken), body, timeoutMs) { ApiResult.Ok(Unit) }
    }

    override suspend fun deleteAccount(accessToken: String, password: String): ApiResult<Unit> {
        val u = url(ServerEndpoint.NANO_AUTH_DELETE_PATH) ?: return noEndpoint
        return HttpJson.call("POST", u, headers(accessToken), "{}", timeoutMs) { ApiResult.Ok(Unit) }
    }
}

/** 按 auth 基址选择 memory_core AuthApi 或 nano RelayAuthApi。 */
object AuthBackendFactory {
    fun create(endpoint: () -> ServerEndpoint?, apiKey: () -> String?): AuthBackend =
        object : AuthBackend {
            private fun inner(): AuthBackend {
                val ep = endpoint()
                return if (ep != null && ServerEndpoint.looksLikeNanoRelay(ep)) RelayAuthApi(endpoint)
                else AuthApi(endpoint, apiKey)
            }
            override suspend fun status() = inner().status()
            override suspend fun requestCode(identifier: String) = inner().requestCode(identifier)
            override suspend fun verifyCode(identifier: String, code: String) = inner().verifyCode(identifier, code)
            override suspend fun login(username: String, password: String) = inner().login(username, password)
            override suspend fun register(username: String, password: String, displayName: String?, claimUserId: String?) =
                inner().register(username, password, displayName, claimUserId)
            override suspend fun refresh(refreshToken: String) = inner().refresh(refreshToken)
            override suspend fun me(accessToken: String) = inner().me(accessToken)
            override suspend fun logout(accessToken: String, refreshToken: String?, everywhere: Boolean) =
                inner().logout(accessToken, refreshToken, everywhere)
            override suspend fun changePassword(accessToken: String, oldPassword: String, newPassword: String) =
                inner().changePassword(accessToken, oldPassword, newPassword)
            override suspend fun deleteAccount(accessToken: String, password: String) =
                inner().deleteAccount(accessToken, password)
        }
}
