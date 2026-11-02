package com.miguo.voice.adapters.local

/**
 * 六态合法迁移（契约 §3.6 状态机，2026-10-30）。
 *
 * | from \ to | idle | running | waiting | error | done | recover |
 * | idle       |  =   |   ✓     |         |       |      |         |
 * | running    |      |   =     |    ✓    |   ✓   |  ✓   |         |
 * | waiting    |      |   ✓     |    =    |   ✓   |      |         |
 * | error      |      |   ✓*    |         |   =   |      |    ✓    |
 * | done       |  ✓   |         |         |       |  =   |         |
 * | recover    |  ✓   |   ✓**   |    ✓**  |       |      |    =    |
 *
 * * error→running 仅 retryable=true（可原地重试）。
 * ** recover→running / waiting：仅「核对后重新开始」，清空 checkpoint，禁止伪续跑。
 * confirmWaiting 内部原子路径：waiting → running → done|error（对外可一次完成）。
 */
object StatusTransitions {
    private val allowed: Map<AgentStatus, Set<AgentStatus>> = mapOf(
        AgentStatus.idle to setOf(AgentStatus.running),
        AgentStatus.running to setOf(AgentStatus.waiting, AgentStatus.done, AgentStatus.error),
        AgentStatus.waiting to setOf(AgentStatus.running, AgentStatus.error),
        AgentStatus.error to setOf(AgentStatus.running, AgentStatus.recover),
        AgentStatus.done to setOf(AgentStatus.idle),
        AgentStatus.recover to setOf(AgentStatus.idle, AgentStatus.running, AgentStatus.waiting),
    )

    fun canTransit(from: AgentStatus, to: AgentStatus): Boolean {
        if (from == to) return true
        return allowed[from]?.contains(to) == true
    }

    fun requireTransit(from: AgentStatus, to: AgentStatus) {
        if (!canTransit(from, to)) {
            throw IllegalStatusTransitionException(from, to)
        }
    }
}
