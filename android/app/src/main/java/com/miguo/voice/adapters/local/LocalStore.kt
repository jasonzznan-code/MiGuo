package com.miguo.voice.adapters.local

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 内存 LocalStore：实现 [MiguoLocalAdapter]。
 * 正式产品默认不注入演示数据（seed=false）；冷启动为空主会话。
 * [seedDemoData] 仅供显式调试。不落盘、不碰服务器 / `.env` / Docker。
 *
 * 2026-10-30：六态 + confirmToken 防重 + 改内容失效 + error/recover 场景。
 */
class LocalStore(
    /** 正式产品默认 false：冷启动空态，不注入演示 waiting。仅调试时可显式 true。 */
    seed: Boolean = false,
) : MiguoLocalAdapter {

    private val sessions = linkedMapOf<String, SessionSummary>()
    private val messages = mutableMapOf<String, MutableList<SessionMessage>>()
    private val tasks = linkedMapOf<String, Task>()
    private val services = linkedMapOf<String, ServiceCard>()

    private val primarySessionId = AtomicReference<String?>(null)
    private val agentStatus = AtomicReference(AgentStatus.idle)

    init {
        if (seed) {
            seedDemoData()
        } else {
            ensureEmptyPrimarySession()
        }
    }

    private fun nowIso(): String = Instant.now().toString()

    private fun newId(prefix: String): String =
        "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun newConfirmToken(): String = "ct-${UUID.randomUUID().toString().take(12)}"

    /** 产品空启动：仅建一个空主会话，无任务、无演示气泡。 */
    private fun ensureEmptyPrimarySession() {
        if (sessions.isNotEmpty()) return
        val id = "sess-primary"
        val iso = nowIso()
        sessions[id] = SessionSummary(
            id = id,
            title = "主会话",
            updatedAt = iso,
            archived = false,
            isPrimary = true,
        )
        messages[id] = mutableListOf()
        primarySessionId.set(id)
        agentStatus.set(AgentStatus.idle)
    }

    /**
     * 显式调试用种子；正式路径不要调用。
     * 含：waiting 确认卡、可重复 error（retryable）、不可恢复 recover 场景。
     */
    fun seedDemoData() {
        sessions.clear()
        messages.clear()
        tasks.clear()
        services.clear()

        val t0 = "2026-10-29T08:00:00+08:00"
        val t1 = "2026-10-29T09:10:00+08:00"
        val t2 = "2026-10-29T09:20:00+08:00"
        val t3 = "2026-10-30T11:00:00+08:00"

        val primary = SessionSummary(
            id = "sess-primary",
            title = "周六出行准备",
            updatedAt = t3,
            archived = false,
            isPrimary = true,
        )
        val recentA = SessionSummary(
            id = "sess-recent-a",
            title = "整理下周工作安排",
            updatedAt = t1,
            archived = false,
            isPrimary = false,
        )
        val recentB = SessionSummary(
            id = "sess-recent-b",
            title = "周末去哪里走走？",
            updatedAt = t0,
            archived = false,
            isPrimary = false,
        )
        sessions[primary.id] = primary
        sessions[recentA.id] = recentA
        sessions[recentB.id] = recentB
        primarySessionId.set(primary.id)

        messages[primary.id] = mutableListOf(
            SessionMessage("msg-p1", "user", "周六早上九点提醒我准备出行。", t0),
            SessionMessage("msg-p2", "assistant", "好呀，我先把内容整理好了。你确认一下，我再继续。", t1),
            SessionMessage("msg-p3", "system", "【联调】已注入等待确认 / 可重试失败 / 中断待重开三场景样例。", t3),
        )
        messages[recentA.id] = mutableListOf(
            SessionMessage("msg-a1", "user", "张家口周末路线？", t0),
            SessionMessage("msg-a2", "assistant", "已记下出行要点。", t1),
        )
        messages[recentB.id] = mutableListOf(
            SessionMessage("msg-b1", "system", "本会话已归档；任务对象未删除。", t0),
        )

        services["svc-calendar-stub"] = ServiceCard(
            id = "svc-calendar-stub",
            name = "日历占位",
            kind = "life",
            status = "stub",
            entrypoint = null,
        )
        services["svc-maps-stub"] = ServiceCard(
            id = "svc-maps-stub",
            name = "地图占位",
            kind = "builtin",
            status = "stub",
            entrypoint = null,
        )

        tasks["task-waiting-ticket"] = Task(
            id = "task-waiting-ticket",
            title = "周六出行准备",
            category = TaskCategory.travel,
            status = AgentStatus.waiting,
            sessionId = primary.id,
            serviceIds = listOf("svc-calendar-stub"),
            summary = "提醒类 · 周六出行准备。请核对提醒内容。确认后才会进入执行。",
            createdAt = t0,
            updatedAt = t2,
            recoverable = true,
            waitingReason = "user_confirm",
            remindAt = "2026-10-31 09:00",
            currentStep = 2,
            totalSteps = 3,
            nextHint = "请核对时间与内容。确认后才会创建提醒并加入计划。",
            nextActions = listOf("confirm", "modify", "view_detail"),
            errorCode = null,
            retryable = false,
            checkpoint = TaskCheckpoint(
                stepId = "review_reminder",
                stepIndex = 2,
                label = "等待用户确认提醒",
                resumeSupported = false,
                payloadJson = """{"remind_at":"2026-10-31 09:00"}""",
            ),
            confirmToken = "ct-seed-waiting01",
            contentRevision = 1,
        )

        tasks["task-error-mail"] = Task(
            id = "task-error-mail",
            title = "发送周报邮件",
            category = TaskCategory.work,
            status = AgentStatus.error,
            sessionId = primary.id,
            serviceIds = emptyList(),
            summary = "附件上传失败（可重复）：网络抖动，允许原地重试。",
            createdAt = t0,
            updatedAt = t1,
            recoverable = true,
            waitingReason = null,
            currentStep = 2,
            totalSteps = 3,
            nextHint = "可点「重试」再次上传附件。",
            nextActions = listOf("retry"),
            errorCode = "UPLOAD_FAILED",
            retryable = true,
            checkpoint = TaskCheckpoint(
                stepId = "upload_attachment",
                stepIndex = 2,
                label = "上传附件",
                resumeSupported = true,
                payloadJson = """{"file":"weekly.docx","attempt":1}""",
            ),
            confirmToken = null,
            contentRevision = 1,
        )

        tasks["task-recover-booking"] = Task(
            id = "task-recover-booking",
            title = "确认高铁订票",
            category = TaskCategory.travel,
            status = AgentStatus.recover,
            sessionId = primary.id,
            serviceIds = listOf("svc-maps-stub"),
            summary = "后端暂不支持从中断点恢复。请核对行程后重新开始，不要点「继续执行」。",
            createdAt = t0,
            updatedAt = t3,
            recoverable = true,
            waitingReason = null,
            currentStep = 2,
            totalSteps = 4,
            nextHint = "核对后重新开始",
            nextActions = listOf("restart_after_review", "cancel"),
            errorCode = "RESUME_UNSUPPORTED",
            retryable = false,
            checkpoint = TaskCheckpoint(
                stepId = "hold_seat",
                stepIndex = 2,
                label = "占座中断（不可续跑）",
                resumeSupported = false,
                payloadJson = """{"train":"G123","date":"2026-11-01"}""",
            ),
            confirmToken = null,
            contentRevision = 1,
        )

        tasks["task-idle-grocery"] = Task(
            id = "task-idle-grocery",
            title = "周末采购清单",
            category = TaskCategory.life,
            status = AgentStatus.idle,
            sessionId = recentA.id,
            serviceIds = listOf("svc-calendar-stub"),
            summary = "尚未启动。",
            createdAt = t0,
            updatedAt = t0,
            recoverable = false,
            nextActions = listOf("start"),
            retryable = false,
            contentRevision = 1,
        )
        tasks["task-done-reading"] = Task(
            id = "task-done-reading",
            title = "读完契约第 3 章",
            category = TaskCategory.learn,
            status = AgentStatus.done,
            sessionId = recentB.id,
            summary = "已完成。",
            createdAt = t0,
            updatedAt = t1,
            recoverable = false,
            nextActions = emptyList(),
            currentStep = 3,
            totalSteps = 3,
            contentRevision = 1,
        )
        tasks["task-running-idea"] = Task(
            id = "task-running-idea",
            title = "整理产品点子簿",
            category = TaskCategory.ideas,
            status = AgentStatus.running,
            sessionId = primary.id,
            summary = "正在归类今日点子。",
            createdAt = t1,
            updatedAt = t2,
            recoverable = false,
            nextActions = listOf("cancel"),
            currentStep = 1,
            totalSteps = 2,
            contentRevision = 1,
        )

        agentStatus.set(AgentStatus.waiting)
    }

    @Synchronized
    override fun listSessions(): List<SessionSummary> =
        sessions.values.sortedWith(
            compareByDescending<SessionSummary> { it.isPrimary }
                .thenBy { it.archived }
                .thenByDescending { it.updatedAt },
        )

    @Synchronized
    override fun getSession(sessionId: String): SessionDetail {
        val s = sessions[sessionId] ?: error("会话不存在: $sessionId")
        val msgs = messages[sessionId].orEmpty()
        return SessionDetail(
            summary = s,
            messages = msgs.toList(),
            agentStatus = if (primarySessionId.get() == sessionId) agentStatus.get() else AgentStatus.idle,
        )
    }

    @Synchronized
    override fun openSession(sessionId: String): SessionDetail {
        val s = sessions[sessionId] ?: error("会话不存在: $sessionId")
        sessions.keys.toList().forEach { k ->
            sessions[k] = sessions.getValue(k).copy(isPrimary = k == sessionId)
        }
        primarySessionId.set(sessionId)
        agentStatus.set(AgentStatus.idle)
        sessions[sessionId] = s.copy(isPrimary = true, updatedAt = nowIso())
        return SessionDetail(
            summary = sessions.getValue(sessionId),
            messages = messages[sessionId].orEmpty().toList(),
            agentStatus = agentStatus.get(),
        )
    }

    @Synchronized
    override fun createSession(title: String?): SessionDetail {
        val id = newId("sess")
        val iso = nowIso()
        sessions.keys.toList().forEach { k ->
            sessions[k] = sessions.getValue(k).copy(isPrimary = false)
        }
        val summary = SessionSummary(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: "主会话",
            updatedAt = iso,
            archived = false,
            isPrimary = true,
        )
        sessions[id] = summary
        messages[id] = mutableListOf()
        primarySessionId.set(id)
        agentStatus.set(AgentStatus.idle)
        return SessionDetail(summary, messages.getValue(id).toList(), AgentStatus.idle)
    }

    @Synchronized
    override fun renameSession(id: String, title: String): SessionSummary {
        val cur = sessions[id] ?: error("会话不存在: $id")
        val updated = cur.copy(title = title, updatedAt = nowIso())
        sessions[id] = updated
        return updated
    }

    @Synchronized
    override fun archiveSession(id: String): SessionSummary {
        val cur = sessions[id] ?: error("会话不存在: $id")
        val updated = cur.copy(archived = true, isPrimary = false, updatedAt = nowIso())
        sessions[id] = updated
        if (primarySessionId.get() == id) {
            primarySessionId.set(sessions.values.firstOrNull { !it.archived }?.id)
            primarySessionId.get()?.let { pid ->
                sessions[pid] = sessions.getValue(pid).copy(isPrimary = true)
            }
            agentStatus.set(AgentStatus.idle)
        }
        return updated
    }

    @Synchronized
    override fun deleteSession(id: String) {
        sessions.remove(id)
        messages.remove(id)
        tasks.keys.toList().forEach { tid ->
            val t = tasks[tid] ?: return@forEach
            if (t.sessionId == id) {
                tasks[tid] = t.copy(sessionId = null, updatedAt = nowIso())
            }
        }
        if (primarySessionId.get() == id) {
            primarySessionId.set(sessions.values.firstOrNull { !it.archived }?.id)
            primarySessionId.get()?.let { pid ->
                sessions[pid] = sessions.getValue(pid).copy(isPrimary = true)
            }
            agentStatus.set(AgentStatus.idle)
        }
    }

    @Synchronized
    override fun listTasks(category: TaskCategory?): List<Task> {
        val all = tasks.values.toList()
        return if (category == null) all else all.filter { it.category == category }
    }

    @Synchronized
    override fun getTask(taskId: String): Task =
        tasks[taskId] ?: error("任务不存在: $taskId")

    @Synchronized
    override fun upsertTask(task: Task): Task {
        val iso = nowIso()
        val existing = tasks[task.id]
        val saved = if (existing == null) {
            task.copy(createdAt = task.createdAt.ifBlank { iso }, updatedAt = iso)
        } else {
            task.copy(createdAt = existing.createdAt, updatedAt = iso)
        }
        tasks[saved.id] = saved
        saved.sessionId?.let { appendBridgeEvent(it, "任务「${saved.title}」已更新 → ${saved.status}") }
        syncPrimaryAgentFromTask(saved)
        return saved
    }

    @Synchronized
    override fun setTaskStatus(taskId: String, status: AgentStatus, meta: StatusMeta?): Task {
        val cur = tasks[taskId] ?: error("任务不存在: $taskId")
        StatusTransitions.requireTransit(cur.status, status)
        if (cur.status == AgentStatus.error && status == AgentStatus.running && !cur.retryable) {
            error("该错误不能直接重试，请「核对后重新开始」")
        }
        val waitingReason = when (status) {
            AgentStatus.waiting -> meta?.waitingReason ?: cur.waitingReason ?: "user_input"
            else -> null
        }
        val retryable = meta?.retryable ?: when (status) {
            AgentStatus.error -> cur.retryable
            AgentStatus.recover -> false
            else -> false
        }
        val recoverable = when (status) {
            AgentStatus.waiting, AgentStatus.error, AgentStatus.recover -> true
            else -> cur.recoverable && status == AgentStatus.running
        }
        val nextActions = meta?.nextActions ?: defaultNextActions(status, retryable)
        val updated = cur.copy(
            status = status,
            updatedAt = nowIso(),
            waitingReason = waitingReason,
            recoverable = recoverable,
            retryable = retryable,
            errorCode = when (status) {
                AgentStatus.error, AgentStatus.recover -> meta?.errorCode ?: cur.errorCode
                else -> null
            },
            nextActions = nextActions,
            checkpoint = meta?.checkpoint ?: when {
                status == AgentStatus.recover || status == AgentStatus.error -> cur.checkpoint
                status == AgentStatus.waiting -> cur.checkpoint
                status == AgentStatus.running && cur.status == AgentStatus.recover -> null
                else -> cur.checkpoint
            },
            confirmToken = if (status == AgentStatus.waiting) (cur.confirmToken ?: newConfirmToken()) else null,
            summary = meta?.note?.takeIf { it.isNotBlank() } ?: cur.summary,
            nextHint = when (status) {
                AgentStatus.recover -> "核对后重新开始"
                else -> cur.nextHint
            },
        )
        tasks[taskId] = updated
        updated.sessionId?.let {
            appendBridgeEvent(it, "任务「${updated.title}」状态 ${cur.status} → ${updated.status}")
        }
        syncPrimaryAgentFromTask(updated)
        return updated
    }

    private fun defaultNextActions(status: AgentStatus, retryable: Boolean): List<String> = when (status) {
        AgentStatus.waiting -> listOf("confirm", "modify", "view_detail")
        AgentStatus.error -> if (retryable) listOf("retry") else listOf("restart_after_review")
        AgentStatus.recover -> listOf("restart_after_review", "cancel")
        AgentStatus.running -> listOf("cancel")
        AgentStatus.idle -> listOf("start")
        AgentStatus.done -> emptyList()
    }

    private fun syncPrimaryAgentFromTask(task: Task) {
        if (task.sessionId != null && task.sessionId == primarySessionId.get()) {
            agentStatus.set(task.status)
        }
    }

    @Synchronized
    override fun listServices(): List<ServiceCard> = services.values.toList()

    @Synchronized
    override fun getAgentStatus(): AgentStatus = agentStatus.get()

    @Synchronized
    override fun setAgentStatus(status: AgentStatus): AgentStatus {
        agentStatus.set(status)
        return status
    }

    @Synchronized
    override fun createWaitingFromUserRequest(userText: String, sessionId: String?): Task {
        val sid = sessionId ?: primarySessionId.get() ?: run {
            ensureEmptyPrimarySession()
            primarySessionId.get()!!
        }
        if (!sessions.containsKey(sid)) {
            val iso = nowIso()
            sessions.keys.toList().forEach { k ->
                sessions[k] = sessions.getValue(k).copy(isPrimary = false)
            }
            sessions[sid] = SessionSummary(
                id = sid,
                title = userText.take(18).ifBlank { "主会话" },
                updatedAt = iso,
                archived = false,
                isPrimary = true,
            )
            messages[sid] = mutableListOf()
            primarySessionId.set(sid)
        }
        val title = deriveTitle(userText)
        val remind = deriveRemindAt(userText)
        val iso = nowIso()
        val existingWaiting = tasks.values.firstOrNull {
            it.status == AgentStatus.waiting && it.sessionId == sid
        }
        val id = existingWaiting?.id ?: newId("task")
        val rev = (existingWaiting?.contentRevision ?: 0) + 1
        val token = newConfirmToken()
        val task = Task(
            id = id,
            title = title,
            category = TaskCategory.travel,
            status = AgentStatus.waiting,
            sessionId = sid,
            serviceIds = emptyList(),
            summary = "请核对提醒内容。确认后才会进入执行。",
            createdAt = existingWaiting?.createdAt ?: iso,
            updatedAt = iso,
            recoverable = true,
            waitingReason = "user_confirm",
            remindAt = remind,
            currentStep = 2,
            totalSteps = 3,
            nextHint = "请核对时间与内容。确认后才会创建提醒并加入计划。",
            nextActions = listOf("confirm", "modify", "view_detail"),
            errorCode = null,
            retryable = false,
            checkpoint = TaskCheckpoint(
                stepId = "review_reminder",
                stepIndex = 2,
                label = "等待用户确认提醒",
                resumeSupported = false,
                payloadJson = """{"remind_at":"$remind"}""",
            ),
            confirmToken = token,
            contentRevision = rev,
        )
        tasks[id] = task
        appendBridgeEvent(sid, "已根据你的需求整理待确认任务「${task.title}」（rev=$rev）")
        sessions[sid]?.let { s ->
            sessions[sid] = s.copy(
                title = if (s.title == "主会话" || s.title == "新会话") title else s.title,
                updatedAt = iso,
                isPrimary = true,
            )
        }
        agentStatus.set(AgentStatus.waiting)
        return task
    }

    private fun deriveTitle(userText: String): String {
        val t = userText.trim()
        return when {
            "出行" in t -> "出行提醒"
            "工作" in t -> "工作安排"
            "周末" in t || "走走" in t -> "周末出行"
            t.length <= 18 -> t.ifBlank { "新提醒" }
            else -> t.take(18) + "…"
        }
    }

    private fun deriveRemindAt(userText: String): String {
        return when {
            "九点" in userText || "9点" in userText || "09:" in userText -> "2026-10-31 09:00"
            "早上" in userText -> "2026-10-31 09:00"
            else -> "待定"
        }
    }

    @Synchronized
    override fun confirmWaiting(
        taskId: String,
        decision: WaitingDecision,
        expectConfirmToken: String?,
    ): Task {
        val cur = tasks[taskId] ?: error("任务不存在: $taskId")
        if (cur.status != AgentStatus.waiting) {
            throw ConfirmAlreadyConsumedException(taskId)
        }
        val token = cur.confirmToken
        if (expectConfirmToken != null && expectConfirmToken != token) {
            throw StaleConfirmException(taskId)
        }
        if (token == null) {
            throw StaleConfirmException(taskId, "确认令牌缺失，请核对后重新确认")
        }

        val next = when (decision) {
            WaitingDecision.approve -> AgentStatus.done
            WaitingDecision.reject -> AgentStatus.error
        }
        StatusTransitions.requireTransit(AgentStatus.waiting, AgentStatus.running)
        StatusTransitions.requireTransit(AgentStatus.running, next)

        val iso = nowIso()
        val decisionLabel = if (decision == WaitingDecision.approve) "确认通过" else "已拒绝"
        val updated = cur.copy(
            status = next,
            updatedAt = iso,
            waitingReason = null,
            recoverable = next == AgentStatus.error,
            retryable = next == AgentStatus.error,
            errorCode = if (next == AgentStatus.error) "USER_REJECTED" else null,
            summary = "${cur.summary}（$decisionLabel）",
            currentStep = if (next == AgentStatus.done) cur.totalSteps else cur.currentStep,
            nextHint = if (next == AgentStatus.done) "已加入计划，可在计划页查看。" else cur.nextHint,
            nextActions = if (next == AgentStatus.error) listOf("retry", "restart_after_review") else emptyList(),
            confirmToken = null,
            checkpoint = if (next == AgentStatus.done) null else cur.checkpoint,
        )
        tasks[taskId] = updated

        val sid = updated.sessionId ?: primarySessionId.get()
        if (sid != null) {
            appendBridgeEvent(
                sid,
                "【等待确认】任务「${updated.title}」$decisionLabel → ${updated.status}",
            )
            sessions.keys.toList().forEach { k ->
                sessions[k] = sessions.getValue(k).copy(isPrimary = k == sid)
            }
            primarySessionId.set(sid)
            sessions[sid] = sessions.getValue(sid).copy(updatedAt = iso)
        }
        agentStatus.set(if (next == AgentStatus.done) AgentStatus.idle else next)
        return updated
    }

    @Synchronized
    override fun cancelWaiting(
        taskId: String,
        expectConfirmToken: String?,
    ): Task {
        val cur = tasks[taskId] ?: error("任务不存在: $taskId")
        if (cur.status != AgentStatus.waiting) {
            throw ConfirmAlreadyConsumedException(taskId, "取消操作不可重复执行（非 waiting）")
        }
        val token = cur.confirmToken
        if (expectConfirmToken != null && expectConfirmToken != token) {
            throw StaleConfirmException(taskId)
        }
        if (token == null) {
            throw StaleConfirmException(taskId, "确认令牌缺失，无法取消")
        }

        // 本地约定：waiting → running → error；清 confirm_token；不另造「确认中」
        StatusTransitions.requireTransit(AgentStatus.waiting, AgentStatus.running)
        StatusTransitions.requireTransit(AgentStatus.running, AgentStatus.error)

        val iso = nowIso()
        val updated = cur.copy(
            status = AgentStatus.error,
            updatedAt = iso,
            waitingReason = null,
            recoverable = true,
            retryable = true,
            errorCode = "USER_CANCELLED",
            summary = "${cur.summary}（已取消确认）",
            nextHint = "已取消等待确认。可重试或核对后重新开始。",
            nextActions = listOf("retry", "restart_after_review"),
            confirmToken = null,
        )
        tasks[taskId] = updated

        val sid = updated.sessionId ?: primarySessionId.get()
        if (sid != null) {
            appendBridgeEvent(
                sid,
                "【等待确认】任务「${updated.title}」已取消 → ${updated.status}",
            )
            sessions.keys.toList().forEach { k ->
                sessions[k] = sessions.getValue(k).copy(isPrimary = k == sid)
            }
            primarySessionId.set(sid)
            sessions[sid] = sessions.getValue(sid).copy(updatedAt = iso)
        }
        agentStatus.set(AgentStatus.error)
        return updated
    }

    @Synchronized
    override fun editWaitingContent(taskId: String, edit: WaitingContentEdit): Task {
        val cur = tasks[taskId] ?: error("任务不存在: $taskId")
        require(cur.status == AgentStatus.waiting) {
            "editWaitingContent 仅适用于 waiting，当前=${cur.status}"
        }
        val iso = nowIso()
        val newToken = newConfirmToken()
        val newRev = cur.contentRevision + 1
        val remind = edit.remindAt ?: cur.remindAt
        val updated = cur.copy(
            title = edit.title?.takeIf { it.isNotBlank() } ?: cur.title,
            remindAt = remind,
            summary = edit.summary?.takeIf { it.isNotBlank() } ?: cur.summary,
            nextHint = edit.nextHint ?: cur.nextHint,
            updatedAt = iso,
            confirmToken = newToken,
            contentRevision = newRev,
            checkpoint = cur.checkpoint?.copy(
                payloadJson = """{"remind_at":"$remind","rev":$newRev}""",
            ),
            nextActions = listOf("confirm", "modify", "view_detail"),
        )
        tasks[taskId] = updated
        updated.sessionId?.let {
            appendBridgeEvent(
                it,
                "【确认失效】任务「${updated.title}」内容已修改（rev=$newRev），请使用新确认。",
            )
        }
        syncPrimaryAgentFromTask(updated)
        return updated
    }

    @Synchronized
    override fun enterRecover(taskId: String, note: String?): Task {
        val cur = tasks[taskId] ?: error("任务不存在: $taskId")
        StatusTransitions.requireTransit(cur.status, AgentStatus.recover)
        val iso = nowIso()
        val updated = cur.copy(
            status = AgentStatus.recover,
            updatedAt = iso,
            recoverable = true,
            retryable = false,
            errorCode = cur.errorCode ?: "RESUME_UNSUPPORTED",
            waitingReason = null,
            confirmToken = null,
            nextHint = "核对后重新开始",
            nextActions = listOf("restart_after_review", "cancel"),
            summary = note?.takeIf { it.isNotBlank() }
                ?: "后端暂不支持从中断点恢复。请核对后重新开始，勿点「继续执行」。",
            checkpoint = (cur.checkpoint ?: TaskCheckpoint(
                stepId = "interrupted",
                stepIndex = cur.currentStep,
                label = "中断",
                resumeSupported = false,
            )).copy(resumeSupported = false),
        )
        tasks[taskId] = updated
        updated.sessionId?.let {
            appendBridgeEvent(it, "任务「${updated.title}」→ recover：核对后重新开始（不可伪续跑）")
        }
        syncPrimaryAgentFromTask(updated)
        return updated
    }

    @Synchronized
    override fun restartAfterReview(taskId: String, toWaiting: Boolean): Task {
        val cur = tasks[taskId] ?: error("任务不存在: $taskId")
        require(cur.status == AgentStatus.recover || (cur.status == AgentStatus.error && !cur.retryable)) {
            "当前状态不能「核对后重新开始」，请返回计划查看任务状态"
        }
        val target = if (toWaiting) AgentStatus.waiting else AgentStatus.running
        if (cur.status == AgentStatus.error) {
            StatusTransitions.requireTransit(AgentStatus.error, AgentStatus.recover)
        }
        StatusTransitions.requireTransit(AgentStatus.recover, target)

        val iso = nowIso()
        val token = if (toWaiting) newConfirmToken() else null
        val rev = cur.contentRevision + 1
        val updated = cur.copy(
            status = target,
            updatedAt = iso,
            currentStep = 1,
            waitingReason = if (toWaiting) "user_confirm" else null,
            recoverable = toWaiting,
            retryable = false,
            errorCode = null,
            confirmToken = token,
            contentRevision = rev,
            checkpoint = null,
            nextHint = if (toWaiting) "核对后重新开始：请再次确认内容。" else "已重新开始执行（无旧检查点）。",
            nextActions = if (toWaiting) listOf("confirm", "modify", "view_detail") else listOf("cancel"),
            summary = "核对后重新开始（旧检查点已丢弃，rev=$rev）。${cur.title}",
        )
        tasks[taskId] = updated
        updated.sessionId?.let {
            appendBridgeEvent(
                it,
                "【核对后重新开始】任务「${updated.title}」→ ${updated.status}（checkpoint 已清空）",
            )
        }
        syncPrimaryAgentFromTask(updated)
        return updated
    }

    @Synchronized
    override fun joinDebugSnapshot(taskId: String): JoinDebugSnapshot =
        getTask(taskId).toJoinDebugSnapshot()

    @Synchronized
    override fun currentPrimarySessionId(): String? = primarySessionId.get()

    private fun appendBridgeEvent(sessionId: String, text: String) {
        val list = messages.getOrPut(sessionId) { mutableListOf() }
        list += SessionMessage(
            id = newId("msg"),
            role = "system",
            text = text,
            createdAt = nowIso(),
        )
        sessions[sessionId]?.let {
            sessions[sessionId] = it.copy(updatedAt = nowIso())
        }
    }
}
