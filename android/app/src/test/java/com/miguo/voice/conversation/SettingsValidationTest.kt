package com.miguo.voice.conversation

import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.ReadableError
import com.miguo.voice.protocol.ServerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** fix1：会导致 OkHttp 抛 IllegalArgumentException 的设置，必须在发请求前变成可读错误 */
class SettingsValidationTest {
    private fun code(f: () -> Unit): String? = try { f(); null } catch (e: ReadableError) { e.code }

    @Test fun okSettings() {
        val p = AppSettings(address = "192.168.10.104:8765", userId = " jason ").makeParams("  key123\n", ClientConfig())
        assertEquals("jason", p.userId)
        assertEquals("key123", p.apiKey)
        assertEquals("ws://192.168.10.104:8765/api/v1/voice/session", p.endpoint.sessionUrl)
    }

    @Test fun fullWidthApiKeyIsNormalized() {
        val p = AppSettings(userId = "jason").makeParams("ａｂｃ１２３", ClientConfig())
        assertEquals("abc123", p.apiKey)
    }

    @Test fun nonAsciiApiKeyRejected() {
        assertEquals("invalid_api_key", code { AppSettings(userId = "jason").makeParams("密钥abc", ClientConfig()) })
        assertEquals("invalid_api_key", code { AppSettings(userId = "jason").makeParams("ab cd", ClientConfig()) })
    }

    @Test fun nonAsciiUserIdRejected() {
        assertEquals("invalid_user_id", code { AppSettings(userId = "米果").makeParams(null, ClientConfig()) })
        assertEquals("invalid_user_id", code { AppSettings(userId = "").makeParams(null, ClientConfig()) })
        assertEquals("invalid_user_id", code { AppSettings(userId = "a b").makeParams(null, ClientConfig()) })
    }

    @Test fun badAddressRejected() {
        assertEquals("bad_address", code { AppSettings(address = "").makeParams(null, ClientConfig()) })
        assertEquals("bad_address", code { AppSettings(address = "ftp://x").makeParams(null, ClientConfig()) })
        assertEquals("bad_address", code { AppSettings(address = "http://").makeParams(null, ClientConfig()) })
    }

    @Test fun fullWidthAddressNormalized() {
        assertEquals("http://192.168.10.104:8765", ServerEndpoint.parse("１９２．１６８．１０．１０４：８７６５").httpBase)
    }

    @Test fun headersNeverContainUnsafeValuesAfterValidation() {
        val p = AppSettings(userId = "jason").makeParams(" k\u200B ", ClientConfig())
        val h = ServerEndpoint.headers(p.userId, p.apiKey)
        assertEquals("k", h["X-API-Key"])
        assertNull(code { validateIdentity("jason", null) })
        try { validateIdentity("jason", "😀"); fail() } catch (_: ReadableError) {}
    }
}
