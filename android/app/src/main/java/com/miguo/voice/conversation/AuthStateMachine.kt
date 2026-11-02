package com.miguo.voice.conversation

import com.miguo.voice.protocol.AuthLoginMessages

import com.miguo.voice.BuildConfig

import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.AuthServerStatus
import com.miguo.voice.protocol.AuthSession
import com.miguo.voice.protocol.AuthUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 服务器 /api/v1/auth/…（实现见 transport/AuthApi.kt；测试里用假的） */
interface AuthBackend {
    suspend fun status(): ApiResult<AuthServerStatus>
    /** nano: POST /v1/auth/code；memory_core 返回 NotSupported */
    suspend fun requestCode(identifier: String): ApiResult<Unit>
    /** nano: POST /v1/auth/verify；memory_core 返回 NotSupported */
    suspend fun verifyCode(identifier: String, code: String): ApiResult<AuthSession>
    suspend fun login(username: String, password: String): ApiResult<AuthSession>
    suspend fun register(username: String, password: String, displayName: String?, claimUserId: String?): ApiResult<AuthSession>
    suspend fun refresh(refreshToken: String): ApiResult<AuthSession>
    suspend fun me(accessToken: String): ApiResult<AuthUser>
    suspend fun logout(accessToken: String, refreshToken: String?, everywhere: Boolean = false): ApiResult<Unit>
    suspend fun changePassword(accessToken: String, oldPassword: String, newPassword: String): ApiResult<Unit>
    suspend fun deleteAccount(accessToken: String, password: String): ApiResult<Unit>
}

/** token 持久化（App 里是 SecureStore 加密；测试里是内存） */
interface TokenStore {
    fun load(): AuthSession?
    fun save(s: AuthSession?)
}

/**
 * 登录状态机：
 * ```
 * Unknown ─bootstrap→ Checking ─┬─ 有 token 且 /auth/me 200 ────────────→ LoggedIn
 *                               ├─ /auth/me 401 → Refreshing ─ok→ LoggedIn │ ─401→ Expired → LoggedOut
 *                               ├─ 没 token ───────────────────────────────→ LoggedOut
 *                               └─ /auth/… 404（服务器没装 v2）────────────→ UserIdMode（提示，沿用 user_id）
 * LoggedOut ─login→ LoggingIn ─ok→ LoggedIn / ─失败→ LoggedOut(error)
 * LoggedOut ─register→ Registering ─ok→ LoggedIn / ─失败→ LoggedOut(error)
 * LoggedIn ─接口 401→ Refreshing ─ok→ LoggedIn / ─401→ Expired → LoggedOut
 * LoggedIn ─logout→ LoggedOut
 * LoggedOut ─skip（服务器不强制登录时）→ UserIdMode
 * ```
 * 网络不通时：有本地 token 就先按 LoggedIn(offline=true) 放行（服务器恢复后第一次 401 再刷新），没 token 就 LoggedOut(error)。
 */
sealed class AuthState(val name: String) {
    object Unknown : AuthState("unknown")
    object Checking : AuthState("checking")
    data class LoggedOut(val error: String? = null, val required: Boolean = false, val allowRegister: Boolean = true) : AuthState("logged_out")
    object LoggingIn : AuthState("logging_in")
    object Registering : AuthState("registering")
    data class LoggedIn(val session: AuthSession, val offline: Boolean = false) : AuthState("logged_in")
    data class Refreshing(val session: AuthSession) : AuthState("refreshing")
    data class Expired(val message: String) : AuthState("expired")
    /** 不登录：旧的 X-User-Id 模式（服务器没装 v2 → notice 说明原因；用户主动跳过 → notice = null） */
    data class UserIdMode(val notice: String?) : AuthState("user_id_mode")

    /** 是否可以进入对话界面 */
    val canUseApp: Boolean get() = this is LoggedIn || this is Refreshing || this is UserIdMode
    val busy: Boolean get() = this is Checking || this is LoggingIn || this is Registering || this is Refreshing || this is Unknown
    override fun toString(): String = name
}

class AuthStateMachine(
    private val backend: AuthBackend,
    private val store: TokenStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 用户上次选择了“不登录” */
    private val skippedProvider: () -> Boolean = { false },
    private val setSkipped: (Boolean) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state
    private val refreshLock = Mutex()
    @Volatile private var serverStatus: AuthServerStatus? = null

    val session: AuthSession? get() = when (val s = _state.value) {
        is AuthState.LoggedIn -> s.session
        is AuthState.Refreshing -> s.session
        else -> null
    }

    /** 当前请求该用的 access token（UserIdMode / 未登录 → null） */
    val accessToken: String? get() = session?.accessToken

    private fun set(s: AuthState) {
        if (_state.value != s) log("auth ${_state.value} → $s")
        _state.value = s
    }

    private fun loggedOut(error: String? = null) = AuthState.LoggedOut(error, serverStatus?.required ?: false, serverStatus?.allowRegister ?: true)

    /** 启动 / 换服务器后调用 */
    suspend fun bootstrap() {
        set(AuthState.Checking)
        val saved = store.load()
        if (saved == null) {
            when (val st = backend.status()) {
                is ApiResult.Ok -> {
                    serverStatus = st.value
                    if (!st.value.enabled) { set(AuthState.UserIdMode(NOT_ENABLED)); return }
                    if (skippedProvider() && !st.value.required) set(AuthState.UserIdMode(null)) else set(loggedOut())
                }
                ApiResult.NotSupported -> set(AuthState.UserIdMode(NOT_SUPPORTED))
                is ApiResult.Network -> if (skippedProvider()) set(AuthState.UserIdMode(null)) else set(loggedOut(AuthLoginMessages.tagged(AuthLoginMessages.CODE_AUTH_UNREACHABLE, AuthLoginMessages.BUBBLE_UNREACHABLE_LINE)))
                is ApiResult.Unauthorized -> set(loggedOut("API Key 不对：${st.message}"))
                is ApiResult.HttpError -> set(loggedOut(httpMsg(st)))
            }
            return
        }
        when (val me = backend.me(saved.accessToken)) {
            is ApiResult.Ok -> {
                val s = saved.copy(displayName = me.value.displayName, username = me.value.username, userId = me.value.userId)
                if (s != saved) store.save(s)
                set(AuthState.LoggedIn(s))
            }
            is ApiResult.Unauthorized -> refreshNow(saved)
            ApiResult.NotSupported -> { store.save(null); set(AuthState.UserIdMode(NOT_SUPPORTED)) }
            is ApiResult.Network -> set(AuthState.LoggedIn(saved, offline = true))
            is ApiResult.HttpError -> set(AuthState.LoggedIn(saved, offline = true))
        }
    }


    suspend fun requestCode(identifier: String): String? {
        val id = identifier.trim()
        if (id.isEmpty()) {
            val m = AuthLoginMessages.tagged(AuthLoginMessages.CODE_BAD_IDENTIFIER, AuthLoginMessages.BUBBLE_EMPTY_ID_LINE)
            set(loggedOut(m)); return m
        }
        if (!isValidIdentifier(id)) {
            val m = AuthLoginMessages.tagged(AuthLoginMessages.CODE_BAD_IDENTIFIER, AuthLoginMessages.BUBBLE_BAD_ID_LINE)
            set(loggedOut(m)); return m
        }
        set(AuthState.LoggingIn)
        return when (val r = backend.requestCode(id)) {
            is ApiResult.Ok -> { set(loggedOut(null)); null }
            is ApiResult.Unauthorized -> { set(loggedOut(r.message)); r.message }
            ApiResult.NotSupported -> {
                val m = AuthLoginMessages.tagged(AuthLoginMessages.CODE_AUTH_UNREACHABLE, AuthLoginMessages.BUBBLE_UNREACHABLE_LINE)
                set(loggedOut(m)); m
            }
            is ApiResult.HttpError -> { val m = httpMsg(r); set(loggedOut(m)); m }
            is ApiResult.Network -> {
                val m = AuthLoginMessages.tagged(AuthLoginMessages.CODE_AUTH_UNREACHABLE, AuthLoginMessages.BUBBLE_UNREACHABLE_LINE)
                set(loggedOut(m)); m
            }
        }
    }

    suspend fun verifyCode(identifier: String, code: String): Boolean {
        val id = identifier.trim()
        val c = code.trim()
        when {
            id.isEmpty() -> {
                set(loggedOut(AuthLoginMessages.tagged(AuthLoginMessages.CODE_BAD_IDENTIFIER, AuthLoginMessages.BUBBLE_EMPTY_ID_LINE)))
                return false
            }
            !isValidIdentifier(id) -> {
                set(loggedOut(AuthLoginMessages.tagged(AuthLoginMessages.CODE_BAD_IDENTIFIER, AuthLoginMessages.BUBBLE_BAD_ID_LINE)))
                return false
            }
            c.isEmpty() -> { set(loggedOut("请输入验证码")); return false }
        }
        set(AuthState.LoggingIn)
        return finishAuth(backend.verifyCode(id, c))
    }

    suspend fun login(username: String, password: String): Boolean {
        val u = username.trim()
        validateCredentials(u, password)?.let { set(loggedOut(it)); return false }
        set(AuthState.LoggingIn)
        return finishAuth(backend.login(u, password))
    }

    suspend fun register(username: String, password: String, displayName: String?, claimUserId: String?): Boolean {
        val u = username.trim()
        validateCredentials(u, password, serverStatus?.minPassword ?: 6)?.let { set(loggedOut(it)); return false }
        set(AuthState.Registering)
        return finishAuth(backend.register(u, password, displayName?.trim(), claimUserId?.trim()))
    }

    private fun finishAuth(r: ApiResult<AuthSession>): Boolean = when (r) {
        is ApiResult.Ok -> {
            store.save(r.value); setSkipped(false)
            set(AuthState.LoggedIn(r.value)); true
        }
        is ApiResult.Unauthorized -> { set(loggedOut(r.message.ifBlank { "用户名或密码错误" })); false }
        ApiResult.NotSupported -> { set(AuthState.UserIdMode(NOT_SUPPORTED)); false }
        is ApiResult.HttpError -> { set(loggedOut(httpMsg(r))); false }
        is ApiResult.Network -> { set(loggedOut(AuthLoginMessages.tagged(AuthLoginMessages.CODE_AUTH_UNREACHABLE, AuthLoginMessages.BUBBLE_UNREACHABLE_LINE))); false }
    }

    /** 不登录，沿用 user_id 模式（服务器 MEMORY_AUTH_REQUIRED=true 时不允许） */
    fun skipLogin(): Boolean {
        if (!BuildConfig.ALLOW_SKIP_LOGIN) {
            set(loggedOut("正式版须登录"))
            return false
        }
        if (serverStatus?.required == true) { set(loggedOut("服务器要求登录（MEMORY_AUTH_REQUIRED=true）")); return false }
        setSkipped(true)
        set(AuthState.UserIdMode(null))
        return true
    }

    /** 从 UserIdMode 回到登录页 */
    fun showLogin() {
        setSkipped(false)
        set(loggedOut())
    }

    /**
     * 退出登录。everywhere=true → POST /auth/logout {everywhere:true}，踢掉所有设备的 token。
     * 本地一律先清 token；服务端失败不影响本机退出。
     */
    suspend fun logout(everywhere: Boolean = false) {
        val s = session
        store.save(null)
        set(loggedOut())
        if (s != null) backend.logout(s.accessToken, s.refreshToken, everywhere)
    }

    /**
     * 修改密码（POST /auth/password）。成功后服务端会吊销该账号全部 token，本机清会话并回到登录页。
     * @return null = 成功；非空 = 错误文案（保持 LoggedIn，方便用户改完再试）。
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): String? {
        val s = session ?: return "未登录"
        val min = serverStatus?.minPassword ?: 6
        when {
            oldPassword.isEmpty() -> return "请输入原密码"
            newPassword.length < min -> return "新密码至少 $min 位"
            newPassword == oldPassword -> return "新密码不能与原密码相同"
        }
        ensureFresh()
        val token = session?.accessToken ?: s.accessToken
        return when (val r = backend.changePassword(token, oldPassword, newPassword)) {
            is ApiResult.Ok -> {
                store.save(null)
                set(loggedOut("密码已修改，请用新密码重新登录"))
                null
            }
            is ApiResult.Unauthorized -> r.message.ifBlank { "原密码错误" }
            ApiResult.NotSupported -> "服务器不支持修改密码（需 memory_core 账号接口）"
            is ApiResult.HttpError -> httpMsg(r)
            is ApiResult.Network -> "连不上服务器：${r.message}"
        }
    }

    /**
     * 注销账号（nano: POST /v1/auth/delete；非 nano: DELETE /auth/me）。成功后清本地会话。
     * @return null = 成功；非空 = 错误文案。
     */
    suspend fun deleteAccount(password: String): String? {
        val s = session ?: return "未登录"
        val nano = AuthSession.isNanoApiKey(s.accessToken)
        if (!nano && password.isEmpty()) return "请输入密码以确认注销"
        ensureFresh()
        val token = session?.accessToken ?: s.accessToken
        return when (val r = backend.deleteAccount(token, if (nano) "" else password)) {
            is ApiResult.Ok -> {
                store.save(null)
                set(loggedOut("账号已注销"))
                null
            }
            is ApiResult.Unauthorized -> r.message.ifBlank { "密码错误" }
            ApiResult.NotSupported -> "服务器不支持注销账号（需 memory_core 账号接口）"
            is ApiResult.HttpError -> httpMsg(r)
            is ApiResult.Network -> "连不上服务器：${r.message}"
        }
    }

    /**
     * 某个接口返回 401（或 WS error code=unauthorized）时调用：用 refresh token 换新 token。
     * 并发的多次 401 只刷新一次。返回 true = 已拿到新 token，调用方可以重试一次。
     */
    suspend fun onUnauthorized(failedAccessToken: String?): Boolean = refreshLock.withLock {
        val cur = session ?: return@withLock false
        if (failedAccessToken != null && cur.accessToken != failedAccessToken) return@withLock true   // 别人已刷新
        refreshNowLocked(cur)
    }

    /** access token 快过期就提前刷新（发起 WS 之前调用） */
    suspend fun ensureFresh(): Boolean = refreshLock.withLock {
        val cur = session ?: return@withLock false
        if (!cur.accessExpired(now())) return@withLock true
        refreshNowLocked(cur)
    }

    private suspend fun refreshNow(s: AuthSession) { refreshLock.withLock { refreshNowLocked(s) } }

    private suspend fun refreshNowLocked(s: AuthSession): Boolean {
        set(AuthState.Refreshing(s))
        return when (val r = backend.refresh(s.refreshToken)) {
            is ApiResult.Ok -> { store.save(r.value); set(AuthState.LoggedIn(r.value)); true }
            is ApiResult.Unauthorized -> {
                store.save(null)
                set(AuthState.Expired(EXPIRED))
                set(loggedOut(EXPIRED))
                false
            }
            ApiResult.NotSupported -> { store.save(null); set(AuthState.UserIdMode(NOT_SUPPORTED)); false }
            is ApiResult.Network, is ApiResult.HttpError -> { set(AuthState.LoggedIn(s, offline = true)); false }
        }
    }

    private fun httpMsg(r: ApiResult.HttpError): String {
        val (code, zh) = AuthLoginMessages.parseTagged(r.message)
        if (code != null) {
            if (r.status == 429 || code == AuthLoginMessages.CODE_RATE_LIMITED || code == AuthLoginMessages.CODE_TOO_OFTEN) {
                return AuthLoginMessages.tagged(
                    if (code.startsWith("http_")) AuthLoginMessages.CODE_RATE_LIMITED else code,
                    AuthLoginMessages.bubbleRateLine(r.retryAfterS),
                )
            }
            return if (zh.isNotBlank()) AuthLoginMessages.tagged(code, zh) else r.message
        }
        return when (r.status) {
            429 -> AuthLoginMessages.tagged(
                AuthLoginMessages.CODE_RATE_LIMITED,
                AuthLoginMessages.bubbleRateLine(r.retryAfterS),
            )
            403 -> r.message.ifBlank { "服务器已关闭注册" }
            409 -> r.message.ifBlank { "用户名已被注册，请换一个或直接登录" }
            else -> r.message.ifBlank { AuthLoginMessages.BUBBLE_LOGIN_FAIL_LINE }
        }
    }

    companion object {
        const val NOT_SUPPORTED = "登录服务暂时不可用，请稍后再试。"
        const val NOT_ENABLED = "登录服务暂时不可用，请稍后再试。"
        const val EXPIRED = "登录已过期，请重新登录"

        /** 与服务端 USERNAME_RE ^[A-Za-z0-9_.@\-]{3,32}$ 一致；不用正则（Android ICU 与 JDK 语法差异） */
        fun isValidUsername(u: String): Boolean =
            u.length in 3..32 && u.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.@-" }

        /** 大陆手机 11 位或含 @ 的邮箱（与中继 bad_identifier 提示对齐，客户端只做轻校验） */
        fun isValidIdentifier(id: String): Boolean {
            val s = id.trim()
            if (s.contains('@')) return s.length in 5..128 && '.' in s.substringAfter('@')
            return s.length == 11 && s.all { it in '0'..'9' } && s.startsWith('1')
        }

        fun validateCredentials(u: String, p: String, minPassword: Int = 1): String? = when {
            isValidIdentifier(u) -> when {
                p.isEmpty() -> "请输入密码"
                p.length < minPassword -> "密码至少 $minPassword 位"
                else -> null
            }
            !isValidUsername(u) -> "用户名需 3–32 位，只能用英文字母、数字和 _ . @ -（或填手机号/邮箱）"
            p.isEmpty() -> "请输入密码"
            p.length < minPassword -> "密码至少 $minPassword 位"
            else -> null
        }
    }
}
