package com.miguo.voice.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.ui.components.uiChipLabel
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 开发主稿 2026-10-30：任务/会话辅助 sheet（cancel / edit / result / voicetask / voiceinput / video）。
 * 整段新实现，替换原先 toast 占位。
 */
sealed class MigoAuxSheet {
    data class Cancel(val task: Task) : MigoAuxSheet()
    data class Edit(val task: Task) : MigoAuxSheet()
    data class Result(val task: Task) : MigoAuxSheet()
    data class VoiceTask(val task: Task) : MigoAuxSheet()
    data object VoiceInput : MigoAuxSheet()
    data object Video : MigoAuxSheet()
    data class Pending(val title: String, val body: String) : MigoAuxSheet()
}

@Composable
fun MigoAuxSheetHost(
    sheet: MigoAuxSheet,
    onDismiss: () -> Unit,
    onConfirmCancel: (Task) -> Unit,
    onSaveEdit: (Task, String, String) -> Unit,
    onOpenPlans: () -> Unit,
    onInsertVoiceDemo: (String) -> Unit = {},
) {
    BackHandler(onBack = onDismiss)
    when (sheet) {
        is MigoAuxSheet.Cancel -> CancelTaskSheet(
            onDismiss = onDismiss,
            onConfirm = { onConfirmCancel(sheet.task) },
        )
        is MigoAuxSheet.Edit -> EditTaskSheet(
            task = sheet.task,
            onDismiss = onDismiss,
            onSave = { title, time -> onSaveEdit(sheet.task, title, time) },
        )
        is MigoAuxSheet.Result -> ResultTaskSheet(
            task = sheet.task,
            onDismiss = onDismiss,
            onOpenPlans = {
                onDismiss()
                onOpenPlans()
            },
        )
        is MigoAuxSheet.VoiceTask -> VoiceTaskSheet(
            task = sheet.task,
            onDismiss = onDismiss,
        )
        MigoAuxSheet.VoiceInput -> VoiceInputSheet(
            onDismiss = onDismiss,
            onInsertDemo = {
                onInsertVoiceDemo("（示例语音文字）周六早上九点，提醒我准备出行。")
                onDismiss()
            },
        )
        MigoAuxSheet.Video -> VideoCallSheet(onDismiss = onDismiss)
        is MigoAuxSheet.Pending -> PendingAbilitySheet(
            title = sheet.title,
            body = sheet.body,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun CancelTaskSheet(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AuxSheetScaffold(title = "取消这项任务？", onDismiss = onDismiss) {
        Text(
            "取消后的演示记录仍保留在计划中。真实任务能否停止、哪些步骤已经生效，需要后台确认。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuxPrimaryButton("确认取消", Modifier.weight(1f), onClick = onConfirm)
            AuxSecondaryButton("继续保留", onClick = onDismiss)
        }
    }
}

@Composable
private fun EditTaskSheet(
    task: Task,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var title by remember(task.id) { mutableStateOf(task.title) }
    var time by remember(task.id) {
        mutableStateOf(task.remindAt?.replace(' ', 'T') ?: "2026-10-31T09:00")
    }
    AuxSheetScaffold(title = "修改提醒", onDismiss = onDismiss) {
        AuxFieldLabel("提醒内容")
        AuxTextField(
            value = title,
            onValueChange = { title = it.take(40) },
            placeholder = "提醒内容",
            contentDescription = "提醒内容",
        )
        AuxFieldLabel("提醒时间 · 本地时区")
        AuxTextField(
            value = time,
            onValueChange = { time = it.take(32) },
            placeholder = "2026-10-31T09:00",
            contentDescription = "提醒时间",
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuxPrimaryButton("保存并重新确认", Modifier.weight(1f)) {
                onSave(title.trim(), time.trim())
            }
            AuxSecondaryButton("返回", onClick = onDismiss)
        }
    }
}

@Composable
private fun ResultTaskSheet(
    task: Task,
    onDismiss: () -> Unit,
    onOpenPlans: () -> Unit,
) {
    val time = (task.remindAt ?: task.updatedAt).replace('T', ' ')
    AuxSheetScaffold(title = "提醒结果 · 演示", onDismiss = onDismiss) {
        Text(
            "内容：${task.title}\n时间：$time\n来源：当前会话\n任务编号：${task.id.take(12)}",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "这不是实际提醒。真实成功状态必须以工具回执与结果标识为准。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuxPrimaryButton("查看计划", Modifier.weight(1f), onClick = onOpenPlans)
            AuxSecondaryButton("关闭", onClick = onDismiss)
        }
    }
}

@Composable
private fun VoiceTaskSheet(task: Task, onDismiss: () -> Unit) {
    AuxSheetScaffold(title = "任务进展 · ${task.status.uiChipLabel()}", onDismiss = onDismiss) {
        Text(
            "请核对这次操作的具体内容。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(17.dp))
                .background(MigoTokens.CardMint)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MigoIcons.Clock, contentDescription = null, tint = MigoTokens.Primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("提醒任务 · 演示", color = MigoTokens.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(task.status.uiChipLabel(), color = MigoTokens.WaitingText, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(task.title, color = MigoTokens.Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                "提醒时间  ${(task.remindAt ?: "—").replace('T', ' ')}",
                color = MigoTokens.Muted,
                fontSize = 12.sp,
            )
            if (task.summary.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(task.summary, color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "任务状态与麦克风开关互相独立。当前为模拟交互。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        AuxSecondaryButton("回到通话", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}

@Composable
private fun VoiceInputSheet(onDismiss: () -> Unit, onInsertDemo: () -> Unit) {
    AuxSheetScaffold(title = "录一条语音消息", onDismiss = onDismiss) {
        Text(
            "这里用于录制并发送一条语音消息。持续通话请从 + 内选择“打电话”。当前原型没有访问麦克风。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuxPrimaryButton("插入示例语音文字", Modifier.weight(1f), onClick = onInsertDemo)
            AuxSecondaryButton("取消", onClick = onDismiss)
        }
    }
}

@Composable
private fun VideoCallSheet(onDismiss: () -> Unit) {
    AuxSheetScaffold(title = "视频通话", onDismiss = onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFEFF4E9))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MigoIcons.Video, contentDescription = null, tint = MigoTokens.Primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("让米果看见你想分享的事", color = MigoTokens.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("视频能力待接入", color = MigoTokens.Muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "连接视频能力后，可在这里开启摄像头，把眼前的物品或场景分享给米果。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "当前原型没有访问摄像头或开始视频通话。正式接入前还需确认摄像头授权、切换镜头、关闭视频及识别能力。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        AuxPrimaryButton("继续语音通话", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}

@Composable
private fun PendingAbilitySheet(title: String, body: String, onDismiss: () -> Unit) {
    AuxSheetScaffold(title = title, onDismiss = onDismiss) {
        Text(body, color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(20.dp))
        AuxSecondaryButton("知道了", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}

@Composable
private fun AuxSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x40293226))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(topStart = 23.dp, topEnd = 23.dp))
                .background(MigoTokens.White)
                .navigationBarsPadding()
                .padding(start = 23.dp, end = 23.dp, top = 23.dp, bottom = 35.dp)
                .verticalScroll(rememberScrollState())
                .semantics { contentDescription = title },
        ) {
            Text(title, color = MigoTokens.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun AuxFieldLabel(text: String) {
    Text(
        text,
        color = MigoTokens.Muted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 7.dp),
    )
}

@Composable
private fun AuxTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    contentDescription: String,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MigoTokens.LineSolid, RoundedCornerShape(10.dp))
            .background(MigoTokens.White)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MigoTokens.Ink, fontSize = 13.sp),
            cursorBrush = SolidColor(MigoTokens.Primary),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.contentDescription = contentDescription },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = MigoTokens.Muted, fontSize = 13.sp)
                }
                inner()
            },
        )
    }
}

@Composable
private fun AuxPrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MigoTokens.Primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MigoTokens.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AuxSecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MigoTokens.LineSolid, RoundedCornerShape(12.dp))
            .background(MigoTokens.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MigoTokens.Ink, fontSize = 13.sp)
    }
}
