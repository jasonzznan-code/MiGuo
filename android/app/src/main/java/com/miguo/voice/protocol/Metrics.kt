package com.miguo.voice.protocol

import kotlin.math.roundToInt

/** 每一轮的客户端时间点 + 服务端 metrics（对应 iOS Metrics.swift）。时间用单调时钟秒。 */
data class TurnTiming(
    val turnId: Int,
    val vadEndRecvAt: Double? = null,
    val firstAudioRecvAt: Double? = null,
    val firstAudibleAt: Double? = null,
    val server: TurnMetrics? = null,
    val interrupted: Boolean = false,
    val noSpeech: Boolean = false,
) {
    /** client_first_audio_ms = 第一个音频块到达 − vad_end 到达 */
    val clientFirstAudioMs: Double?
        get() = if (firstAudioRecvAt != null && vadEndRecvAt != null) (firstAudioRecvAt - vadEndRecvAt) * 1000 else null

    /** client_first_audible_ms = 第一个 buffer 预计开始出声 − vad_end 到达 */
    val clientFirstAudibleMs: Double?
        get() = if (firstAudibleAt != null && vadEndRecvAt != null) (firstAudibleAt - vadEndRecvAt) * 1000 else null
}

object Stats {
    /** 最近邻插值分位数（p 0–100） */
    fun percentile(xs: List<Double>, p: Double): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val rank = (p / 100 * (s.size - 1)).roundToInt().coerceIn(0, s.size - 1)
        return s[rank]
    }

    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2 else s[m]
    }
}

/** 指标面板（不可变；ConversationEngine 每次更新都替换成新对象，方便 Compose 观察） */
data class MetricsBoard(
    val turns: List<TurnTiming> = emptyList(),
    val rttSamples: List<Double> = emptyList(),
    val lastBargeStopMs: Double? = null,
    val connectMs: Double? = null,
    val keep: Int = 20,
) {
    fun update(turnId: Int, f: (TurnTiming) -> TurnTiming): MetricsBoard {
        val i = turns.indexOfLast { it.turnId == turnId }
        val nt = if (i >= 0) {
            turns.toMutableList().also { it[i] = f(it[i]) }
        } else {
            (turns + f(TurnTiming(turnId))).takeLast(keep)
        }
        return copy(turns = nt)
    }

    fun addRtt(ms: Double) = copy(rttSamples = (rttSamples + ms).takeLast(5))

    /** rtt_ms：最近 5 次中位数 */
    val rttMs: Double? get() = Stats.median(rttSamples)
    val last: TurnTiming? get() = turns.lastOrNull { !it.noSpeech }

    fun recent(key: (TurnTiming) -> Double?, n: Int = 10): List<Double> =
        turns.filter { !it.noSpeech }.mapNotNull(key).takeLast(n)

    fun p50(key: (TurnTiming) -> Double?) = Stats.percentile(recent(key), 50.0)
    fun p90(key: (TurnTiming) -> Double?) = Stats.percentile(recent(key), 90.0)
}
