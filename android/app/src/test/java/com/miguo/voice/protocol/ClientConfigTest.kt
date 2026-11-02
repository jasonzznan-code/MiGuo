package com.miguo.voice.protocol

import com.miguo.voice.conversation.AppSettings
import com.miguo.voice.conversation.VADPreset
import com.miguo.voice.conversation.makeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientConfigTest {
    private val sample = """{
      "config_version": 3,
      "min_client_version": {"android": "0.2.0", "ios": "1.0"},
      "update_url": {"android": "http://192.168.10.104:8765/static/miguo.apk"},
      "voice": {"output_format": "pcm_s16le", "sample_rate": 16000, "frame_ms": 40, "barge_in": true,
                "vad": {"threshold": 0.6, "min_silence_ms": 600}, "voice": "zh-CN-XiaoyiNeural",
                "use_memory": false, "resume_listen_delay_ms": 350, "future_knob": 1},
      "playback": {"watchdog_interval_ms": 250, "stall_timeout_s": 8, "status_log_interval_s": 3},
      "labels": {"listening": "在听", "brand_new_state": "新"},
      "errors": {"auth_failed": {"title": "钥匙不对", "fix": "去 .env 抄 MEMORY_API_KEY"}},
      "notice": "今晚 23 点维护",
      "unknown_top": {"a": [1, 2]}
    }"""

    @Test fun mergeOverDefaults() {
        val c = ClientConfig().mergedWith(ClientConfig.parseJson(sample))
        assertEquals(3, c.configVersion)
        assertEquals("0.2.0", c.minClientVersion)
        assertEquals("http://192.168.10.104:8765/static/miguo.apk", c.updateUrl)
        assertEquals("pcm_s16le", c.voice.outputFormat)
        assertEquals(40, c.voice.frameMs)
        assertEquals(600, c.voice.vad.minSilenceMs)
        assertEquals(250, c.voice.vad.minSpeechMs)         // 未给的子字段保持默认
        assertEquals(350, c.voice.resumeListenDelayMs)
        assertEquals(250L, c.playback.watchdogIntervalMs)
        assertEquals(8.0, c.playback.stallTimeoutS, 0.0)
        assertEquals("在听", c.label("listening"))
        assertEquals("米果在说", c.label("speaking"))        // 未覆盖的用内置
        assertEquals("今晚 23 点维护", c.notice)
        assertEquals("server", c.source)
        val e = c.apply(ReadableError("auth_failed", "API Key 不对", "m", "f"))
        assertEquals("钥匙不对", e.title)
        assertEquals("去 .env 抄 MEMORY_API_KEY", e.fix)
        assertEquals("m", e.message)
    }

    @Test fun outOfRangeIgnored() {
        val c = ClientConfig().mergedWith(ClientConfig.parseJson(
            """{"voice":{"sample_rate":12345,"frame_ms":0},"playback":{"watchdog_interval_ms":5}}"""))
        assertEquals(16000, c.voice.sampleRate)
        assertEquals(20, c.voice.frameMs)
        assertEquals(500L, c.playback.watchdogIntervalMs)
    }

    @Test fun plainStringPlatformFields() {
        val c = ClientConfig().mergedWith(ClientConfig.parseJson("""{"min_client_version":"1.0.0"}"""))
        assertTrue(c.needsUpdate("0.1.0-phase1"))
        assertFalse(c.needsUpdate("1.0.0"))
        assertFalse(ClientConfig().needsUpdate("0.0.1"))
    }

    @Test fun versions() {
        assertTrue(ClientConfig.compareVersions("0.1.0-phase1", "0.2.0") < 0)
        assertEquals(0, ClientConfig.compareVersions("1.2", "1.2.0"))
        assertTrue(ClientConfig.compareVersions("1.10.0", "1.9.9") > 0)
    }

    @Test fun userOverridesOnlyWhenExplicit() {
        val c = ClientConfig().mergedWith(ClientConfig.parseJson(sample))
        val follow = AppSettings().makeParams("k", c, "android-x")
        assertEquals(AudioPayloadFormat.PcmS16LE, follow.start.outputFormat)   // 跟随服务器
        assertEquals(40, follow.frameMs)
        assertFalse(follow.start.useMemory)
        assertEquals("zh-CN-XiaoyiNeural", follow.start.voice)
        assertTrue("v0.2.0：默认跟随服务端 barge_in=true", follow.start.bargeIn)
        assertFalse("用户显式关闭打断", AppSettings(bargeIn = false).makeParams("k", c, "android-x").start.bargeIn)

        val explicit = AppSettings(outputFormat = "mp3", vadPreset = VADPreset.FAST, frameMs = 20, useMemory = true)
            .makeParams("k", c, "android-x")
        assertEquals(AudioPayloadFormat.Mp3, explicit.start.outputFormat)
        assertEquals(500, explicit.start.vad.minSilenceMs)
        assertEquals(20, explicit.frameMs)
        assertTrue(explicit.start.useMemory)
    }

    @Test fun builtinWhenNoServer() {
        val p = AppSettings().makeParams(null, ClientConfig(), "android-x")
        assertEquals(AudioPayloadFormat.Mp3, p.start.outputFormat)
        assertNull(p.apiKey)
        assertEquals("wss://ai.njzyb.com:3010/api/v1/voice/session", p.endpoint.sessionUrl)
    }

    @Test(expected = ReadableError::class) fun badUserId() {
        AppSettings(userId = "a b").makeParams("k", ClientConfig())
    }
}
