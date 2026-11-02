package com.miguo.voice.adapters.local

/**
 * 本地适配层接口草案（契约 §4）。
 * 内存 / JSON mock 均可；本仓库默认 LocalStore 内存实现。
 */
interface MiguoLocalAdapter {
    fun listSessions(): List<SessionSummary>

    fun openSession(sessionId: String): SessionDetail

    /** 只读取会话详情，不改变主会话 / Agent 状态 */
    fun getSession(sessionId: String): SessionDetail

    fun createSession(title: String? = null): SessionDetail

    fun renameSession(id: String, title: String): SessionSummary

    fun archiveSession(id: String): SessionSummary

    fun deleteSession(id: String)

    fun listTasks(category: TaskCategory? = null): List<Task>

    fun getTask(taskId: String): Task

    fun upsertTask(task: Task): Task

    fun setTaskStatus(taskId: String, status: AgentStatus, meta: StatusMeta? = null): Task

    fun listServices(): List<ServiceCard>

    fun getAgentStatus(): AgentStatus

    fun setAgentStatus(status: AgentStatus): AgentStatus

    /**
     * 流程 C：确认/拒绝 waiting，回写会话事件并迁移状态。
     * @param expectConfirmToken 非空时必须与任务当前 token 一致，否则 StaleConfirmException。
     * 二次调用（已非 waiting）抛 ConfirmAlreadyConsumedException。
     */
    fun confirmWaiting(
        taskId: String,
        decision: WaitingDecision,
        expectConfirmToken: String? = null,
    ): Task

    /**
     * 正式取消 waiting：cancelWaiting(taskId, expectConfirmToken?)。
     * 成功后清 confirm_token；状态按本地约定 waiting→error（USER_CANCELLED），
     * 不另造「确认中」AgentStatus（提交中仅 UI confirming）。
     */
    fun cancelWaiting(
        taskId: String,
        expectConfirmToken: String? = null,
    ): Task

    fun currentPrimarySessionId(): String?

    /**
     * 产品主路径：用户提出需求 → 本地整理一张 waiting 待确认卡。
     * @param sessionId 绑定会话；null 则用当前主会话
     */
    fun createWaitingFromUserRequest(userText: String, sessionId: String? = null): Task

    /** 修改 waiting 内容：轮换 confirmToken、contentRevision++，旧确认失效 */
    fun editWaitingContent(taskId: String, edit: WaitingContentEdit): Task

    /**
     * 不可原地恢复的失败 → recover。
     * UI/接口文案必须是「核对后重新开始」，禁止显示「继续执行」。
     */
    fun enterRecover(taskId: String, note: String? = null): Task

    /**
     * 核对后重新开始：清空 checkpoint，发新 waiting 确认卡（或直接 running）。
     * 绝不从旧 checkpoint 伪续跑。
     */
    fun restartAfterReview(taskId: String, toWaiting: Boolean = true): Task

    /** 联调快照：把任务字段整理为 JoinDebugSnapshot */
    fun joinDebugSnapshot(taskId: String): JoinDebugSnapshot
}
