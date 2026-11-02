package com.miguo.voice.protocol

/**
 * MP3 流式分帧器。服务端（edge-tts）下发的 mp3 块与 MP3 帧边界【不对齐】：一个块可能以半帧开头/结尾。
 * MediaCodec(audio/mpeg) 每个输入 buffer 最好是完整帧，所以这里：
 *  1. 累积字节；跳过 ID3v2 标签；
 *  2. 在 0xFFE 同步字上对齐，解析帧头（版本/层/码率/采样率/padding）算出帧长；
 *  3. 帧数据凑齐才输出；如果后面还有数据，要求紧随其后的也是合法帧头（防止误同步），否则右移 1 字节重新找同步。
 * 纯 Kotlin，无 Android 依赖，可在 JVM 单元测试里跑。
 */
class Mp3FrameSplitter {

    data class FrameHeader(
        val version: Int,          // 1 = MPEG1, 2 = MPEG2, 25 = MPEG2.5
        val layer: Int,            // 1/2/3
        val bitrateKbps: Int,
        val sampleRate: Int,
        val padding: Boolean,
        val channels: Int,
        val frameLength: Int,
        val samplesPerFrame: Int,
    )

    data class Frame(val bytes: ByteArray, val header: FrameHeader)

    private var buf = ByteArray(16 * 1024)
    private var len = 0

    var framesOut = 0
        private set
    var bytesSkipped = 0
        private set

    val pendingBytes: Int get() = len

    fun reset() {
        len = 0
    }

    /** 喂入一个下行块，返回其中（以及之前残留的）所有完整帧。 */
    fun feed(data: ByteArray, off: Int = 0, n: Int = data.size): List<Frame> {
        ensureCapacity(len + n)
        System.arraycopy(data, off, buf, len, n)
        len += n
        return drain(final = false)
    }

    /** 一句结束：把最后一个完整帧冲出来（不再要求后面跟着同步字），丢弃不完整残留。 */
    fun flush(): List<Frame> {
        val out = drain(final = true)
        if (len > 0) bytesSkipped += len
        len = 0
        return out
    }

    private fun drain(final: Boolean): List<Frame> {
        val out = ArrayList<Frame>()
        var pos = 0
        while (true) {
            // ID3v2: "ID3" + ver(2) + flags(1) + size(4, syncsafe)
            if (len - pos >= 3 && buf[pos] == 'I'.code.toByte() && buf[pos + 1] == 'D'.code.toByte() && buf[pos + 2] == '3'.code.toByte()) {
                if (len - pos < 10) break
                val sz = ((buf[pos + 6].toInt() and 0x7f) shl 21) or ((buf[pos + 7].toInt() and 0x7f) shl 14) or
                    ((buf[pos + 8].toInt() and 0x7f) shl 7) or (buf[pos + 9].toInt() and 0x7f)
                val total = 10 + sz + if ((buf[pos + 5].toInt() and 0x10) != 0) 10 else 0
                if (len - pos < total) break
                pos += total
                bytesSkipped += total
                continue
            }
            if (len - pos < 4) break
            val h = parseHeader(buf, pos)
            if (h == null) {
                pos++
                bytesSkipped++
                continue
            }
            if (len - pos < h.frameLength) break       // 等更多数据
            val next = pos + h.frameLength
            if (len - next >= 4) {
                // 校验下一帧头（防止数据里偶然出现 0xFFE 误同步）
                val nh = parseHeader(buf, next)
                if (nh == null && !isId3At(next)) {
                    pos++
                    bytesSkipped++
                    continue
                }
            } else if (!final && len - next > 0) {
                // 后面有不足 4 字节的数据，尚无法校验：等下一块
                break
            }
            out.add(Frame(buf.copyOfRange(pos, next), h))
            framesOut++
            pos = next
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
        return out
    }

    private fun isId3At(p: Int) = len - p >= 3 && buf[p] == 'I'.code.toByte() && buf[p + 1] == 'D'.code.toByte() && buf[p + 2] == '3'.code.toByte()

    private fun ensureCapacity(n: Int) {
        if (n <= buf.size) return
        var c = buf.size
        while (c < n) c *= 2
        buf = buf.copyOf(c)
    }

    companion object {
        private val BITRATE_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1)
        private val BITRATE_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, -1)
        private val BITRATE_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, -1)
        private val BITRATE_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, -1)
        private val BITRATE_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1)
        private val SR_V1 = intArrayOf(44100, 48000, 32000)

        /** 解析 4 字节 MPEG 音频帧头；不合法（或 free-format 码率）返回 null。 */
        fun parseHeader(b: ByteArray, p: Int): FrameHeader? {
            if (p + 4 > b.size) return null
            val b0 = b[p].toInt() and 0xff
            val b1 = b[p + 1].toInt() and 0xff
            val b2 = b[p + 2].toInt() and 0xff
            val b3 = b[p + 3].toInt() and 0xff
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
            val verBits = (b1 shr 3) and 0x3      // 00=2.5 01=reserved 10=2 11=1
            val layerBits = (b1 shr 1) and 0x3    // 01=III 10=II 11=I
            if (verBits == 1 || layerBits == 0) return null
            val version = when (verBits) { 3 -> 1; 2 -> 2; else -> 25 }
            val layer = 4 - layerBits
            val brIdx = (b2 shr 4) and 0xf
            val srIdx = (b2 shr 2) and 0x3
            if (brIdx == 0 || brIdx == 15 || srIdx == 3) return null
            val padding = ((b2 shr 1) and 1) == 1
            val chMode = (b3 shr 6) and 0x3
            val bitrate = when {
                version == 1 && layer == 3 -> BITRATE_V1_L3[brIdx]
                version == 1 && layer == 2 -> BITRATE_V1_L2[brIdx]
                version == 1 -> BITRATE_V1_L1[brIdx]
                layer == 1 -> BITRATE_V2_L1[brIdx]
                else -> BITRATE_V2_L23[brIdx]
            }
            val sr = SR_V1[srIdx] / when (version) { 1 -> 1; 2 -> 2; else -> 4 }
            val samples = when {
                layer == 1 -> 384
                layer == 2 -> 1152
                version == 1 -> 1152
                else -> 576
            }
            val pad = if (padding) (if (layer == 1) 4 else 1) else 0
            val frameLen = if (layer == 1) {
                (12 * bitrate * 1000 / sr + (if (padding) 1 else 0)) * 4
            } else {
                samples / 8 * bitrate * 1000 / sr + pad
            }
            if (frameLen < 4) return null
            return FrameHeader(version, layer, bitrate, sr, padding, if (chMode == 3) 1 else 2, frameLen, samples)
        }
    }
}
