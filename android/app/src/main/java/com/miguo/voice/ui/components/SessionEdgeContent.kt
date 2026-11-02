package com.miguo.voice.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/** shots/21–23 会话边缘态：空态 / 加载骨架 / 错误重载。 */
enum class SessionEdgeKind { Empty, Loading, Failed }

@Composable
fun SessionEdgeContent(
    kind: SessionEdgeKind,
    onPrimary: () -> Unit,
    onSecondary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (kind) {
        SessionEdgeKind.Empty -> SessionEdgeEmpty(onChat = onPrimary, modifier = modifier)
        SessionEdgeKind.Loading -> SessionEdgeLoading(modifier = modifier)
        SessionEdgeKind.Failed -> SessionEdgeFailed(
            onReload = onPrimary,
            onBack = onSecondary ?: onPrimary,
            modifier = modifier,
        )
    }
}

@Composable
fun SessionEdgeEmpty(
    onChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .semantics { contentDescription = "会话空态" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            MigoIcons.Plan,
            contentDescription = null,
            tint = MigoTokens.Primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "这里还没有任务",
            color = MigoTokens.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "告诉米果一件想做的事，我们从第一步开始。",
            color = MigoTokens.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MigoTokens.Primary)
                .clickable(onClick = onChat)
                .semantics { contentDescription = "去聊一聊" }
                .padding(horizontal = 28.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("去聊一聊", color = MigoTokens.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun SessionEdgeLoading(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 28.dp, start = 8.dp, end = 8.dp)
            .semantics { contentDescription = "会话加载中" },
    ) {
        Text(
            "正在加载，请稍候…",
            color = MigoTokens.Muted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))
        listOf(1f, 0.72f, 0.48f).forEach { frac ->
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MigoTokens.AssistantBubble),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun SessionEdgeFailed(
    onReload: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp)
            .semantics { contentDescription = "会话加载失败" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            MigoIcons.Refresh,
            contentDescription = null,
            tint = MigoTokens.Primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "暂时没能加载出来",
            color = MigoTokens.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "内容会保留，你可以重试，或先回到会话。",
            color = MigoTokens.Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.widthIn(max = 300.dp),
        )
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .sizeIn(minHeight = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MigoTokens.Primary)
                .clickable(onClick = onReload)
                .semantics { contentDescription = "重新加载" }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("重新加载", color = MigoTokens.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 320.dp)
                .sizeIn(minHeight = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MigoTokens.Line, RoundedCornerShape(14.dp))
                .background(MigoTokens.White)
                .clickable(onClick = onBack)
                .semantics { contentDescription = "回到会话" }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("回到会话", color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
