package com.miguo.voice.transport

import android.util.Log
import com.miguo.voice.protocol.ClientControl
import com.miguo.voice.protocol.StartMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OkHttp WebSocket 封装（对应 iOS VoiceSocket.swift）。
 * - 握手头：X-User-Id（必填）、X-API-Key（可选）
 * - 连接后立即发送 start（服务端 15 s 内必须收到）
 * - binary 模式：audio JSON 头之后的下一个二进制帧就是该头的音频 → [InboundPairer] 配对
 * - OkHttp 的 send 线程安全且保序（内部队列），无需再串行化
 */
class VoiceSocket(private val baseClient: OkHttpClient = sharedClient) : VoiceTransport {
    @Volatile private var ws: WebSocket? = null
    private val pairer = InboundPairer()
    val bytesSent = AtomicLong()
    val framesSent = AtomicLong()

    override suspend fun connect(url: String, headers: Map<String, String>, start: StartMessage, timeoutMs: Long): ReceiveChannel<Inbound> {
        close(1000)
        pairer.reset()
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)          // 长连接：靠应用层 ping + 服务端 idle_timeout
            .build()
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val inbound = Channel<Inbound>(Channel.UNLIMITED)
        val opened = CompletableDeferred<Unit>()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                for (m in pairer.onText(text)) inbound.trySend(m)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                inbound.trySend(pairer.onBinary(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(if (code == 1000) null else VoiceSocketError.Closed(code, reason.ifEmpty { null }))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(if (code == 1000) null else VoiceSocketError.Closed(code, reason.ifEmpty { null }))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val e: VoiceSocketError = if (response != null && response.code != 101) {
                    VoiceSocketError.HandshakeRejected(response.code)
                } else {
                    VoiceSocketError.Transport(t.message ?: t.javaClass.simpleName, t)
                }
                if (!opened.isCompleted) opened.completeExceptionally(e)
                finish(e)
            }

            private fun finish(e: VoiceSocketError?) {
                if (inbound.isClosedForSend) return
                inbound.trySend(Inbound.Closed(e))
                inbound.close()
            }
        }

        val socket = client.newWebSocket(req, listener)
        ws = socket
        try {
            withTimeout(timeoutMs) { opened.await() }
        } catch (e: VoiceSocketError) {
            ws = null
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            socket.cancel()
            ws = null
            throw VoiceSocketError.Transport("连接超时（${timeoutMs} ms）", e)
        }
        if (!socket.send(start.encode())) {
            ws = null
            throw VoiceSocketError.NotConnected
        }
        Log.i(TAG, "connected $url, start sent")
        return inbound
    }

    override fun send(c: ClientControl): Boolean = ws?.send(c.encode()) ?: false

    override fun sendAudio(d: ByteArray): Boolean {
        val ok = ws?.send(d.toByteString()) ?: false
        if (ok) {
            bytesSent.addAndGet(d.size.toLong())
            framesSent.incrementAndGet()
        }
        return ok
    }

    override fun close(code: Int) {
        val w = ws ?: return
        ws = null
        if (!w.close(code, null)) w.cancel()
    }

    companion object {
        private const val TAG = "miguo-ws"
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                // v0.2.0：uvicorn 默认 5 秒关闭空闲 keep-alive 连接；OkHttp 默认池保留 5 分钟，又不重试 →
                // 空闲后第一次请求常报“unexpected end of stream”（模拟器上 /client/config、/voice/health 复现）。池保留 4 秒避免复用已被服务器关掉的连接。
                .connectionPool(okhttp3.ConnectionPool(5, 4, TimeUnit.SECONDS))
                .build()
        }
    }
}
