package com.miguo.voice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/** 定稿顶栏/芯片文案（shots 用「错误」「恢复」；契约 enum labelZh 保留失败/中断待重开）。 */
fun AgentStatus.uiChipLabel(): String = when (this) {
    AgentStatus.error -> "错误"
    AgentStatus.recover -> "恢复"
    else -> labelZh
}

/** 定稿主会话 waiting 确认卡（shots/09）：主按钮全宽，修改/取消并排。 */
@Composable
fun TaskConfirmCard(
    task: Task,
    onConfirm: () -> Unit,
    onModify: () -> Unit,
    onViewDetail: () -> Unit,
    onCancel: (() -> Unit)? = null,
    confirming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val canAct = task.status == AgentStatus.waiting && task.confirmToken != null && !confirming
    Column(
        modifier
            .fillMaxWidth()
            .clip(MigoTokens.CardShape)
            .background(MigoTokens.White)
            .border(1.dp, MigoTokens.LineSolid, MigoTokens.CardShape)
            .padding(16.dp)
            .semantics { contentDescription = "提醒任务确认卡" },
    ) {
        TaskCardHeader(statusLabel = if (confirming) "提交中…" else task.status.uiChipLabel(), waiting = true)
        Spacer(Modifier.height(12.dp))
        Text(task.title, color = MigoTokens.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("提醒时间", color = MigoTokens.Muted, fontSize = 13.sp)
            Text(task.remindAt ?: "待定", color = MigoTokens.Ink, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            task.nextHint ?: "请核对提醒内容。确认后才会进入执行。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(14.dp))
        PrimaryFillButton(label = "确认创建", enabled = canAct, loading = confirming, onClick = onConfirm)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryOutlineButton(label = "修改", enabled = canAct, onClick = onModify, modifier = Modifier.weight(1f))
            if (onCancel != null) {
                SecondaryOutlineButton(label = "取消", enabled = canAct, onClick = onCancel, modifier = Modifier.weight(1f))
            }
        }
        DetailLink(onClick = onViewDetail, enabled = !confirming)
    }
}

/** 主会话六态任务卡（非 idle）：按 status 切换按钮区。 */
@Composable
fun SessionTaskCard(
    task: Task,
    onConfirm: () -> Unit = {},
    onModify: () -> Unit = {},
    onCancel: () -> Unit = {},
    onViewDetail: () -> Unit,
    onStop: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRestart: () -> Unit = {},
    onViewResult: () -> Unit = {},
    confirming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (task.status) {
        AgentStatus.waiting -> TaskConfirmCard(
            task = task,
            onConfirm = onConfirm,
            onModify = onModify,
            onViewDetail = onViewDetail,
            onCancel = onCancel,
            confirming = confirming,
            modifier = modifier,
        )
        AgentStatus.running -> {
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(MigoTokens.CardShape)
                    .background(MigoTokens.CardMint.copy(alpha = 0.55f))
                    .border(1.dp, MigoTokens.LineSolid, MigoTokens.CardShape)
                    .padding(16.dp),
            ) {
                TaskCardHeader(statusLabel = task.status.uiChipLabel(), waiting = false)
                Spacer(Modifier.height(12.dp))
                Text(task.title, color = MigoTokens.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("提醒时间", color = MigoTokens.Muted, fontSize = 13.sp)
                    Text(task.remindAt ?: "待定", color = MigoTokens.Ink, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(task.nextHint ?: "正在创建提醒，已保留确认的信息。", color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                StepBars(task.currentStep, task.totalSteps)
                Spacer(Modifier.height(6.dp))
                Text("第 ${task.currentStep} 步 / 共 ${task.totalSteps} 步", color = MigoTokens.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                SecondaryOutlineButton(label = "停止任务", enabled = true, onClick = onStop, modifier = Modifier.fillMaxWidth())
                DetailLink(onClick = onViewDetail)
            }
        }
        AgentStatus.error -> {
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(MigoTokens.CardShape)
                    .background(MigoTokens.White)
                    .border(1.dp, MigoTokens.LineSolid, MigoTokens.CardShape)
                    .padding(16.dp),
            ) {
                TaskCardHeader(statusLabel = task.status.uiChipLabel(), waiting = false, error = true)
                Spacer(Modifier.height(12.dp))
                Text(task.title, color = MigoTokens.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("提醒时间", color = MigoTokens.Muted, fontSize = 13.sp)
                    Text(task.remindAt ?: "待定", color = MigoTokens.Ink, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                val hint = task.nextHint ?: buildString {
                    append("执行失败。若 retryable=true 可原地重试；否则请「核对后重新开始」。")
                    task.errorCode?.let { append("样例：$it") }
                }
                Text(hint, color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.retryable) {
                        PrimaryFillButton(label = "重试", enabled = !confirming, onClick = onRetry, modifier = Modifier.weight(1f))
                        SecondaryOutlineButton(label = "核对后重新开始", enabled = !confirming, onClick = onRestart, modifier = Modifier.weight(1f))
                    } else {
                        PrimaryFillButton(label = "核对后重新开始", enabled = !confirming, onClick = onRestart, modifier = Modifier.fillMaxWidth())
                    }
                }
                DetailLink(onClick = onViewDetail)
            }
        }
        AgentStatus.done -> {
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(MigoTokens.CardShape)
                    .background(MigoTokens.CardMint.copy(alpha = 0.55f))
                    .border(1.dp, MigoTokens.LineSolid, MigoTokens.CardShape)
                    .padding(16.dp),
            ) {
                TaskCardHeader(statusLabel = task.status.uiChipLabel(), waiting = false)
                Spacer(Modifier.height(12.dp))
                Text(task.title, color = MigoTokens.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("提醒时间", color = MigoTokens.Muted, fontSize = 13.sp)
                    Text(task.remindAt ?: "待定", color = MigoTokens.Ink, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(task.nextHint ?: "演示结果：提醒已创建。可在计划中查看。", color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(14.dp))
                PrimaryFillButton(label = "查看结果", enabled = true, onClick = onViewResult)
                DetailLink(onClick = onViewDetail)
            }
        }
        AgentStatus.recover -> {
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(MigoTokens.CardShape)
                    .background(MigoTokens.CardMint.copy(alpha = 0.55f))
                    .border(1.dp, MigoTokens.LineSolid, MigoTokens.CardShape)
                    .padding(16.dp),
            ) {
                TaskCardHeader(statusLabel = task.status.uiChipLabel(), waiting = false)
                Spacer(Modifier.height(12.dp))
                Text(task.title, color = MigoTokens.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("提醒时间", color = MigoTokens.Muted, fontSize = 13.sp)
                    Text(task.remindAt ?: "待定", color = MigoTokens.Ink, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    task.nextHint
                        ?: "后端暂不支持从中断点恢复（resumeSupported=false）。请核对行程后重新开始，不要点「继续执行」。",
                    color = MigoTokens.Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryFillButton(label = "核对后重新开始", enabled = !confirming, onClick = onRestart, modifier = Modifier.weight(1.2f))
                    SecondaryOutlineButton(label = "取消任务", enabled = !confirming, onClick = onCancel, modifier = Modifier.weight(1f))
                }
                DetailLink(onClick = onViewDetail)
            }
        }
        AgentStatus.idle -> Unit
    }
}

@Composable
private fun TaskCardHeader(statusLabel: String, waiting: Boolean, error: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(MigoIcons.Clock, contentDescription = null, tint = MigoTokens.Muted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text("提醒任务", color = MigoTokens.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        val fg = when {
            error -> MigoTokens.Error
            waiting -> MigoTokens.WaitingText
            else -> MigoTokens.Muted
        }
        val bg = when {
            error -> MigoTokens.Error.copy(alpha = 0.12f)
            waiting -> MigoTokens.Waiting.copy(alpha = 0.45f)
            else -> MigoTokens.AssistantBubble
        }
        Text(
            statusLabel,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(MigoTokens.PillShape)
                .background(bg)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DetailLink(onClick: () -> Unit, enabled: Boolean = true) {
    Spacer(Modifier.height(4.dp))
    Text(
        "查看任务详情 >",
        color = MigoTokens.Primary,
        fontSize = 13.sp,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = "查看任务详情" }
            .padding(vertical = 12.dp),
    )
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
                    .background(if (idx < current) MigoTokens.Primary else MigoTokens.AssistantBubble),
            )
        }
    }
}

@Composable
internal fun PrimaryFillButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MigoTokens.ButtonShape)
            .background(if (enabled || loading) MigoTokens.Primary else MigoTokens.Primary.copy(alpha = 0.45f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(label, color = MigoTokens.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun SecondaryOutlineButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(MigoTokens.ButtonShape)
            .border(1.dp, MigoTokens.FieldBorder, MigoTokens.ButtonShape)
            .background(MigoTokens.White)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MigoTokens.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
