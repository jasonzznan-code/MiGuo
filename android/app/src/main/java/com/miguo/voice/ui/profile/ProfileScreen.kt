package com.miguo.voice.ui.profile

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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

/**
 * 主稿 profile 空态：账号 / 个人资料 / 偏好字段待确认。
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onBackHome: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MigoTokens.White)
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                MigoIcons.Back,
                contentDescription = "返回",
                tint = MigoTokens.Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack)
                    .padding(8.dp),
            )
            Text(
                "个人主页",
                color = MigoTokens.Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = "个人主页" },
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(MigoIcons.Leaf, contentDescription = null, tint = MigoTokens.Primary, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                "你好，欢迎来到 Migo",
                color = MigoTokens.Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "个人资料功能即将开放。",
                color = MigoTokens.Muted,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MigoTokens.LineSolid, RoundedCornerShape(12.dp))
                    .background(MigoTokens.White)
                    .clickable(onClick = onBackHome)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .semantics { contentDescription = "回到会话" },
                contentAlignment = Alignment.Center,
            ) {
                Text("回到会话", color = MigoTokens.Ink, fontSize = 13.sp)
            }
        }
    }
}
