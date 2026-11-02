package com.miguo.voice.conversation

import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.ReadableError

/** 状态机（对应 iOS ConversationState.swift；Phase 1 不实现 suspended/reconnecting 的自动恢复，保留供阶段 2）。 */
sealed class ConversationState(val labelKey: String) {
    object Idle : ConversationState("idle")
    object Starting : ConversationState("starting")
    object Listening : ConversationState("listening")
    data class UserSpeaking(val turnId: Int) : ConversationState("user_speaking")
    /** VAD 结束后、最终转写出来之前：语音识别中 */
    data class Recognizing(val turnId: Int) : ConversationState("recognizing")
    /** 已有转写，等待 / 正在生成回复 */
    data class Thinking(val turnId: Int) : ConversationState("thinking")
    /** 首包音频前的 TTS 合成阶段（也可在 Speaking 中继续合成后续句） */
    data class Synthesizing(val turnId: Int) : ConversationState("synthesizing")
    data class Speaking(val turnId: Int) : ConversationState("speaking")
    data class Suspended(val reason: String) : ConversationState("suspended")
    data class Reconnecting(val attempt: Int) : ConversationState("reconnecting")
    object Stopping : ConversationState("stopping")
    data class Error(val error: ReadableError) : ConversationState("error")

    val isActive: Boolean get() = this !is Idle && this !is Error

    /** 文案优先用服务端 /client/config 的 labels，缺省为内置（与 iOS 完全一致：正在听 / 你在说话 / 米果在想… / 米果在说） */
    fun title(cfg: ClientConfig): String = when (this) {
        is Error -> error.title
        is Reconnecting -> cfg.label(labelKey).let { if (it.contains("{n}")) ClientConfig.fill(it, mapOf("n" to "$attempt")) else "$it（第 $attempt 次）" }
        else -> cfg.label(labelKey)
    }

    val turn: Int?
        get() = when (this) {
            is UserSpeaking -> turnId
            is Recognizing -> turnId
            is Thinking -> turnId
            is Synthesizing -> turnId
            is Speaking -> turnId
            else -> null
        }

    override fun toString(): String = labelKey + (turn?.let { "($it)" } ?: "")
}

/** 字幕：一轮 = 用户一句 + 米果的回复（reply_text 逐句追加） */
data class CaptionTurn(
    val id: Int,
    val turnId: Int,
    val user: String = "",
    val assistant: String = "",
    /** "text" = 文字输入轮 */
    val userSource: String? = null,
    val interrupted: Boolean = false,
    val failed: String? = null,
)

enum class HapticKind { START, VAD_START, BARGE_IN, STOP, ERROR }

data class HapticEvent(val id: Int, val kind: HapticKind)
