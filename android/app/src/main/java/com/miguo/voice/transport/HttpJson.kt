package com.miguo.voice.transport

import android.util.Log
import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.AuthJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * v0.2.0：JSON HTTP 小工具（/auth/…、/chat/text、/conversations）。
 * 所有异常（含 OkHttp 对非 ASCII 头抛的 IllegalArgumentException）都在这里接住，转成 [ApiResult]，绝不闪退。
 * 日志只打方法 + 路径 + 状态码，不打请求体/响应体/token。
 */
object HttpJson {
    private const val TAG = "miguo"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Raw(val status: Int, val body: String, val retryAfter: Int?)

    suspend fun <T> call(
        method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Long,
        client: OkHttpClient = VoiceSocket.sharedClient,
        onStream: ((Response) -> T)? = null,
        parse: (Raw) -> ApiResult<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?')
        try {
            val c = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val rb = Request.Builder().url(url)
            for ((k, v) in headers) rb.header(k, v)
            rb.header("Accept", "application/json")
            when (method) {
                "GET" -> rb.get()
                "DELETE" -> if (body == null) rb.delete() else rb.delete(body.toRequestBody(JSON))
                else -> rb.method(method, (body ?: "{}").toRequestBody(JSON))
            }
            c.newCall(rb.build()).execute().use { resp ->
                Log.i(TAG, "[miguo] $method /$path → HTTP ${resp.code}")
                if (onStream != null && resp.code in 200..299) return@withContext ApiResult.Ok(onStream(resp))
                val raw = Raw(resp.code, resp.body?.string() ?: "", resp.header("Retry-After")?.toIntOrNull())
                classify(raw, parse)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[miguo] $method /$path 失败：${e.javaClass.simpleName}: ${e.message}")
            ApiResult.Network(describe(e))
        }
    }

    fun <T> classify(raw: Raw, parse: (Raw) -> ApiResult<T>): ApiResult<T> = when (raw.status) {
        in 200..299 -> parse(raw)
        401 -> ApiResult.Unauthorized(AuthJson.detail(raw.body, 401))
        404, 405 -> ApiResult.NotSupported
        else -> ApiResult.HttpError(raw.status, AuthJson.detail(raw.body, raw.status, raw.retryAfter), raw.retryAfter)
    }

    fun describe(e: Throwable): String = when (classifyNetwork(e)) {
        com.miguo.voice.protocol.ErrorCatalog.NetKind.UNKNOWN_HOST -> "找不到服务器（域名/地址不对）"
        com.miguo.voice.protocol.ErrorCatalog.NetKind.TIMEOUT -> "连接服务器超时"
        com.miguo.voice.protocol.ErrorCatalog.NetKind.UNREACHABLE -> "连不上服务器（地址/端口/防火墙）"
        com.miguo.voice.protocol.ErrorCatalog.NetKind.CLEARTEXT_BLOCKED -> "系统拦截了明文 HTTP"
        else -> if (e is IllegalArgumentException) "请求头含非法字符（检查 API Key / user_id）" else (e.message ?: e.javaClass.simpleName)
    }
}
