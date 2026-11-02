package com.miguo.voice.ui.plan

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.BuildConfig
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.ui.components.uiChipLabel
import com.miguo.voice.ui.home.MigoAuxSheet
import com.miguo.voice.ui.home.MigoAuxSheetHost
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

@Composable
fun TaskDetailScreen(
    vm: PlanViewModel,
    taskId: String,
    onBackToPlan: () -> Unit,
    onBackToConversation: (conversationId: String?) -> Unit,
    onModify: () -> Unit = {},
) {
    val ui by vm.ui.collectAsState()
    val confirming = ui.confirming
    val task = ui.tasks.firstOrNull { it.id == taskId } ?: runCatching { vm.adapter().getTask(taskId) }.getOrNull()
    var auxSheet by remember { mutableStateOf<MigoAuxSheet?>(null) }

    Box(Modifier.fillMaxSize().background(MigoTokens.White)) {
        Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MigoIcons.Back,
                contentDescription = "返回",
                tint = MigoTokens.Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBackToPlan)
                    .semantics { contentDescription = "返回计划" }
                    .padding(8.dp),
            )
            Text(
                "任务详情",
                color = MigoTokens.Ink,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f).semantics { contentDescription = "任务详情" },
            )
            Icon(
                MigoIcons.Menu,
                contentDescription = "菜单",
                tint = MigoTokens.Ink,
                modifier = Modifier.size(40.dp).padding(8.dp),
            )
        }

        if (task == null) {
            Text("任务不存在", color = MigoTokens.Error, modifier = Modifier.padding(20.dp))
            return
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${task.category.labelZh} / 提醒 · 演示",
                    color = MigoTokens.Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    task.status.uiChipLabel(),
                    color = if (task.status == AgentStatus.waiting) MigoTokens.WaitingText else MigoTokens.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(
                            if (task.status == AgentStatus.waiting) MigoTokens.Waiting.copy(alpha = 0.35f)
                            else MigoTokens.AssistantBubble,
                            MigoTokens.PillShape,
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(task.title, color = MigoTokens.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "任务编号 ${task.id} · 来自当前会话",
                color = MigoTokens.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(12.dp))
            FlavorTaskDebugBlock(task)
            if (!BuildConfig.IS_INTERNAL) {
                UserStatusSummary(task)
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MigoTokens.CardMint, MigoTokens.CardShape)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .background(MigoTokens.White, MigoTokens.CardShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("10月", color = MigoTokens.Muted, fontSize = 11.sp)
                    Text("31", color = MigoTokens.Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        task.remindAt?.substringAfter(' ') ?: "09:00",
                        color = MigoTokens.Ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${task.remindAt ?: "待定"} · 本地时区",
                        color = MigoTokens.Muted,
                        fontSize = 12.sp,
                    )
                    Text("提醒我 · ${task.title}", color = MigoTokens.Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "提醒演示，不会真实创建，可修改内容与时间",
                color = MigoTokens.Muted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(18.dp))
            Text("执行步骤", color = MigoTokens.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            StepRow(task.currentStep, task.status)

            Spacer(Modifier.height(18.dp))
            when (task.status) {
                AgentStatus.waiting -> {
                    Text("待确认操作", color = MigoTokens.Muted, fontSize = 12.sp)
                    Text("创建一条提醒", color = MigoTokens.Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        task.nextHint ?: "请核对内容后再确认。确认后才会创建提醒并加入计划。",
                        color = MigoTokens.Muted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "修改内容或时间 →",
                        color = MigoTokens.Primary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clickable(enabled = !confirming, onClick = {
                                auxSheet = MigoAuxSheet.Edit(task)
                            })
                            .semantics { contentDescription = "修改内容或时间" },
                    )
                }
                AgentStatus.recover -> {
                    Text("当前进展", color = MigoTokens.Muted, fontSize = 12.sp)
                    Text("恢复", color = MigoTokens.Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        "后端暂不支持从中断点恢复（resumeSupported=false）。请核对行程后重新开始，不要点「继续执行」。",
                        color = MigoTokens.WaitingText,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                else -> {
                    Text("当前进展", color = MigoTokens.Muted, fontSize = 12.sp)
                    Text(task.status.uiChipLabel(), color = MigoTokens.Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        task.nextHint ?: task.summary,
                        color = MigoTokens.Muted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            ui.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MigoTokens.Error, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
        }

        // 定稿底栏：主操作 | 回到原对话 左右并排
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (task.status) {
                AgentStatus.waiting -> {
                    DetailPrimaryButton(
                        label = "确认创建",
                        enabled = task.confirmToken != null && !confirming,
                        loading = confirming,
                        onClick = {
                            val ok = vm.confirmWaiting(task.id, true)
                            if (ok != null) onBackToConversation(task.sessionId)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    DetailSecondaryButton(
                        label = "取消",
                        onClick = { auxSheet = MigoAuxSheet.Cancel(task) },
                        modifier = Modifier.weight(1f),
                    )
                }
                AgentStatus.recover -> {
                    DetailPrimaryButton(
                        label = "核对后重新开始",
                        enabled = !confirming,
                        onClick = {
                            vm.restartAfterReview(task.id)
                            onBackToConversation(task.sessionId)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                AgentStatus.error -> {
                    if (task.retryable) {
                        DetailPrimaryButton(
                            label = "重试",
                            enabled = !confirming,
                            onClick = { vm.retryTask(task.id) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        DetailPrimaryButton(
                            label = "核对后重新开始",
                            enabled = !confirming,
                            onClick = {
                                vm.restartAfterReview(task.id)
                                onBackToConversation(task.sessionId)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                else -> {
                    DetailPrimaryButton(
                        label = "返回计划",
                        enabled = true,
                        onClick = onBackToPlan,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (task.status != AgentStatus.waiting) {
                DetailSecondaryButton(
                    label = "回到原对话",
                    onClick = { onBackToConversation(task.sessionId) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

        auxSheet?.let { sheet ->
            MigoAuxSheetHost(
                sheet = sheet,
                onDismiss = { auxSheet = null },
                onConfirmCancel = { t ->
                    auxSheet = null
                    val ok = vm.cancelWaiting(t.id)
                    if (ok != null) onBackToConversation(t.sessionId)
                },
                onSaveEdit = { t, title, time ->
                    auxSheet = null
                    vm.editWaitingContent(t.id, remindAt = time.replace('T', ' '), title = title)
                    onModify()
                },
                onOpenPlans = {
                    auxSheet = null
                    onBackToPlan()
                },
            )
        }
    }
}

@Composable
private fun DetailPrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(MigoTokens.ButtonShape)
            .background(if (enabled || loading) MigoTokens.Primary else MigoTokens.Primary.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, color = MigoTokens.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DetailSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(MigoTokens.ButtonShape)
            .border(1.dp, MigoTokens.FieldBorder, MigoTokens.ButtonShape)
            .background(MigoTokens.White)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MigoTokens.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun UserStatusSummary(task: com.miguo.voice.adapters.local.Task) {
    val statusText = when (task.status) {
        AgentStatus.error -> if (task.retryable) "刚才没完成，可以点「重试」。" else "需要核对后重新开始。"
        AgentStatus.recover -> "进度已中断，请核对后重新开始（不能从中断点继续）。"
        AgentStatus.waiting -> "请核对内容后再确认。"
        AgentStatus.running -> "正在执行…"
        AgentStatus.done -> "已完成。"
        AgentStatus.idle -> "待开始。"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MigoTokens.AssistantBubble.copy(alpha = 0.55f), MigoTokens.CardShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("当前状态：${task.status.uiChipLabel()}", color = MigoTokens.Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(statusText, color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 18.sp)
        task.nextHint?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MigoTokens.Ink, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun StepRow(currentStep: Int, status: AgentStatus) {
    val labels = listOf("整理提醒内容", "创建提醒", "返回结果并加入计划")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        labels.forEachIndexed { idx, label ->
            val step = idx + 1
            val done = step < currentStep || status == AgentStatus.done
            val current = step == currentStep && status != AgentStatus.done
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                done -> MigoTokens.Primary
                                current -> MigoTokens.Ink
                                else -> MigoTokens.AssistantBubble
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (done) "✓" else "$step",
                        color = if (done || current) MigoTokens.White else MigoTokens.Muted,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(label, color = MigoTokens.Muted, fontSize = 10.sp, maxLines = 2, lineHeight = 13.sp)
            }
        }
    }
}
