package com.miguo.voice.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthLoginMessagesTest {
    @Test
    fun mapLoginFailure_formalCodes() {
        assertEquals(
            AuthLoginMessages.SERVER_UNREACHABLE,
            AuthLoginMessages.mapLoginFailure(AuthLoginMessages.CODE_AUTH_UNREACHABLE, null),
        )
        assertEquals(
            AuthLoginMessages.CODE_NOT_RECEIVED,
            AuthLoginMessages.mapLoginFailure(AuthLoginMessages.CODE_AUTH_CODE_TIMEOUT, null),
        )
        assertEquals(
            AuthLoginMessages.CODE_NOT_RECEIVED,
            AuthLoginMessages.mapLoginFailure(AuthLoginMessages.CODE_AUTH_CODE_MISSING, null),
        )
        assertEquals(
            AuthLoginMessages.LOGIN_FAILED,
            AuthLoginMessages.mapLoginFailure(AuthLoginMessages.CODE_AUTH_VERIFY_FAILED, null),
        )
    }

    @Test
    fun mapLoginFailure_compatAliases() {
        assertEquals(AuthLoginMessages.SERVER_UNREACHABLE, AuthLoginMessages.mapLoginFailure("network", "x"))
        assertEquals(AuthLoginMessages.SERVER_UNREACHABLE, AuthLoginMessages.mapLoginFailure("server_unreachable", null))
    }

    @Test
    fun isUnreachable_detectsChinese() {
        assertTrue(AuthLoginMessages.isUnreachable("连不上服务器：timeout"))
        assertTrue(AuthLoginMessages.isUnreachable(AuthLoginMessages.SERVER_UNREACHABLE))
        assertFalse(AuthLoginMessages.isUnreachable("验证码错误"))
    }

    @Test
    fun loading_isNotErrorBubble() {
        assertNull(
            AuthLoginMessages.bubbleFor(
                code = null,
                rawMessage = null,
                loading = true,
            ),
        )
        assertTrue(AuthLoginMessages.isLoadingMessage(AuthLoginMessages.LOADING_LOGIN))
        // loading 时即使有文案也不应被当成正式错误码表项
        assertEquals(
            AuthLoginMessages.LOGIN_FAILED,
            AuthLoginMessages.mapLoginFailure(AuthLoginMessages.CODE_AUTH_VERIFY_FAILED, AuthLoginMessages.LOADING_LOGIN),
        )
    }

    @Test
    fun bubbleFor_mapsFormalCodesWhenNotLoading() {
        assertEquals(
            AuthLoginMessages.SERVER_UNREACHABLE,
            AuthLoginMessages.bubbleFor(AuthLoginMessages.CODE_AUTH_UNREACHABLE, null, loading = false),
        )
        assertEquals(
            AuthLoginMessages.CODE_NOT_RECEIVED,
            AuthLoginMessages.bubbleFor(AuthLoginMessages.CODE_AUTH_CODE_MISSING, null, loading = false),
        )
    }
}
