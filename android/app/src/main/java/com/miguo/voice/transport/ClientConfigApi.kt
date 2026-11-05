package com.miguo.voice.transport

import android.util.Log
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.DeepSeekProviderContract
import com.miguo.voice.protocol.ServerEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GET /api/v1/client/config?platform=android&version=<versionName>（服务端是唯一真相来源）。
 * 结果：成功 → 原始 JSON（由调用方合并 + 缓存）；404/老服务端/网络错误 → null（调用方继续用缓存或内置默认）。
 */
object ClientConfigApi {
    private const val TAG = "miguo-config"

    sealed class Result {
        data class Ok(val json: JsonObject, val raw: String) : Result()
        /** 服务端还没有这个接口（404/405） */
        object NotSupported : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun fetch(
        endpoint: ServerEndpoint, userId: String?, apiKey: String?, version: String,
        timeoutMs: Long = 3000, client: OkHttpClient = VoiceSocket.sharedClient,
    ): Result = withContext(Dispatchers.IO) {
        // fix1：构造 URL/请求头也可能抛 IllegalArgumentException（非 ASCII 的 key/user_id），必须放进 try，
        // 否则在 AppModel.init / 设置页“完成”的协程里直接闪退
        try {
            val url = (endpoint.httpBase + ClientConfig.PATH).toHttpUrl().newBuilder()
                .addQueryParameter("platform", "android")
                .addQueryParameter("version", version)
                .build()
            val c = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val req = Request.Builder().url(url).apply {
                val k = com.miguo.voice.protocol.HeaderValues.cleanApiKey(apiKey)
                if (!k.isNullOrEmpty()) header("X-API-Key", k)
                if (!userId.isNullOrBlank()) header("X-User-Id", userId)
            }.build()
            c.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 || resp.code == 405 -> {
                        Log.i(TAG, "server has no ${ClientConfig.PATH} (HTTP ${resp.code}), using builtin/cache")
                        Result.NotSupported
                    }
                    resp.code != 200 -> Result.Failed("HTTP ${resp.code}")
                    else -> {
                        val raw = resp.body?.string() ?: ""
                        // 没有密钥字段时 safe == raw，缓存内容与服务端原文一致。
                        val safe = DeepSeekProviderContract.redactConfigSecrets(raw)
                        try {
                            Result.Ok(ClientConfig.parseJson(safe), safe)
                        } catch (e: Exception) {
                            Result.Failed(DeepSeekProviderContract.redactLog("返回不是 JSON 对象：${e.message}")!!)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[miguo] /client/config 请求失败：${DeepSeekProviderContract.redactLog(e.toString())}")
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
