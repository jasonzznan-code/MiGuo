package com.miguo.voice.adapters.local

/**
 * 米果统一 Agent / 任务六态（契约 §3.6；2026-10-30 补 recover）。
 * UI 的「确认」不是独立状态：确认卡一律用 waiting + waitingReason。
 * 禁止引入第二套 Mood 字符串（如 IDLE/WORKING / confirm）。
 */
enum class AgentStatus {
    idle,
    running,
    waiting,
    error,
    done,
    recover,
    ;

    val labelZh: String
        get() = when (this) {
            idle -> "空闲"
            running -> "执行中"
            waiting -> "等待确认"
            error -> "失败"
            done -> "完成"
            recover -> "中断待重开"
        }
}
