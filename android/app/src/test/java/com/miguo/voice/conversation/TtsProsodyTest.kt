package com.miguo.voice.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsProsodyTest {
    @Test fun formatPercentSigned() {
        assertEquals("+0%", TtsProsody.formatPercent(0))
        assertEquals("+10%", TtsProsody.formatPercent(10))
        assertEquals("-20%", TtsProsody.formatPercent(-20))
        assertEquals("+50%", TtsProsody.formatPercent(50))
        assertEquals("-50%", TtsProsody.formatPercent(-50))
    }

    @Test fun formatPercentClamps() {
        assertEquals("+50%", TtsProsody.formatPercent(99))
        assertEquals("-50%", TtsProsody.formatPercent(-100))
    }

    @Test fun formatPitchSigned() {
        assertEquals("+0Hz", TtsProsody.formatPitchHz(0))
        assertEquals("+5Hz", TtsProsody.formatPitchHz(5))
        assertEquals("-10Hz", TtsProsody.formatPitchHz(-10))
        assertEquals("+50Hz", TtsProsody.formatPitchHz(50))
        assertEquals("-50Hz", TtsProsody.formatPitchHz(-50))
    }

    @Test fun formatPitchClamps() {
        assertEquals("+50Hz", TtsProsody.formatPitchHz(80))
        assertEquals("-50Hz", TtsProsody.formatPitchHz(-80))
    }

    @Test fun clampHelpers() {
        assertEquals(50, TtsProsody.clampRate(999))
        assertEquals(-50, TtsProsody.clampVolume(-999))
        assertEquals(0, TtsProsody.clampPitch(0))
    }

    @Test fun matchesEdgeTtsRegexShape() {
        val rateRe = Regex("""^[+-]\d{1,3}%$""")
        val pitchRe = Regex("""^[+-]\d{1,3}Hz$""")
        for (v in listOf(-50, -1, 0, 1, 50)) {
            assertTrue(rateRe.matches(TtsProsody.formatPercent(v)))
            assertTrue(pitchRe.matches(TtsProsody.formatPitchHz(v)))
        }
    }

    @Test fun makeParamsCarriesProsody() {
        val p = AppSettings(
            userId = "jason",
            voice = "zh-CN-XiaoyiNeural",
            ttsRatePercent = 10,
            ttsPitchHz = -5,
            ttsVolumePercent = 20,
        ).makeParams(null, com.miguo.voice.protocol.ClientConfig())
        assertEquals("zh-CN-XiaoyiNeural", p.start.voice)
        assertEquals("+10%", p.start.rate)
        assertEquals("+20%", p.start.volume)
        assertEquals("-5Hz", p.start.pitch)
    }

    @Test fun makeParamsDefaultsProsody() {
        val p = AppSettings(userId = "jason").makeParams(null, com.miguo.voice.protocol.ClientConfig())
        assertEquals("+0%", p.start.rate)
        assertEquals("+0%", p.start.volume)
        assertEquals("+0Hz", p.start.pitch)
    }
}
