package com.miguo.voice.conversation

import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.HistoryMessage
import com.miguo.voice.protocol.TextChatEvent
import com.miguo.voice.protocol.TextChatRequest
import com.miguo.voice.protocol.TextChatResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeChat : ChatBackend {
    val reqs = ArrayList<Pair<TextChatRequest, Identity>>()
    val results = ArrayDeque<ApiResult<TextChatResult>>()
    var events: List<TextChatEvent> = emptyList()
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun sendText(req: TextChatRequest, id: Identity, onEvent: (TextChatEvent) -> Unit): ApiResult<TextChatResult> {
        reqs += req to id
        events.forEach(onEvent)
        gate?.await()
        return results.removeFirst()
    }
    override suspend fun messages(conversationId: String, id: Identity): ApiResult<List<HistoryMessage>> = ApiResult.Ok(emptyList())
    override suspend fun newConversation(id: Identity, prefix: String): ApiResult<String> = ApiResult.Ok("android-test-new")
    override suspend fun latestConversation(id: Identity): ApiResult<String?> = ApiResult.Ok(null)
    override suspend fun listConversations(id: Identity, limit: Int) = ApiResult.Ok(emptyList<com.miguo.voice.protocol.ConversationSummary>())
}

private class FakePlayer : ReplyAudioPlayer {
    val played = ArrayList<ByteArray>(); var stops = 0
    override fun play(mp3: ByteArray) { played += mp3 }
    override fun stop() { stops++ }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TextChatControllerTest {
    private var conv: String? = null
    private var bearer: String? = "acc1"
    private var inSession = false
    private val wsSent = ArrayList<String>()
    private var loc: com.miguo.voice.protocol.WireLocation? = null

    private fun TestScope.ctl(be: FakeChat, chat: ChatLog, player: FakePlayer? = null, speak: Boolean = false,
                              refresh: suspend (String?) -> Boolean = { false }) = TextChatController(
        backend = be, chat = chat, scope = backgroundScope,
        identity = { Identity("jason", bearer) },
        conversationId = { conv }, onConversationId = { conv = it },
        voice = { t -> if (inSession) { wsSent += t; true } else false },
        speak = { speak }, player = player, refreshAuth = refresh,
        location = { loc },
    )

    @Test fun httpCarriesCurrentLocation() = runTest {
        val be = FakeChat().apply { results += ApiResult.Ok(TextChatResult("c", "你在张家口", null, null)); results += ApiResult.Ok(TextChatResult("c", "不知道", null, null)) }
        val chat = ChatLog(); val c = ctl(be, chat)
        loc = com.miguo.voice.protocol.WireLocation(40.8244, 114.8875, 12f, "gps", 1L)
        assertTrue(c.send("我在哪")); runCurrent()
        assertEquals(loc, be.reqs.last().first.location)
        loc = null
        assertTrue(c.send("我在哪")); runCurrent()
        assertNull("没有定位 → 不发 location", be.reqs.last().first.location)
    }

    @Test fun inSessionGoesOverWebSocket() = runTest {
        val be = FakeChat(); val chat = ChatLog(); inSession = true
        val c = ctl(be, chat)
        assertTrue(c.send("  你好  "))
        assertEquals(listOf("你好"), wsSent); assertEquals("ws", c.lastRoute)
        assertTrue(be.reqs.isEmpty()); assertTrue(chat.entries.value.isEmpty())   // 气泡由语音引擎按 transcript 显示
        assertEquals(TextChatState.Idle, c.state.value)
    }

    @Test fun httpStreamsAndStoresConversation() = runTest {
        val be = FakeChat().apply {
            events = listOf(TextChatEvent.Start("conv-1"), TextChatEvent.Reply(0, "你好"), TextChatEvent.Reply(1, "呀"))
            gate = CompletableDeferred()
            results += ApiResult.Ok(TextChatResult("conv-1", "你好呀", null, null))
        }
        val chat = ChatLog(); val c = ctl(be, chat)
        assertTrue(c.send("在吗"))
        assertEquals("http", c.lastRoute)
        assertFalse("Sending 期间拒绝第二条", c.send("再来"))
        runCurrent()
        val e = chat.entries.value.single()
        assertEquals("在吗", e.user); assertEquals("你好呀", e.assistant); assertTrue(e.pending)  // 流式中
        val streaming = e.messagePresentation()
        assertEquals(listOf(ChatMessageKind.USER, ChatMessageKind.ASSISTANT), streaming.map { it.kind })
        assertFalse(streaming.first().showAiGeneratedLabel)
        assertTrue(streaming.last().showAiGeneratedLabel)
        assertEquals("你好呀", streaming.last().text)
        assertFalse(e.assistant.contains(AiGeneratedLabel.TEXT))
        assertFalse(be.reqs.single().first.encode().contains(AiGeneratedLabel.TEXT))
        assertEquals("conv-1", conv)
        val (req, id) = be.reqs.single()
        assertNull(req.conversationId); assertTrue(req.resume)
        assertEquals("acc1", id.bearer)
        be.gate!!.complete(Unit); runCurrent()
        assertFalse(chat.entries.value.single().pending)
        assertEquals(TextChatState.Idle, c.state.value)
        // 第二轮带上稳定的对话 ID
        be.gate = null; be.events = emptyList(); be.results += ApiResult.Ok(TextChatResult("conv-1", "好", null, null))
        assertTrue(c.send("第二句")); runCurrent()
        assertEquals("conv-1", be.reqs.last().first.conversationId); assertFalse(be.reqs.last().first.resume)
        assertEquals(2, chat.entries.value.size)
    }

    @Test fun unauthorizedRefreshesAndRetriesOnce() = runTest {
        val be = FakeChat().apply {
            results += ApiResult.Unauthorized("token expired")
            results += ApiResult.Ok(TextChatResult("c", "ok", null, null))
        }
        var refreshed: String? = null
        val chat = ChatLog()
        val c = ctl(be, chat, refresh = { t -> refreshed = t; bearer = "acc2"; true })
        c.send("hi"); runCurrent()
        assertEquals("acc1", refreshed)
        assertEquals(listOf("acc1", "acc2"), be.reqs.map { it.second.bearer })
        assertEquals("ok", chat.entries.value.single().assistant)
        assertEquals(TextChatState.Idle, c.state.value)
        val plan = chat.entries.value.single().messagePresentation()
        assertEquals(1, plan.count { it.showAiGeneratedLabel })
        assertEquals("ok", plan.first { it.showAiGeneratedLabel }.text)
        assertTrue(be.reqs.all { !it.first.encode().contains(AiGeneratedLabel.TEXT) })
        assertFalse(chat.entries.value.single().assistant.contains(AiGeneratedLabel.TEXT))
    }

    @Test fun notSupportedFailsWithHint() = runTest {
        val be = FakeChat().apply { results += ApiResult.NotSupported }
        val chat = ChatLog(); val c = ctl(be, chat)
        c.send("hi"); runCurrent()
        val f = c.state.value as TextChatState.Failed
        assertTrue(f.message.contains("v2"))
        assertEquals(f.message, chat.entries.value.single().failed)
        assertFalse(chat.entries.value.single().pending)
        c.dismissError(); assertEquals(TextChatState.Idle, c.state.value)
    }

    @Test fun notSupportedErrorIsNotAnAiReply() = runTest {
        val be = FakeChat().apply { results += ApiResult.NotSupported }
        val chat = ChatLog(); val c = ctl(be, chat)
        c.send("hi"); runCurrent()
        val entry = chat.entries.value.single()
        val failed = c.state.value as TextChatState.Failed
        assertEquals(failed.message, entry.failed)
        assertTrue(entry.assistant.isEmpty())
        val plan = entry.messagePresentation()
        assertTrue(plan.any { it.kind == ChatMessageKind.ERROR && it.text == failed.message && !it.showAiGeneratedLabel })
        assertTrue(plan.none { it.showAiGeneratedLabel })
        assertFalse(entry.failed!!.contains(AiGeneratedLabel.TEXT))
        assertTrue(be.reqs.all { !it.first.encode().contains(AiGeneratedLabel.TEXT) })
    }

    @Test fun speakPlaysAudio() = runTest {
        val be = FakeChat().apply { results += ApiResult.Ok(TextChatResult("c", "你好", null, byteArrayOf(1, 2, 3))) }
        val p = FakePlayer(); val c = ctl(be, ChatLog(), player = p, speak = true)
        c.send("hi"); runCurrent()
        assertTrue(be.reqs.single().first.speak)
        assertEquals(3, p.played.single().size)
    }

    @Test fun tooLongAndBlankRejected() = runTest {
        val c = ctl(FakeChat(), ChatLog())
        assertFalse(c.send("   "))
        assertFalse(c.send("字".repeat(TextChatController.MAX_LEN + 1)))
        assertTrue(c.state.value is TextChatState.Failed)
    }
}
