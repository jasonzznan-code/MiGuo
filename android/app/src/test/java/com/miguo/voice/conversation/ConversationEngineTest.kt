package com.miguo.voice.conversation

import com.miguo.voice.protocol.AudioPayloadFormat
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.ClientControl
import com.miguo.voice.protocol.ServerEventDecoder
import com.miguo.voice.protocol.StartMessage
import com.miguo.voice.transport.Inbound
import com.miguo.voice.transport.VoiceSocketError
import com.miguo.voice.transport.VoiceTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeAudio : VoiceAudioIO {
    override var onEvent: ((AudioIOEvent) -> Unit)? = null
    val mic = Channel<ByteArray>(Channel.UNLIMITED)
    val gate = ArrayList<Boolean>()
    val enqueued = ArrayList<Pair<Int, AudioPayloadFormat>>()
    var started = 0
    var stopped = 0
    var tuning: ClientConfig.PlaybackTuning? = null
    override var isPlaybackIdle = true
    override suspend fun requestRecordPermission() = true
    override fun startSession(sendSampleRate: Int, frameMs: Int, tuning: ClientConfig.PlaybackTuning): ReceiveChannel<ByteArray> {
        started++; this.tuning = tuning; return mic
    }
    override fun stopSession() { stopped++ }
    override fun setUploadGate(open: Boolean) { gate += open }
    override fun enqueue(data: ByteArray, format: AudioPayloadFormat, sampleRate: Int, turn: Int, seq: Int) {
        enqueued += turn to format; isPlaybackIdle = false
    }
    override fun endOfTurnAudio(turn: Int) {}
    override fun flushPlayback(fadeOutMs: Int) { isPlaybackIdle = true }
    override fun routeInfo(): AudioRouteInfo? = null
}

private class FakeTransport(private val reject: Int? = null) : VoiceTransport {
    val inbound = Channel<Inbound>(Channel.UNLIMITED)
    val sent = ArrayList<ClientControl>()
    var audioFrames = 0
    var closed = false
    var start: StartMessage? = null
    override suspend fun connect(url: String, headers: Map<String, String>, start: StartMessage, timeoutMs: Long): ReceiveChannel<Inbound> {
        reject?.let { throw VoiceSocketError.HandshakeRejected(it) }
        this.start = start
        return inbound
    }
    override fun send(c: ClientControl): Boolean { sent += c; return true }
    override fun sendAudio(d: ByteArray): Boolean { audioFrames++; return true }
    override fun close(code: Int) { closed = true }
    fun ev(json: String) { inbound.trySend(Inbound.Event(ServerEventDecoder.decode(json))) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationEngineTest {
    private val serverCfg = ClientConfig().mergedWith(ClientConfig.parseJson(
        """{"config_version":2,"voice":{"output_format":"pcm_s16le","resume_listen_delay_ms":300},
           "playback":{"stall_timeout_s":7},"labels":{"listening":"在听"},
           "errors":{"auth_failed":{"title":"钥匙不对"}}}"""))

    private fun TestScope.engine(audio: FakeAudio, tr: FakeTransport) = ConversationEngine(
        audio = audio,
        makeTransport = { tr },
        paramsProvider = { AppSettings().makeParams("k", serverCfg, "android-test") },
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime / 1000.0 },
    )

    @Test fun fullTurnHalfDuplex() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport()
        val e = engine(audio, tr)
        e.toggle(); runCurrent()
        assertEquals(ConversationState.Starting, e.state.value)
        assertEquals(AudioPayloadFormat.PcmS16LE, tr.start!!.outputFormat)   // 来自服务端配置
        assertEquals(7.0, audio.tuning!!.stallTimeoutS, 0.0)                  // 看门狗阈值来自服务端配置
        assertFalse(tr.start!!.bargeIn)

        tr.ev("""{"type":"ready","session_id":"android-test","audio_out":{"format":"pcm_s16le","sample_rate":24000}}""")
        runCurrent()
        assertEquals(ConversationState.Listening, e.state.value)
        assertEquals("在听", e.state.value.title(e.config))
        assertTrue(audio.gate.last())
        audio.mic.trySend(ByteArray(640)); audio.mic.trySend(ByteArray(640)); runCurrent()
        assertEquals(2, tr.audioFrames)

        tr.ev("""{"type":"vad_start","turn_id":1}"""); runCurrent()
        assertEquals(ConversationState.UserSpeaking(1), e.state.value)
        tr.ev("""{"type":"vad_end","turn_id":1}"""); runCurrent()
        assertEquals(ConversationState.Recognizing(1), e.state.value)
        assertFalse("说完后关闭上传闸门（半双工）", audio.gate.last())

        tr.ev("""{"type":"transcript_partial","turn_id":1,"text":"你","partial":true}"""); runCurrent()
        assertEquals("你", e.captions.value.single().user)
        assertEquals(ConversationState.Recognizing(1), e.state.value)

        tr.ev("""{"type":"transcript","turn_id":1,"text":"你好"}""")
        runCurrent()
        assertEquals(ConversationState.Thinking(1), e.state.value)
        tr.ev("""{"type":"reply_text","turn_id":1,"seq":0,"text":"你好呀"}""")
        tr.inbound.trySend(Inbound.Audio(
            (ServerEventDecoder.decode("""{"type":"audio","turn_id":1,"seq":0,"format":"pcm_s16le","sample_rate":24000,"bytes":2}""")
                as com.miguo.voice.protocol.ServerEvent.AudioHeaderEv).h, byteArrayOf(0, 0)))
        runCurrent()
        assertEquals(ConversationState.Speaking(1), e.state.value)
        assertEquals(listOf(1 to AudioPayloadFormat.PcmS16LE), audio.enqueued)
        assertEquals("你好", e.captions.value.single().user)
        assertEquals("你好呀", e.captions.value.single().assistant)

        tr.ev("""{"type":"response_end","turn_id":1}"""); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals("还在播放 → 保持“米果在说”", ConversationState.Speaking(1), e.state.value)

        audio.isPlaybackIdle = true
        audio.onEvent!!(AudioIOEvent.PlaybackDrained); runCurrent()
        advanceTimeBy(200); runCurrent()
        assertEquals("resume_listen_delay_ms=300 未到", ConversationState.Speaking(1), e.state.value)
        advanceTimeBy(150); runCurrent()
        assertEquals(ConversationState.Listening, e.state.value)
        assertTrue(audio.gate.last())

        // 再点一下：stop → 等 session_end → Idle
        e.toggle(); runCurrent()
        assertEquals(ConversationState.Stopping, e.state.value)
        assertTrue(tr.sent.contains(ClientControl.Stop(false)))
        tr.ev("""{"type":"session_end","reason":"stop"}"""); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(ConversationState.Idle, e.state.value)
        assertTrue(tr.closed)
        assertEquals(1, audio.stopped)
    }

    @Test fun noSpeechReturnsToListening() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport()
        val e = engine(audio, tr)
        e.toggle(); runCurrent()
        tr.ev("""{"type":"ready","session_id":"s","audio_out":{"format":"mp3","sample_rate":24000}}""")
        tr.ev("""{"type":"vad_start","turn_id":1}""")
        tr.ev("""{"type":"vad_end","turn_id":1}""")
        tr.ev("""{"type":"no_speech","turn_id":1}""")
        runCurrent()
        assertEquals(ConversationState.Listening, e.state.value)
        assertTrue(audio.gate.last())
        assertTrue(e.captions.value.isEmpty())
    }

    @Test fun handshake403UsesServerErrorText() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport(reject = 403)
        val e = engine(audio, tr)
        e.toggle(); runCurrent()
        val s = e.state.value as ConversationState.Error
        assertEquals("auth_failed", s.error.code)
        assertEquals("钥匙不对", s.error.title)                        // errors{} 覆盖标题
        assertTrue(s.error.fix!!.contains("MEMORY_API_KEY"))            // 未覆盖的字段保留内置
        assertEquals(1, audio.stopped)
    }

    @Test fun badUserThenClose4400() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport()
        val e = engine(audio, tr)
        e.toggle(); runCurrent()
        tr.ev("""{"type":"error","code":"bad_user","message":"invalid user_id","fatal":true}""")
        tr.inbound.trySend(Inbound.Closed(VoiceSocketError.Closed(4400, "bad_user")))
        runCurrent()
        assertEquals("bad_user", (e.state.value as ConversationState.Error).error.code)
        assertTrue(tr.closed)
    }

    @Test fun readyTimeout() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport()
        val e = engine(audio, tr)
        e.toggle(); runCurrent()
        advanceTimeBy(12_500); runCurrent()
        assertEquals("ready_timeout", (e.state.value as ConversationState.Error).error.code)
    }

    // ---------------- v0.2.0：全双工打断 / 文字 / 对话续接 ----------------

    private fun TestScope.duplexEngine(audio: FakeAudio, tr: FakeTransport, chat: ChatLog = ChatLog(), bargeIn: Boolean = true) = ConversationEngine(
        audio = audio,
        makeTransport = { tr },
        paramsProvider = { AppSettings(bargeIn = bargeIn).makeParams("k", serverCfg, "conv-1") },
        scope = backgroundScope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = { testScheduler.currentTime / 1000.0 },
        chat = chat,
    )

    private fun FakeTransport.speakTurn(t: Int) {
        ev("""{"type":"vad_start","turn_id":$t}""")
        ev("""{"type":"vad_end","turn_id":$t}""")
        ev("""{"type":"transcript","turn_id":$t,"text":"问题$t"}""")
        ev("""{"type":"reply_text","turn_id":$t,"seq":0,"text":"回答$t"}""")
        inbound.trySend(Inbound.Audio(
            (ServerEventDecoder.decode("""{"type":"audio","turn_id":$t,"seq":0,"format":"pcm_s16le","sample_rate":24000,"bytes":2}""")
                as com.miguo.voice.protocol.ServerEvent.AudioHeaderEv).h, byteArrayOf(0, 0)))
    }

    private val readyJson = """{"type":"ready","session_id":"conv-1","audio_out":{"format":"pcm_s16le","sample_rate":24000},
        "config":{"barge_in":true},
        "conversation":{"id":"conv-1","resumed":true,"turns":2,"persistent":true,
          "history":[{"role":"user","content":"我叫杰森"},{"role":"assistant","content":"你好杰森"}]}}"""

    @Test fun duplexKeepsMicOpenAndVadStartFlushes() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport(); val chat = ChatLog()
        val e = duplexEngine(audio, tr, chat)
        var readyConv: String? = null
        e.onReady = { readyConv = it.conversation?.id }
        e.toggle(); runCurrent()
        assertTrue(tr.start!!.bargeIn)
        assertEquals("conv-1", tr.start!!.sessionId)
        tr.ev(readyJson); runCurrent()
        assertTrue(e.bargeInEnabled)
        assertEquals("conv-1", readyConv)
        // 历史进入聊天气泡
        val h = chat.entries.value.single()
        assertTrue(h.history); assertEquals("我叫杰森", h.user); assertEquals("你好杰森", h.assistant)

        val gatesAtReady = audio.gate.size
        tr.speakTurn(1); runCurrent()
        assertEquals(ConversationState.Speaking(1), e.state.value)
        assertTrue("全双工：说话期间上传闸门不关", audio.gate.drop(gatesAtReady).all { it } && audio.gate.last())
        audio.mic.trySend(ByteArray(640)); runCurrent()
        assertTrue(tr.audioFrames >= 1)
        assertFalse(audio.isPlaybackIdle)

        // 用户在米果说话时开口 → 本地立刻停播
        tr.ev("""{"type":"barge_in","turn_id":1,"reason":"vad","active":true}""")
        tr.ev("""{"type":"vad_start","turn_id":2}"""); runCurrent()
        assertTrue(audio.isPlaybackIdle)
        assertEquals(ConversationState.UserSpeaking(2), e.state.value)
        assertTrue(chat.entries.value.first { it.user == "问题1" }.interrupted)
        // 被打断那一轮的迟到音频不再入队
        val before = audio.enqueued.size
        tr.inbound.trySend(Inbound.Audio(
            (ServerEventDecoder.decode("""{"type":"audio","turn_id":1,"seq":1,"format":"pcm_s16le","sample_rate":24000,"bytes":2}""")
                as com.miguo.voice.protocol.ServerEvent.AudioHeaderEv).h, byteArrayOf(0, 0)))
        runCurrent()
        assertEquals(before, audio.enqueued.size)
        assertEquals(ConversationState.UserSpeaking(2), e.state.value)
    }

    @Test fun tapInterruptSendsInterruptAndListens() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport()
        val e = duplexEngine(audio, tr, bargeIn = false)
        e.toggle(); runCurrent()
        assertFalse(tr.start!!.bargeIn)
        tr.ev(readyJson); runCurrent()
        assertFalse(e.bargeInEnabled)
        assertFalse("不在说话时不能打断", e.interrupt())
        tr.speakTurn(1); runCurrent()
        assertFalse("半双工：说话时闸门关", audio.gate.last())
        assertTrue(e.interrupt()); runCurrent()
        assertTrue(tr.sent.contains(ClientControl.Interrupt))
        assertTrue(audio.isPlaybackIdle)
        assertEquals(ConversationState.Listening, e.state.value)
        assertTrue(audio.gate.last())
        tr.ev("""{"type":"barge_in","turn_id":1,"reason":"client_interrupt","active":true}"""); runCurrent()
        assertEquals(ConversationState.Listening, e.state.value)
    }

    @Test fun serverBargeInForCurrentTurnReturnsToListening() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport()
        val e = duplexEngine(audio, tr)
        e.toggle(); runCurrent(); tr.ev(readyJson); runCurrent()
        tr.speakTurn(1); runCurrent()
        tr.ev("""{"type":"barge_in","turn_id":1,"reason":"text","active":true}"""); runCurrent()
        assertTrue(audio.isPlaybackIdle)
        assertEquals(ConversationState.Listening, e.state.value)
    }

    @Test fun sendTextInSession() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport(); val chat = ChatLog()
        val e = duplexEngine(audio, tr, chat)
        assertFalse("没会话 → false（TextChatController 改走 HTTP）", e.sendText("hi"))
        e.toggle(); runCurrent(); tr.ev(readyJson); runCurrent()
        assertTrue(e.inSession)
        tr.speakTurn(1); runCurrent()
        assertTrue(e.sendText("换个话题"))
        assertTrue(tr.sent.contains(ClientControl.Text("换个话题")))
        assertTrue("文字打断正在播的回复", audio.isPlaybackIdle)
        tr.ev("""{"type":"barge_in","turn_id":1,"reason":"text","active":true}""")
        tr.ev("""{"type":"transcript","turn_id":2,"text":"换个话题","source":"text"}""")
        tr.ev("""{"type":"reply_text","turn_id":2,"seq":0,"text":"好的"}""")
        runCurrent()
        val last = chat.entries.value.last()
        assertEquals("换个话题", last.user); assertEquals("text", last.userSource); assertEquals("好的", last.assistant)
    }

    @Test fun sendLocationInSessionAndAckIgnored() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport()
        val e = duplexEngine(audio, tr)
        val loc = com.miguo.voice.protocol.WireLocation(40.8244, 114.8875, 12f, "gps", 1_761_450_000_000)
        assertFalse("没会话 → false（下次 start 带上）", e.sendLocation(loc))
        e.toggle(); runCurrent(); tr.ev(readyJson); runCurrent()
        assertTrue(e.sendLocation(loc))
        assertTrue(tr.sent.contains(ClientControl.Location(loc)))
        assertFalse("非法坐标不发", e.sendLocation(com.miguo.voice.protocol.WireLocation(0.0, 0.0)))
        val before = e.state.value
        tr.ev("""{"type":"location_ack","enabled":true,"known":true,"address":"河北省张家口市桥东区","accuracy_m":12}"""); runCurrent()
        assertEquals("location_ack 不改变状态", before, e.state.value)
    }

    @Test fun unauthorizedErrorCallsBack() = runTest {
        val audio = FakeAudio(); val tr = FakeTransport()
        val e = duplexEngine(audio, tr)
        var called = false
        e.onUnauthorized = { called = true }
        e.toggle(); runCurrent()
        tr.ev("""{"type":"error","code":"unauthorized","message":"invalid token","fatal":true}""")
        tr.inbound.trySend(Inbound.Closed(VoiceSocketError.Closed(4401, "unauthorized")))
        runCurrent()
        assertTrue(called)
        assertEquals("unauthorized", (e.state.value as ConversationState.Error).error.code)
    }
}
