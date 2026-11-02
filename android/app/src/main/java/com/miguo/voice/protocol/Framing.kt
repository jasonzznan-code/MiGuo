package com.miguo.voice.protocol

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 上行分帧（对应 iOS Framing.swift）：16 kHz · 20 ms → 640 B/帧，40 ms → 1280 B/帧。
 * 服务端对帧长没有要求（自己凑 512 样本做 VAD），固定帧长只是为了均匀的发送节奏。
 */
class PCMFramer(val frameBytes: Int) {
    init {
        require(frameBytes > 0 && frameBytes % 2 == 0) { "frameBytes 必须是正偶数（s16le）" }
    }

    constructor(sampleRate: Int, frameMs: Int) : this(frameBytes(sampleRate, frameMs))

    private val acc = java.io.ByteArrayOutputStream(frameBytes * 4)

    val bufferedBytes: Int get() = acc.size()

    /** 追加 PCM，返回凑满的完整帧（剩余不足一帧的字节留在内部） */
    fun append(d: ByteArray, len: Int = d.size): List<ByteArray> {
        if (len <= 0) return emptyList()
        acc.write(d, 0, len)
        if (acc.size() < frameBytes) return emptyList()
        val all = acc.toByteArray()
        val out = ArrayList<ByteArray>(all.size / frameBytes)
        var start = 0
        while (all.size - start >= frameBytes) {
            out.add(all.copyOfRange(start, start + frameBytes))
            start += frameBytes
        }
        acc.reset()
        if (start < all.size) acc.write(all, start, all.size - start)
        return out
    }

    /** 关闭上传闸门时丢弃半帧，避免恢复上传时把旧音频拼进新一轮 */
    fun reset() = acc.reset()

    companion object {
        /** s16le 单声道：sampleRate * frameMs / 1000 个样本 × 2 字节 */
        fun frameBytes(sampleRate: Int, frameMs: Int) = maxOf(1, sampleRate * frameMs / 1000) * 2
    }
}

object PCMMath {
    /** s16le 的 RMS（0…1） */
    fun rms(d: ByteArray, len: Int = d.size): Float {
        val n = len / 2
        if (n == 0) return 0f
        var s = 0.0
        for (i in 0 until n) {
            val v = ((d[2 * i].toInt() and 0xff) or (d[2 * i + 1].toInt() shl 8)).toShort() / 32768.0
            s += v * v
        }
        return sqrt(s / n).toFloat()
    }

    /** s16le 的峰值（0…1） */
    fun peak(d: ByteArray, off: Int = 0, len: Int = d.size): Float {
        var m = 0
        var i = off
        while (i + 1 < off + len) {
            val v = abs(((d[i].toInt() and 0xff) or (d[i + 1].toInt() shl 8)).toShort().toInt())
            if (v > m) m = v
            i += 2
        }
        return m / 32768f
    }

    fun pcmDurationSec(bytes: Int, sampleRate: Int) = if (sampleRate <= 0) 0.0 else (bytes / 2).toDouble() / sampleRate

    /** edge-tts 默认 audio-24khz-48kbitrate-mono-mp3：6000 B/s（仅用于估算） */
    fun mp3DurationEstimate(bytes: Int, bitrate: Int = 48_000) = bytes * 8.0 / bitrate
}
