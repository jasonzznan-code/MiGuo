package com.miguo.voice.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed class LocateOutcome {
    data class Ok(val fix: GeoFix, val level: PermissionLevel) : LocateOutcome()
    object NoPermission : LocateOutcome()
    object LocationOff : LocateOutcome()
    data class Failed(val reason: String, val staleCache: GeoFix?) : LocateOutcome()
}

/**
 * fix2：只用系统 android.location.LocationManager（GPS_PROVIDER + NETWORK_PROVIDER + API31+ FUSED_PROVIDER），
 * 不依赖 Google Play 服务（国产手机大多没有 GMS），也**绝不**用 IP 定位（出口 IP 在北京会把张家口用户定位到北京）。
 *
 * 流程：所有可用 provider 同时请求一次实时位置 → 第一个精度 ≤200 m 的立即采用；否则等到超时取最好的一个；
 * 一个都没有 → 用 10 分钟内的 lastKnown 缓存；再没有 → 失败（不猜、不回退到任何默认城市）。
 */
class DeviceLocator(private val ctx: Context) {
    private val lm: LocationManager? = ctx.getSystemService(LocationManager::class.java)

    fun permissionLevel(): PermissionLevel {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            has(Manifest.permission.ACCESS_FINE_LOCATION) -> PermissionLevel.FINE
            has(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionLevel.COARSE_ONLY
            else -> PermissionLevel.NONE
        }
    }

    fun isLocationEnabled(): Boolean {
        val m = lm ?: return false
        return if (Build.VERSION.SDK_INT >= 28) m.isLocationEnabled
        else runCatching { m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    }

    private fun Location.toFix(provider: String, cache: Boolean): GeoFix {
        val age = ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L).coerceAtLeast(0)
        @Suppress("DEPRECATION")
        val isMock = if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider
        return GeoFix(latitude, longitude, if (hasAccuracy()) accuracy else null, this.provider ?: provider, time, age, cache, isMock)
    }

    @SuppressLint("MissingPermission")   // 权限在 permissionLevel() 中显式检查；SecurityException 也有兜底
    suspend fun locate(timeoutMs: Long = LocationPolicy.CURRENT_TIMEOUT_MS, log: (String) -> Unit = {}): LocateOutcome {
        val m = lm ?: return LocateOutcome.Failed("系统没有定位服务（LocationManager 不可用）", null)
        val level = permissionLevel()
        if (level == PermissionLevel.NONE) return LocateOutcome.NoPermission
        if (!isLocationEnabled()) return LocateOutcome.LocationOff
        val providers = LocationPolicy.providersFor(Build.VERSION.SDK_INT, level) { m.isProviderEnabled(it) }
        log("定位开始：权限=$level providers=$providers 超时=${timeoutMs}ms")

        val ch = Channel<GeoFix>(Channel.UNLIMITED)
        val signals = mutableListOf<CancellationSignal>()
        val listeners = mutableListOf<LocationListener>()
        var best: GeoFix? = null
        try {
            withContext(Dispatchers.Main) {
                for (p in providers) {
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val cs = CancellationSignal().also { signals += it }
                            m.getCurrentLocation(p, cs, ctx.mainExecutor) { loc -> if (loc != null) ch.trySend(loc.toFix(p, false)) }
                        } else {
                            val l = object : LocationListener {
                                override fun onLocationChanged(loc: Location) { ch.trySend(loc.toFix(p, false)) }
                                @Deprecated("API < 29 仍会回调") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                                override fun onProviderEnabled(provider: String) {}
                                override fun onProviderDisabled(provider: String) {}
                            }
                            listeners += l
                            m.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper())
                        }
                    } catch (e: SecurityException) {
                        log("provider $p 无权限：$e")
                    } catch (e: IllegalArgumentException) {
                        log("provider $p 不可用：$e")
                    }
                }
            }
            if (providers.isNotEmpty()) {
                withTimeoutOrNull(timeoutMs) {
                    for (f in ch) {
                        if (!LocationPolicy.validCoord(f.lat, f.lon)) continue
                        log("候选：${f.provider} ${f.lat},${f.lon} ±${f.accuracyM}")
                        best = LocationPolicy.better(best, f)
                        if (LocationPolicy.isGoodEnough(best!!)) break
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Main) {
                signals.forEach { runCatching { it.cancel() } }
                listeners.forEach { runCatching { m.removeUpdates(it) } }
            }
            ch.close()
        }
        best?.let { return LocateOutcome.Ok(it, level) }

        // 兜底：lastKnown（只接受 10 分钟内的）
        val cached = mutableListOf<GeoFix>()
        for (p in runCatching { m.getProviders(true) }.getOrDefault(emptyList())) {
            try { m.getLastKnownLocation(p)?.let { cached += it.toFix(p, true) } } catch (e: SecurityException) { Log.w(TAG, "lastKnown $p: $e") }
        }
        LocationPolicy.pickLastKnown(cached)?.let { return LocateOutcome.Ok(it, level) }
        val stale = cached.minByOrNull { it.ageMs }
        return LocateOutcome.Failed(
            "${timeoutMs / 1000} 秒内没有拿到位置" + if (stale != null) "（只有 ${stale.ageMs / 60000} 分钟前的旧缓存，不采用）" else "",
            stale,
        )
    }

    companion object { private const val TAG = "miguo-loc" }
}
