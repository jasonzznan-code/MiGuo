package com.miguo.voice.location

import com.miguo.voice.conversation.AppSettings
import com.miguo.voice.conversation.makeParams
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.ClientControl
import com.miguo.voice.protocol.StartMessage
import com.miguo.voice.protocol.TextChatRequest
import com.miguo.voice.protocol.WireLocation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.2.0：位置发给服务端（修“你在北京朝阳区”） */
class LocationWireTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private val zjk = WireLocation(40.82441234567, 114.88751234567, 12.34f, "gps", 1_761_450_000_000)
    private val now = 1_761_450_000_000L

    @Test fun startMessageCarriesLocation() {
        val j = obj(StartMessage(sessionId = "c1", location = zjk).encode())
        val l = j["location"]!!.jsonObject
        assertEquals(40.824412, l["lat"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(114.887512, l["lon"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(12.3, l["accuracy_m"]!!.jsonPrimitive.double, 1e-9)
        assertEquals("gps", l["provider"]!!.jsonPrimitive.content)
        assertEquals(1_761_450_000_000, l["ts"]!!.jsonPrimitive.long)
        assertFalse("toString 不打印坐标", StartMessage(sessionId = "c1", location = zjk).toString().contains("40.82"))
    }

    @Test fun startMessageWithoutLocation() {
        assertNull(obj(StartMessage(sessionId = "c1").encode())["location"])
        assertNull("非法坐标不发", obj(StartMessage(sessionId = "c1", location = WireLocation(0.0, 0.0)).encode())["location"])
        assertNull(obj(StartMessage(sessionId = "c1", location = WireLocation(91.0, 10.0)).encode())["location"])
        assertNull(obj(StartMessage(sessionId = "c1", location = WireLocation(Double.NaN, 10.0)).encode())["location"])
    }

    @Test fun controlLocation() {
        val j = obj(ClientControl.Location(zjk.copy(accuracyM = null, tsMs = 0)).encode())
        assertEquals("location", j["type"]!!.jsonPrimitive.content)
        assertEquals(40.824412, j["lat"]!!.jsonPrimitive.double, 1e-9)
        assertNull("没有精度不发 accuracy_m", j["accuracy_m"]); assertNull(j["ts"])
    }

    @Test fun providerSanitized() {
        assertEquals("fused", WireLocation.cleanProvider("fused"))
        assertEquals("gpsx", WireLocation.cleanProvider("g p s\u0000x"))
        assertEquals("unknown", WireLocation.cleanProvider("定位"))
        assertEquals(32, WireLocation.cleanProvider("a".repeat(100)).length)
    }

    @Test fun textChatCarriesLocation() {
        val j = obj(TextChatRequest("我在哪", "c1", location = zjk).encode())
        assertEquals(114.887512, j["location"]!!.jsonObject["lon"]!!.jsonPrimitive.double, 1e-9)
        assertNull(obj(TextChatRequest("我在哪", "c1").encode())["location"])
    }

    @Test fun makeParamsPassesLocation() {
        val s = AppSettings(address = "http://10.0.2.2:8811", userId = "jason")
        assertEquals(zjk, s.makeParams("k", ClientConfig(), sessionId = "c1", location = zjk).start.location)
        assertNull(s.makeParams("k", ClientConfig(), sessionId = "c1").start.location)
        assertNull(s.makeParams("k", ClientConfig(), sessionId = "c1", location = WireLocation(0.0, 0.0)).start.location)
    }

    @Test fun fixToWireAndStaleness() {
        val f = GeoFix(40.8244, 114.8875, 15f, "network", now - 5000, ageMs = 3000)
        val w = LocationWire.toWire(f, fixAtMs = now - 60_000, nowMs = now)!!
        assertEquals("ts = 当前 - (拿到后经过的时间 + 拿到时已有的年龄)", now - 63_000, w.tsMs)
        assertEquals("network", w.provider); assertEquals(15f, w.accuracyM)
        assertNull("超过 2 小时不发", LocationWire.toWire(f, now - LocationWire.MAX_SEND_AGE_MS - 1, now))
        assertNull(LocationWire.toWire(null, now, now))
        assertNull(LocationWire.toWire(f.copy(lat = 0.0, lon = 0.0), now, now))
        assertTrue(LocationWire.needsRefresh(null, 0, now))
        assertFalse(LocationWire.needsRefresh(f, now - 60_000, now))
        assertTrue(LocationWire.needsRefresh(f, now - LocationWire.REFRESH_AGE_MS, now))
        assertNotNull(LocationWire.toWire(f, now - LocationWire.REFRESH_AGE_MS, now))
    }
}
