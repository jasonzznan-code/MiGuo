package com.miguo.voice.transport

import com.miguo.voice.location.LocationPolicy
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.ErrorCatalog
import com.miguo.voice.protocol.ReadableError
import com.miguo.voice.protocol.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** GET /api/v1/map/reverse 的结果（geo/service.py::reverse + _fmt） */
data class ReverseAddress(
    val count: Int,
    val name: String?,
    val addressText: String?,
    val city: String?,
    val province: String?,
    val district: String?,
    val region: String?,
    val distanceM: Double?,
    /** 服务端 map_fix：默认半径内无结果、自动放宽了搜索半径 */
    val widened: Boolean,
    val radiusKmUsed: Double?,
) {
    /** 给 UI 的一行地址 */
    val display: String
        get() = when {
            count == 0 -> "附近没有地址数据（服务器地图里这个坐标周围没有地点）"
            !addressText.isNullOrBlank() && !name.isNullOrBlank() && !addressText.contains(name) -> "$addressText（$name）"
            !addressText.isNullOrBlank() -> addressText
            else -> listOfNotNull(province, city, district, name).distinct().joinToString("")
        }
}

sealed class MapResult {
    data class Ok(val addr: ReverseAddress, val url: String) : MapResult()
    data class Failed(val e: ReadableError, val url: String?) : MapResult()
}

object MapApi {
    const val REVERSE_PATH = "/api/v1/map/reverse"

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.o(k: String) = this[k] as? JsonObject

    /** lat 在前、lon 在后，按参数名传（不是位置参数），小数点固定为 '.' */
    fun reverseUrl(endpoint: ServerEndpoint, lat: Double, lon: Double): HttpUrl =
        (endpoint.httpBase + REVERSE_PATH).toHttpUrl().newBuilder()
            .addQueryParameter("lat", LocationPolicy.formatCoord(lat))
            .addQueryParameter("lon", LocationPolicy.formatCoord(lon))
            .addQueryParameter("limit", "1")
            .build()

    @Throws(ReadableError::class)
    fun parseReverse(httpStatus: Int, text: String): ReverseAddress {
        val o = try { ClientConfig.parseJson(text) } catch (e: Exception) {
            if (httpStatus == 404) throw ReadableError("map_missing", "服务器没有地图接口（HTTP 404）",
                "这台 memory_core 还没有 /api/v1/map（需要 geo/ 地图层 + Photon）。", "按 setup_map.ps1 安装地图层后再试。")
            throw ReadableError("map_decode", "地图接口返回格式不认识（HTTP $httpStatus）", text.take(200))
        }
        if (o.s("status") != "ok") {
            val err = o.o("error")
            val code = err?.s("code") ?: "HTTP_$httpStatus"
            val msg = err?.s("message") ?: (o["detail"]?.toString() ?: text.take(200))
            throw when (code) {
                "PHOTON_UNAVAILABLE" -> ReadableError(code, "服务器地图服务（Photon）没启动", msg, err?.s("fix") ?: "在服务器上运行 run_photon.ps1 / 检查 MiguoPhoton 服务。", retryable = true)
                "OUT_OF_COVERAGE" -> ReadableError(code, "坐标不在中国范围内", msg)
                else -> ReadableError(code, "地址查询失败（$code）", msg)
            }
        }
        val data = o.o("data") ?: throw ReadableError("map_decode", "地图接口缺少 data", text.take(200))
        val r0 = (data["results"] as? JsonArray)?.firstOrNull() as? JsonObject
        val addr = r0?.o("address")
        return ReverseAddress(
            count = data.d("count")?.toInt() ?: 0,
            name = r0?.s("name"),
            addressText = r0?.s("address_text"),
            city = r0?.s("city"),
            province = r0?.s("province"),
            district = addr?.s("district") ?: addr?.s("county"),
            region = data.s("region"),
            distanceM = r0?.d("distance_m"),
            widened = (data["widened"] as? JsonPrimitive)?.booleanOrNull ?: false,
            radiusKmUsed = data.d("radius_km_used"),
        )
    }

    suspend fun reverse(
        endpoint: ServerEndpoint, userId: String, apiKey: String?, lat: Double, lon: Double,
        timeoutMs: Long = 8000, client: OkHttpClient = VoiceSocket.sharedClient,
    ): MapResult = withContext(Dispatchers.IO) {
        var url: String? = null
        try {
            com.miguo.voice.conversation.validateIdentity(userId, apiKey)
            val u = reverseUrl(endpoint, lat, lon)
            url = u.toString()
            val c = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val req = Request.Builder().url(u)
                .apply { ServerEndpoint.headers(userId, apiKey).forEach { (k, v) -> header(k, v) } }
                .build()
            c.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (resp.code == 401 || resp.code == 403) {
                    return@withContext MapResult.Failed(ReadableError("auth_failed", "API Key 错误（HTTP ${resp.code}）",
                        "/map/reverse 返回 ${resp.code}", "到设置里填写与服务端配置的 API Key 一致的 key。"), url)
                }
                MapResult.Ok(parseReverse(resp.code, body), url)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ReadableError) {
            MapResult.Failed(e, url)
        } catch (e: Exception) {
            MapResult.Failed(ErrorCatalog.network(e.message ?: e.javaClass.simpleName, classifyNetwork(e), endpoint.isLoopback), url)
        }
    }
}
