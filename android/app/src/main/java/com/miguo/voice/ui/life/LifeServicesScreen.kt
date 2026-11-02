package com.miguo.voice.ui.life

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.R
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 主稿 services：生活服务落地页（底栏第三项）。
 * lead 插画半透明右下角；六宫格 art 色板按主稿 nth-child；仅「提醒与安排」可进 lifedetail。
 */
@Composable
fun LifeServicesScreen(
    onOpenLifeDetail: () -> Unit,
    onBackToChat: () -> Unit,
    onShowNotice: (String) -> Unit,
) {
    val tiles = listOf(
        LifeServiceTile(
            title = "提醒与安排",
            subtitle = "即将开放",
            icon = MigoIcons.Plan,
            enabled = false,
            artBg = Color(0xFFF0F4E9),
            artTint = MigoTokens.Primary,
        ),
        LifeServiceTile(
            title = "外卖餐饮",
            subtitle = "即将开放",
            icon = MigoIcons.Food,
            enabled = false,
            artBg = Color(0xFFF4EEE3),
            artTint = Color(0xFF927447),
        ),
        LifeServiceTile(
            title = "工作协作",
            subtitle = "即将开放",
            icon = MigoIcons.File,
            enabled = false,
            artBg = Color(0xFFECECF4),
            artTint = Color(0xFF7A7797),
        ),
        LifeServiceTile(
            title = "学习辅助",
            subtitle = "即将开放",
            icon = MigoIcons.Vault,
            enabled = false,
            artBg = Color(0xFFF2EEE6),
            artTint = Color(0xFF948363),
        ),
        LifeServiceTile(
            title = "生活办理",
            subtitle = "即将开放",
            icon = MigoIcons.Leaf,
            enabled = false,
            artBg = Color(0xFFEEF2E7),
            artTint = Color(0xFF7F9168),
        ),
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(MigoTokens.White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                MigoIcons.Back,
                contentDescription = "返回",
                tint = MigoTokens.Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBackToChat)
                    .padding(8.dp),
            )
            Text(
                "生活服务",
                color = MigoTokens.Ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { contentDescription = "生活服务" },
            )
            Icon(
                MigoIcons.Menu,
                contentDescription = "打开侧边栏",
                tint = MigoTokens.Ink,
                modifier = Modifier.size(40.dp).padding(8.dp),
            )
        }

        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MigoTokens.CardShape)
                .background(Color(0xFFEAF0E2))
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            Column(Modifier.fillMaxWidth(0.72f)) {
                Text(
                    "生活里的小事，",
                    color = MigoTokens.Ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 30.sp,
                )
                Text(
                    "一起安排好。",
                    color = MigoTokens.Ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 30.sp,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "从一个需求开始",
                    color = Color(0xFF65745B),
                    fontSize = 11.sp,
                    lineHeight = 19.sp,
                )
                Text(
                    "更多服务正在准备中",
                    color = Color(0xFF65745B),
                    fontSize = 11.sp,
                    lineHeight = 19.sp,
                )
            }
            Image(
                painter = painterResource(R.drawable.migo),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 0.dp, bottom = 0.dp)
                    .size(width = 98.dp, height = 108.dp)
                    .rotate(-10f)
                    .alpha(0.17f),
                contentScale = ContentScale.Fit,
            )
        }

        Spacer(Modifier.height(14.dp))
        tiles.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { tile ->
                    ServiceTileCard(
                        tile = tile,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (tile.enabled) onOpenLifeDetail()
                            else onShowNotice("${tile.title}：即将开放，敬请期待")
                        },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(28.dp))
    }
}

private data class LifeServiceTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val enabled: Boolean,
    val artBg: Color,
    val artTint: Color,
)

@Composable
private fun ServiceTileCard(
    tile: LifeServiceTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .heightIn(min = 132.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
            .border(1.dp, MigoTokens.LineSolid, androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
            .background(if (tile.enabled) MigoTokens.White else Color(0xFFF6F7F3))
            .alpha(if (tile.enabled) 1f else 0.92f)
            .clickable(onClick = onClick)
            .semantics { contentDescription = tile.title },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .background(tile.artBg),
            contentAlignment = Alignment.Center,
        ) {
            // decorative soft card behind icon (主稿 .serviceart:before)
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 18.dp)
                    .size(width = 52.dp, height = 42.dp)
                    .rotate(12f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(Color(0x88FFFFFF)),
            )
            Icon(
                tile.icon,
                contentDescription = null,
                tint = tile.artTint,
                modifier = Modifier
                    .size(28.dp)
                    .rotate(-7f),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
                    .size(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(tile.artTint.copy(alpha = 0.25f)),
            )
        }
        Text(
            tile.title,
            color = MigoTokens.Ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
        )
        Text(
            tile.subtitle,
            color = if (tile.enabled) MigoTokens.Muted else Color(0xFF8A7562),
            fontSize = 9.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        )
    }
}
