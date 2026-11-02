package com.miguo.voice.transport

import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.ErrorCatalog
import com.miguo.voice.protocol.ReadableError
import com.miguo.voice.protocol.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

/** GET /api/v1/voice/health 的子集（voice/router.py::voice_health；对应 iOS VoiceHealthAPI.swift）。 */
data class VoiceHealth(
    val status: String,
    val problems: List<String>,
    val fix: String?,
    val sttLoaded: Boolean?,
    val sttModel: String?,
    val sttDevice: String?,
    val ttsProbe: String?,
    val ttsDefaultVoice: String?,
    val llmModel: String?,
    val llmReachable: Boolean?,
    val sessionsActive: Int?,
) {
    val isDegraded: Boolean get() = status != "ok"
}

sealed class HealthResult {
    data class Ok(val h: VoiceHealth, val latencyMs: Double) : HealthResult()
    data class Degraded(val h: VoiceHealth, val latencyMs: Double) : HealthResult()
    data class Failed(val e: ReadableError) : HealthResult()
}

/** 把 java.net / OkHttp 异常归类成 ErrorCatalog.NetKind */
fun classifyNetwork(t: Throwable?): ErrorCatalog.NetKind = when (t) {
    is UnknownHostException -> ErrorCatalog.NetKind.UNKNOWN_HOST
    is SocketTimeoutException -> ErrorCatalog.NetKind.TIMEOUT
    is ConnectException, is NoRouteToHostException -> ErrorCatalog.NetKind.UNREACHABLE
    is UnknownServiceException -> ErrorCatalog.NetKind.CLEARTEXT_BLOCKED   // CLEARTEXT communication not permitted
    null -> ErrorCatalog.NetKind.OTHER
    else -> if (t.cause != null && t.cause !== t) classifyNetwork(t.cause) else ErrorCatalog.NetKind.OTHER
}

object VoiceHealthApi {
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.o(k: String) = this[k] as? JsonObject

    fun parse(text: String): VoiceHealth {
        val o = ClientConfig.parseJson(text)
        val stt = o.o("stt")
        val tts = o.o("tts")
        val llm = o.o("llm")
        return VoiceHealth(
            status = o.s("status") ?: "unknown",
            problems = (o["problems"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            fix = o.s("fix"),
            sttLoaded = stt?.b("loaded"),
            sttModel = stt?.s("model"),
            sttDevice = stt?.s("device"),
            ttsProbe = tts?.s("probe"),
            ttsDefaultVoice = tts?.s("default_voice"),
            llmModel = llm?.s("model"),
            llmReachable = llm?.b("reachable"),
            sessionsActive = (o["sessions_active"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
        )
    }

    suspend fun check(
        endpoint: ServerEndpoint, userId: String, apiKey: String?,
        loadStt: Boolean = false, probeTts: Boolean = false, timeoutMs: Long = 4000,
        client: OkHttpClient = VoiceSocket.sharedClient,
    ): HealthResult = withContext(Dispatchers.IO) {
        val t = if (loadStt || probeTts) maxOf(timeoutMs, 30_000) else timeoutMs
        val c = client.newBuilder().callTimeout(t, TimeUnit.MILLISECONDS).readTimeout(t, TimeUnit.MILLISECONDS).build()
        // fix1：先校验 user_id / key（非 ASCII 进 OkHttp 头会抛 IllegalArgumentException 并闪退）
        try {
            com.miguo.voice.conversation.validateIdentity(userId, apiKey)
        } catch (e: ReadableError) {
            return@withContext HealthResult.Failed(e)
        }
        val t0 = System.nanoTime()
        try {
            val req = Request.Builder().url(endpoint.voiceHealthUrl(loadStt, probeTts))
                .apply { ServerEndpoint.headers(userId, apiKey).forEach { (k, v) -> header(k, v) } }
                .build()
            c.newCall(req).execute().use { resp ->
                val ms = (System.nanoTime() - t0) / 1e6
                val status = resp.code
                val body = resp.body?.string() ?: ""
                if (status == 401 || status == 403) {
                    return@withContext HealthResult.Failed(
                        ReadableError(
                            "auth_failed", "API Key 错误（HTTP $status）",
                            "/voice/health 返回 $status：服务器启用了 API Key 校验，App 里的 key 不一致或为空。",
                            "到设置里填写与服务端配置的 API Key 一致的 key。",
                        )
                    )
                }
                if (status != 200) {
                    return@withContext HealthResult.Failed(
                        ReadableError("health_$status", "健康检查失败（HTTP $status）", body.take(200), retryable = true)
                    )
                }
                val h = try { parse(body) } catch (e: Exception) {
                    return@withContext HealthResult.Failed(ReadableError("health_decode", "健康检查返回格式不认识", e.toString()))
                }
                if (h.isDegraded) HealthResult.Degraded(h, ms) else HealthResult.Ok(h, ms)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            HealthResult.Failed(
                ErrorCatalog.network(e.message ?: e.javaClass.simpleName, classifyNetwork(e), endpoint.isLoopback)
            )
        }
    }

    /** 给 UI 的一句话摘要 */
    fun summary(r: HealthResult): String = when (r) {
        is HealthResult.Ok -> "服务器正常（${r.latencyMs.toInt()} ms）" + (r.h.sttDevice?.let { " · STT $it" } ?: "")
        is HealthResult.Degraded -> "服务器降级：" + r.h.problems.joinToString("；").ifEmpty { "未知原因" }
        is HealthResult.Failed -> r.e.title
    }
}
