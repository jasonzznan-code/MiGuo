package com.miguo.voice.transport

import com.miguo.voice.conversation.ChatBackend
import com.miguo.voice.conversation.Identity
import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.HistoryMessage
import com.miguo.voice.protocol.ServerEndpoint
import com.miguo.voice.protocol.TextChatEvent
import com.miguo.voice.protocol.TextChatProtocol
import com.miguo.voice.protocol.TextChatRequest
import com.miguo.voice.protocol.TextChatResult
import com.miguo.voice.protocol.ConversationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * /api/v1/chat/text（NDJSON 流）与 /api/v1/conversations 的 OkHttp 实现。
 * 不调用 DeepSeek。模型提供方切换只发生在服务端；这里也不读取 DEEPSEEK_API_KEY。
 */
class ChatApi(
    private val endpoint: () -> ServerEndpoint?,
    private val apiKey: () -> String?,
) : ChatBackend {
    private val noEndpoint = ApiResult.Network("服务器地址不对")
    private fun h(id: Identity): Map<String, String> {
        // 网关仍要 X-API-Key：优先设备/会话凭证；若仅有 Bearer（登录 token），用同一可吊销凭证填 X-API-Key
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: id.bearer
        return ServerEndpoint.headers(id.userId, key, id.bearer)
    }

    override suspend fun sendText(req: TextChatRequest, id: Identity, onEvent: (TextChatEvent) -> Unit): ApiResult<TextChatResult> {
        val ep = endpoint() ?: return noEndpoint
        val r = HttpJson.call<TextChatResult>("POST", ep.httpBase + ServerEndpoint.TEXT_CHAT_PATH, h(id), req.encode(), 200_000,
            onStream = { resp ->
                if (!req.stream) {
                    TextChatProtocol.parseFull(resp.body?.string() ?: "") ?: TextChatResult(null, "", "服务器返回格式不对", null)
                } else {
                    // 在 IO 线程逐行读，每行立即回调 onEvent（调用方负责切回主线程）→ 逐句显示
                    val src = resp.body?.source()
                    val audio = ByteArrayOutputStream()
                    var cid: String? = null
                    var done: TextChatEvent.Done? = null
                    var err: TextChatEvent.Error? = null
                    while (src != null && !src.exhausted()) {
                        val line = src.readUtf8Line() ?: break
                        val ev = TextChatProtocol.parseLine(line) ?: continue
                        onEvent(ev)
                        when (ev) {
                            is TextChatEvent.Start -> cid = ev.conversationId
                            is TextChatEvent.Audio -> audio.write(ev.mp3)
                            is TextChatEvent.Done -> done = ev
                            is TextChatEvent.Error -> err = ev
                            else -> {}
                        }
                    }
                    val e = err
                    val d = done
                    when {
                        d != null -> TextChatResult(d.conversationId ?: cid, d.reply, d.llmError, audio.toByteArray().takeIf { it.isNotEmpty() })
                        e != null -> TextChatResult(cid, "", "${e.code}: ${e.message}", null)
                        else -> TextChatResult(cid, "", "网络不稳定，回复没有完整收到，请重试。", null)
                    }
                }
            }) { raw -> ApiResult.HttpError(raw.status, "意外的响应") }
        return r
    }

    override suspend fun messages(conversationId: String, id: Identity): ApiResult<List<HistoryMessage>> {
        val ep = endpoint() ?: return noEndpoint
        val enc = withContext(Dispatchers.Default) { URLEncoder.encode(conversationId, "UTF-8") }
        return HttpJson.call("GET", "${ep.httpBase}${ServerEndpoint.CONVERSATIONS_PATH}/$enc/messages?limit=200", h(id), null, 8000) { raw ->
            TextChatProtocol.parseMessages(raw.body)?.let { ApiResult.Ok(it) } ?: ApiResult.HttpError(raw.status, "服务器返回格式不对")
        }
    }

    override suspend fun latestConversation(id: Identity): ApiResult<String?> {
        val ep = endpoint() ?: return noEndpoint
        return HttpJson.call("GET", "${ep.httpBase}${ServerEndpoint.CONVERSATIONS_PATH}?limit=1", h(id), null, 8000) { raw ->
            ApiResult.Ok(TextChatProtocol.parseLatest(raw.body))
        }
    }

    override suspend fun newConversation(id: Identity, prefix: String): ApiResult<String> {
        val ep = endpoint() ?: return noEndpoint
        val safe = prefix.filter { it.isLetterOrDigit() }.ifEmpty { "android" }.take(16)
        val body = """{"prefix":"$safe"}"""
        return HttpJson.call("POST", ep.httpBase + ServerEndpoint.CONVERSATIONS_PATH, h(id), body, 8000) { raw ->
            val cid = TextChatProtocol.parseNewConversationId(raw.body)
            if (cid.isNullOrBlank()) ApiResult.HttpError(raw.status, "服务器没有返回 conversation_id")
            else ApiResult.Ok(cid)
        }
    }

    override suspend fun listConversations(id: Identity, limit: Int): ApiResult<List<ConversationSummary>> {
        val ep = endpoint() ?: return noEndpoint
        val lim = limit.coerceIn(1, 200)
        return HttpJson.call("GET", "${ep.httpBase}${ServerEndpoint.CONVERSATIONS_PATH}?limit=$lim", h(id), null, 8000) { raw ->
            TextChatProtocol.parseConversations(raw.body)?.let { ApiResult.Ok(it) }
                ?: ApiResult.HttpError(raw.status, "服务器返回格式不对")
        }
    }

}
