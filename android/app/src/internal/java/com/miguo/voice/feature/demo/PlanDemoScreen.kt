package com.miguo.voice.feature.demo

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.conversation.AiGeneratedLabel
import com.miguo.voice.ui.components.AiGeneratedCaption
import com.miguo.voice.adapters.local.SessionDetail
import com.miguo.voice.adapters.local.SessionSummary
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.adapters.local.TaskCategory

private val Paper = Color(0xFFF7F3EA)
private val Ink = Color(0xFF302F2B)
private val Muted = Color(0xFF716D65)
private val Moss = Color(0xFF788773)
private val Seal = Color(0xFF9F392D)
private val Line = Color(0x3349443A)
private val Card = Color(0xFFF4F0E7)

/**
 * 下午第2–4项最小演示屏：会话目录 → 会话 / 计划 → 任务详情 → waiting 确认回会话。
 * 临时 Compose 屏，不改山水主界面布局。
 */
@Composable
fun PlanDemoScreen(
    onClose: () -> Unit,
    vm: DemoViewModel = remember { DemoViewModel() },
) {
    val ui by vm.ui.collectAsState()
    val store = vm.adapter()

    BackHandler {
        when (ui.route) {
            DemoRoute.Hub -> onClose()
            DemoRoute.Session, DemoRoute.Plan -> vm.goHub()
            DemoRoute.Task -> {
                // 从详情回计划（若从会话点进 waiting 也可 goHub）
                vm.goPlan()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                when (ui.route) {
                    DemoRoute.Hub -> onClose()
                    DemoRoute.Session, DemoRoute.Plan -> vm.goHub()
                    DemoRoute.Task -> vm.goPlan()
                }
            }) { Text("← 返回", color = Moss) }
            Spacer(Modifier.weight(1f))
            AgentStatusChip(ui.agentStatus)
        }
        Text(
            text = when (ui.route) {
                DemoRoute.Hub -> "米果 · 计划/会话演示"
                DemoRoute.Session -> "主会话"
                DemoRoute.Plan -> "计划（六类）"
                DemoRoute.Task -> "任务详情"
            },
            color = Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "契约六态 idle/running/waiting/error/done/recover · confirm≡waiting · 本地 LocalStore",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        ui.flash?.let {
            Banner(it, Moss) { vm.clearFlash() }
        }
        ui.error?.let {
            Banner(it, Seal) { vm.clearFlash() }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ui.route) {
                DemoRoute.Hub -> HubPane(
                    sessions = store.listSessions(),
                    onOpenSession = vm::openSession,
                    onOpenPlan = vm::goPlan,
                )
                DemoRoute.Session -> SessionPane(
                    detail = ui.openSession,
                    onOpenPlan = vm::goPlan,
                    onOpenWaitingTask = {
                        store.listTasks().firstOrNull { it.status == AgentStatus.waiting }?.id?.let(vm::openTask)
                    },
                )
                DemoRoute.Plan -> PlanPane(
                    tasks = store.listTasks(),
                    expanded = ui.expandedCategories,
                    onToggle = vm::toggleCategory,
                    onOpenTask = vm::openTask,
                )
                DemoRoute.Task -> TaskPane(
                    task = ui.selectedTask,
                    onConfirm = { vm.confirmWaiting(it, true) },
                    onReject = { vm.confirmWaiting(it, false) },
                    onRetry = vm::retryTask,
                    onRestart = vm::restartAfterReview,
                    onEdit = vm::editWaiting,
                    onJumpSession = { sid -> vm.openSession(sid) },
                )
            }
        }
    }
}

@Composable
private fun Banner(text: String, tone: Color, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("好的", color = tone, fontSize = 12.sp) }
    }
}

@Composable
private fun HubPane(
    sessions: List<SessionSummary>,
    onOpenSession: (String) -> Unit,
    onOpenPlan: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("一级目录（演示子集）", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip("主/最近会话") {}
            ActionChip("计划", onClick = onOpenPlan)
        }
        Spacer(Modifier.height(14.dp))
        Text("会话列表（流程 A）", fontWeight = FontWeight.Medium, color = Ink)
        Text("点一条打开 → MainSession，形象 idle", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        sessions.forEach { s ->
            SessionCard(s) { onOpenSession(s.id) }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenPlan,
            colors = ButtonDefaults.buttonColors(containerColor = Moss),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("进入计划页（流程 B）") }
    }
}

@Composable
private fun SessionCard(s: SessionSummary, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, color = Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (s.isPrimary) {
                Text("主", color = Moss, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (s.archived) {
                Spacer(Modifier.width(6.dp))
                Text("归档", color = Muted, fontSize = 11.sp)
            }
        }
        Text(s.id, color = Muted, fontSize = 11.sp)
        Text(s.updatedAt, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun SessionPane(
    detail: SessionDetail?,
    onOpenPlan: () -> Unit,
    onOpenWaitingTask: () -> Unit,
) {
    if (detail == null) {
        Text("无会话", color = Seal)
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(detail.summary.title, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        AgentStatusChip(detail.agentStatus)
        Spacer(Modifier.height(12.dp))
        Text("时间线（含 SessionPlanBridge 回写）", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        detail.messages.forEach { m ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .background(Card, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            ) {
                Text("${m.role} · ${m.createdAt}", color = Muted, fontSize = 11.sp)
                Text(m.text, color = Ink, fontSize = 14.sp)
                if (AiGeneratedLabel.isAssistantReply(m.role, m.text)) {
                    AiGeneratedCaption(Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenPlan, modifier = Modifier.fillMaxWidth()) {
            Text("从会话去计划", color = Moss)
        }
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onOpenWaitingTask,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC4923A)),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开 waiting 任务（流程 C）") }
    }
}

@Composable
private fun PlanPane(
    tasks: List<Task>,
    expanded: Set<TaskCategory>,
    onToggle: (TaskCategory) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("六类可折叠；空段不造假数据", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        TaskCategory.displayOrder.forEach { cat ->
            val group = tasks.filter { it.category == cat }
            val open = cat in expanded
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(cat) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${cat.labelZh}（${cat.name}）· ${group.size}",
                    color = Ink,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(if (open) "▾" else "▸", color = Muted)
            }
            if (open) {
                if (group.isEmpty()) {
                    Text("（空）", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                } else {
                    group.forEach { t ->
                        TaskRow(t) { onOpenTask(t.id) }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: Task, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(10.dp))
            .border(1.dp, Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Ink, fontWeight = FontWeight.Medium)
            Text(t.id, color = Muted, fontSize = 11.sp)
        }
        AgentStatusChip(t.status)
    }
}

@Composable
private fun TaskPane(
    task: Task?,
    onConfirm: (String) -> Unit,
    onReject: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRestart: (String) -> Unit,
    onEdit: (String) -> Unit,
    onJumpSession: (String) -> Unit,
) {
    if (task == null) {
        Text("无任务", color = Seal)
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(task.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        AgentStatusChip(task.status)
        Spacer(Modifier.height(12.dp))
        Meta("编号", task.id)
        Meta("分类", task.category.labelZh)
        Meta("状态", task.status.labelZh)
        Meta("会话", task.sessionId ?: "—")
        Meta("可否重试", if (task.retryable) "可以" else "需重新开始")
        Meta("进度点", task.checkpoint?.let { it.label.ifBlank { it.stepId } } ?: "—")
        Meta("下一步", task.nextHint ?: "—")
        Meta("更新时间", task.updatedAt)
        Spacer(Modifier.height(8.dp))
        Text(task.summary, color = Ink, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        if (task.status == AgentStatus.waiting) {
            Text("等待确认", fontWeight = FontWeight.Medium, color = Color(0xFFC4923A))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onConfirm(task.id) },
                enabled = task.confirmToken != null,
                colors = ButtonDefaults.buttonColors(containerColor = Moss),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("确认 → done，回写并回会话") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { onEdit(task.id) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("修改内容（旧确认失效）", color = Ink) }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { onReject(task.id) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("拒绝 → error", color = Seal) }
        }

        if (task.status == AgentStatus.error && task.retryable) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onRetry(task.id) },
                colors = ButtonDefaults.buttonColors(containerColor = Seal),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("可重复错误：重试 → running") }
        }

        if (task.status == AgentStatus.error && !task.retryable) {
            Spacer(Modifier.height(8.dp))
            Text("不可原地重试，请核对后重新开始", color = Seal, fontSize = 13.sp)
        }

        if (task.status == AgentStatus.recover) {
            Spacer(Modifier.height(8.dp))
            Text("核对后重新开始（禁止继续执行）", fontWeight = FontWeight.Medium, color = Color(0xFF7A5C8A))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onRestart(task.id) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7A5C8A)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("核对后重新开始 → 新 waiting") }
        }

        task.sessionId?.let { sid ->
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { onJumpSession(sid) }) {
                Text("跳回关联会话 $sid", color = Moss)
            }
        }
    }
}

@Composable
private fun Meta(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$k：", color = Muted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
        Text(v, color = Ink, fontSize = 12.sp)
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Ink,
        fontSize = 12.sp,
        modifier = Modifier
            .background(Card, RoundedCornerShape(999.dp))
            .border(1.dp, Line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
