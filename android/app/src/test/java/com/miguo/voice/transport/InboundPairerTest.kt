package com.miguo.voice.transport

import com.miguo.voice.protocol.AudioPayloadFormat
import com.miguo.voice.protocol.ServerEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class InboundPairerTest {
    private fun hdr(seq: Int) = """{"type":"audio","turn_id":1,"seq":$seq,"format":"mp3","sample_rate":24000,"bytes":3}"""

    @Test fun headerThenBinary() {
        val p = InboundPairer()
        assertTrue(p.onText(hdr(0)).isEmpty())
        val a = p.onBinary(byteArrayOf(1, 2, 3)) as Inbound.Audio
        assertEquals(0, a.header.seq)
        assertEquals(AudioPayloadFormat.Mp3, a.header.format)
        assertArrayEquals(byteArrayOf(1, 2, 3), a.data)
        // 头已消费 → 再来一个二进制就是孤儿
        assertEquals(Inbound.OrphanBinary(1), p.onBinary(byteArrayOf(9)))
    }

    @Test fun interleavedEventsDoNotBreakPairing() {
        val p = InboundPairer()
        val ev = p.onText("""{"type":"reply_text","turn_id":1,"seq":0,"text":"你好"}""")
        assertTrue((ev.single() as Inbound.Event).event is ServerEvent.ReplyTextEv)
        p.onText(hdr(1))
        assertEquals(1, (p.onBinary(byteArrayOf(0)) as Inbound.Audio).header.seq)
    }

    @Test fun headerWithoutBinaryReportsOrphan() {
        val p = InboundPairer()
        p.onText(hdr(0))
        val out = p.onText(hdr(1))
        assertTrue(out.single() is Inbound.OrphanBinary)
        assertEquals(1, (p.onBinary(byteArrayOf(0)) as Inbound.Audio).header.seq)
    }

    @Test fun base64Mode() {
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(5, 6))
        val out = InboundPairer().onText("""{"type":"audio","turn_id":2,"seq":0,"format":"pcm_s16le","sample_rate":24000,"data":"$b64"}""")
        val a = out.single() as Inbound.Audio
        assertArrayEquals(byteArrayOf(5, 6), a.data)
    }

    @Test fun garbageText() {
        assertTrue(InboundPairer().onText("not json").single() is Inbound.Undecodable)
    }
}
