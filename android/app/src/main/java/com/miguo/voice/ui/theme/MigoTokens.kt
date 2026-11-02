package com.miguo.voice.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 定稿视觉令牌（migo-ui-freeze-20261030 / 开发主稿 HTML :root）。
 * 旧 ZenPalette 仅遗留设置壳使用；主路径用本对象。
 */
object MigoTokens {
    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF272D29)
    val Muted = Color(0xFF727B73)
    val Primary = Color(0xFF526B50) // --sage
    val Pale = Color(0xFFEEF3E9)
    val UserBubble = Color(0xFFE8EFE3)
    val AssistantBubble = Color(0xFFF2F3F1)
    val CardMint = Color(0xFFE8EFE3)
    val Waiting = Color(0xFFE8C9A0)
    val WaitingText = Color(0xFF9A6B2F)
    val Amber = Color(0xFF8B6330)
    val Error = Color(0xFF9F392D)
    val Danger = Color(0xFFA95748)
    val Line = Color(0x1A272D29)
    val LineSolid = Color(0xFFE7EBE5)
    val FieldBorder = Color(0xFFE5E9E0)
    val FieldBg = Color(0xFFFAFBF8)
    val Scrim = Color(0x66000000)
    val EndCall = Color(0xFF9F5A4A)
    val TabPill = Color(0xFF272D29)

    /** 登录页奶油径向底（定稿 .auth-welcome） */
    val AuthCreamTop = Color(0xFFFFFDF8)
    val AuthCreamMid = Color(0xFFF7F2E8)
    val AuthCreamBottom = Color(0xFFEFE9DC)
    val AuthGlow = Color(0xFFDFEAD0)
    val AuthBubbleBg = Color(0xFFF6F1E6)
    val AuthBubbleText = Color(0xFF4C4636)
    val AuthChipBg = Color(0xD9FFFFFF)
    val AuthChipBorder = Color(0xFFE6E1D5)
    val AuthChipText = Color(0xFF4C5A49)
    val AuthBetaBg = Color(0xFFF6EFDF)
    val AuthBetaBorder = Color(0xFFEBDFC4)
    val AuthWarnBubble = Color(0xFFF7E8E4)

    val CardRadius = 17.dp
    val CardShape = RoundedCornerShape(17.dp)
    val SheetTopShape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val FieldShape = RoundedCornerShape(12.dp)
    val PillShape = RoundedCornerShape(999.dp)
    val ButtonShape = RoundedCornerShape(12.dp)
    val BubbleShape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)
}
