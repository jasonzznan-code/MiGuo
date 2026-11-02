package com.miguo.voice.conversation

import com.miguo.voice.BuildConfig
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.ClientControl
import com.miguo.voice.protocol.ErrorCatalog
import com.miguo.voice.protocol.humanizeRuntime
import com.miguo.voice.protocol.MetricsBoard
import com.miguo.voice.protocol.ReadableError
import com.miguo.voice.protocol.Ready
import com.miguo.voice.protocol.ServerError
import com.miguo.voice.protocol.ServerEndpoint
import com.miguo.voice.protocol.ServerEvent
import com.miguo.voice.protocol.SessionEnd
import com.miguo.voice.transport.HealthResult
import com.miguo.voice.transport.Inbound
import com.miguo.voice.transport.VoiceHealthApi
import com.miguo.voice.transport.VoiceSocketError
import com.miguo.voice.transport.VoiceTransport
import com.miguo.voice.transport.classifyNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 对话状态机（对应 iOS ConversationEngine.swift），唯一入口 toggle()：点一下开始，再点一下结束。
 *
 * 两种模式（start.barge_in，v0.2.0 默认跟随服务器 voice.barge_in，android 默认 true）：
 * - 半双工（barge_in=false，fix1 行为）：vad_end → thinking 关闭上传闸门；播放完 → listening 重新打开。
 * - 全双工打断（barge_in=true）：thinking/speaking 期间麦克风继续上传（VOICE_COMMUNICATION + AEC 消回声）；
 *   服务端检测到用户开口 → barge_in{reason=vad|playback} → 立即清空 AudioTrack、丢弃该 turn 剩余音频 → 你在说话。
 *   本地也在 vad_start（说话/思考中）时先清空播放，不等 barge_in 往返。
 * - 任何模式下都可以点“打断”：发 {"type":"interrupt"} + 本地立即停播 → 正在听。
 * - 文字：会话中 [sendText] 走 WS {"type":"text"}（服务端以 reason=text 取消当前回复）。
 * - 收到 response_end 且本地播放完毕 → 等 resume_listen_delay_ms（默认 200 ms）→ listening
 * 指标和字幕只看事件，不靠状态推断。
 *
 * 线程：所有状态只在 [scope]（主线程）上修改；音频回调、socket 回调都 post 回 scope。
 */
class ConversationEngine(
    private val audio: VoiceAudioIO,
    private val makeTransport: () -> VoiceTransport,
    /** 生成会话参数（内部会先刷新 /client/config）；抛 ReadableError 表示设置有误 */
    private val paramsProvider: suspend () -> SessionParams,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Double = { System.nanoTime() / 1e9 },
    /** v0.2.0：聊天气泡（语音轮 + 文字轮 + 历史，按顺序）；captions 保留给旧逻辑/测试 */
    val chat: ChatLog = ChatLog(),
) {
    // ---- 对 UI 公开的状态
    private val _state = MutableStateFlow<ConversationState>(ConversationState.Idle)
    val state: StateFlow<ConversationState> = _state
    private val _captions = MutableStateFlow<List<CaptionTurn>>(emptyList())
    val captions: StateFlow<List<CaptionTurn>> = _captions
    /** 非致命提示（横幅） */
    private val _notice = MutableStateFlow<ReadableError?>(null)
    val notice: StateFlow<ReadableError?> = _notice
    private val _metrics = MutableStateFlow(MetricsBoard())
    val metrics: StateFlow<MetricsBoard> = _metrics
    private val _haptic = MutableStateFlow<HapticEvent?>(null)
    val haptic: StateFlow<HapticEvent?> = _haptic
    private val _health = MutableStateFlow<HealthResult?>(null)
    val health: StateFlow<HealthResult?> = _health
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    /** 客户端静音：丢弃上行麦帧，不改服务端协议；关闸与 barge-in 的 setUploadGate 独立。 */
    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted
    fun setMicMuted(muted: Boolean) {
        if (_micMuted.value == muted) return
        _micMuted.value = muted
        log(if (muted) "mic muted (client uplink drop)" else "mic unmuted")
    }
    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId
    private val _route = MutableStateFlow<AudioRouteInfo?>(null)
    val route: StateFlow<AudioRouteInfo?> = _route
    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog

    /** v0.2.0：本次会话是否全双工打断（start.barge_in 且服务器 ready.config.barge_in 未否认） */
    val bargeInEnabled: Boolean
        get() {
            val p = params ?: return false
            return p.start.bargeIn && (ready?.bargeIn ?: true)
        }
    /** ready 回调（AppModel 用来保存稳定的对话 ID） */
    var onReady: ((Ready) -> Unit)? = null
    /** 服务器拒绝 token（error code=unauthorized）时回调，参数 = 被拒的 token */
    var onUnauthorized: ((String?) -> Unit)? = null
    var lastSessionEnd: SessionEnd? = null
        private set
    var lastStreamEnd: String? = null
        private set

    // ---- 可调参数（测试里会缩短）
    var stopWaitSec = 2.0
    var readyTimeoutSec = 12.0
    var pingIntervalSec = 15.0
    var onLog: ((String) -> Unit)? = null

    // ---- 会话内部状态
    private var transport: VoiceTransport? = null
    private var params: SessionParams? = null
    private var micStream: ReceiveChannel<ByteArray>? = null
    private var epoch = 0
    private var readerJob: Job? = null
    private var uplinkJob: Job? = null
    private var pingJob: Job? = null
    private var readyTimeoutJob: Job? = null
    private var resumeJob: Job? = null
    private val serverDone = HashSet<Int>()
    private val firstAudioSeen = HashSet<Int>()
    private var cancelledUpTo = 0
    private var lastFatal: ServerError? = null
    private var ready: Ready? = null
    private var startTappedAt = 0.0
    private var nextCaptionId = 0
    private var hapticCounter = 0
    private var lastLevelAt = 0.0

    /** 当前生效的服务端配置（每次 start 刷新） */
    val config: ClientConfig get() = params?.config ?: lastConfig
    var lastConfig: ClientConfig = ClientConfig()

    init {
        audio.onEvent = { ev -> scope.launch { onAudioEvent(ev) } }
    }

    // MARK: - 入口

    fun toggle() {
        when (_state.value) {
            is ConversationState.Idle, is ConversationState.Error -> scope.launch { start() }
            ConversationState.Stopping -> {}
            else -> scope.launch { stop() }
        }
    }

    fun dismissNotice() { _notice.value = null }

    fun setHealth(h: HealthResult?) { _health.value = h }

    fun dismissError() {
        if (_state.value is ConversationState.Error) _state.value = ConversationState.Idle
    }

    /** GET /api/v1/voice/health（进入语音页时调用一次；degraded 时展示原因） */
    suspend fun refreshHealth(endpoint: ServerEndpoint?, userId: String, apiKey: String?, loadStt: Boolean = false) {
        if (endpoint == null) { _health.value = null; return }
        _health.value = VoiceHealthApi.check(endpoint, userId, apiKey, loadStt = loadStt)
    }

    // MARK: - 开始

    suspend fun start() {
        if (!BuildConfig.FEATURE_VOICE) {
            // store/开源精简：不连 VoiceSocket，避免半残语音路径
            fail(ReadableError(
                "feature_disabled",
                "语音通话未启用",
                "FEATURE_VOICE=false",
                "当前渠道未开放沉浸语音；请使用文字主会话。",
                retryable = false,
            ))
            return
        }
        val s0 = _state.value
        if (s0 !is ConversationState.Idle && s0 !is ConversationState.Error) return
        epoch += 1
        val my = epoch
        _notice.value = null
        resetSessionState()
        _captions.value = emptyList()
        _metrics.value = MetricsBoard()
        _state.value = ConversationState.Starting
        startTappedAt = clock()
        log("start tapped")

        // fix1：catch Throwable（不只是 Exception）。真机崩溃就是这里：makeParams 触发 SessionIdFactory 类初始化，
        // (?U) 正则在 Android 上抛 PatternSyntaxException → ExceptionInInitializerError（是 Error，不是 Exception），
        // 原来的 catch (e: Exception) 接不住 → 进程退出。
        val p = try {
            paramsProvider()
        } catch (e: ReadableError) {
            fail(e); return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("✗ paramsProvider 异常：${e.stackTraceToString().take(2000)}")
            fail(ReadableError("config", "设置有误 / 内部错误", e.toString(), "请到设置里检查服务器地址、user_id、API Key；仍不行请把“崩溃报告”发给我们。"))
            return
        }
        if (epoch != my || _state.value != ConversationState.Starting) return
        params = p
        lastConfig = p.config
        log("config source=${p.config.source} v=${p.config.configVersion} out=${p.start.outputFormat} frame=${p.frameMs}ms vad_silence=${p.start.vad.minSilenceMs}")

        val granted = try {
            audio.requestRecordPermission()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("✗ 申请麦克风权限异常：$e")
            false
        }
        if (!granted) { if (epoch == my && _state.value == ConversationState.Starting) fail(ErrorCatalog.micDenied()); return }
        if (epoch != my || _state.value != ConversationState.Starting) return

        val mic = try {
            audio.startSession(p.start.sampleRate, p.frameMs, p.config.playback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("✗ startSession 异常：$e")
            try { audio.stopSession() } catch (_: Throwable) {}
            fail(ReadableError("audio_start", "无法启动麦克风/音频引擎", e.message ?: e.toString(),
                "关闭其他正在录音/通话的 App（微信语音、录音机等）后重试；确认已允许麦克风权限。"))
            return
        }
        audio.setUploadGate(false)
        micStream = mic
        _route.value = try { audio.routeInfo() } catch (e: Throwable) { null }

        val tr = makeTransport()
        transport = tr
        val inbound = try {
            tr.connect(p.endpoint.sessionUrl, ServerEndpoint.headers(p.userId, p.apiKey, p.bearer), p.start, 8000)
        } catch (e: CancellationException) {
            teardown()
            throw e
        } catch (e: Throwable) {
            if (epoch != my || _state.value != ConversationState.Starting) return
            teardown()
            fail(readable(e))
            return
        }
        if (epoch != my || _state.value != ConversationState.Starting) {
            tr.close(1000)
            return
        }
        log("ws connected, start sent: session_id=${p.start.sessionId ?: "(resume=${p.start.resume})"} out=${p.start.outputFormat} barge_in=${p.start.bargeIn} auth=${if (p.bearer != null) "bearer" else "user_id"}")

        readerJob = scope.launch {
            for (m in inbound) {
                if (epoch != my) break
                try {
                    receive(m, my)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("✗ 处理服务器消息异常（已忽略）：$e")   // fix1：单条消息出错不再让 App 闪退
                }
            }
        }
        val rt = readyTimeoutSec
        readyTimeoutJob = scope.launch {
            delay((rt * 1000).toLong())
            if (epoch == my && _state.value == ConversationState.Starting) {
                teardown()
                fail(ReadableError("ready_timeout", "服务器没有响应 start", "${rt.toInt()} 秒内没收到 ready。",
                    "检查服务器日志 logs\\server.err.log。", retryable = true))
            }
        }
    }

    // MARK: - 结束

    suspend fun stop() {
        when (_state.value) {
            ConversationState.Idle, ConversationState.Stopping -> return
            is ConversationState.Error -> { _state.value = ConversationState.Idle; return }
            else -> {}
        }
        val my = epoch
        _state.value = ConversationState.Stopping
        resumeJob?.cancel()
        audio.flushPlayback()
        audio.setUploadGate(false)
        val tr = transport
        if (tr != null && ready != null) {
            if (!tr.send(ClientControl.Stop(waitForReply = false))) log("send stop failed")
            val deadline = clock() + stopWaitSec
            while (lastSessionEnd == null && lastStreamEnd == null && clock() < deadline && epoch == my) {
                delay(50)
            }
            log(when {
                lastSessionEnd != null -> "session_end received"
                lastStreamEnd != null -> "socket closed by server without session_end ($lastStreamEnd)"
                else -> "session_end wait timed out"
            })
        }
        if (epoch != my) return
        teardown()
        if (epoch != my || _state.value != ConversationState.Stopping) return
        _state.value = ConversationState.Idle
        emitHaptic(HapticKind.STOP)
    }

    // MARK: - 入站

    internal fun receive(m: Inbound, e: Int) {
        if (e != epoch) return
        when (m) {
            is Inbound.Event -> handle(m.event)
            is Inbound.Audio -> handleAudio(m)
            is Inbound.OrphanBinary -> log("orphan binary frame (${m.bytes} B) ignored")
            is Inbound.Undecodable -> log("undecodable frame ignored: ${m.error} ${m.text}")
            is Inbound.Closed -> streamEnded(m.error, e)
        }
    }

    internal fun handle(ev: ServerEvent) {
        when (ev) {
            is ServerEvent.ReadyEv -> {
                val r = ev.ready
                readyTimeoutJob?.cancel()
                ready = r
                _sessionId.value = r.sessionId
                _metrics.value = _metrics.value.copy(connectMs = (clock() - startTappedAt) * 1000)
                val conv = r.conversation
                log("ready session_id=${r.sessionId} audio_out=${r.audioOut.format}@${r.audioOut.sampleRate} vad=${r.vadEngine ?: "?"} barge_in=${r.bargeIn ?: "?"} full_duplex=$bargeInEnabled" +
                    (conv?.let { " conversation resumed=${it.resumed} turns=${it.turns} history=${it.history.size} persistent=${it.persistent ?: "?"}" } ?: " (no conversation info: v1 server)"))
                if (conv != null && conv.history.isNotEmpty()) chat.setHistory(conv.history)
                try { onReady?.invoke(r) } catch (e: Throwable) { log("✗ onReady 异常：$e") }
                if (_state.value != ConversationState.Starting) return
                _state.value = ConversationState.Listening
                audio.setUploadGate(true)
                startUplink()
                startPing()
                emitHaptic(HapticKind.START)
            }
            is ServerEvent.VadStartEv -> {
                val t = ev.v.turnId
                log("vad_start turn=$t")
                if (_state.value == ConversationState.Stopping) return
                val prev = _state.value
                val pt = prev.turn
                if (bargeInEnabled && pt != null && pt != t && (prev is ConversationState.Recognizing || prev is ConversationState.Thinking || prev is ConversationState.Synthesizing || prev is ConversationState.Speaking)) {
                    // 全双工：用户在米果说话/思考时开口 → 本地立即停播（服务端的 barge_in 随后到达，重复 flush 无害）
                    cutPlayback(pt, "vad_start")
                }
                resumeJob?.cancel()
                _state.value = ConversationState.UserSpeaking(t)
                captionSlot(t)
                _metrics.value = _metrics.value.update(t) { it }
                emitHaptic(HapticKind.VAD_START)
            }
            is ServerEvent.VadEndEv -> {
                val t = ev.v.turnId
                log("vad_end turn=$t speech_ms=${ev.v.speechMs ?: 0}")
                val now = clock()
                _metrics.value = _metrics.value.update(t) { it.copy(vadEndRecvAt = now) }
                if (_state.value == ConversationState.Stopping) return
                _state.value = ConversationState.Recognizing(t)
                if (!bargeInEnabled) audio.setUploadGate(false)
            }
            is ServerEvent.TranscriptPartialEv -> {
                val t = ev.t
                if (t.text.isBlank()) return
                log("transcript_partial turn=${t.turnId} ${t.text.take(40)}")
                captionSlot(t.turnId)
                // 仅更新字幕/气泡草稿，不推进 Thinking（等最终 transcript）
                updateCaption(t.turnId) { it.copy(user = t.text) }
            }
            is ServerEvent.TranscriptEv -> {
                val t = ev.t
                log("transcript turn=${t.turnId} ${t.text}")
                captionSlot(t.turnId)
                updateCaption(t.turnId) { it.copy(user = t.text, userSource = t.source) }
                if (_state.value == ConversationState.Stopping) return
                // 有最终转写 → 进入思考（LLM）；尚无音频时不要跳到合成
                if (_state.value.turn == t.turnId || _state.value is ConversationState.Recognizing) {
                    _state.value = ConversationState.Thinking(t.turnId)
                }
            }
            is ServerEvent.NoSpeechEv -> {
                val n = ev.n
                log("no_speech turn=${n.turnId} text=${n.text ?: ""}")
                _metrics.value = _metrics.value.update(n.turnId) { it.copy(noSpeech = true) }
                _captions.value = _captions.value.filterNot { it.turnId == n.turnId && it.user.isEmpty() && it.assistant.isEmpty() }
                val k = chatKey(n.turnId)
                chat.removeIf { it.key == k && it.user.isEmpty() && it.assistant.isEmpty() }
                if (_state.value.turn == n.turnId) backToListening()
            }
            is ServerEvent.ResponseStart -> {
                captionSlot(ev.turnId)
                val st = _state.value
                if (st.turn == ev.turnId && (st is ConversationState.Thinking || st is ConversationState.Recognizing)) {
                    _state.value = ConversationState.Synthesizing(ev.turnId)
                }
            }
            is ServerEvent.ReplyTextEv -> {
                captionSlot(ev.r.turnId)
                updateCaption(ev.r.turnId) { it.copy(assistant = it.assistant + ev.r.text) }
                val st = _state.value
                if (st.turn == ev.r.turnId && st is ConversationState.Thinking) {
                    _state.value = ConversationState.Synthesizing(ev.r.turnId)
                }
            }
            is ServerEvent.AudioHeaderEv -> log("unpaired audio header seq=${ev.h.seq}")   // 正常情况下已被 InboundPairer 配对
            is ServerEvent.ResponseEndEv -> {
                val r = ev.r
                log("response_end turn=${r.turnId} interrupted=${r.interrupted ?: false} error=${r.error ?: "-"}")
                serverDone.add(r.turnId)
                audio.endOfTurnAudio(r.turnId)
                r.metrics?.let { m -> _metrics.value = _metrics.value.update(r.turnId) { it.copy(server = m) } }
                if (r.interrupted == true) {
                    _metrics.value = _metrics.value.update(r.turnId) { it.copy(interrupted = true) }
                    updateCaption(r.turnId) { it.copy(interrupted = true) }
                }
                r.error?.let { err ->
                    updateCaption(r.turnId) { it.copy(failed = err) }
                    if (_notice.value == null) _notice.value = config.apply(ReadableError("turn_failed", "这一轮处理失败", err, retryable = true,
                        vars = mapOf("server_message" to err), runtimeMessage = true))
                }
                maybeFinishTurn()
            }
            is ServerEvent.MetricsEv -> {
                val m = ev.m
                m.turnId?.let { t -> _metrics.value = _metrics.value.update(t) { it.copy(server = m) } }
                log("metrics turn=${m.turnId ?: -1} first_audio_ms=${m.firstAudioMs} stt_ms=${m.sttMs} llm_ms=${m.llmMs} tts_ms=${m.ttsMs} total_ms=${m.totalMs}")
            }
            is ServerEvent.BargeInEv -> {
                val t = ev.b.turnId ?: _state.value.turn ?: cancelledUpTo
                val already = t <= cancelledUpTo
                cutPlayback(t, "barge_in reason=${ev.b.reason ?: "?"} active=${ev.b.active ?: "?"}")
                if (!already) emitHaptic(HapticKind.BARGE_IN)
                val st = _state.value
                // 被打断的是当前轮且还停在 thinking/speaking（例如 client_interrupt / text）→ 回到正在听
                if (st.turn == t && (st is ConversationState.Recognizing || st is ConversationState.Thinking || st is ConversationState.Synthesizing || st is ConversationState.Speaking)) backToListening()
                else maybeFinishTurn()
            }
            is ServerEvent.ErrorEv -> {
                val e = ev.e
                log("error code=${e.code} fatal=${e.isFatal} ${e.message ?: ""}")
                if (e.isFatal) lastFatal = e else _notice.value = config.apply(ErrorCatalog.fromServer(e))
                if (e.code == "unauthorized") try { onUnauthorized?.invoke(params?.bearer) } catch (x: Throwable) { log("✗ onUnauthorized 异常：$x") }
            }
            is ServerEvent.SessionEndEv -> {
                val s = ev.s
                log("session_end reason=${s.reason} turns=${s.turns ?: 0}")
                lastSessionEnd = s
                if (_state.value == ConversationState.Stopping) return
                val my = epoch
                val fatal = lastFatal
                scope.launch {
                    teardown()
                    if (epoch != my) return@launch
                    if (fatal != null) {
                        fail(ErrorCatalog.fromServer(fatal))
                    } else {
                        _state.value = ConversationState.Idle
                        if (s.reason != "stop" && s.reason != "client_closed") {
                            _notice.value = config.apply(ReadableError("session_end_${s.reason}", "服务器结束了会话（${s.reason}）",
                                "", "点一下重新开始。", retryable = true))
                        }
                        emitHaptic(HapticKind.STOP)
                    }
                }
            }
            is ServerEvent.PongEv -> ev.p.t?.let { t -> _metrics.value = _metrics.value.addRtt(maxOf(0.0, clock() * 1000 - t)) }
            is ServerEvent.Unknown -> log("unknown event ${ev.type} ignored")
        }
    }

    private fun handleAudio(a: Inbound.Audio) {
        val h = a.header
        if (h.turnId <= cancelledUpTo) return            // 被打断 turn 的在途音频
        when (_state.value) {
            ConversationState.Idle, ConversationState.Stopping, ConversationState.Starting, is ConversationState.Error -> return
            else -> {}
        }
        if (firstAudioSeen.add(h.turnId)) {
            val now = clock()
            _metrics.value = _metrics.value.update(h.turnId) { it.copy(firstAudioRecvAt = now) }
            log("first audio turn=${h.turnId} format=${h.format} bytes=${h.bytes}")
        }
        if (!bargeInEnabled) audio.setUploadGate(false)  // 轮流说话：播放期间暂停上传
        if (_state.value != ConversationState.Speaking(h.turnId)) _state.value = ConversationState.Speaking(h.turnId)
        audio.enqueue(a.data, h.format, h.sampleRate, h.turnId, h.seq)
    }

    // MARK: - 音频事件

    internal fun onAudioEvent(ev: AudioIOEvent) {
        try { onAudioEventUnsafe(ev) } catch (e: Throwable) { log("✗ 音频事件处理异常（已忽略）：$e") }
    }

    /** fix1：协程里出现未捕获异常时（CoroutineExceptionHandler 调用）把会话收掉并显示错误，而不是闪退 */
    fun onUncaught(e: Throwable) {
        log("✗ 未捕获异常：$e")
        try { teardown() } catch (_: Throwable) {}
        fail(ReadableError("internal", "内部错误，会话已结束", e.toString(), "点一下重试；如反复出现，请把“崩溃报告”发给我们。"))
    }

    private fun onAudioEventUnsafe(ev: AudioIOEvent) {
        when (ev) {
            is AudioIOEvent.Level -> {
                val now = clock()
                if (now - lastLevelAt > 0.05) { _level.value = ev.rms; lastLevelAt = now }
            }
            is AudioIOEvent.FirstBufferScheduled -> {
                val at = clock() + ev.audibleDelaySec
                _metrics.value = _metrics.value.update(ev.turn) { if (it.firstAudibleAt == null) it.copy(firstAudibleAt = at) else it }
            }
            AudioIOEvent.PlaybackDrained -> maybeFinishTurn()
            AudioIOEvent.InterruptionBegan -> {
                if (!_state.value.isActive) return
                _notice.value = ReadableError("interrupted", "来电或其他应用打断了对话", "", "点一下继续。")
                scope.launch { stop() }
            }
            is AudioIOEvent.RouteChanged -> {
                _route.value = audio.routeInfo()
                log("route change: ${ev.reason}")
                if (ev.oldDeviceUnavailable) { audio.flushPlayback(); maybeFinishTurn() }   // 拔耳机：停止当前播放，避免突然外放
            }
            is AudioIOEvent.PlaybackRestarted -> {
                _route.value = audio.routeInfo()
                log("playback restarted (${ev.reason}) ok=${ev.ok}")
            }
            is AudioIOEvent.DecodeError -> log("decode error: ${ev.message}")
            is AudioIOEvent.CaptureFailed -> {
                log("capture failed: ${ev.message}")
                if (!_state.value.isActive) return
                teardown()
                fail(ReadableError("capture_failed", "麦克风录音中断", ev.message, "关闭其他占用麦克风的 App 后点一下重试。", retryable = true))
            }
        }
    }

    // MARK: - 轮次收尾

    private fun maybeFinishTurn() {
        val st = _state.value
        val t = st.turn ?: return
        if (st !is ConversationState.Recognizing && st !is ConversationState.Thinking && st !is ConversationState.Synthesizing && st !is ConversationState.Speaking) return
        if (!serverDone.contains(t) || !audio.isPlaybackIdle) return
        resumeJob?.cancel()
        val my = epoch
        val delayMs = config.voice.resumeListenDelayMs.toLong()
        resumeJob = scope.launch {
            delay(delayMs)
            val s = _state.value
            if (epoch != my || s.turn != t) return@launch
            if (s !is ConversationState.Recognizing && s !is ConversationState.Thinking && s !is ConversationState.Synthesizing && s !is ConversationState.Speaking) return@launch
            if (!audio.isPlaybackIdle) return@launch
            backToListening()
        }
    }

    private fun backToListening() {
        resumeJob?.cancel()
        _state.value = ConversationState.Listening
        audio.setUploadGate(true)
        log("listening (upload resumed)")
    }

    // MARK: - 后台任务

    private fun startUplink() {
        val mic = micStream ?: return
        val tr = transport ?: return
        uplinkJob?.cancel()
        uplinkJob = scope.launch(ioDispatcher) {
            for (frame in mic) {
                if (!isActive) break
                if (_micMuted.value) continue
                if (!tr.sendAudio(frame)) break
            }
        }
    }

    private fun startPing() {
        val tr = transport ?: return
        pingJob?.cancel()
        val interval = pingIntervalSec
        pingJob = scope.launch {
            var first = true
            while (isActive) {
                delay(((if (first) 1.0 else interval) * 1000).toLong())
                first = false
                if (!tr.send(ClientControl.Ping((clock() * 1000).toLong()))) return@launch
            }
        }
    }

    // MARK: - 收尾 / 错误

    private fun streamEnded(error: VoiceSocketError?, e: Int) {
        if (e != epoch) return
        lastStreamEnd = error?.message ?: "finished"
        when (_state.value) {
            ConversationState.Stopping, ConversationState.Idle, is ConversationState.Error -> return
            else -> {}
        }
        if (lastSessionEnd != null) return     // session_end 分支已经处理
        val f = lastFatal
        val r = when {
            f != null -> ErrorCatalog.fromServer(f)
            error != null -> readable(error)
            else -> ReadableError("closed", "服务器关闭了连接", "", retryable = true, vars = mapOf("code" to "1000"))
        }
        log("stream ended: ${r.code}")
        scope.launch {
            teardown()
            if (epoch == e) fail(r)
        }
    }

    private fun readable(error: Throwable): ReadableError {
        if (error is ReadableError) return error
        val loop = params?.endpoint?.isLoopback ?: false
        return when (error) {
            is VoiceSocketError.HandshakeRejected -> ErrorCatalog.handshake(error.status)
            is VoiceSocketError.Closed -> ErrorCatalog.close(error.code, error.reason)
                ?: ReadableError("closed", "连接已关闭", error.reason ?: "", retryable = true,
                    vars = mapOf("code" to "${error.code}", "reason" to (error.reason ?: "")), runtimeMessage = !error.reason.isNullOrBlank())
            is VoiceSocketError.Transport -> ErrorCatalog.network(humanizeRuntime(error.description), classifyNetwork(error.cause0), loop)
            is VoiceSocketError.NotConnected -> ErrorCatalog.network("未连接", ErrorCatalog.NetKind.OTHER, loop)
            else -> ErrorCatalog.network(humanizeRuntime(error.message ?: error.toString()), classifyNetwork(error), loop)
        }
    }

    private fun fail(e: ReadableError) {
        val mapped = config.apply(e)
        log("error state: ${mapped.code} ${mapped.title}")
        _state.value = ConversationState.Error(mapped)
        emitHaptic(HapticKind.ERROR)
    }

    private fun teardown() {
        readyTimeoutJob?.cancel(); readyTimeoutJob = null
        resumeJob?.cancel(); resumeJob = null
        uplinkJob?.cancel(); uplinkJob = null
        pingJob?.cancel(); pingJob = null
        readerJob?.cancel(); readerJob = null
        val tr = transport
        transport = null
        micStream = null
        audio.setUploadGate(false)
        audio.stopSession()
        _level.value = 0f
        _micMuted.value = false
        tr?.close(1000)
    }

    private fun resetSessionState() {
        serverDone.clear()
        firstAudioSeen.clear()
        cancelledUpTo = 0
        lastFatal = null
        lastSessionEnd = null
        lastStreamEnd = null
        ready = null
        _sessionId.value = null
    }

    // MARK: - 小工具

    private fun captionSlot(turnId: Int) {
        chat.ensure(chatKey(turnId))
        if (_captions.value.any { it.turnId == turnId }) return
        nextCaptionId += 1
        _captions.value = (_captions.value + CaptionTurn(nextCaptionId, turnId)).takeLast(50)
    }

    private fun updateCaption(turnId: Int, f: (CaptionTurn) -> CaptionTurn) {
        var updated: CaptionTurn? = null
        _captions.value = _captions.value.map { if (it.turnId == turnId) f(it).also { u -> updated = u } else it }
        val u = updated ?: return
        chat.update(chatKey(turnId)) {
            it.copy(user = u.user, assistant = u.assistant, userSource = u.userSource, interrupted = u.interrupted, failed = u.failed)
        }
    }

    private fun chatKey(turnId: Int) = "v$epoch-$turnId"

    /** 立即停播并丢弃 ≤ turn 的在途音频 */
    private fun cutPlayback(turn: Int, why: String) {
        val t0 = clock()
        audio.flushPlayback(fadeOutMs = VoiceAudioIO.BARGE_FADE_MS)
        _metrics.value = _metrics.value.copy(lastBargeStopMs = (clock() - t0) * 1000)
        cancelledUpTo = maxOf(cancelledUpTo, turn)
        updateCaption(turn) { it.copy(interrupted = true) }
        _metrics.value = _metrics.value.update(turn) { it.copy(interrupted = true) }
        log("cut playback turn=$turn ($why)")
    }

    // MARK: - v0.2.0 打断 / 文字

    /** 点“打断”：发 interrupt，本地立即停播 → 正在听。不在会话里返回 false。 */
    fun interrupt(): Boolean {
        val st = _state.value
        val tr = transport ?: return false
        if (ready == null) return false
        val t = st.turn
        if (st !is ConversationState.Recognizing && st !is ConversationState.Thinking && st !is ConversationState.Synthesizing && st !is ConversationState.Speaking) return false
        if (!tr.send(ClientControl.Interrupt)) log("send interrupt failed")
        if (t != null) cutPlayback(t, "client tap")
        emitHaptic(HapticKind.BARGE_IN)
        backToListening()
        return true
    }

    /** 会话进行中：文字走 WS（服务端会以 reason=text 取消正在进行的回复）。没有会话返回 false。 */
    fun sendText(text: String): Boolean {
        val tr = transport ?: return false
        if (ready == null) return false
        when (_state.value) {
            ConversationState.Listening, is ConversationState.UserSpeaking, is ConversationState.Recognizing, is ConversationState.Thinking, is ConversationState.Synthesizing, is ConversationState.Speaking -> {}
            else -> return false
        }
        if (!tr.send(ClientControl.Text(text))) { log("send text failed"); return false }
        _state.value.turn?.let { t ->
            val st = _state.value
            if (st is ConversationState.Recognizing || st is ConversationState.Thinking || st is ConversationState.Synthesizing || st is ConversationState.Speaking) cutPlayback(t, "text input")
        }
        log("text sent in session (${text.length} chars)")
        return true
    }

    /** v0.2.0：会话中更新位置（服务端回 location_ack）。没有会话 → false（下次 start 会带上） */
    fun sendLocation(loc: com.miguo.voice.protocol.WireLocation): Boolean {
        if (!loc.isValid) return false
        val tr = transport ?: return false
        if (ready == null || !_state.value.isActive || _state.value == ConversationState.Stopping) return false
        val ok = tr.send(ClientControl.Location(loc))
        log(if (ok) "location sent in session (±${loc.accuracyM ?: "?"}m)" else "send location failed")
        return ok
    }

    /** 当前是否有可发文字的语音会话 */
    val inSession: Boolean get() = transport != null && ready != null && _state.value.isActive && _state.value != ConversationState.Stopping

    private fun emitHaptic(k: HapticKind) {
        hapticCounter += 1
        _haptic.value = HapticEvent(hapticCounter, k)
    }

    private fun log(s: String) {
        val line = String.format(Locale.US, "%.3f ", clock()) + s
        _eventLog.value = (_eventLog.value + line).takeLast(120)
        onLog?.invoke(line)
    }
}
