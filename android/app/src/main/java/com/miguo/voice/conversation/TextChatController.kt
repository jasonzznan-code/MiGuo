package com.miguo.voice.conversation

import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.HistoryMessage
import com.miguo.voice.protocol.TextChatEvent
import com.miguo.voice.protocol.TextChatRequest
import com.miguo.voice.protocol.humanizeRuntime
import com.miguo.voice.protocol.TextChatResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 请求身份：登录后 bearer 非空（服务器以 token 为准）；user_id 模式下 bearer = null */
data class Identity(val userId: String?, val bearer: String?)

/** /chat/text 与 /conversations（实现见 transport/ChatApi.kt） */
interface ChatBackend {
    /** [onEvent] 可能在 IO 线程回调 */
    suspend fun sendText(req: TextChatRequest, id: Identity, onEvent: (TextChatEvent) -> Unit): ApiResult<TextChatResult>
    suspend fun messages(conversationId: String, id: Identity): ApiResult<List<HistoryMessage>>
    suspend fun latestConversation(id: Identity): ApiResult<String?>
    /** POST /api/v1/conversations → 新 conversation_id */
    suspend fun newConversation(id: Identity, prefix: String = "android"): ApiResult<String>
    /** GET /api/v1/conversations → 列表（最新在前） */
    suspend fun listConversations(id: Identity, limit: Int = 50): ApiResult<List<com.miguo.voice.protocol.ConversationSummary>>
}

/** 会话进行中时，文字走语音 WS（{"type":"text"}）；返回 false = 当前没有可用的语音会话 */
fun interface VoiceTextSink {
    fun sendTextInSession(text: String): Boolean
}

/** 文字回复朗读（App 里用 MediaPlayer 播 mp3） */
interface ReplyAudioPlayer {
    fun play(mp3: ByteArray)
    fun stop()
}

sealed class TextChatState(val name: String) {
    object Idle : TextChatState("idle")
    data class Sending(val text: String) : TextChatState("sending")
    data class Failed(val message: String) : TextChatState("failed")
    override fun toString() = name
}

/**
 * v0.2.0 文字输入状态机：
 * ```
 * Idle ─send(语音会话进行中)→ 走 WS text（状态不变，回复由 ConversationEngine 按普通轮次显示/播放）
 * Idle ─send(无会话)→ Sending ─done→ Idle ／ ─401→ 刷新 token → 重试一次 ／ ─失败→ Failed（下次 send 可重试）
 * ```
 * Sending 期间再 send 会被拒绝（返回 false），避免同一对话里并发两轮。
 */
class TextChatController(
    private val backend: ChatBackend,
    private val chat: ChatLog,
    private val scope: CoroutineScope,
    private val identity: () -> Identity,
    private val conversationId: () -> String?,
    private val onConversationId: (String) -> Unit,
    private val voice: VoiceTextSink? = null,
    private val speak: () -> Boolean = { false },
    private val useMemory: () -> Boolean = { true },
    /** TTS 音色（空/null = 不发，跟随服务器）；与 VoiceTextSink 的 voice 参数名区分 */
    private val ttsVoice: () -> String? = { null },
    private val ttsRate: () -> String = { "+0%" },
    private val ttsVolume: () -> String = { "+0%" },
    private val ttsPitch: () -> String = { "+0Hz" },
    private val player: ReplyAudioPlayer? = null,
    private val refreshAuth: suspend (String?) -> Boolean = { false },
    private val log: (String) -> Unit = {},
    /** v0.2.0：当前位置（HTTP 文字对话带上；会话中走 WS 时服务端已有会话位置） */
    private val location: () -> com.miguo.voice.protocol.WireLocation? = { null },
) {
    private val _state = MutableStateFlow<TextChatState>(TextChatState.Idle)
    val state: StateFlow<TextChatState> = _state
    private var n = 0
    private var job: Job? = null

    /** 上一次走的通道（"ws" | "http"），测试/诊断用 */
    var lastRoute: String? = null
        private set

    val busy: Boolean get() = _state.value is TextChatState.Sending

    fun send(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty() || busy) return false
        if (text.length > MAX_LEN) { _state.value = TextChatState.Failed("一次最多 $MAX_LEN 个字"); return false }
        if (voice?.sendTextInSession(text) == true) {
            lastRoute = "ws"
            log("text → ws (${text.length} chars)")
            if (_state.value is TextChatState.Failed) _state.value = TextChatState.Idle
            return true
        }
        lastRoute = "http"
        n += 1
        val key = "t$n"
        chat.ensure(key) { it.copy(user = text, userSource = "text", pending = true) }
        _state.value = TextChatState.Sending(text)
        player?.stop()
        val wantSpeak = speak()
        job = scope.launch {
            try {
                runHttp(key, text, wantSpeak)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finishFailed(key, humanizeRuntime(e.message ?: e.toString()))
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
        player?.stop()
        if (busy) _state.value = TextChatState.Idle
        chat.removeIf { it.pending && it.assistant.isEmpty() && it.userSource == "text" && it.key.startsWith("t") }
    }

    fun dismissError() { if (_state.value is TextChatState.Failed) _state.value = TextChatState.Idle }

    private suspend fun runHttp(key: String, text: String, wantSpeak: Boolean) {
        var id = identity()
        var r = call(key, text, wantSpeak, id)
        if (r is ApiResult.Unauthorized && id.bearer != null) {
            log("text chat 401 → refresh token")
            if (refreshAuth(id.bearer)) {
                id = identity()
                chat.update(key) { it.copy(assistant = "") }
                r = call(key, text, wantSpeak, id)
            }
        }
        when (r) {
            is ApiResult.Ok -> {
                val res = r.value
                res.conversationId?.let(onConversationId)
                if (res.reply.isBlank()) {
                    finishFailed(key, "米果暂时没有回复，请稍后再试。")
                    return
                }
                chat.update(key) { it.copy(assistant = res.reply, pending = false) }
                _state.value = TextChatState.Idle
                val audio = res.audioMp3
                if (wantSpeak && audio != null && audio.isNotEmpty()) player?.play(audio)
            }
            is ApiResult.Unauthorized -> finishFailed(key, "登录已失效，请重新登录")
            ApiResult.NotSupported -> finishFailed(key, "服务暂时不可用，请稍后再试。")
            is ApiResult.HttpError -> finishFailed(key, humanizeRuntime("HTTP ${r.status}：${r.message}"))
            is ApiResult.Network -> finishFailed(key, humanizeRuntime(r.message))
        }
    }

    private suspend fun call(key: String, text: String, wantSpeak: Boolean, id: Identity): ApiResult<TextChatResult> {
        val cid = conversationId()
        val req = TextChatRequest(
            text = text,
            conversationId = cid,
            resume = cid == null,
            speak = wantSpeak,
            useMemory = useMemory(),
            voice = ttsVoice(),
            rate = ttsRate(),
            volume = ttsVolume(),
            pitch = ttsPitch(),
            location = location(),
        )
        return backend.sendText(req, id) { ev -> scope.launch { onStreamEvent(key, ev) } }
    }

    private fun onStreamEvent(key: String, ev: TextChatEvent) {
        when (ev) {
            is TextChatEvent.Start -> ev.conversationId?.let(onConversationId)
            is TextChatEvent.Reply -> chat.update(key) { if (it.pending) it.copy(assistant = it.assistant + ev.text) else it }
            else -> {}
        }
    }

    private fun finishFailed(key: String, msg: String) {
        chat.update(key) { it.copy(pending = false, failed = msg) }
        _state.value = TextChatState.Failed(msg)
        log("text chat failed: $msg")
    }

    companion object {
        const val MAX_LEN = 2000
    }
}
