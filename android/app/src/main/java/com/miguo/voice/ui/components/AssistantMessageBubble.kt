package com.miguo.voice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.conversation.AiGeneratedLabel
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 贴在对应 AI 回复旁的小号说明。不放进气泡底色里，避免被当成消息正文。
 */
@Composable
fun AiGeneratedCaption(
    modifier: Modifier = Modifier,
    color: Color = MigoTokens.Muted,
) {
    Text(
        text = AiGeneratedLabel.TEXT,
        color = color,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        modifier = modifier,
    )
}

/**
 * 助手正文气泡。标识由展示计划决定，且只画一次。
 * [showAiGeneratedLabel] 为 false 时用于「思考中…」等非模型正文。
 */
@Composable
fun AssistantMessageBubble(
    text: String,
    showAiGeneratedLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text,
            color = MigoTokens.Ink,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(MigoTokens.AssistantBubble, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (showAiGeneratedLabel) {
            AiGeneratedCaption(Modifier.padding(top = 4.dp, start = 4.dp))
        }
    }
}
