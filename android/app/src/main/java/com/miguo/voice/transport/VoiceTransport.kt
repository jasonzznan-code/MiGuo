package com.miguo.voice.transport

import com.miguo.voice.protocol.AudioHeader
import com.miguo.voice.protocol.ClientControl
import com.miguo.voice.protocol.ServerEvent
import com.miguo.voice.protocol.ServerEventDecoder
import com.miguo.voice.protocol.StartMessage
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.Base64

/** 从 socket 读出来的入站消息：普通事件，或已配对好的 “audio 头 + 音频字节”（对应 iOS VoiceTransport.swift）。 */
sealed class Inbound {
    data class Event(val event: ServerEvent) : Inbound()
    class Audio(val header: AudioHeader, val data: ByteArray) : Inbound()
    /** 没有前置 audio 头的二进制帧（协议错误，记录后忽略）；bytes=-1 表示上一个头没等到二进制 */
    data class OrphanBinary(val bytes: Int) : Inbound()
    /** 无法解码的文本帧（记录后忽略，不中断会话） */
    data class Undecodable(val text: String, val error: String) : Inbound()
    /** 连接结束：error=null 表示正常关闭（1000） */
    data class Closed(val error: VoiceSocketError?) : Inbound()
}

/** 传输层错误分类，供状态机给出可读提示。 */
sealed class VoiceSocketError(msg: String) : Exception(msg) {
    /** 握手阶段收到非 101 的 HTTP 响应（403 = API Key 错误） */
    class HandshakeRejected(val status: Int) : VoiceSocketError("handshake HTTP $status")
    /** 连接被关闭（4400 = user_id 非法，4000 = 服务器结束会话） */
    class Closed(val code: Int, val reason: String?) : VoiceSocketError("closed $code $reason")
    /** 其他网络错误 */
    class Transport(val description: String, val cause0: Throwable?) : VoiceSocketError(description)
    object NotConnected : VoiceSocketError("未连接")
}

/** 便于替换成假实现做测试 */
interface VoiceTransport {
    /** 建立 WS、立即发送 start；返回入站消息通道（最后一条一定是 [Inbound.Closed]） */
    suspend fun connect(url: String, headers: Map<String, String>, start: StartMessage, timeoutMs: Long = 8000): ReceiveChannel<Inbound>
    fun send(c: ClientControl): Boolean
    fun sendAudio(d: ByteArray): Boolean
    fun close(code: Int = 1000)
}

/**
 * binary 模式的“音频头 + 二进制帧”配对逻辑（纯逻辑，便于单元测试）。
 * 服务端 session.py send_audio 在 send_lock 里先发 JSON 头再发二进制，保证相邻；
 * OkHttp 在单个读线程里按顺序回调 onMessage(text)/onMessage(bytes)，所以这里只要记住“待配对的头”。
 * base64 模式下头里带 data 字段，直接解码。
 */
class InboundPairer {
    private var pendingHeader: AudioHeader? = null

    fun onText(text: String): List<Inbound> {
        val ev = try {
            ServerEventDecoder.decode(text)
        } catch (e: Exception) {
            return listOf(Inbound.Undecodable(text.take(300), e.message ?: e.toString()))
        }
        if (ev is ServerEvent.AudioHeaderEv) {
            val h = ev.h
            val b64 = h.data
            if (b64 != null) {
                return try {
                    listOf(Inbound.Audio(h, Base64.getDecoder().decode(b64)))
                } catch (e: IllegalArgumentException) {
                    listOf(Inbound.Undecodable("audio.data(base64)", "base64 解码失败"))
                }
            }
            val out = if (pendingHeader != null) listOf<Inbound>(Inbound.OrphanBinary(-1)) else emptyList()
            pendingHeader = h
            return out
        }
        return listOf(Inbound.Event(ev))
    }

    fun onBinary(bytes: ByteArray): Inbound {
        val h = pendingHeader ?: return Inbound.OrphanBinary(bytes.size)
        pendingHeader = null
        return Inbound.Audio(h, bytes)
    }

    fun reset() {
        pendingHeader = null
    }
}
