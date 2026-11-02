package com.miguo.voice.transport

import com.miguo.voice.protocol.ServerEvent
import com.miguo.voice.protocol.StartMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 用 MockWebServer 模拟 session.py：握手头、start 首包、音频头+二进制配对、关闭码。 */
class VoiceSocketTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    private fun url() = server.url("/api/v1/voice/session").toString().replaceFirst("http", "ws")

    @Test fun startHeaderAndPairing() = runBlocking {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
                if (text.contains("\"type\":\"start\"")) {
                    webSocket.send("""{"type":"ready","session_id":"s1","audio_out":{"format":"pcm_s16le","sample_rate":24000}}""")
                    webSocket.send("""{"type":"audio","turn_id":1,"seq":0,"format":"pcm_s16le","sample_rate":24000,"bytes":4}""")
                    webSocket.send(byteArrayOf(1, 2, 3, 4).toByteString())
                    webSocket.send("""{"type":"response_end","turn_id":1}""")
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { received.add("bin:${bytes.size}") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        server.start()
        val sock = VoiceSocket()
        val inbound = sock.connect(url(), mapOf("X-User-Id" to "jason", "X-API-Key" to "k"), StartMessage("android-t"), 5000)
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("jason", req.getHeader("X-User-Id"))
        assertEquals("k", req.getHeader("X-API-Key"))

        withTimeout(5000) {
            assertTrue((inbound.receive() as Inbound.Event).event is ServerEvent.ReadyEv)
            val a = inbound.receive() as Inbound.Audio
            assertEquals(1, a.header.turnId)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), a.data)
            assertTrue((inbound.receive() as Inbound.Event).event is ServerEvent.ResponseEndEv)
        }
        assertTrue(received.poll(5, TimeUnit.SECONDS)!!.contains("\"session_id\":\"android-t\""))
        assertTrue(sock.sendAudio(ByteArray(640)))
        assertEquals("bin:640", received.poll(5, TimeUnit.SECONDS))
        sock.close(1000)
        withTimeout(5000) {
            val c = inbound.receive() as Inbound.Closed
            assertEquals("正常关闭 1000 → error=null", null, c.error)
        }
    }

    @Test fun badKeyIs403() = runBlocking {
        // 服务端 4401 在握手阶段关闭 → 客户端看到 HTTP 403
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        try {
            VoiceSocket().connect(url(), mapOf("X-User-Id" to "jason"), StartMessage("s"), 5000)
            fail("应当抛出 HandshakeRejected")
        } catch (e: VoiceSocketError.HandshakeRejected) {
            assertEquals(403, e.status)
        }
    }

    @Test fun badUserErrorThenClose4400() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"error","code":"bad_user","message":"invalid user_id"}""")
                webSocket.close(4400, "bad_user")
            }
        }))
        server.start()
        val inbound = VoiceSocket().connect(url(), mapOf("X-User-Id" to "a b"), StartMessage("s"), 5000)
        withTimeout(5000) {
            val ev = (inbound.receive() as Inbound.Event).event as ServerEvent.ErrorEv
            assertEquals("bad_user", ev.e.code)
            val c = inbound.receive() as Inbound.Closed
            assertEquals(4400, (c.error as VoiceSocketError.Closed).code)
        }
    }
}
