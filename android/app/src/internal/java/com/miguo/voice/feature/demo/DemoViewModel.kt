package com.miguo.voice.feature.demo

import androidx.lifecycle.ViewModel
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.adapters.local.LocalStore
import com.miguo.voice.adapters.local.MiguoLocalAdapter
import com.miguo.voice.adapters.local.SessionDetail
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.adapters.local.TaskCategory
import com.miguo.voice.adapters.local.WaitingDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DemoRoute {
    Hub,       // 目录：会话列表 + 计划入口
    Session,   // 流程 A：主会话
    Plan,      // 计划六类
    Task,      // 流程 B：任务详情
}

data class DemoUiState(
    val route: DemoRoute = DemoRoute.Hub,
    val sessionsRefresh: Int = 0,
    val openSession: SessionDetail? = null,
    val selectedTask: Task? = null,
    val agentStatus: AgentStatus = AgentStatus.idle,
    val expandedCategories: Set<TaskCategory> = TaskCategory.displayOrder.toSet(),
    val flash: String? = null,
    val error: String? = null,
)

/**
 * 演示用 ViewModel：持有 [LocalStore]，驱动三最小流程。
 * 与现有 AppModel / 山水主屏解耦，避免大改无关 UI。
 */
class DemoViewModel(
    private val store: MiguoLocalAdapter = LocalStore(seed = true),
) : ViewModel() {

    private val _ui = MutableStateFlow(
        DemoUiState(agentStatus = store.getAgentStatus()),
    )
    val ui: StateFlow<DemoUiState> = _ui.asStateFlow()

    fun adapter(): MiguoLocalAdapter = store

    fun clearFlash() = _ui.update { it.copy(flash = null, error = null) }

    fun goHub() = _ui.update {
        it.copy(route = DemoRoute.Hub, selectedTask = null, openSession = null, flash = null, error = null)
    }

    fun goPlan() = _ui.update {
        it.copy(route = DemoRoute.Plan, selectedTask = null, flash = null, error = null)
    }

    /** 流程 A：打开会话 */
    fun openSession(sessionId: String) {
        runCatching {
            val detail = store.openSession(sessionId)
            _ui.update {
                it.copy(
                    route = DemoRoute.Session,
                    openSession = detail,
                    agentStatus = detail.agentStatus,
                    selectedTask = null,
                    flash = "已打开会话「${detail.summary.title}」",
                    error = null,
                    sessionsRefresh = it.sessionsRefresh + 1,
                )
            }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "打开会话失败") }
        }
    }

    fun refreshOpenSession() {
        val id = _ui.value.openSession?.summary?.id ?: store.currentPrimarySessionId() ?: return
        runCatching {
            val detail = store.getSession(id)
            val agent = store.getAgentStatus()
            _ui.update {
                it.copy(
                    openSession = detail.copy(agentStatus = agent),
                    agentStatus = agent,
                    sessionsRefresh = it.sessionsRefresh + 1,
                )
            }
        }
    }

    fun toggleCategory(cat: TaskCategory) {
        _ui.update { st ->
            val next = st.expandedCategories.toMutableSet()
            if (!next.add(cat)) next.remove(cat)
            st.copy(expandedCategories = next)
        }
    }

    /** 流程 B：查看任务详情 */
    fun openTask(taskId: String) {
        runCatching {
            val task = store.getTask(taskId)
            _ui.update {
                it.copy(route = DemoRoute.Task, selectedTask = task, flash = null, error = null)
            }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "打开任务失败") }
        }
    }

    fun retryTask(taskId: String) {
        runCatching {
            store.setTaskStatus(taskId, AgentStatus.running)
            openTask(taskId)
            _ui.update { it.copy(flash = "已发起可恢复重试 → running", agentStatus = store.getAgentStatus()) }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "重试失败") }
        }
    }

    /** 流程 C：waiting 确认/拒绝后回会话 */
    fun restartAfterReview(taskId: String) {
        runCatching {
            val task = store.restartAfterReview(taskId, toWaiting = true)
            openTask(task.id)
            refreshOpenSession()
            _ui.update {
                it.copy(
                    flash = "核对后重新开始 → waiting，请再次确认",
                    agentStatus = store.getAgentStatus(),
                )
            }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "重新开始失败") }
        }
    }

    fun editWaiting(taskId: String) {
        runCatching {
            val cur = store.getTask(taskId)
            val task = store.editWaitingContent(
                taskId,
                com.miguo.voice.adapters.local.WaitingContentEdit(
                    remindAt = (cur.remindAt ?: "2026-10-31 09:00") + "(改)",
                    title = cur.title + "·已改",
                ),
            )
            openTask(task.id)
            refreshOpenSession()
            _ui.update { it.copy(flash = "已改内容，旧确认失效（rev=${task.contentRevision}）") }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "修改失败") }
        }
    }

    fun confirmWaiting(taskId: String, approve: Boolean) {
        runCatching {
            val decision = if (approve) WaitingDecision.approve else WaitingDecision.reject
            val cur = store.getTask(taskId)
            val task = store.confirmWaiting(taskId, decision, expectConfirmToken = cur.confirmToken)
            val sid = task.sessionId ?: store.currentPrimarySessionId()
                ?: error("无关联会话可回写")
            // 用 getSession 保留 confirmWaiting 写入的事件与 Agent 态（勿再 open 重置 idle）
            val detail = store.getSession(sid)
            val agent = store.getAgentStatus()
            _ui.update {
                it.copy(
                    route = DemoRoute.Session,
                    openSession = detail.copy(agentStatus = agent),
                    agentStatus = agent,
                    selectedTask = task,
                    flash = "确认完成：${task.status}，已回写会话并回到主会话",
                    error = null,
                    sessionsRefresh = it.sessionsRefresh + 1,
                )
            }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "确认失败") }
        }
    }
}
