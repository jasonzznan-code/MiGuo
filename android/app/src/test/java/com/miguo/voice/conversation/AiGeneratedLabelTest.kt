package com.miguo.voice.conversation

import com.miguo.voice.protocol.HistoryMessage
import com.miguo.voice.protocol.TextChatRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标识是展示计划上的标志，不进入正文，也不进入发往后端的文字请求。
 * 覆盖：流式片段、生成完成、历史恢复、重试后替换、用户消息与错误提示。
 */
class AiGeneratedLabelTest {
    @Test fun disclosureTextIsTheRequiredPhrase() {
        assertEquals("内容由 AI 生成", AiGeneratedLabel.TEXT)
    }

    @Test fun streamingPartialIsLabeledOnceAndBodyStaysClean() {
        val chat = ChatLog()
        chat.ensure("t1") { it.copy(user = "在吗", userSource = "text", pending = true) }
        assertEquals(listOf(ChatMessageKind.USER, ChatMessageKind.PENDING), kinds(chat))
        assertFalse(chat.entries.value.single().messagePresentation().any { it.showAiGeneratedLabel })
        assertEquals(AiGeneratedLabel.PENDING_PLACEHOLDER, chat.entries.value.single().messagePresentation().last().text)

        chat.update("t1") { it.copy(assistant = "你") }
        chat.update("t1") { it.copy(assistant = it.assistant + "好呀") }
        val streaming = chat.entries.value.single()
        assertTrue(streaming.pending)
        assertAssistantOnce(streaming, "你好呀")
    }

    @Test fun completedAndRegeneratedRepliesStayLabeledWithoutTouchingBody() {
        val chat = ChatLog()
        chat.ensure("t1") { it.copy(user = "再答一次", assistant = "旧回答", pending = false) }
        assertAssistantOnce(chat.entries.value.single(), "旧回答")

        chat.update("t1") { it.copy(assistant = "", pending = true) }
        val waiting = chat.entries.value.single().messagePresentation()
        assertEquals(ChatMessageKind.PENDING, waiting.last().kind)
        assertFalse(waiting.any { it.showAiGeneratedLabel })
        assertFalse(waiting.any { it.text.contains(AiGeneratedLabel.TEXT) })

        chat.update("t1") { it.copy(assistant = "新的回答", pending = false) }
        val regenerated = chat.entries.value.single()
        assertAssistantOnce(regenerated, "新的回答")
        assertFalse(regenerated.assistant.contains("旧回答"))
    }

    @Test fun historyRestoreLabelsAssistantOnly() {
        val entries = ChatLog.pairUp(
            listOf(
                HistoryMessage("user", "我叫杰森", null),
                HistoryMessage("assistant", "你好杰森", null),
                HistoryMessage("system", "系统错误：连接中断", null),
                HistoryMessage("user", "只有我这句话", null),
                HistoryMessage("assistant", "", null),
            ),
        )
        assertEquals(2, entries.size)

        val restored = entries[0]
        assertTrue(restored.history)
        assertAssistantOnce(restored, "你好杰森")
        assertEquals("我叫杰森", restored.messagePresentation().first().text)
        assertFalse(restored.messagePresentation().first().showAiGeneratedLabel)

        val userOnly = entries[1]
        assertEquals(listOf(ChatMessageKind.USER), userOnly.messagePresentation().map { it.kind })
        assertFalse(userOnly.messagePresentation().any { it.showAiGeneratedLabel })
        assertTrue(entries.none { e -> e.messagePresentation().any { it.text.contains("连接中断") } })
        assertTrue(entries.none { e -> e.user.contains(AiGeneratedLabel.TEXT) || e.assistant.contains(AiGeneratedLabel.TEXT) })
    }

    @Test fun assistantOnlyHistoryIsLabeled() {
        val entry = ChatLog.pairUp(listOf(HistoryMessage("assistant", "单独的回复", "1"))).single()
        assertAssistantOnce(entry, "单独的回复")
        assertTrue(entry.messagePresentation().none { it.kind == ChatMessageKind.USER })
    }

    @Test fun userMessagesAndErrorsAreNotAiReplies() {
        val entry = ChatEntry(
            key = "e1",
            user = "这是我的话",
            assistant = "",
            pending = false,
            failed = "登录已失效，请重新登录",
        )
        val plan = entry.messagePresentation()
        assertEquals(listOf(ChatMessageKind.USER, ChatMessageKind.ERROR), plan.map { it.kind })
        assertTrue(plan.none { it.showAiGeneratedLabel })
        assertEquals("登录已失效，请重新登录", plan.last().text)

        val partialThenError = ChatEntry(
            key = "e2",
            user = "问",
            assistant = "半句",
            failed = "这一轮处理失败",
        )
        assertAssistantOnce(partialThenError, "半句")
        val error = partialThenError.messagePresentation().last()
        assertEquals(ChatMessageKind.ERROR, error.kind)
        assertFalse(error.showAiGeneratedLabel)
        assertEquals("这一轮处理失败", error.text)
    }

    @Test fun timelineRolesMatchChatDisclosure() {
        assertTrue(AiGeneratedLabel.isAssistantReply("assistant", "好呀，我先把内容整理好了。"))
        assertFalse(AiGeneratedLabel.isAssistantReply("user", "周六早上九点提醒我准备出行。"))
        assertFalse(AiGeneratedLabel.isAssistantReply("system", "【联调】已注入等待确认。"))
        assertFalse(AiGeneratedLabel.isAssistantReply("assistant", "   "))
        assertFalse(AiGeneratedLabel.isAssistantReply("Assistant", "大小写不是助手角色"))
    }

    @Test fun outboundTextRequestDoesNotCarryDisclosure() {
        val req = TextChatRequest(text = "在吗", conversationId = "conv-1", resume = false)
        val wire = req.encode()
        assertFalse(wire.contains(AiGeneratedLabel.TEXT))
        assertTrue(wire.contains("\"text\":\"在吗\""))
        assertFalse(wire.contains("assistant"))
    }

    private fun kinds(chat: ChatLog) = chat.entries.value.single().messagePresentation().map { it.kind }

    private fun assertAssistantOnce(entry: ChatEntry, body: String) {
        val assistants = entry.messagePresentation().filter { it.kind == ChatMessageKind.ASSISTANT }
        assertEquals(1, assistants.size)
        assertEquals(body, assistants.single().text)
        assertEquals(body, entry.assistant)
        assertTrue(assistants.single().showAiGeneratedLabel)
        assertEquals(1, entry.messagePresentation().count { it.showAiGeneratedLabel })
        assertFalse(entry.assistant.contains(AiGeneratedLabel.TEXT))
        assertFalse(entry.user.contains(AiGeneratedLabel.TEXT))
        assertTrue(entry.messagePresentation().none { it.kind != ChatMessageKind.ASSISTANT && it.showAiGeneratedLabel })
    }
}
