package com.miguo.voice.location

import com.miguo.voice.protocol.WireLocation

/** v0.2.0：设备定位 → 发给服务端的位置（start.location / {type:"location"} / 文字对话 location） */
object LocationWire {
    /** 超过这个时间的定位不再发给服务端（避免把几小时前的位置当成当前位置） */
    const val MAX_SEND_AGE_MS = 2 * 60 * 60 * 1000L
    /** 会话开始时定位比这个旧就在后台静默重新定位一次（仅在已授权时） */
    const val REFRESH_AGE_MS = 10 * 60 * 1000L

    /** [nowMs] 墙钟；[fixAtMs] = 拿到这个定位时的墙钟（GeoFix.timeMs 可能来自 GPS 时钟，所以另外记） */
    fun toWire(f: GeoFix?, fixAtMs: Long, nowMs: Long): WireLocation? {
        if (f == null || !LocationPolicy.validCoord(f.lat, f.lon)) return null
        val age = (nowMs - fixAtMs).coerceAtLeast(0) + f.ageMs
        if (age > MAX_SEND_AGE_MS) return null
        return WireLocation(f.lat, f.lon, f.accuracyM, f.provider, nowMs - age)
    }

    fun needsRefresh(f: GeoFix?, fixAtMs: Long, nowMs: Long): Boolean =
        f == null || (nowMs - fixAtMs).coerceAtLeast(0) + f.ageMs > REFRESH_AGE_MS
}
