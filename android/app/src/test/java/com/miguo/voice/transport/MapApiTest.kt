package com.miguo.voice.transport

import com.miguo.voice.protocol.ReadableError
import com.miguo.voice.protocol.ServerEndpoint
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MapApiTest {
    private val zjk = """{"status":"ok","domain":"map","action":"reverse","data":{"lat":40.82,"lon":114.88,"region":"CN-HE","count":1,
        "results":[{"name":"张家口市幼儿园","lat":40.8198716,"lon":114.8798225,"city":"张家口市","province":"河北省","region":"CN-HE",
        "address":{"country":"中国","state":"河北省","city":"张家口市","district":"明德南街街道","street":"清水河北路"},
        "address_text":"河北省张家口市明德南街街道长青路社区清水河北路","distance_m":20.7}],"radius_km_used":null,"widened":false,"took_ms":12.0},
        "error":null,"api_version":"v1"}"""

    @Test fun urlHasLatThenLonByName() {
        val u = MapApi.reverseUrl(ServerEndpoint.parse("192.168.10.104:8765"), 40.82, 114.88)
        assertEquals("http://192.168.10.104:8765/api/v1/map/reverse?lat=40.82&lon=114.88&limit=1", u.toString())
    }

    @Test fun parseZhangjiakou() {
        val a = MapApi.parseReverse(200, zjk)
        assertEquals("CN-HE", a.region)
        assertEquals("张家口市", a.city)
        assertEquals("明德南街街道", a.district)
        assertTrue(a.display, a.display.startsWith("河北省张家口市") && a.display.contains("张家口市幼儿园"))
        assertEquals(false, a.widened)
    }

    @Test fun parseEmptyAndErrors() {
        val empty = MapApi.parseReverse(200, """{"status":"ok","data":{"count":0,"results":[],"region":null}}""")
        assertTrue(empty.display.contains("没有地址数据"))
        try {
            MapApi.parseReverse(503, """{"status":"error","data":null,"error":{"code":"PHOTON_UNAVAILABLE","message":"Photon 不可达"}}""")
            fail()
        } catch (e: ReadableError) { assertEquals("PHOTON_UNAVAILABLE", e.code) }
        try {
            MapApi.parseReverse(404, """<html>not found</html>""")
            fail()
        } catch (e: ReadableError) { assertEquals("map_missing", e.code) }
    }

    @Test fun reverseSendsHeadersAndCoords() = runBlocking {
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setBody(zjk).setHeader("Content-Type", "application/json; charset=utf-8"))
        srv.start()
        try {
            val ep = ServerEndpoint.parse("http://127.0.0.1:${srv.port}")
            val r = MapApi.reverse(ep, "jason", "k123", 40.82, 114.88, client = OkHttpClient())
            assertTrue(r.toString(), r is MapResult.Ok)
            val req = srv.takeRequest()
            assertEquals("/api/v1/map/reverse?lat=40.82&lon=114.88&limit=1", req.path)
            assertEquals("jason", req.getHeader("X-User-Id"))
            assertEquals("k123", req.getHeader("X-API-Key"))
            assertEquals("河北省张家口市明德南街街道长青路社区清水河北路（张家口市幼儿园）", (r as MapResult.Ok).addr.display)
        } finally {
            srv.shutdown()
        }
    }

    @Test fun reverseAuthFailure() = runBlocking {
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad key"}"""))
        srv.start()
        try {
            val r = MapApi.reverse(ServerEndpoint.parse("http://127.0.0.1:${srv.port}"), "jason", null, 40.82, 114.88, client = OkHttpClient())
            assertTrue(r is MapResult.Failed && r.e.code == "auth_failed")
        } finally {
            srv.shutdown()
        }
    }
}
