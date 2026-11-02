package com.miguo.voice.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.ui.theme.MigoTokens

/** internal：任务详情调试字段块。store 源集为空实现。 */
@Composable
fun FlavorTaskDebugBlock(task: Task) {
    val cp = task.checkpoint
    Column(
        Modifier
            .fillMaxWidth()
            .background(MigoTokens.AssistantBubble.copy(alpha = 0.55f), MigoTokens.CardShape)
            .padding(12.dp)
            .semantics { contentDescription = "状态字段追踪" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("调试状态（仅内验）", color = MigoTokens.Muted, fontSize = 11.sp)
        TrackLine("task_id", task.id)
        TrackLine("conversation_id", task.conversationId ?: "—")
        TrackLine("status", task.status.name)
        TrackLine("next_actions", task.nextActions.joinToString(",") { it }.ifBlank { "—" })
        TrackLine("confirm_token", task.confirmToken?.let { it.take(10) + "…" } ?: "（已消费/无）")
        TrackLine("content_revision", task.contentRevision.toString())
        TrackLine("waitingReason", task.waitingReason ?: "—")
        TrackLine("错误码", task.errorCode ?: "—")
        TrackLine("可否重试", if (task.retryable) "可以重试" else "需重新开始")
        TrackLine(
            "进度点",
            if (cp == null) "—" else "${cp.label.ifBlank { cp.stepId }}（第 ${cp.stepIndex} 步）",
        )
        TrackLine("下一步提示", task.nextHint ?: "—")
    }
}

@Composable
private fun TrackLine(k: String, v: String) {
    Text("$k = $v", color = MigoTokens.Ink, fontSize = 11.sp, lineHeight = 15.sp)
}
