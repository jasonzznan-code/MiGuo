package com.miguo.voice.audio

import android.media.MediaCodec
import android.media.MediaFormat
import com.miguo.voice.protocol.Mp3FrameSplitter

/**
 * mp3 → PCM s16le 流式解码（对应 iOS MP3StreamDecoder.swift，用 MediaCodec "audio/mpeg" 同步模式）。
 * 输入必须是 [Mp3FrameSplitter] 切好的完整帧；每句（turn, seq）是独立 mp3 流，但帧可以连续喂给同一个解码器。
 * response_end 时调用 [endOfStream] 把解码器里剩余的帧冲出来（之后 flush 复用）。
 * 只在播放工作线程上调用。
 */
class Mp3StreamDecoder(
    private val onPcm: (pcm: ByteArray, sampleRate: Int) -> Unit,
    private val log: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var codecRate = 0
    private var codecChannels = 0
    private var outRate = 24_000
    private var outChannels = 1
    private var ptsUs = 0L
    private val info = MediaCodec.BufferInfo()
    var framesDecoded = 0
        private set

    fun feed(frames: List<Mp3FrameSplitter.Frame>) {
        for (f in frames) {
            val c = ensureCodec(f.header) ?: return
            var idx = -1
            var tries = 0
            while (idx < 0 && tries < 50) {
                idx = c.dequeueInputBuffer(10_000)
                if (idx < 0) drain(c, 0)
                tries++
            }
            if (idx < 0) { log("mp3 解码器输入缓冲区一直不可用，丢弃 1 帧"); continue }
            val buf = c.getInputBuffer(idx) ?: continue
            buf.clear()
            buf.put(f.bytes)
            c.queueInputBuffer(idx, 0, f.bytes.size, ptsUs, 0)
            ptsUs += f.header.samplesPerFrame * 1_000_000L / f.header.sampleRate
            framesDecoded++
            drain(c, 0)
        }
    }

    /** 一轮结束：送 EOS，把剩余输出全部取出，然后 flush 以便下一轮复用 */
    fun endOfStream() {
        val c = codec ?: return
        try {
            var idx = -1
            var tries = 0
            while (idx < 0 && tries < 20) { idx = c.dequeueInputBuffer(10_000); if (idx < 0) drain(c, 0); tries++ }
            if (idx >= 0) {
                c.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val deadline = System.nanoTime() + 300_000_000L
                while (System.nanoTime() < deadline) {
                    if (drain(c, 10_000)) break
                }
            }
            c.flush()
        } catch (e: Exception) {
            log("mp3 解码器收尾失败：$e，重建")
            release()
        }
    }

    /** flush（barge_in / 清空队列）：丢弃解码器内部状态 */
    fun reset() {
        try { codec?.flush() } catch (e: Exception) { release() }
    }

    fun release() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }

    private fun ensureCodec(h: Mp3FrameSplitter.FrameHeader): MediaCodec? {
        codec?.let { if (codecRate == h.sampleRate && codecChannels == h.channels) return it }
        release()
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, h.sampleRate, h.channels)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c
            codecRate = h.sampleRate
            codecChannels = h.channels
            outRate = h.sampleRate
            outChannels = h.channels
            log("mp3 解码器：${c.name} ${h.sampleRate} Hz × ${h.channels} ch, MPEG${if (h.version == 25) "2.5" else h.version.toString()} L${h.layer} ${h.bitrateKbps} kbps")
            c
        } catch (e: Exception) {
            log("✗ 无法创建 mp3 解码器：$e")
            null
        }
    }

    /** 取出所有可用输出；返回是否遇到 EOS */
    private fun drain(c: MediaCodec, timeoutUs: Long): Boolean {
        while (true) {
            val idx = c.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                idx >= 0 -> {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(idx)
                        if (ob != null) {
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            ob.get(bytes)
                            onPcm(if (outChannels == 2) downmix(bytes) else bytes, outRate)
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (eos) return true
                }
                else -> return false
            }
        }
    }

    private fun downmix(st: ByteArray): ByteArray {
        val n = st.size / 4
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val l = ((st[4 * i].toInt() and 0xff) or (st[4 * i + 1].toInt() shl 8)).toShort().toInt()
            val r = ((st[4 * i + 2].toInt() and 0xff) or (st[4 * i + 3].toInt() shl 8)).toShort().toInt()
            val m = (l + r) / 2
            out[2 * i] = (m and 0xff).toByte()
            out[2 * i + 1] = ((m shr 8) and 0xff).toByte()
        }
        return out
    }
}
