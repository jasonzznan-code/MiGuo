package com.miguo.voice.transport

import com.miguo.voice.protocol.ErrorCatalog
import com.miguo.voice.protocol.ReadableError
import com.miguo.voice.protocol.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// Auth base health check: nano GET /healthz; memory_core GET /api/v1/health.
object AuthHealthApi {
    data class Ok(val summary: String, val latencyMs: Double, val url: String)
    data class Failed(val title: String, val detail: String, val url: String)

    suspend fun check(
        endpoint: ServerEndpoint,
        timeoutMs: Long = 4000,
        client: OkHttpClient = VoiceSocket.sharedClient,
    ): Any = withContext(Dispatchers.IO) {
        val url = endpoint.authHealthUrl()
        val c = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val t0 = System.nanoTime()
        try {
            val resp = c.newCall(Request.Builder().url(url).get().build()).execute()
            resp.use {
                val ms = (System.nanoTime() - t0) / 1e6
                val body = it.body?.string().orEmpty()
                if (it.code != 200) {
                    return@withContext Failed("健康检查失败（HTTP ${it.code}）", body.take(200), url)
                }
                val summary = summarize(endpoint, body, ms)
                Ok(summary, ms, url)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val kind = classifyNetwork(e)
            val err = ErrorCatalog.network(e.message ?: e.javaClass.simpleName, kind, endpoint.isLoopback)
            Failed(err.title, err.message, url)
        }
    }

    private fun summarize(ep: ServerEndpoint, body: String, ms: Double): String {
        val o = try {
            com.miguo.voice.protocol.ClientConfig.parseJson(body)
        } catch (_: Exception) {
            null
        }
        return if (ServerEndpoint.looksLikeNanoRelay(ep)) {
            val ok = (o?.get("ok") as? JsonPrimitive)?.booleanOrNull
            val ver = (o?.get("version") as? JsonPrimitive)?.contentOrNull
            val models = (o?.get("models") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.take(3)
                ?.joinToString("/")
            buildString {
                append(if (ok == true) "中继正常" else "中继响应")
                append("（${ms.toInt()} ms）")
                ver?.let { append(" · v$it") }
                if (!models.isNullOrBlank()) append(" · $models")
            }
        } else {
            val status = (o?.get("status") as? JsonPrimitive)?.contentOrNull ?: "ok"
            "服务器 $status（${ms.toInt()} ms）"
        }
    }

    fun format(r: Any): String = when (r) {
        is Ok -> "✓ ${r.summary}\n请求：${r.url}"
        is Failed -> "✗ ${r.title}\n${r.detail}\n请求：${r.url}"
        else -> r.toString()
    }
}
