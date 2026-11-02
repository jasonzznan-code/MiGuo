package com.miguo.voice.adapters.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalStoreTest {

    @Test
    fun default_noSeed_emptyPrimary_noWaiting() {
        val store = LocalStore()
        assertEquals(false, store.listTasks().any { it.status == AgentStatus.waiting })
        assertNotNull(store.currentPrimarySessionId())
        assertEquals(AgentStatus.idle, store.getAgentStatus())
        assertTrue(store.listSessions().any { it.isPrimary })
    }

    @Test
    fun createWaitingFromUserRequest_bindsCurrentSession() {
        val store = LocalStore(seed = false)
        val sid = store.currentPrimarySessionId()
        val task = store.createWaitingFromUserRequest("周六早上九点提醒我准备出行。")
        assertEquals(AgentStatus.waiting, task.status)
        assertEquals(sid, task.sessionId)
        assertEquals(AgentStatus.waiting, store.getAgentStatus())
        assertTrue(task.recoverable)
        assertEquals("user_confirm", task.waitingReason)
        assertNotNull(task.confirmToken)
        assertTrue(task.nextActions.contains("confirm"))
    }

    @Test
    fun seed_hasWaiting_retryableError_andRecover() {
        val store = LocalStore(seed = true)
        val sessions = store.listSessions()
        assertTrue(sessions.size >= 3)
        assertTrue(sessions.any { it.isPrimary })
        val tasks = store.listTasks()
        assertTrue(tasks.any { it.status == AgentStatus.waiting && it.recoverable })
        assertTrue(tasks.any { it.status == AgentStatus.error && it.retryable })
        assertTrue(tasks.any { it.status == AgentStatus.recover && !it.retryable })
        assertTrue(store.listServices().any { it.status == "stub" })
        assertTrue(store.listTasks(TaskCategory.social).isEmpty())
    }

    @Test
    fun openSession_setsPrimary_andAgentIdle() {
        val store = LocalStore(seed = true)
        val detail = store.openSession("sess-recent-a")
        assertEquals("sess-recent-a", detail.summary.id)
        assertTrue(detail.summary.isPrimary)
        assertEquals(AgentStatus.idle, detail.agentStatus)
        assertEquals("sess-recent-a", store.currentPrimarySessionId())
    }

    @Test
    fun setTaskStatus_rejectsIllegalTransition() {
        val store = LocalStore(seed = true)
        try {
            store.setTaskStatus("task-idle-grocery", AgentStatus.done)
            fail("expected IllegalStatusTransitionException")
        } catch (e: IllegalStatusTransitionException) {
            assertEquals(AgentStatus.idle, e.from)
            assertEquals(AgentStatus.done, e.to)
        }
    }

    @Test
    fun confirmWaiting_approve_writesBridgeEvent_andReturnsDone() {
        val store = LocalStore(seed = true)
        val before = store.getSession("sess-primary").messages.size
        val task = store.confirmWaiting("task-waiting-ticket", WaitingDecision.approve)
        assertEquals(AgentStatus.done, task.status)
        assertNull(task.waitingReason)
        assertNull(task.confirmToken)
        val after = store.getSession("sess-primary")
        assertTrue(after.messages.size > before)
        assertTrue(after.messages.any { it.role == "system" && it.text.contains("确认通过") })
        assertEquals(AgentStatus.idle, store.getAgentStatus())
    }

    @Test
    fun confirmWaiting_reject_goesError() {
        val store = LocalStore(seed = true)
        val task = store.confirmWaiting("task-waiting-ticket", WaitingDecision.reject)
        assertEquals(AgentStatus.error, task.status)
        assertTrue(task.recoverable)
        assertNotNull(store.getSession("sess-primary").messages.lastOrNull())
    }

    @Test
    fun cancelWaiting_clearsToken_andGoesError() {
        val store = LocalStore(seed = true)
        val before = store.getTask("task-waiting-ticket")
        assertEquals(AgentStatus.waiting, before.status)
        assertNotNull(before.confirmToken)
        val task = store.cancelWaiting("task-waiting-ticket", expectConfirmToken = before.confirmToken)
        assertEquals(AgentStatus.error, task.status)
        assertNull(task.confirmToken)
        assertEquals("USER_CANCELLED", task.errorCode)
        assertTrue(task.recoverable)
        assertTrue(task.retryable)
        assertTrue(
            store.getSession("sess-primary").messages.any {
                it.role == "system" && it.text.contains("已取消")
            },
        )
        assertEquals(AgentStatus.error, store.getAgentStatus())
    }

    @Test
    fun cancelWaiting_staleToken_throws() {
        val store = LocalStore(seed = true)
        try {
            store.cancelWaiting("task-waiting-ticket", expectConfirmToken = "ct-stale-wrong")
            fail("expected StaleConfirmException")
        } catch (_: StaleConfirmException) {
            // ok
        }
        val still = store.getTask("task-waiting-ticket")
        assertEquals(AgentStatus.waiting, still.status)
        assertNotNull(still.confirmToken)
    }

    @Test
    fun cancelWaiting_notDoubleExecutable() {
        val store = LocalStore(seed = true)
        store.cancelWaiting("task-waiting-ticket")
        try {
            store.cancelWaiting("task-waiting-ticket")
            fail("expected ConfirmAlreadyConsumedException")
        } catch (_: ConfirmAlreadyConsumedException) {
            // ok
        }
    }

    @Test
    fun confirmWaiting_notDoubleExecutable() {
        val store = LocalStore(seed = true)
        store.confirmWaiting("task-waiting-ticket", WaitingDecision.approve)
        try {
            store.confirmWaiting("task-waiting-ticket", WaitingDecision.approve)
            fail("expected ConfirmAlreadyConsumedException")
        } catch (_: ConfirmAlreadyConsumedException) {
            // ok
        }
    }

    @Test
    fun editWaitingContent_invalidatesOldConfirmToken() {
        val store = LocalStore(seed = true)
        val before = store.getTask("task-waiting-ticket")
        val oldToken = before.confirmToken!!
        val edited = store.editWaitingContent(
            "task-waiting-ticket",
            WaitingContentEdit(remindAt = "2026-10-31 10:00", title = "周六出行准备（已改）"),
        )
        assertEquals(AgentStatus.waiting, edited.status)
        assertNotEquals(oldToken, edited.confirmToken)
        assertEquals(before.contentRevision + 1, edited.contentRevision)
        try {
            store.confirmWaiting(
                "task-waiting-ticket",
                WaitingDecision.approve,
                expectConfirmToken = oldToken,
            )
            fail("expected StaleConfirmException")
        } catch (_: StaleConfirmException) {
            // ok
        }
        val ok = store.confirmWaiting(
            "task-waiting-ticket",
            WaitingDecision.approve,
            expectConfirmToken = edited.confirmToken,
        )
        assertEquals(AgentStatus.done, ok.status)
        assertTrue(
            store.getSession("sess-primary").messages.any { it.text.contains("确认失效") },
        )
    }

    @Test
    fun retryable_error_canSetRunning() {
        val store = LocalStore(seed = true)
        val t = store.setTaskStatus("task-error-mail", AgentStatus.running)
        assertEquals(AgentStatus.running, t.status)
    }

    @Test
    fun recover_restartAfterReview_clearsCheckpoint_noFakeContinue() {
        val store = LocalStore(seed = true)
        val before = store.getTask("task-recover-booking")
        assertEquals(AgentStatus.recover, before.status)
        assertFalse(before.retryable)
        assertNotNull(before.checkpoint)
        assertFalse(before.checkpoint!!.resumeSupported)
        assertEquals("核对后重新开始", before.nextHint)
        val restarted = store.restartAfterReview("task-recover-booking", toWaiting = true)
        assertEquals(AgentStatus.waiting, restarted.status)
        assertNull(restarted.checkpoint)
        assertEquals(1, restarted.currentStep)
        assertNotNull(restarted.confirmToken)
        assertTrue(
            store.getSession("sess-primary").messages.any {
                it.text.contains("核对后重新开始") && it.text.contains("checkpoint")
            },
        )
    }

    @Test
    fun joinDebugSnapshot_hasRequiredFields() {
        val store = LocalStore(seed = true)
        val snap = store.joinDebugSnapshot("task-waiting-ticket")
        assertEquals("task-waiting-ticket", snap.task_id)
        assertEquals("sess-primary", snap.conversation_id)
        assertEquals("waiting", snap.status)
        assertTrue(snap.next_actions.isNotEmpty())
        assertEquals("user_confirm", snap.waitingReason)
        assertNotNull(snap.checkpoint)
        assertNotNull(snap.confirm_token)
    }

    @Test
    fun deleteSession_doesNotCascadeDeleteTasks() {
        val store = LocalStore(seed = true)
        store.deleteSession("sess-recent-a")
        val grocery = store.getTask("task-idle-grocery")
        assertNull(grocery.sessionId)
        assertFalse(store.listSessions().any { it.id == "sess-recent-a" })
    }

    @Test
    fun createSession_hidesOldWaitingFromNewPrimary() {
        val store = LocalStore(seed = false)
        store.createWaitingFromUserRequest("周六早上九点提醒我准备出行。")
        val oldSid = store.currentPrimarySessionId()
        store.createSession("主会话")
        val newSid = store.currentPrimarySessionId()
        assertTrue(oldSid != newSid)
        val waitingOnNew = store.listTasks().firstOrNull {
            it.status == AgentStatus.waiting && it.sessionId == newSid
        }
        assertNull(waitingOnNew)
        val waitingOnOld = store.listTasks().firstOrNull {
            it.status == AgentStatus.waiting && it.sessionId == oldSid
        }
        assertNotNull(waitingOnOld)
    }

    @Test
    fun taskCategory_hasExactlySix_noNanoNames() {
        val names = TaskCategory.entries.map { it.name }.toSet()
        assertEquals(
            setOf("travel", "work", "life", "learn", "social", "ideas"),
            names,
        )
        assertFalse(names.contains("family"))
        assertFalse(names.contains("more"))
    }

    @Test
    fun agentStatus_hasExactlySix_includingRecover_noConfirm() {
        assertEquals(
            listOf("idle", "running", "waiting", "error", "done", "recover"),
            AgentStatus.entries.map { it.name },
        )
        assertFalse(AgentStatus.entries.any { it.name == "confirm" })
    }
}
