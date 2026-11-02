package com.miguo.voice.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPolicyTest {
    private fun fix(acc: Float?, age: Long = 0, p: String = "gps", cache: Boolean = false, lat: Double = 40.82, lon: Double = 114.88) =
        GeoFix(lat, lon, acc, p, 1_700_000_000_000, age, cache)

    @Test fun providersByPermission() {
        val all = { _: String -> true }
        assertEquals(listOf("gps", "network", "fused"), LocationPolicy.providersFor(31, PermissionLevel.FINE, all))
        assertEquals(listOf("gps", "network"), LocationPolicy.providersFor(30, PermissionLevel.FINE, all))
        // 只有大致位置：不能用 GPS
        assertEquals(listOf("network", "fused"), LocationPolicy.providersFor(33, PermissionLevel.COARSE_ONLY, all))
        assertTrue(LocationPolicy.providersFor(33, PermissionLevel.NONE, all).isEmpty())
        // 只返回已启用的；isEnabled 抛异常视为不可用
        assertEquals(listOf("gps"), LocationPolicy.providersFor(33, PermissionLevel.FINE) { if (it == "fused") error("x") else it == "gps" })
    }

    @Test fun thresholds() {
        assertTrue(LocationPolicy.isGoodEnough(fix(200f)))
        assertFalse(LocationPolicy.isGoodEnough(fix(201f)))
        assertFalse(LocationPolicy.isGoodEnough(fix(null)))
        assertTrue(LocationPolicy.isPoor(fix(1500f)))
        assertTrue(LocationPolicy.isPoor(fix(null)))
        assertFalse(LocationPolicy.isPoor(fix(1000f)))
    }

    @Test fun betterPrefersAccuracyThenFreshness() {
        val gps = fix(8f, p = "gps")
        val net = fix(1200f, p = "network")
        assertEquals(gps, LocationPolicy.better(net, gps))
        assertEquals(gps, LocationPolicy.better(gps, net))
        val older = fix(100f, age = 60_000)
        val newer = fix(110f, age = 1_000)
        assertEquals(newer, LocationPolicy.better(older, newer))
        assertEquals(net, LocationPolicy.better(null, net))
    }

    @Test fun lastKnownAgeLimit() {
        val fresh = fix(50f, age = 5 * 60_000L, cache = true)
        val stale = fix(5f, age = 11 * 60_000L, cache = true)
        assertEquals(fresh, LocationPolicy.pickLastKnown(listOf(stale, fresh)))
        assertNull(LocationPolicy.pickLastKnown(listOf(stale)))
        assertNull(LocationPolicy.pickLastKnown(listOf(fix(5f, lat = 0.0, lon = 0.0))))
    }

    @Test fun formatCoordIsLocaleIndependentAndKeepsOrder() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)   // 德语用逗号作小数点
            assertEquals("40.82", LocationPolicy.formatCoord(40.82))
            assertEquals("114.88", LocationPolicy.formatCoord(114.88))
            assertEquals("40.123457", LocationPolicy.formatCoord(40.1234567))
            assertEquals("-0.0000001".length > 0, true)
            assertEquals("0", LocationPolicy.formatCoord(0.0000001))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test fun validCoord() {
        assertTrue(LocationPolicy.validCoord(40.82, 114.88))
        assertFalse(LocationPolicy.validCoord(114.88, 40.82))     // lat/lon 对调 → 非法
        assertFalse(LocationPolicy.validCoord(0.0, 0.0))
        assertFalse(LocationPolicy.validCoord(Double.NaN, 1.0))
    }

    @Test fun warnings() {
        assertTrue(LocationPolicy.warnings(fix(10f), PermissionLevel.FINE).isEmpty())
        val coarse = LocationPolicy.warnings(fix(2000f, p = "network"), PermissionLevel.COARSE_ONLY)
        assertTrue(coarse.any { it.contains("大致位置") })
        assertTrue(coarse.any { it.contains("精度差") })
        assertTrue(LocationPolicy.warnings(fix(500f), PermissionLevel.FINE).any { it.contains("精度一般") })
        assertTrue(LocationPolicy.warnings(fix(50f, age = 120_000, cache = true), PermissionLevel.FINE).any { it.contains("缓存") })
        assertTrue(LocationPolicy.describe(fix(12f)).contains("±12 m"))
    }
}
