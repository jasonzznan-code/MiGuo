package com.miguo.voice.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.adapters.local.TaskCategory
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.components.SessionEdgeEmpty
import com.miguo.voice.ui.theme.MigoTokens
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun PlanScreen(
    vm: PlanViewModel,
    onOpenTask: (String) -> Unit,
    onBackToChat: () -> Unit,
    onNewPlan: () -> Unit = {},
) {
    val ui by vm.ui.collectAsState()
    val today = remember { LocalDate.now() }
    val week = remember(today) { (0..6).map { today.with(DayOfWeek.MONDAY).plusDays(it.toLong()) } }
    val selected = ui.selectedDateEpochDay?.let { LocalDate.ofEpochDay(it) } ?: today
    val planCount = ui.tasks.size

    Column(
        Modifier
            .fillMaxSize()
            .background(MigoTokens.White)
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 4.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "计划",
            color = MigoTokens.Ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { contentDescription = "计划" },
        )
        Text(
            "把聊过的事，一步步安排好。",
            color = MigoTokens.Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "$planCount",
                color = MigoTokens.Ink,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text("项演示任务", color = MigoTokens.Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                "+ 新建计划",
                color = MigoTokens.Primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onNewPlan)
                    .semantics { contentDescription = "新建计划" }
                    .padding(8.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("2026 年 10 月", color = MigoTokens.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("提醒日期 · 演示", color = MigoTokens.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            week.forEach { day ->
                val isSel = day == selected
                val weekLabel = when (day.dayOfWeek) {
                    DayOfWeek.MONDAY -> "一"
                    DayOfWeek.TUESDAY -> "二"
                    DayOfWeek.WEDNESDAY -> "三"
                    DayOfWeek.THURSDAY -> "四"
                    DayOfWeek.FRIDAY -> "五"
                    DayOfWeek.SATURDAY -> "六"
                    DayOfWeek.SUNDAY -> "日"
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSel) MigoTokens.Primary else MigoTokens.AssistantBubble)
                        .clickable { vm.selectDate(day.toEpochDay()) }
                        .padding(vertical = 10.dp),
                ) {
                    Text(weekLabel, color = if (isSel) MigoTokens.White else MigoTokens.Muted, fontSize = 11.sp)
                    Text("${day.dayOfMonth}", color = if (isSel) MigoTokens.White else MigoTokens.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (isSel) {
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.size(4.dp).clip(CircleShape).background(MigoTokens.White))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (ui.tasks.isEmpty()) {
            SessionEdgeEmpty(onChat = onBackToChat, modifier = Modifier.fillMaxWidth().height(360.dp))
        } else TaskCategory.displayOrder.forEach { cat ->
            val tasks = ui.tasks.filter { it.category == cat }
            val expanded = cat in ui.expandedCategories
            CategorySection(
                cat = cat,
                count = tasks.size,
                expanded = expanded,
                onToggle = { vm.toggleCategory(cat) },
                tasks = tasks,
                onOpenTask = onOpenTask,
                onBackToChat = onBackToChat,
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CategorySection(
    cat: TaskCategory,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    tasks: List<Task>,
    onOpenTask: (String) -> Unit,
    onBackToChat: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .semantics { contentDescription = cat.labelZh }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MigoTokens.UserBubble),
                contentAlignment = Alignment.Center,
            ) {
                Text(catGlyph(cat), fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(cat.labelZh, color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("$count 项", color = MigoTokens.Muted, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) MigoIcons.ChevronDown else MigoIcons.ChevronRight,
                contentDescription = null,
                tint = MigoTokens.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            if (tasks.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, bottom = 10.dp)
                        .background(MigoTokens.AssistantBubble, MigoTokens.CardShape)
                        .padding(14.dp),
                ) {
                    Text("还没有此类计划", color = MigoTokens.Muted, fontSize = 13.sp)
                    Text(
                        "回到主会话提出需求 >",
                        color = MigoTokens.Primary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clickable(onClick = onBackToChat)
                            .semantics { contentDescription = "回到主会话" },
                    )
                }
            } else {
                tasks.forEach { task ->
                    PlanTaskCard(task = task, onClick = { onOpenTask(task.id) })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.Line.copy(alpha = 0.5f)))
    }
}

@Composable
private fun PlanTaskCard(task: Task, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MigoTokens.CardShape)
            .background(MigoTokens.CardMint)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "查看任务 ${task.title}" }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(task.title, color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                task.status.labelZh,
                color = statusColor(task.status),
                fontSize = 11.sp,
                modifier = Modifier
                    .background(MigoTokens.White.copy(alpha = 0.75f), MigoTokens.PillShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        StepBars(task.currentStep, task.totalSteps)
        Spacer(Modifier.height(8.dp))
        Text(task.nextHint ?: "下一步：查看详情", color = MigoTokens.Muted, fontSize = 12.sp)
        Text(
            "查看任务 >",
            color = MigoTokens.Primary,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun StepBars(current: Int, total: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total.coerceAtLeast(1)) { idx ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (idx < current) MigoTokens.Primary else MigoTokens.White),
            )
        }
    }
}

private fun statusColor(status: AgentStatus) = when (status) {
    AgentStatus.waiting -> MigoTokens.WaitingText
    AgentStatus.error -> MigoTokens.Error
    AgentStatus.running -> MigoTokens.Primary
    AgentStatus.done -> MigoTokens.Muted
    AgentStatus.idle -> MigoTokens.Muted
    AgentStatus.recover -> MigoTokens.WaitingText
}

private fun catGlyph(cat: TaskCategory): String = when (cat) {
    TaskCategory.travel -> "出"
    TaskCategory.work -> "工"
    TaskCategory.life -> "生"
    TaskCategory.learn -> "学"
    TaskCategory.social -> "社"
    TaskCategory.ideas -> "点"
}
