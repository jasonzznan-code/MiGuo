package com.miguo.voice.adapters.local

/** 会话列表摘要（契约 §4 listSessions） */
data class SessionSummary(
    val id: String,
    val title: String,
    val updatedAt: String,
    val archived: Boolean = false,
    val isPrimary: Boolean = false,
)

/** 会话内一条时间线消息/事件 */
data class SessionMessage(
    val id: String,
    val role: String, // user | assistant | system
    val text: String,
    val createdAt: String,
)

/** 打开会话后的详情 */
data class SessionDetail(
    val summary: SessionSummary,
    val messages: List<SessionMessage>,
    val agentStatus: AgentStatus,
)

/**
 * 任务检查点（联调 / 恢复判定）。
 * resumeSupported=false 时禁止「继续执行」，只能「核对后重新开始」。
 */
data class TaskCheckpoint(
    val stepId: String,
    val stepIndex: Int,
    val label: String,
    val resumeSupported: Boolean = false,
    val payloadJson: String? = null,
)

/**
 * 任务对象（契约 §3.4 + 2026-10-30 联调字段）。
 * id / sessionId(=conversation_id) / serviceIds 分属不同 ID 空间。
 *
 * 确认防重：confirmToken + contentRevision；改内容后 token 轮换，旧确认失效。
 */
data class Task(
    val id: String,
    val title: String,
    val category: TaskCategory,
    val status: AgentStatus,
    val sessionId: String? = null,
    val serviceIds: List<String> = emptyList(),
    val summary: String = "",
    val createdAt: String,
    val updatedAt: String,
    val recoverable: Boolean = false,
    val waitingReason: String? = null,
    /** reminder display e.g. 2026-10-31 09:00 */
    val remindAt: String? = null,
    val currentStep: Int = 1,
    val totalSteps: Int = 3,
    val nextHint: String? = null,
    /** 联调：下一步可执行动作，如 confirm / modify / restart / retry */
    val nextActions: List<String> = emptyList(),
    val errorCode: String? = null,
    /** 是否允许原地重试（error→running）；与 recoverable 区分：recoverable 还可进 recover */
    val retryable: Boolean = false,
    val checkpoint: TaskCheckpoint? = null,
    /** 单次确认令牌；消费后或内容变更后失效 */
    val confirmToken: String? = null,
    val contentRevision: Int = 1,
) {
    /** 联调别名：conversation_id == sessionId */
    val conversationId: String? get() = sessionId
}

/** 服务 / MCP 占位卡片（契约 §3.5，本阶段 stub） */
data class ServiceCard(
    val id: String,
    val name: String,
    val kind: String, // mcp | life | builtin
    val status: String = "stub", // available | unavailable | stub
    val entrypoint: String? = null,
)

/** 流程 C 确认决策 */
enum class WaitingDecision {
    approve,
    reject,
}

/** setTaskStatus / confirmWaiting 可选元数据 */
data class StatusMeta(
    val waitingReason: String? = null,
    val note: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean? = null,
    val nextActions: List<String>? = null,
    val checkpoint: TaskCheckpoint? = null,
)

/** 修改 waiting 内容（会使旧 confirmToken 失效） */
data class WaitingContentEdit(
    val title: String? = null,
    val remindAt: String? = null,
    val summary: String? = null,
    val nextHint: String? = null,
)

class IllegalStatusTransitionException(
    val from: AgentStatus,
    val to: AgentStatus,
    message: String = "非法状态迁移: $from → $to",
) : IllegalArgumentException(message)

class StaleConfirmException(
    val taskId: String,
    message: String = "确认已失效：内容已修改或令牌已消费，请核对后重新确认",
) : IllegalStateException(message)

class ConfirmAlreadyConsumedException(
    val taskId: String,
    message: String = "确认操作不可重复执行",
) : IllegalStateException(message)
