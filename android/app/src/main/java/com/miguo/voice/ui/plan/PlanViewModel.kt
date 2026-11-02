package com.miguo.voice.ui.plan

import androidx.lifecycle.ViewModel
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.adapters.local.LocalStore
import com.miguo.voice.adapters.local.MiguoLocalAdapter
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.adapters.local.TaskCategory
import com.miguo.voice.adapters.local.WaitingDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlanUiState(
    val tasks: List<Task> = emptyList(),
    val agentStatus: AgentStatus = AgentStatus.idle,
    val primarySessionId: String? = null,
    val expandedCategories: Set<TaskCategory> = setOf(TaskCategory.travel),
    val selectedTaskId: String? = null,
    val selectedDateEpochDay: Long? = null,
    val flash: String? = null,
    val error: String? = null,
    /** 确认提交中：UI disabled；不引入独立 AgentStatus「确认中」。 */
    val confirming: Boolean = false,
    /** 计划/会话内容加载中（shots/22）。 */
    val loading: Boolean = false,
    /** 计划/会话内容加载失败（shots/23）。 */
    val pageFailed: Boolean = false,
    val revision: Int = 0,
)

/**
 * 正式计划/详情 ViewModel：沿用 LocalStore，与主会话确认卡共享同一实例。
 */
class PlanViewModel(
    private val store: MiguoLocalAdapter = sharedStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(PlanUiState())
    val ui: StateFlow<PlanUiState> = _ui.asStateFlow()

    init { refresh() }

    fun adapter(): MiguoLocalAdapter = store

    fun refresh() {
        _ui.update { it.copy(loading = true, pageFailed = false, error = null) }
        try {
            _ui.update {
                it.copy(
                    tasks = store.listTasks(),
                    agentStatus = store.getAgentStatus(),
                    primarySessionId = store.currentPrimarySessionId(),
                    revision = it.revision + 1,
                    flash = null,
                    error = null,
                    confirming = false,
                    loading = false,
                    pageFailed = false,
                )
            }
        } catch (e: Exception) {
            _ui.update {
                it.copy(
                    loading = false,
                    pageFailed = true,
                    error = e.message ?: "加载失败",
                    confirming = false,
                )
            }
        }
    }

    fun clearFlash() = _ui.update { it.copy(flash = null, error = null) }

    fun clearPageFailed() = _ui.update { it.copy(pageFailed = false, error = null) }

    fun toggleCategory(cat: TaskCategory) {
        _ui.update { st ->
            val next = st.expandedCategories.toMutableSet()
            if (!next.add(cat)) next.remove(cat)
            st.copy(expandedCategories = next)
        }
    }

    fun selectTask(taskId: String?) {
        _ui.update { it.copy(selectedTaskId = taskId) }
    }

    fun selectDate(epochDay: Long) {
        _ui.update { it.copy(selectedDateEpochDay = epochDay) }
    }

    fun selectedTask(): Task? =
        _ui.value.selectedTaskId?.let { runCatching { store.getTask(it) }.getOrNull() }

    /** 仅当前主会话下的 waiting；无则 null（确认卡不渲染）。 */
    fun waitingTaskForCurrentSession(): Task? {
        val sid = store.currentPrimarySessionId() ?: return null
        return store.listTasks().firstOrNull {
            it.status == AgentStatus.waiting && it.sessionId == sid
        }
    }

    fun waitingTask(): Task? = waitingTaskForCurrentSession()

    /**
     * 新对话：新建本地主会话。旧会话上的 waiting 仍保留在旧 sessionId，
     * 主会话空态不再挂全局 waiting。
     */
    fun beginFreshSession(title: String? = "主会话") {
        store.createSession(title)
        refresh()
    }

    /** 用户提出需求 → 创建/更新一张 waiting 确认卡。 */
    fun createWaitingFromRequest(userText: String): Task? {
        return runCatching {
            val task = store.createWaitingFromUserRequest(
                userText = userText,
                sessionId = store.currentPrimarySessionId(),
            )
            _ui.update {
                it.copy(
                    tasks = store.listTasks(),
                    agentStatus = store.getAgentStatus(),
                    primarySessionId = store.currentPrimarySessionId(),
                    selectedTaskId = task.id,
                    flash = null,
                    error = null,
                    confirming = false,
                    revision = it.revision + 1,
                )
            }
            task
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "创建待确认任务失败", confirming = false) }
        }.getOrNull()
    }

    /**
     * confirmWaiting(taskId, approve=true)：提交中 confirming=true（仅 UI），不另造 AgentStatus。
     * approve=false 时转正式 [cancelWaiting]。
     */
    fun confirmWaiting(taskId: String, approve: Boolean): Task? {
        if (!approve) return cancelWaiting(taskId)
        if (_ui.value.confirming) return null
        _ui.update { it.copy(confirming = true, error = null) }
        return runCatching {
            val cur = store.getTask(taskId)
            val task = store.confirmWaiting(
                taskId,
                WaitingDecision.approve,
                expectConfirmToken = cur.confirmToken,
            )
            _ui.update {
                it.copy(
                    tasks = store.listTasks(),
                    agentStatus = store.getAgentStatus(),
                    primarySessionId = store.currentPrimarySessionId(),
                    selectedTaskId = task.id,
                    flash = "已确认创建：${task.title}",
                    error = null,
                    confirming = false,
                    revision = it.revision + 1,
                )
            }
            task
        }.onFailure { e ->
            _ui.update {
                it.copy(
                    confirming = false,
                    error = e.message ?: "确认失败",
                    tasks = store.listTasks(),
                    agentStatus = store.getAgentStatus(),
                    revision = it.revision + 1,
                )
            }
        }.getOrNull()
    }

    /**
     * 正式 API：cancelWaiting(taskId, expectConfirmToken?)。
     * 成功清 confirm_token；状态回 error(USER_CANCELLED)；提交中仅 UI confirming。
     */
    fun cancelWaiting(taskId: String, expectConfirmToken: String? = null): Task? {
        if (_ui.value.confirming) return null
        _ui.update { it.copy(confirming = true, error = null) }
        return runCatching {
            val cur = store.getTask(taskId)
            val token = expectConfirmToken ?: cur.confirmToken
            val task = store.cancelWaiting(taskId, expectConfirmToken = token)
            _ui.update {
                it.copy(
                    tasks = store.listTasks(),
                    agentStatus = store.getAgentStatus(),
                    primarySessionId = store.currentPrimarySessionId(),
                    selectedTaskId = task.id,
                    flash = "已取消确认：${task.title}",
                    error = null,
                    confirming = false,
                    revision = it.revision + 1,
                )
            }
            task
        }.onFailure { e ->
            _ui.update {
                it.copy(
                    confirming = false,
                    error = e.message ?: "取消失败",
                    tasks = store.listTasks(),
                    agentStatus = store.getAgentStatus(),
                    revision = it.revision + 1,
                )
            }
        }.getOrNull()
    }

    fun retryTask(taskId: String) {
        runCatching {
            store.setTaskStatus(taskId, AgentStatus.running)
            refresh()
            _ui.update { it.copy(flash = "已重试 → running", selectedTaskId = taskId) }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "重试失败") }
        }
    }

    fun editWaitingContent(taskId: String, remindAt: String? = null, title: String? = null): Task? {
        return runCatching {
            val task = store.editWaitingContent(
                taskId,
                com.miguo.voice.adapters.local.WaitingContentEdit(title = title, remindAt = remindAt),
            )
            refresh()
            _ui.update { it.copy(flash = "内容已修改，请重新确认", selectedTaskId = task.id) }
            task
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "修改失败") }
        }.getOrNull()
    }

    fun restartAfterReview(taskId: String) {
        runCatching {
            val task = store.restartAfterReview(taskId, toWaiting = true)
            refresh()
            _ui.update {
                it.copy(
                    flash = "核对后重新开始：请再次确认",
                    selectedTaskId = task.id,
                    agentStatus = store.getAgentStatus(),
                )
            }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "重新开始失败") }
        }
    }


    fun stopRunning(taskId: String) {
        runCatching {
            store.setTaskStatus(
                taskId,
                AgentStatus.error,
                com.miguo.voice.adapters.local.StatusMeta(
                    errorCode = "USER_STOPPED",
                    retryable = true,
                    note = "已停止任务",
                ),
            )
            refresh()
            _ui.update { it.copy(flash = "已停止任务", selectedTaskId = taskId) }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "停止失败") }
        }
    }

    fun cancelTask(taskId: String) {
        runCatching {
            store.setTaskStatus(
                taskId,
                AgentStatus.error,
                com.miguo.voice.adapters.local.StatusMeta(
                    errorCode = "USER_CANCELLED",
                    retryable = true,
                    note = "已取消任务",
                ),
            )
            refresh()
            _ui.update { it.copy(flash = "已取消任务", selectedTaskId = taskId) }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "取消失败") }
        }
    }

    fun enterRecover(taskId: String) {
        runCatching {
            store.enterRecover(taskId)
            refresh()
            _ui.update { it.copy(flash = "已进入：核对后重新开始", selectedTaskId = taskId) }
        }.onFailure { e ->
            _ui.update { it.copy(error = e.message ?: "进入 recover 失败") }
        }
    }

    companion object {
        /** 进程内单例，保证主会话确认卡与计划页共用同一 LocalStore。正式路径 seed=false。 */
        val sharedStore: MiguoLocalAdapter by lazy { LocalStore(seed = false) }
    }
}
