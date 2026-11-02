package com.miguo.voice.ui.drawer

import com.miguo.voice.conversation.ChatEntry
import com.miguo.voice.protocol.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeRecentSessionsTest {
    @Test
    fun localActiveMainSessionAppearsWhenApiEmpty() {
        val entries = listOf(
            ChatEntry(key = "t1", user = "mumu-项10-连通测试", history = false),
            ChatEntry(key = "t1a", assistant = "好的", history = false),
        )
        val rows = mergeRecentSessions(emptyList(), "conv-main-1", entries)
        assertEquals(1, rows.size)
        assertEquals("conv-main-1", rows[0].id)
        assertTrue(rows[0].title.contains("mumu"))
    }

    @Test
    fun doesNotDuplicateWhenApiAlreadyHasCurrent() {
        val api = listOf(
            ConversationSummary(
                id = "conv-main-1",
                title = "服务端标题",
                turns = 2,
                updatedAt = "2026-11-01T00:00:00Z",
                preview = "preview",
            ),
        )
        val entries = listOf(ChatEntry(key = "t1", user = "hello", history = false))
        val rows = mergeRecentSessions(api, "conv-main-1", entries)
        assertEquals(1, rows.size)
        assertEquals("服务端标题", rows[0].title)
    }

    @Test
    fun emptyWhenNoLocalUserMessageAndApiEmpty() {
        val rows = mergeRecentSessions(emptyList(), "conv-main-1", emptyList())
        assertTrue(rows.isEmpty())
    }
}