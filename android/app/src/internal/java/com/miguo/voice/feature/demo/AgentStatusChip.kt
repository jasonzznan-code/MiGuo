package com.miguo.voice.feature.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.adapters.local.AgentStatus

private val ChipBg = Color(0xFFEFEAE0)
private val Ink = Color(0xFF302F2B)

fun agentStatusColor(status: AgentStatus): Color = when (status) {
    AgentStatus.idle -> Color(0xFF9AA396)
    AgentStatus.running -> Color(0xFF4A7EB5)
    AgentStatus.waiting -> Color(0xFFC4923A)
    AgentStatus.error -> Color(0xFF9F392D)
    AgentStatus.done -> Color(0xFF5E8A5A)
    AgentStatus.recover -> Color(0xFF7A5C8A)
}

/** 六态色块 + 文字（契约 §3.6；不必完整 Avatar） */
@Composable
fun AgentStatusChip(status: AgentStatus, modifier: Modifier = Modifier) {
    val c = agentStatusColor(status)
    Row(
        modifier
            .background(ChipBg, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(c, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${status.name} · ${status.labelZh}",
            color = Ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
