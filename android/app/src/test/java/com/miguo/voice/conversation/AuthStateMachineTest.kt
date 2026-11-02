package com.miguo.voice.conversation

import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.AuthServerStatus
import com.miguo.voice.protocol.AuthSession
import com.miguo.voice.protocol.AuthUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun sess(n: Int = 1) = AuthSession("jason", "jason", "Jason", "acc$n", "ref$n", 0)

private class FakeAuthBackend : AuthBackend {
    var status: ApiResult<AuthServerStatus> = ApiResult.Ok(AuthServerStatus(enabled = true, required = false, allowRegister = true, minPassword = 6))
    var me: ApiResult<AuthUser> = ApiResult.Ok(AuthUser("jason", "jason", "Jason"))
    var login: ApiResult<AuthSession> = ApiResult.Ok(sess(1))
    var refresh: ApiResult<AuthSession> = ApiResult.Ok(sess(2))
    var refreshGate: CompletableDeferred<Unit>? = null
    var refreshCalls = 0
    var logoutCalls = 0
    var lastEverywhere = false
    var changePasswordResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var deleteAccountResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var lastChange: List<String>? = null
    var lastDeletePassword: String? = null
    var lastRegister: List<String?>? = null
    override suspend fun status() = status
    override suspend fun requestCode(identifier: String): ApiResult<Unit> = ApiResult.NotSupported
    override suspend fun verifyCode(identifier: String, code: String): ApiResult<AuthSession> = ApiResult.NotSupported
    override suspend fun login(username: String, password: String) = login
    override suspend fun register(username: String, password: String, displayName: String?, claimUserId: String?): ApiResult<AuthSession> {
        lastRegister = listOf(username, password, displayName, claimUserId); return login
    }
    override suspend fun refresh(refreshToken: String): ApiResult<AuthSession> {
        refreshCalls++; refreshGate?.await(); return refresh
    }
    override suspend fun me(accessToken: String) = me
    override suspend fun logout(accessToken: String, refreshToken: String?, everywhere: Boolean): ApiResult<Unit> {
        logoutCalls++; lastEverywhere = everywhere; return ApiResult.Ok(Unit)
    }
    override suspend fun changePassword(accessToken: String, oldPassword: String, newPassword: String): ApiResult<Unit> {
        lastChange = listOf(accessToken, oldPassword, newPassword); return changePasswordResult
    }
    override suspend fun deleteAccount(accessToken: String, password: String): ApiResult<Unit> {
        lastDeletePassword = password; return deleteAccountResult
    }
}

private class MemStore(var s: AuthSession? = null) : TokenStore {
    override fun load() = s
    override fun save(s: AuthSession?) { this.s = s }
}

class AuthStateMachineTest {
    private var skipped = false
    private fun sm(b: FakeAuthBackend, st: MemStore) = AuthStateMachine(b, st, now = { 1000L }, skippedProvider = { skipped }, setSkipped = { skipped = it })

    @Test fun noTokenGoesLoggedOut() = runTest {
        val m = sm(FakeAuthBackend(), MemStore()); m.bootstrap()
        val s = m.state.value as AuthState.LoggedOut
        assertNull(s.error); assertFalse(s.required); assertFalse(m.state.value.canUseApp)
    }

    @Test fun savedTokenAndMeOkIsLoggedIn() = runTest {
        val st = MemStore(sess(1)); val m = sm(FakeAuthBackend(), st); m.bootstrap()
        assertTrue(m.state.value is AuthState.LoggedIn)
        assertEquals("acc1", m.accessToken)
        assertTrue(m.state.value.canUseApp)
    }

    @Test fun me401RefreshesOk() = runTest {
        val b = FakeAuthBackend().apply { me = ApiResult.Unauthorized("expired") }
        val st = MemStore(sess(1)); val m = sm(b, st); m.bootstrap()
        assertEquals("acc2", m.accessToken)
        assertEquals("acc2", st.s!!.accessToken)
        assertEquals(1, b.refreshCalls)
    }

    @Test fun refresh401LogsOutExpired() = runTest {
        val b = FakeAuthBackend().apply { me = ApiResult.Unauthorized("x"); refresh = ApiResult.Unauthorized("bad refresh") }
        val st = MemStore(sess(1)); val m = sm(b, st); m.bootstrap()
        assertEquals(AuthStateMachine.EXPIRED, (m.state.value as AuthState.LoggedOut).error)
        assertNull(st.s); assertNull(m.accessToken)
    }

    @Test fun oldServer404FallsBackToUserIdMode() = runTest {
        val b = FakeAuthBackend().apply { status = ApiResult.NotSupported }
        val m = sm(b, MemStore()); m.bootstrap()
        assertEquals(AuthStateMachine.NOT_SUPPORTED, (m.state.value as AuthState.UserIdMode).notice)
        assertTrue(m.state.value.canUseApp)
        val b2 = FakeAuthBackend().apply { me = ApiResult.NotSupported }
        val st = MemStore(sess(1)); val m2 = sm(b2, st); m2.bootstrap()
        assertTrue(m2.state.value is AuthState.UserIdMode); assertNull(st.s)
    }

    @Test fun networkErrorKeepsSessionOffline() = runTest {
        val b = FakeAuthBackend().apply { me = ApiResult.Network("timeout") }
        val m = sm(b, MemStore(sess(1))); m.bootstrap()
        val s = m.state.value as AuthState.LoggedIn
        assertTrue(s.offline); assertEquals("acc1", m.accessToken)
    }

    @Test fun loginRegisterLogout() = runTest {
        val b = FakeAuthBackend(); val st = MemStore(); val m = sm(b, st); m.bootstrap()
        assertFalse(m.login("ab", "pw"))                      // 用户名太短：不发请求
        assertTrue((m.state.value as AuthState.LoggedOut).error!!.contains("3–32"))
        b.login = ApiResult.Unauthorized("用户名或密码错误")
        assertFalse(m.login("jason", "wrong"))
        assertEquals("用户名或密码错误", (m.state.value as AuthState.LoggedOut).error)
        b.login = ApiResult.Ok(sess(1))
        assertFalse("密码少于 min_password", m.register("jason", "123", null, null))
        assertTrue(m.register("jason", "secret1", "Jason", "jason"))
        assertEquals(listOf("jason", "secret1", "Jason", "jason"), b.lastRegister)
        assertEquals("acc1", st.s!!.accessToken)
        m.logout()
        assertTrue(m.state.value is AuthState.LoggedOut); assertNull(st.s); assertEquals(1, b.logoutCalls)
    }

    @Test fun rateLimitShowsRetryAfter() = runTest {
        val b = FakeAuthBackend(); val m = sm(b, MemStore()); m.bootstrap()
        b.login = ApiResult.HttpError(429, "尝试太频繁", 30)
        assertFalse(m.login("jason", "pw"))
        assertTrue((m.state.value as AuthState.LoggedOut).error!!.contains("30 秒"))
    }

    @Test fun concurrentUnauthorizedRefreshesOnce() = runTest {
        val b = FakeAuthBackend().apply { refreshGate = CompletableDeferred() }
        val m = sm(b, MemStore(sess(1))); m.bootstrap()
        val calls = List(3) { async { m.onUnauthorized("acc1") } }
        testScheduler.runCurrent()
        b.refreshGate!!.complete(Unit)
        assertEquals(listOf(true, true, true), calls.awaitAll())
        assertEquals(1, b.refreshCalls)
        assertEquals("acc2", m.accessToken)
    }

    @Test fun skipRefusedWhenRequired() = runTest {
        val b = FakeAuthBackend().apply { status = ApiResult.Ok(AuthServerStatus(true, required = true, allowRegister = false, minPassword = 6)) }
        val m = sm(b, MemStore()); m.bootstrap()
        assertTrue((m.state.value as AuthState.LoggedOut).required)
        assertFalse(m.skipLogin())
        assertFalse(skipped)

        val m2 = sm(FakeAuthBackend(), MemStore()); m2.bootstrap()
        assertTrue(m2.skipLogin()); assertTrue(skipped)
        assertTrue(m2.state.value is AuthState.UserIdMode)
        // 下次启动记住“跳过”
        val m3 = sm(FakeAuthBackend(), MemStore()); m3.bootstrap()
        assertTrue(m3.state.value is AuthState.UserIdMode)
        m3.showLogin(); assertFalse(skipped); assertTrue(m3.state.value is AuthState.LoggedOut)
    }

    @Test fun usernameValidationMatchesServer() {
        assertTrue(AuthStateMachine.isValidUsername("jason.z@x-1_"))
        assertFalse(AuthStateMachine.isValidUsername("ab"))
        assertFalse(AuthStateMachine.isValidUsername("a".repeat(33)))
        assertFalse(AuthStateMachine.isValidUsername("张三abc"))
        assertFalse(AuthStateMachine.isValidUsername("a b c"))
    }

    @Test fun ensureFreshRefreshesExpiringToken() = runTest {
        val b = FakeAuthBackend()
        val m = AuthStateMachine(b, MemStore(sess(1).copy(accessExpiresAtMs = 1500)), now = { 1000L })
        m.bootstrap()
        assertTrue(m.ensureFresh())
        assertEquals("acc2", m.accessToken)
    }

    @Test fun changePasswordClearsSessionOnSuccess() = runTest {
        val b = FakeAuthBackend(); val st = MemStore(sess(1)); val m = sm(b, st); m.bootstrap()
        assertNull(m.changePassword("oldpass", "newpass1"))
        assertEquals(listOf("acc1", "oldpass", "newpass1"), b.lastChange)
        assertNull(st.s); assertTrue(m.state.value is AuthState.LoggedOut)
        assertTrue((m.state.value as AuthState.LoggedOut).error!!.contains("新密码"))
    }

    @Test fun changePasswordKeepsSessionOnWrongOld() = runTest {
        val b = FakeAuthBackend().apply { changePasswordResult = ApiResult.Unauthorized("原密码错误") }
        val st = MemStore(sess(1)); val m = sm(b, st); m.bootstrap()
        assertEquals("原密码错误", m.changePassword("bad", "newpass1"))
        assertTrue(m.state.value is AuthState.LoggedIn); assertEquals("acc1", st.s!!.accessToken)
    }

    @Test fun logoutEverywherePassesFlag() = runTest {
        val b = FakeAuthBackend(); val st = MemStore(sess(1)); val m = sm(b, st); m.bootstrap()
        m.logout(everywhere = true)
        assertTrue(b.lastEverywhere); assertEquals(1, b.logoutCalls)
        assertNull(st.s); assertTrue(m.state.value is AuthState.LoggedOut)
    }

    @Test fun deleteAccountClearsSession() = runTest {
        val b = FakeAuthBackend(); val st = MemStore(sess(1)); val m = sm(b, st); m.bootstrap()
        assertNull(m.deleteAccount("secret1"))
        assertEquals("secret1", b.lastDeletePassword)
        assertNull(st.s); assertTrue((m.state.value as AuthState.LoggedOut).error!!.contains("注销"))
    }
}
