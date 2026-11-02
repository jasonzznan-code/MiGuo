package com.miguo.voice.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientMessagesTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun startSnakeCase() {
        val o = obj(StartMessage(sessionId = "android-1", outputFormat = AudioPayloadFormat.PcmS16LE).encode())
        assertEquals("start", o["type"]!!.jsonPrimitive.content)
        assertEquals("android-1", o["session_id"]!!.jsonPrimitive.content)
        assertEquals(16000, o["sample_rate"]!!.jsonPrimitive.int)
        assertEquals("pcm_s16le", o["output_format"]!!.jsonPrimitive.content)
        assertEquals("binary", o["audio_transport"]!!.jsonPrimitive.content)
        assertFalse(o["barge_in"]!!.jsonPrimitive.boolean)
        assertTrue(o["use_memory"]!!.jsonPrimitive.boolean)
        assertEquals(700, o["vad"]!!.jsonObject["min_silence_ms"]!!.jsonPrimitive.int)
        assertFalse("voice=null 不发送", o.containsKey("voice"))
        assertTrue(obj(StartMessage("s", voice = "zh-CN-XiaoyiNeural").encode()).containsKey("voice"))
    }

    @Test fun controls() {
        assertEquals("""{"type":"stop","wait_for_reply":true}""", ClientControl.Stop(true).encode())
        assertEquals("""{"type":"interrupt"}""", ClientControl.Interrupt.encode())
        assertEquals("""{"type":"text","text":"你好"}""", ClientControl.Text("你好").encode())
        assertEquals("""{"type":"ping","t":123}""", ClientControl.Ping(123).encode())
    }

    @Test fun ids() {
        val id = SessionIdFactory.make()
        assertTrue(id, id.startsWith("android-"))
        assertTrue(SessionIdFactory.isValid(id))
        assertTrue(UserIdValidator.isValid("jason"))
        assertTrue(UserIdValidator.isValid("a.b-c_d@e"))
        assertTrue("与服务端 Python \\w 一致：中文合法", UserIdValidator.isValid("米果"))
        assertFalse(UserIdValidator.isValid("a b"))
        assertFalse(UserIdValidator.isValid(""))
        assertFalse(UserIdValidator.isValid("x".repeat(65)))
    }

    @Test fun framer() {
        val f = PCMFramer(640)
        assertEquals(0, f.append(ByteArray(600)).size)
        val out = f.append(ByteArray(700))
        assertEquals(2, out.size)
        assertTrue(out.all { it.size == 640 })
    }
}
