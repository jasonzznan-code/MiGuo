package com.miguo.voice.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.2.0：对话续接 / 打断 / 文字 / 登录相关协议 */
class V2ProtocolTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun readyConversation() {
        val r = (ServerEventDecoder.decode("""{"type":"ready","session_id":"c1","audio_out":{"format":"mp3"},
            "conversation":{"id":"c1","resumed":true,"turns":3,"persistent":true,
              "history":[{"role":"user","content":"a"},{"role":"assistant","content":"b"},{"bad":1},{"role":"user","text":"c"}]}}""")
            as ServerEvent.ReadyEv).ready
        val c = r.conversation!!
        assertEquals("c1", c.id); assertTrue(c.resumed); assertEquals(3, c.turns); assertEquals(true, c.persistent)
        assertEquals(listOf("a", "b", "c"), c.history.map { it.content })
        // 旧服务器：没有 conversation
        assertNull((ServerEventDecoder.decode("""{"type":"ready","session_id":"s","audio_out":{"format":"mp3"}}""") as ServerEvent.ReadyEv).ready.conversation)
    }

    @Test fun bargeInReason() {
        val b = (ServerEventDecoder.decode("""{"type":"barge_in","turn_id":4,"reason":"client_interrupt","active":false}""") as ServerEvent.BargeInEv).b
        assertEquals(4, b.turnId); assertEquals("client_interrupt", b.reason); assertEquals(false, b.active)
        val old = (ServerEventDecoder.decode("""{"type":"barge_in","turn_id":1,"message":"x"}""") as ServerEvent.BargeInEv).b
        assertNull(old.reason); assertNull(old.active)
    }

    @Test fun startMessageV2Fields() {
        val fixed = obj(StartMessage(sessionId = "conv-1", token = "secret-token", bargeIn = true).encode())
        assertEquals("conv-1", fixed["session_id"]!!.jsonPrimitive.content)
        assertEquals("conv-1", fixed["conversation_id"]!!.jsonPrimitive.content)
        assertEquals("secret-token", fixed["token"]!!.jsonPrimitive.content)
        assertEquals("android", fixed["client"]!!.jsonPrimitive.content)
        assertFalse(fixed.containsKey("resume"))
        assertFalse("toString 不泄露 token", StartMessage(sessionId = "x", token = "secret-token").toString().contains("secret-token"))
        val resume = obj(StartMessage(sessionId = null, resume = true).encode())
        assertFalse(resume.containsKey("session_id")); assertFalse(resume.containsKey("token"))
        assertEquals("true", resume["resume"]!!.jsonPrimitive.content)
    }

    @Test fun bearerHeaders() {
        val h = ServerEndpoint.headers(null, "k", "tok")
        assertEquals("Bearer tok", h["Authorization"]); assertEquals("k", h["X-API-Key"]); assertFalse(h.containsKey("X-User-Id"))
        val legacy = ServerEndpoint.headers("jason", "k")
        assertEquals("jason", legacy["X-User-Id"]); assertFalse(legacy.containsKey("Authorization"))
    }

    @Test fun ndjsonParser() {
        assertEquals(TextChatEvent.Start("c1"), TextChatProtocol.parseLine("""{"type":"start","conversation_id":"c1"}"""))
        assertEquals(TextChatEvent.Reply(2, "你好"), TextChatProtocol.parseLine("""{"type":"reply_text","seq":2,"text":"你好"}"""))
        val a = TextChatProtocol.parseLine("""{"type":"audio","seq":0,"data":"AQID"}""") as TextChatEvent.Audio
        assertEquals(listOf<Byte>(1, 2, 3), a.mp3.toList())
        assertNull(TextChatProtocol.parseLine("""{"type":"audio","data":"@@@"}"""))
        assertEquals(TextChatEvent.Done("c1", "完整", null), TextChatProtocol.parseLine("""{"type":"done","conversation_id":"c1","reply":"完整"}"""))
        assertEquals("busy", (TextChatProtocol.parseLine("""{"type":"error","code":"busy","message":"m"}""") as TextChatEvent.Error).code)
        assertNull(TextChatProtocol.parseLine(""))
        assertNull(TextChatProtocol.parseLine("not json"))
        assertNull(TextChatProtocol.parseLine("""{"type":"future_thing"}"""))
        assertEquals("c9", TextChatProtocol.parseLatest("""{"latest":"c9","conversations":[]}"""))
        assertEquals(2, TextChatProtocol.parseMessages("""{"messages":[{"role":"user","content":"a"},{"role":"assistant","content":"b"}]}""")!!.size)
        val req = obj(TextChatRequest("hi", null, resume = true, speak = true).encode())
        assertFalse(req.containsKey("conversation_id")); assertEquals("true", req["speak"]!!.jsonPrimitive.content)
    }

    @Test fun authSessionRoundTrip() {
        val o = AuthSession.parse("""{"user_id":"jason","username":"jason","display_name":"J","access_token":"a","refresh_token":"r",
            "expires_in":604800,"refresh_expires_at":"2027-01-01T00:00:00Z"}""")!!
        val s = AuthSession.fromTokenResponse(o, nowMs = 1000)!!
        assertEquals(1000 + 604_800_000L, s.accessExpiresAtMs)
        assertEquals(s, AuthSession.decode(s.encode()))
        assertFalse(s.toString().contains("\"a\"") || s.toString().contains("access_token"))
        assertNull(AuthSession.decode("garbage")); assertNull(AuthSession.decode(null))
        assertNull(AuthSession.fromTokenResponse(AuthSession.parse("""{"user_id":"x"}""")!!, 0))
        assertTrue(s.accessExpired(1000 + 604_800_000L - 1))
        assertFalse(s.accessExpired(1000))
        assertEquals("用户名或密码错误", AuthJson.detail("""{"detail":"用户名或密码错误"}""", 401))
    }

    @Test fun featuresParsed() {
        val c = ClientConfig().mergedWith(ClientConfig.parseJson(
            """{"config_version":3,"features":{"auth":true,"text_chat":true,"conversations":true,"resume_conversation":false}}"""))
        assertEquals(true, c.features.auth); assertEquals(true, c.features.textChat); assertEquals(false, c.features.resumeConversation)
        assertNull(ClientConfig().features.auth)
        assertEquals("登录已失效", ErrorCatalog.fromServer(ServerError("unauthorized", "bad", fatal = true)).title)
    }
}
