package com.miguo.voice.transport

import com.miguo.voice.conversation.AuthBackend
import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.AuthJson
import com.miguo.voice.protocol.AuthServerStatus
import com.miguo.voice.protocol.AuthSession
import com.miguo.voice.protocol.AuthUser
import com.miguo.voice.protocol.ServerEndpoint

/**
 * /api/v1/auth/… 的 OkHttp 实现。X-API-Key 仍然每次都带（服务端仍要求）；token 只放 Authorization 头 / 请求体。
 * [endpoint] / [apiKey] 每次调用时取（设置里可能刚改过）。
 */
class AuthApi(
    private val endpoint: () -> ServerEndpoint?,
    private val apiKey: () -> String?,
    private val timeoutMs: Long = 8000,
) : AuthBackend {
    private fun url(p: String): String? = endpoint()?.let { it.httpBase + ServerEndpoint.AUTH_PATH + p }
    private fun headers(bearer: String? = null) = ServerEndpoint.headers(null, apiKey(), bearer)
    private val noEndpoint = ApiResult.Network("服务器地址不对")

    private fun session(raw: HttpJson.Raw): ApiResult<AuthSession> {
        val o = AuthSession.parse(raw.body) ?: return ApiResult.HttpError(raw.status, "服务器返回的不是 JSON")
        return AuthSession.fromTokenResponse(o, System.currentTimeMillis())?.let { ApiResult.Ok(it) }
            ?: ApiResult.HttpError(raw.status, "服务器返回缺少 token 字段")
    }

    override suspend fun status(): ApiResult<AuthServerStatus> {
        val u = url("/status") ?: return noEndpoint
        return HttpJson.call("GET", u, headers(), null, timeoutMs) { raw ->
            AuthSession.parse(raw.body)?.let { ApiResult.Ok(AuthServerStatus.from(it)) } ?: ApiResult.HttpError(raw.status, "服务器返回的不是 JSON")
        }
    }

    override suspend fun requestCode(identifier: String): ApiResult<Unit> = ApiResult.NotSupported

    override suspend fun verifyCode(identifier: String, code: String): ApiResult<AuthSession> = ApiResult.NotSupported

        override suspend fun login(username: String, password: String): ApiResult<AuthSession> {
        val u = url("/login") ?: return noEndpoint
        return HttpJson.call("POST", u, headers(), AuthJson.loginBody(username, password), timeoutMs, parse = ::session)
    }

    override suspend fun register(username: String, password: String, displayName: String?, claimUserId: String?): ApiResult<AuthSession> {
        val u = url("/register") ?: return noEndpoint
        return HttpJson.call("POST", u, headers(), AuthJson.registerBody(username, password, displayName, claimUserId), timeoutMs, parse = ::session)
    }

    override suspend fun refresh(refreshToken: String): ApiResult<AuthSession> {
        val u = url("/refresh") ?: return noEndpoint
        return HttpJson.call("POST", u, headers(), AuthJson.refreshBody(refreshToken), timeoutMs, parse = ::session)
    }

    override suspend fun me(accessToken: String): ApiResult<AuthUser> {
        val u = url("/me") ?: return noEndpoint
        return HttpJson.call("GET", u, headers(accessToken), null, timeoutMs) { raw ->
            AuthSession.parse(raw.body)?.let { AuthUser.from(it) }?.let { ApiResult.Ok(it) } ?: ApiResult.HttpError(raw.status, "服务器返回格式不对")
        }
    }

    override suspend fun logout(accessToken: String, refreshToken: String?, everywhere: Boolean): ApiResult<Unit> {
        val u = url("/logout") ?: return noEndpoint
        return HttpJson.call("POST", u, headers(accessToken), AuthJson.logoutBody(refreshToken, everywhere), timeoutMs) { ApiResult.Ok(Unit) }
    }

    override suspend fun changePassword(accessToken: String, oldPassword: String, newPassword: String): ApiResult<Unit> {
        val u = url("/password") ?: return noEndpoint
        return HttpJson.call("POST", u, headers(accessToken), AuthJson.changePasswordBody(oldPassword, newPassword), timeoutMs) { ApiResult.Ok(Unit) }
    }

    override suspend fun deleteAccount(accessToken: String, password: String): ApiResult<Unit> {
        val u = url("/me") ?: return noEndpoint
        return HttpJson.call("DELETE", u, headers(accessToken), AuthJson.deleteAccountBody(password), timeoutMs) { ApiResult.Ok(Unit) }
    }
}
