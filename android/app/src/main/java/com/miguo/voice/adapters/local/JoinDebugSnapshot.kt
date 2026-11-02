package com.miguo.voice.adapters.local

/**
 * 联调示例载荷：字段名对齐前后端约定（snake 与 camel 双写便于对照）。
 * 由 [MiguoLocalAdapter.joinDebugSnapshot] / [Task.toJoinDebugSnapshot] 生成。
 */
data class JoinDebugSnapshot(
    val task_id: String,
    val conversation_id: String?,
    val current_step: Int,
    val total_steps: Int,
    val status: String,
    val next_actions: List<String>,
    val waitingReason: String?,
    val error_code: String?,
    val retryable: Boolean,
    val recoverable: Boolean,
    val checkpoint: TaskCheckpoint?,
    val confirm_token: String?,
    val content_revision: Int,
    val title: String,
    val category: String,
    val summary: String,
    val remind_at: String?,
    val next_hint: String?,
    val session_id: String?,
    val service_ids: List<String>,
    val created_at: String,
    val updated_at: String,
)

fun Task.toJoinDebugSnapshot(): JoinDebugSnapshot = JoinDebugSnapshot(
    task_id = id,
    conversation_id = conversationId,
    current_step = currentStep,
    total_steps = totalSteps,
    status = status.name,
    next_actions = nextActions,
    waitingReason = waitingReason,
    error_code = errorCode,
    retryable = retryable,
    recoverable = recoverable,
    checkpoint = checkpoint,
    confirm_token = confirmToken,
    content_revision = contentRevision,
    title = title,
    category = category.name,
    summary = summary,
    remind_at = remindAt,
    next_hint = nextHint,
    session_id = sessionId,
    service_ids = serviceIds,
    created_at = createdAt,
    updated_at = updatedAt,
)
