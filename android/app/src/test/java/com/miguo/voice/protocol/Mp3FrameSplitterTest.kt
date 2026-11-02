package com.miguo.voice.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3FrameSplitterTest {
    /** MPEG2 Layer3, 48 kbps, 24 kHz, mono（edge-tts 24khz-48kbitrate-mono-mp3）→ 帧长 72*48000/24000 = 144 B */
    private fun frame(tag: Int): ByteArray {
        val b = ByteArray(144) { tag.toByte() }
        b[0] = 0xFF.toByte(); b[1] = 0xF3.toByte(); b[2] = 0x64; b[3] = 0xC0.toByte()
        return b
    }

    @Test fun header() {
        val h = Mp3FrameSplitter.parseHeader(frame(1), 0)
        assertNotNull(h)
        h!!
        assertEquals(2, h.version); assertEquals(3, h.layer)
        assertEquals(48, h.bitrateKbps); assertEquals(24000, h.sampleRate)
        assertEquals(1, h.channels); assertEquals(144, h.frameLength); assertEquals(576, h.samplesPerFrame)
    }

    @Test fun unalignedChunks() {
        val stream = (1..10).map { frame(it) }.reduce { a, b -> a + b }
        val s = Mp3FrameSplitter()
        val out = ArrayList<Mp3FrameSplitter.Frame>()
        // 故意切成与帧边界不对齐的块：100, 37, 250, 1, ...
        var p = 0
        val sizes = intArrayOf(100, 37, 250, 1, 300, 52, 700)
        for (n in sizes) {
            val k = minOf(n, stream.size - p)
            if (k <= 0) break
            out += s.feed(stream.copyOfRange(p, p + k))
            p += k
        }
        out += s.flush()
        assertEquals(10, out.size)
        out.forEachIndexed { i, f ->
            assertEquals(144, f.bytes.size)
            assertEquals((i + 1).toByte(), f.bytes[10])
        }
        assertEquals(0, s.pendingBytes)
    }

    @Test fun lastFrameWaitsForFlush() {
        val s = Mp3FrameSplitter()
        // 一帧完整到达，但后面还不知道是什么 → 不急着输出（防误同步），flush 时输出
        val first = s.feed(frame(1))
        val total = first.size + s.flush().size
        assertEquals(1, total)
    }

    @Test fun skipsId3AndGarbage() {
        val id3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 10) + ByteArray(10)
        val garbage = byteArrayOf(0x00, 0xFF.toByte(), 0x12, 0x34, 0xFF.toByte())
        val data = id3 + garbage + frame(1) + frame(2) + frame(3)
        val s = Mp3FrameSplitter()
        val out = s.feed(data) + s.flush()
        assertEquals(3, out.size)
        assertTrue(s.bytesSkipped >= garbage.size)
        assertEquals(3.toByte(), out[2].bytes[10])
    }

    @Test fun truncatedTailDropped() {
        val s = Mp3FrameSplitter()
        val out = s.feed(frame(1) + frame(2).copyOf(60)) + s.flush()
        assertEquals(1, out.size)
    }
}
