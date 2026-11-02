package com.miguo.voice.ui.life

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.ui.components.uiChipLabel
import com.miguo.voice.ui.home.MigoAuxSheet
import com.miguo.voice.ui.home.MigoAuxSheetHost
import com.miguo.voice.ui.plan.PlanViewModel
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 主稿 lifedetail：日历 stub + 关联任务六态卡（与计划/详情复用同一套动作）。
 * 服务入口 entrypoint=null 为占位；任务字段走真实 waiting 契约。
 */
@Composable
fun LifeDetailScreen(
    planVm: PlanViewModel,
    onBackToServices: () -> Unit,
    onOpenTaskDetail: (String) -> Unit,
    onShowNotice: (String) -> Unit,
) {
    val ui by planVm.ui.collectAsState()
    val confirming = ui.confirming
    val linked = ui.tasks.firstOrNull { it.status == AgentStatus.waiting }
        ?: ui.tasks.firstOrNull()
    var auxSheet by remember { mutableStateOf<MigoAuxSheet?>(null) }

    Box(Modifier.fillMaxSize().background(MigoTokens.White)) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    MigoIcons.Back,
                    contentDescription = "返回",
                    tint = MigoTokens.Ink,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(onClick = onBackToServices)
                        .padding(8.dp),
                )
                Text(
                    "生活服务详情",
                    color = MigoTokens.Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "生活服务详情" },
                )
                Icon(
                    MigoIcons.Menu,
                    contentDescription = "打开侧边栏",
                    tint = MigoTokens.Ink,
                    modifier = Modifier.size(40.dp).padding(8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MigoTokens.CardShape)
                    .background(MigoTokens.Pale)
                    .padding(16.dp),
            ) {
                Text("日历占位", color = MigoTokens.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "svc-calendar-stub · status=stub",
                        color = MigoTokens.Muted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    TagChip(label = "原型占位", real = false)
                }
            }

            Spacer(Modifier.height(12.dp))
            if (linked != null) {
                LinkedLifeTaskCard(
                    task = linked,
                    confirming = confirming,
                    onPrimary = { action ->
                        when (action) {
                            LifeTaskAction.Confirm -> {
                                val t = planVm.confirmWaiting(linked.id, true)
                                if (t != null) onShowNotice("已确认创建")
                                else onShowNotice(planVm.ui.value.error ?: "确认失败")
                            }
                            LifeTaskAction.Modify -> {
                                auxSheet = MigoAuxSheet.Edit(linked)
                            }
                            LifeTaskAction.Cancel -> {
                                if (linked.status == AgentStatus.waiting) {
                                    auxSheet = MigoAuxSheet.Cancel(linked)
                                } else {
                                    planVm.cancelTask(linked.id)
                                    onShowNotice("已取消任务")
                                }
                            }
                            LifeTaskAction.Stop -> {
                                planVm.stopRunning(linked.id)
                                onShowNotice("已停止任务")
                            }
                            LifeTaskAction.Retry -> {
                                planVm.retryTask(linked.id)
                                onShowNotice("已重试")
                            }
                            LifeTaskAction.Restart -> {
                                planVm.restartAfterReview(linked.id)
                                onShowNotice("请核对后重新确认")
                            }
                            LifeTaskAction.Result -> {
                                auxSheet = MigoAuxSheet.Result(linked)
                            }
                            LifeTaskAction.StartDemo -> {
                                onShowNotice("请在主会话提出需求以生成确认卡")
                            }
                        }
                    },
                    onOpenDetail = { onOpenTaskDetail(linked.id) },
                )
            } else {
                StubLifeTaskCard(
                    onStartDemo = { onShowNotice("请在主会话提出需求以生成确认卡") },
                    onOpenDetail = { onShowNotice("暂无关联任务，请先在主会话生成 waiting 确认卡") },
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "生活服务卡片本身为 stub；任务六态组件与主会话/计划/详情复用同一套。",
                color = MigoTokens.Amber,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(28.dp))
        }

        auxSheet?.let { sheet ->
            MigoAuxSheetHost(
                sheet = sheet,
                onDismiss = { auxSheet = null },
                onConfirmCancel = { t ->
                    auxSheet = null
                    val ok = planVm.cancelWaiting(t.id)
                    if (ok != null) onShowNotice("已取消")
                    else onShowNotice(planVm.ui.value.error ?: "取消失败")
                },
                onSaveEdit = { t, title, remindAt ->
                    auxSheet = null
                    planVm.editWaitingContent(t.id, remindAt = remindAt, title = title)
                    onShowNotice("内容已改，旧确认失效，请重新确认")
                },
                onOpenPlans = {
                    auxSheet = null
                    onOpenTaskDetail(linked?.id ?: return@MigoAuxSheetHost)
                },
            )
        }
    }
}

private enum class LifeTaskAction {
    Confirm, Modify, Cancel, Stop, Retry, Restart, Result, StartDemo
}

@Composable
private fun LinkedLifeTaskCard(
    task: Task,
    confirming: Boolean,
    onPrimary: (LifeTaskAction) -> Unit,
    onOpenDetail: () -> Unit,
) {
    val canActWaiting = task.status == AgentStatus.waiting && task.confirmToken != null && !confirming
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MigoTokens.CardShape)
            .background(Color(0xFFFAFCF8))
            .border(1.dp, MigoTokens.LineSolid, MigoTokens.CardShape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MigoIcons.Clock, contentDescription = null, tint = MigoTokens.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("关联任务", color = MigoTokens.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            StatusChip(status = task.status)
        }
        Spacer(Modifier.height(10.dp))
        Text(task.title, color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            lifeStateCopy(task),
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        FieldRow("task_id", task.id, real = true, realLabel = "真实字段")
        FieldRow("waitingReason", task.waitingReason ?: "user_confirm", real = true)
        FieldRow("next_actions", lifeNextActions(task), real = true)
        FieldRow("服务入口", "entrypoint=null", real = false)
        Spacer(Modifier.height(14.dp))
        LifeTaskButtons(
            task = task,
            enabledWaiting = canActWaiting,
            enabledOther = !confirming,
            onAction = onPrimary,
        )
        Text(
            "打开统一任务详情 ›",
            color = MigoTokens.Primary,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable(onClick = onOpenDetail)
                .semantics { contentDescription = "打开统一任务详情" }
                .padding(vertical = 10.dp),
        )
    }
}

/** 无真实任务时仍按主稿展示 stub 卡，避免生活详情空白。 */
@Composable
private fun StubLifeTaskCard(
    onStartDemo: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MigoTokens.CardShape)
            .background(Color(0xFFFAFCF8))
            .border(1.dp, MigoTokens.LineSolid, MigoTokens.CardShape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MigoIcons.Clock, contentDescription = null, tint = MigoTokens.Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("关联任务", color = MigoTokens.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            StatusChip(status = AgentStatus.waiting)
        }
        Spacer(Modifier.height(10.dp))
        Text("周六出行准备", color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "请核对提醒内容。确认后才会进入执行。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        FieldRow("task_id", "task-waiting-ticket", real = true, realLabel = "真实字段")
        FieldRow("waitingReason", "user_confirm", real = true)
        FieldRow("next_actions", "confirm / modify / view_detail", real = true)
        FieldRow("服务入口", "entrypoint=null", real = false)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LifeBtn("确认创建", primary = true, enabled = true, onClick = onStartDemo, modifier = Modifier.weight(1.2f))
            LifeBtn("修改", primary = false, enabled = true, onClick = onStartDemo, modifier = Modifier.weight(0.8f))
            LifeBtn("取消", primary = false, enabled = true, onClick = onStartDemo, modifier = Modifier.weight(0.8f))
        }
        Text(
            "打开统一任务详情 ›",
            color = MigoTokens.Primary,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable(onClick = onOpenDetail)
                .semantics { contentDescription = "打开统一任务详情" }
                .padding(vertical = 10.dp),
        )
    }
}

@Composable
private fun LifeTaskButtons(
    task: Task,
    enabledWaiting: Boolean,
    enabledOther: Boolean,
    onAction: (LifeTaskAction) -> Unit,
) {
    when (task.status) {
        AgentStatus.waiting -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LifeBtn("确认创建", primary = true, enabled = enabledWaiting, onClick = { onAction(LifeTaskAction.Confirm) }, modifier = Modifier.weight(1.2f))
                LifeBtn("修改", primary = false, enabled = enabledWaiting, onClick = { onAction(LifeTaskAction.Modify) }, modifier = Modifier.weight(0.8f))
                LifeBtn("取消", primary = false, enabled = enabledWaiting, onClick = { onAction(LifeTaskAction.Cancel) }, modifier = Modifier.weight(0.8f))
            }
        }
        AgentStatus.running -> {
            LifeBtn("停止任务", primary = false, enabled = enabledOther, onClick = { onAction(LifeTaskAction.Stop) }, modifier = Modifier.fillMaxWidth())
        }
        AgentStatus.error -> {
            if (task.retryable) {
                LifeBtn("重试", primary = true, enabled = enabledOther, onClick = { onAction(LifeTaskAction.Retry) }, modifier = Modifier.fillMaxWidth())
            } else {
                LifeBtn("核对后重新开始", primary = true, enabled = enabledOther, onClick = { onAction(LifeTaskAction.Restart) }, modifier = Modifier.fillMaxWidth())
            }
        }
        AgentStatus.done -> {
            LifeBtn("查看结果", primary = true, enabled = enabledOther, onClick = { onAction(LifeTaskAction.Result) }, modifier = Modifier.fillMaxWidth())
        }
        AgentStatus.recover -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LifeBtn("核对后重新开始", primary = true, enabled = enabledOther, onClick = { onAction(LifeTaskAction.Restart) }, modifier = Modifier.weight(1.2f))
                LifeBtn("取消任务", primary = false, enabled = enabledOther, onClick = { onAction(LifeTaskAction.Cancel) }, modifier = Modifier.weight(0.8f))
            }
        }
        AgentStatus.idle -> {
            LifeBtn("开始示例任务", primary = true, enabled = enabledOther, onClick = { onAction(LifeTaskAction.StartDemo) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StatusChip(status: AgentStatus) {
    val dot = when (status) {
        AgentStatus.idle -> Color(0xFF9AA396)
        AgentStatus.running -> Color(0xFF4A7EB5)
        AgentStatus.waiting -> Color(0xFFC4923A)
        AgentStatus.error -> MigoTokens.Error
        AgentStatus.done -> MigoTokens.Primary
        AgentStatus.recover -> MigoTokens.WaitingText
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MigoTokens.PillShape)
            .background(Color(0xFFEFEAE0))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(
            "${status.name} · ${status.uiChipLabel()}",
            color = MigoTokens.Ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun FieldRow(key: String, value: String, real: Boolean, realLabel: String = "真实") {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, color = MigoTokens.Muted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(value, color = MigoTokens.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
        TagChip(label = if (real) realLabel else "占位", real = real)
    }
}

@Composable
private fun TagChip(label: String, real: Boolean) {
    Text(
        label,
        color = if (real) Color(0xFF3F5E3C) else MigoTokens.Amber,
        fontSize = 9.sp,
        modifier = Modifier
            .clip(MigoTokens.ButtonShape)
            .background(if (real) Color(0xFFE6EFE3) else Color(0xFFF0E6D4))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun LifeBtn(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(MigoTokens.ButtonShape)
            .then(
                if (primary) Modifier.background(if (enabled) MigoTokens.Primary else MigoTokens.Primary.copy(alpha = 0.45f))
                else Modifier.border(1.dp, MigoTokens.FieldBorder, MigoTokens.ButtonShape).background(MigoTokens.White),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 11.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) MigoTokens.White else MigoTokens.Ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun lifeStateCopy(task: Task): String {
    if (task.errorCode == "USER_CANCELLED") return "这项任务已取消。你可以修改后重新开始。"
    return when (task.status) {
        AgentStatus.idle -> "准备好后，告诉米果你想做什么。"
        AgentStatus.waiting -> task.nextHint ?: "请核对提醒内容。确认后才会进入执行。"
        AgentStatus.running -> "正在创建提醒，已保留确认的信息。"
        AgentStatus.error -> "执行失败。若 retryable=true 可原地重试；否则请「核对后重新开始」。样例：UPLOAD_TRANSIENT（可重试）。"
        AgentStatus.done -> "演示结果：提醒已创建。可在计划中查看。"
        AgentStatus.recover -> "后端暂不支持从中断点恢复（resumeSupported=false）。请核对行程后重新开始，不要点「继续执行」。"
    }
}

private fun lifeNextActions(task: Task): String {
    if (task.nextActions.isNotEmpty()) return task.nextActions.joinToString(" / ")
    return when (task.status) {
        AgentStatus.waiting -> "confirm / modify / view_detail"
        AgentStatus.error -> "retry"
        AgentStatus.recover -> "restart_after_review / cancel"
        AgentStatus.running -> "cancel"
        else -> "—"
    }
}
