package com.miguo.voice.location

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * fix2（定位）：一次定位结果。坐标系 = WGS-84。
 *
 * 坐标系说明：Android 的 android.location.LocationManager（GPS / 网络 / fused）返回的都是 WGS-84；
 * 服务器的 Photon / OpenStreetMap 数据也是 WGS-84，所以**不需要**做 GCJ-02（火星坐标）转换。
 * 只有接高德/腾讯/百度 SDK 或它们的地图瓦片时才涉及 GCJ-02/BD-09，本 App 不用这些 SDK。
 */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    /** 水平精度（米，68% 置信半径）；null = 系统没给 */
    val accuracyM: Float?,
    /** gps / network / fused / passive */
    val provider: String,
    /** 定位时间（墙钟，毫秒），仅用于显示 */
    val timeMs: Long,
    /** 取得结果时这个位置已经多旧（毫秒，基于 elapsedRealtime） */
    val ageMs: Long = 0,
    /** true = 来自 getLastKnownLocation（缓存），不是本次实时定位 */
    val fromCache: Boolean = false,
    /** 系统标记的模拟位置（开发者选项 / 模拟器 geo fix） */
    val mock: Boolean = false,
)

/** 用户授予的定位权限级别（Android 12+ 用户可以只给“大致位置”） */
enum class PermissionLevel { NONE, COARSE_ONLY, FINE }

/** 纯逻辑（不依赖 Android 类，JVM 单测可测）：精度阈值、候选选择、提示文案、坐标格式化。 */
object LocationPolicy {
    const val GPS = "gps"          // LocationManager.GPS_PROVIDER
    const val NETWORK = "network"  // LocationManager.NETWORK_PROVIDER（国产手机一般是厂商/基站/Wi-Fi 定位，不需要 GMS）
    const val FUSED = "fused"      // LocationManager.FUSED_PROVIDER（API 31+，系统自带，不是 Play 服务）

    /** 精度 ≤ 200 m：立即接受，停止等待 */
    const val ACCEPT_ACCURACY_M = 200f
    /** 精度 > 1000 m：显示“精度差”警告（多半是基站/IP 级别的粗定位） */
    const val WARN_ACCURACY_M = 1000f
    /** 本次实时定位最长等待 */
    const val CURRENT_TIMEOUT_MS = 20_000L
    /** 缓存位置（lastKnown）最多可接受 10 分钟前的 */
    const val LAST_KNOWN_MAX_AGE_MS = 10 * 60_000L

    /** 按权限选择要请求的 provider（只用 LocationManager，不用 Google Play 服务，不用任何 IP 定位） */
    fun providersFor(sdkInt: Int, level: PermissionLevel, isEnabled: (String) -> Boolean): List<String> {
        if (level == PermissionLevel.NONE) return emptyList()
        val wanted = buildList {
            if (level == PermissionLevel.FINE) add(GPS)       // GPS 需要精确定位权限
            add(NETWORK)
            if (sdkInt >= 31) add(FUSED)
        }
        return wanted.filter { runCatching { isEnabled(it) }.getOrDefault(false) }
    }

    fun validCoord(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)

    fun isGoodEnough(f: GeoFix): Boolean = f.accuracyM != null && f.accuracyM <= ACCEPT_ACCURACY_M

    fun isPoor(f: GeoFix): Boolean = f.accuracyM == null || f.accuracyM > WARN_ACCURACY_M

    /** 两个候选里取更好的：精度数值更小者优先；精度相近（差 < 20%）时取更新的 */
    fun better(a: GeoFix?, b: GeoFix): GeoFix {
        if (a == null) return b
        val aa = a.accuracyM ?: Float.MAX_VALUE
        val bb = b.accuracyM ?: Float.MAX_VALUE
        if (aa == Float.MAX_VALUE && bb == Float.MAX_VALUE) return if (b.ageMs < a.ageMs) b else a
        val close = kotlin.math.abs(aa - bb) <= 0.2f * minOf(aa, bb)
        return when {
            close -> if (b.ageMs < a.ageMs) b else a
            bb < aa -> b
            else -> a
        }
    }

    /** lastKnown 兜底：只要不超过 maxAgeMs 的，取最好的一个；都太旧 → null */
    fun pickLastKnown(cands: List<GeoFix>, maxAgeMs: Long = LAST_KNOWN_MAX_AGE_MS): GeoFix? =
        cands.filter { it.ageMs in 0..maxAgeMs && validCoord(it.lat, it.lon) }
            .fold(null as GeoFix?) { acc, f -> better(acc, f) }

    /** 坐标转查询参数：固定用 '.' 小数点（不受系统语言影响，德语/法语等会把 String.format 写成 40,82），最多 6 位小数（约 0.1 m） */
    fun formatCoord(v: Double): String =
        BigDecimal(v.toString()).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun providerLabel(p: String): String = when (p) {
        GPS -> "GPS 卫星"
        NETWORK -> "网络定位（基站/Wi-Fi）"
        FUSED -> "系统融合定位"
        else -> p
    }

    fun describe(f: GeoFix): String {
        val acc = f.accuracyM?.let { "±${it.toInt()} m" } ?: "精度未知"
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(f.timeMs))
        val src = if (f.fromCache) "，缓存 ${f.ageMs / 1000} 秒前" else ""
        return "${providerLabel(f.provider)} · $acc · $t$src" + if (f.mock) " · 模拟位置" else ""
    }

    /** 给用户的提示（空 = 没问题） */
    fun warnings(f: GeoFix, level: PermissionLevel): List<String> = buildList {
        if (level == PermissionLevel.COARSE_ONLY) {
            add("你只授予了“大致位置”，系统会把位置模糊到约 1–3 km，地址可能落到隔壁街道甚至相邻区县。要准确地址请到 设置→应用→米果→权限→位置 打开“使用确切位置”。")
        }
        if (isPoor(f)) {
            add("定位精度差（${f.accuracyM?.let { "±${it.toInt()} m" } ?: "未知"}），结果只能参考。请到室外/窗边、打开 GPS 和 Wi-Fi 后点“重新定位”。")
        } else if (!isGoodEnough(f)) {
            add("定位精度一般（±${f.accuracyM?.toInt()} m），地址可能有偏差。")
        }
        if (f.fromCache) add("这是 ${f.ageMs / 1000} 秒前的缓存位置（本次实时定位超时）。")
        if (f.mock) add("系统标记为模拟位置（开发者选项/模拟器）。")
    }
}
