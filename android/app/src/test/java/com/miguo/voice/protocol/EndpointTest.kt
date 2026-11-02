package com.miguo.voice.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointTest {

    @Test fun defaultChatAddressIsMemoryCore() {
        assertEquals("https://ai.njzyb.com:3010", ServerEndpoint.DEFAULT_ADDRESS)
        val e = ServerEndpoint.parse(ServerEndpoint.DEFAULT_ADDRESS)
        assertEquals("https://ai.njzyb.com:3010", e.httpBase)
        assertEquals("wss://ai.njzyb.com:3010", e.wsBase)
        assertEquals("wss://ai.njzyb.com:3010/api/v1/voice/session", e.sessionUrl)
    }

    @Test fun defaultAuthAddressIsNanoRelay() {
        assertEquals("https://ai.njzyb.com:3010/nm", ServerEndpoint.DEFAULT_AUTH_ADDRESS)
        val e = ServerEndpoint.parse(ServerEndpoint.DEFAULT_AUTH_ADDRESS)
        assertTrue(ServerEndpoint.looksLikeNanoRelay(e))
        assertEquals("https://ai.njzyb.com:3010/nm/healthz", e.authHealthUrl())
    }

    @Test fun plainHostPort() {
        val e = ServerEndpoint.parse("192.168.10.104:8765")
        assertEquals("http://192.168.10.104:8765", e.httpBase)
        assertEquals("ws://192.168.10.104:8765/api/v1/voice/session", e.sessionUrl)
        assertFalse(ServerEndpoint.looksLikeNanoRelay(e))
        assertEquals("http://192.168.10.104:8765/api/v1/health", e.authHealthUrl())
    }

    @Test fun fullWidthAndSpaces() {
        val raw = " ｈｔｔｐ：／／１９２。１６８．10.104：8765 \u200B"
        assertEquals("http://192.168.10.104:8765", ServerEndpoint.normalizeInput(raw))
        assertEquals("http://192.168.10.104:8765", ServerEndpoint.parse(raw).httpBase)
    }

    @Test fun stripsApiSuffixAndSchemes() {
        assertEquals("http://h:8765", ServerEndpoint.parse("http://h:8765/api/v1/voice/session").httpBase)
        assertEquals("https://h/proxy", ServerEndpoint.parse("wss://h/proxy/").httpBase)
        assertEquals("wss://h/proxy/api/v1/voice/session", ServerEndpoint.parse("https://h/proxy/api/v1").sessionUrl)
        assertEquals("http://h:8765/api/v1/voice/health?load_stt=true&probe_tts=true",
            ServerEndpoint.parse("h:8765").voiceHealthUrl(loadStt = true, probeTts = true))
    }

    @Test(expected = ServerEndpoint.ParseError::class) fun empty() { ServerEndpoint.parse("   ") }

    @Test(expected = ServerEndpoint.ParseError::class) fun badScheme() { ServerEndpoint.parse("ftp://h") }

    @Test fun loopbackAndHeaders() {
        assertTrue(ServerEndpoint.parse("127.0.0.1:8765").isLoopback)
        assertFalse(ServerEndpoint.parse("192.168.10.104:8765").isLoopback)
        assertEquals(mapOf("X-User-Id" to "jason"), ServerEndpoint.headers("jason", "  "))
        assertEquals("k", ServerEndpoint.headers("jason", " k ")["X-API-Key"])
    }
}
