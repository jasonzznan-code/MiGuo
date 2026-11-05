package com.miguo.voice.conversation

/**
 * 助手回复的界面标识。只决定气泡旁要不要显示「内容由 AI 生成」，
 * 不写入 [ChatEntry.assistant]、历史消息正文，也不进入发给后端的请求。
 */
object AiGeneratedLabel {
    const val TEXT = "内容由 AI 生成"

    /** 等待首个模型片段前的占位，是界面状态，不是模型输出。 */
    const val PENDING_PLACEHOLDER = "思考中…"

    /**
     * 一条时间线消息是否为应标注的 AI 回复。
     * 仅 role=assistant 且正文非空；用户消息、系统消息、空白都不标。
     */
    fun isAssistantReply(role: String, body: String): Boolean =
        role == "assistant" && body.isNotBlank()
}

enum class ChatMessageKind {
    USER,
    ASSISTANT,
    /** 助手气泡占位（思考中），不标成 AI 生成内容。 */
    PENDING,
    /** 失败/系统错误提示，不标成 AI 回复。 */
    ERROR,
}

/**
 * 一条气泡的展示计划。标识是独立标志，调用方不得把它拼进 [text]。
 * 每个 [ChatEntry] 最多一条 [ChatMessageKind.ASSISTANT]，因此标识不会重复。
 */
data class ChatMessagePresentation(
    val kind: ChatMessageKind,
    val text: String,
    val showAiGeneratedLabel: Boolean,
) {
    init {
        require(showAiGeneratedLabel == (kind == ChatMessageKind.ASSISTANT)) {
            "AI 标识只贴在助手正文上"
        }
        require(text.isNotBlank())
    }
}

/**
 * 主会话、语音字幕、历史恢复共用的展示计划。
 * 流式过程中只要助手正文已有片段就标注；生成完成、历史、重试后替换正文同样只标一次。
 */
fun ChatEntry.messagePresentation(): List<ChatMessagePresentation> {
    val out = ArrayList<ChatMessagePresentation>(3)
    if (user.isNotBlank()) {
        out += ChatMessagePresentation(ChatMessageKind.USER, user, showAiGeneratedLabel = false)
    }
    when {
        assistant.isNotBlank() ->
            out += ChatMessagePresentation(
                ChatMessageKind.ASSISTANT,
                assistant,
                showAiGeneratedLabel = true,
            )
        pending ->
            out += ChatMessagePresentation(
                ChatMessageKind.PENDING,
                AiGeneratedLabel.PENDING_PLACEHOLDER,
                showAiGeneratedLabel = false,
            )
    }
    failed?.takeIf { it.isNotBlank() }?.let { err ->
        out += ChatMessagePresentation(ChatMessageKind.ERROR, err, showAiGeneratedLabel = false)
    }
    return out
}
