package com.miguo.voice.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.R
import com.miguo.voice.support.PersonaPrefs
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 开发主稿 2026-10-30：右上角进球 → migomenu / personalize / switchaccount / loginother。
 * 整段新实现，替换旧 MigoPersonaPopup 薄菜单。
 */
enum class MigoPersonaSheet {
    Menu,
    Personalize,
    SwitchAccount,
    LoginOther,
}

@Composable
fun MigoPersonaFlow(
    sheet: MigoPersonaSheet,
    accountName: String,
    prefs: PersonaPrefs,
    onSheetChange: (MigoPersonaSheet?) -> Unit,
    @Suppress("UNUSED_PARAMETER") onRequestRealLogin: () -> Unit,
) {
    val ctx = LocalContext.current
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    BackHandler { onSheetChange(null) }

    when (sheet) {
        MigoPersonaSheet.Menu -> MigoMenuPopover(
            accountName = accountName,
            replyStyle = prefs.replyStyle,
            onDismiss = { onSheetChange(null) },
            onPersonalize = { onSheetChange(MigoPersonaSheet.Personalize) },
            onSwitchAccount = { onSheetChange(MigoPersonaSheet.SwitchAccount) },
        )
        MigoPersonaSheet.Personalize -> PersonalizeSheet(
            initialNickname = prefs.nickname,
            initialReplyStyle = prefs.replyStyle,
            onDismiss = { onSheetChange(null) },
            onBack = { onSheetChange(MigoPersonaSheet.Menu) },
            onSave = { nick, style ->
                prefs.nickname = nick
                prefs.replyStyle = style
                toast("偏好已保存到本机")
                onSheetChange(MigoPersonaSheet.Menu)
            },
        )
        MigoPersonaSheet.SwitchAccount -> SwitchAccountSheet(
            accountName = accountName,
            onDismiss = { onSheetChange(null) },
            onBack = { onSheetChange(MigoPersonaSheet.Menu) },
            onLoginOther = { onSheetChange(MigoPersonaSheet.LoginOther) },
        )
        MigoPersonaSheet.LoginOther -> LoginOtherSheet(
            onDismiss = { onSheetChange(null) },
            onBack = { onSheetChange(MigoPersonaSheet.SwitchAccount) },
            onPreviewNext = { account ->
                if (account.isBlank()) {
                    toast("请输入账号")
                } else {
                    // 主稿 previewlogin：不执行真实登录/切换；禁止假成功与 OTP。
                    toast("下一步需接入身份验证，当前未执行登录或切换。")
                }
            },
        )
    }
}

@Composable
private fun MigoMenuPopover(
    accountName: String,
    replyStyle: String,
    onDismiss: () -> Unit,
    onPersonalize: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x12273425))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .semantics { contentDescription = "关闭米果菜单" },
        )
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 108.dp, end = 17.dp, start = 54.dp)
                .fillMaxWidth()
                .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x24283923), spotColor = Color(0x24283923))
                .clip(RoundedCornerShape(20.dp))
                .background(MigoTokens.White)
                .border(1.dp, Color(0xFFE2E8DC), RoundedCornerShape(20.dp))
                .padding(14.dp)
                .semantics { contentDescription = "米果菜单" },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.migo),
                    contentDescription = null,
                    modifier = Modifier.size(width = 32.dp, height = 36.dp),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("你的米果", color = MigoTokens.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "$accountName · $replyStyle",
                        color = MigoTokens.Muted,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = "关闭米果菜单" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("×", color = MigoTokens.Muted, fontSize = 18.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
            Spacer(Modifier.height(6.dp))
            MigoMenuRow(
                icon = MigoIcons.Leaf,
                title = "个性化设置",
                subtitle = "称呼与回复偏好",
                onClick = onPersonalize,
            )
            // 策略A：隐藏「切换账号」
        }
    }
}

@Composable
private fun MigoMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp, horizontal = 4.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MigoTokens.Primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MigoTokens.Ink, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = MigoTokens.Muted, fontSize = 10.sp)
        }
        Icon(MigoIcons.ChevronRight, contentDescription = null, tint = Color(0xFF98A38E), modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun SheetScaffold(
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
private fun PersonalizeSheet(
    initialNickname: String,
    initialReplyStyle: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var nickname by remember { mutableStateOf(initialNickname) }
    var replyStyle by remember { mutableStateOf(initialReplyStyle) }
    SheetScaffold(title = "个性化设置", onDismiss = onDismiss) {
        Text("让米果更懂你的表达习惯。", color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 20.sp)
        FieldLabel("米果怎么称呼你")
        PrefTextField(
            value = nickname,
            onValueChange = { nickname = it.take(20) },
            placeholder = "输入你喜欢的称呼",
            contentDescription = "米果怎么称呼你",
        )
        FieldLabel("回复风格")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PersonaPrefs.REPLY_STYLES.forEach { style ->
                val selected = style == replyStyle
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, if (selected) MigoTokens.Primary else MigoTokens.LineSolid, RoundedCornerShape(10.dp))
                        .background(if (selected) MigoTokens.Pale else MigoTokens.White)
                        .clickable { replyStyle = style }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .semantics { contentDescription = "回复风格 $style" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(style, color = MigoTokens.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (selected) Text("✓", color = MigoTokens.Primary, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "偏好保存在本机。当前版本不会改变对话回复风格。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("保存偏好", Modifier.weight(1f)) { onSave(nickname.trim(), replyStyle) }
            SecondaryButton("返回") { onBack() }
        }
    }
}

@Composable
private fun SwitchAccountSheet(
    accountName: String,
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onLoginOther: () -> Unit,
) {
    SheetScaffold(title = "切换账号", onDismiss = onDismiss) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xFFEFF4E9))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("✓", color = MigoTokens.Primary, fontSize = 16.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(accountName, color = MigoTokens.Ink, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("正在使用", color = MigoTokens.Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onLoginOther)
                .padding(vertical = 14.dp)
                .semantics { contentDescription = "登录其他账号" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MigoIcons.Add, contentDescription = null, tint = MigoTokens.Primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("登录其他账号", color = MigoTokens.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(MigoIcons.ChevronRight, contentDescription = null, tint = Color(0xFF98A38E), modifier = Modifier.size(14.dp))
        }
        Text(
            "账号登录与身份验证待接入，当前不会切换真实账号。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(20.dp))
        SecondaryButton("返回", Modifier.fillMaxWidth()) { onBack() }
    }
}

@Composable
private fun LoginOtherSheet(
    onDismiss: () -> Unit,
    onBack: () -> Unit,
    onPreviewNext: (String) -> Unit,
) {
    var account by remember { mutableStateOf("") }
    SheetScaffold(title = "登录其他账号", onDismiss = onDismiss) {
        Text(
            "正式接入后在这里完成验证，再载入对应的会话和个人仓库。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
        FieldLabel("账号")
        PrefTextField(
            value = account,
            onValueChange = { account = it },
            placeholder = "手机号 / 邮箱 / 账号",
            contentDescription = "其他账号",
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("预览登录下一步", Modifier.weight(1f)) { onPreviewNext(account.trim()) }
            SecondaryButton("返回") { onBack() }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "预览不会切换真实账号，也不会发起验证码。",
            color = MigoTokens.Muted,
            fontSize = 11.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        color = MigoTokens.Muted,
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 7.dp),
    )
}

@Composable
private fun PrefTextField(
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
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = MigoTokens.Muted, fontSize = 13.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = MigoTokens.Ink, fontSize = 13.sp),
            cursorBrush = SolidColor(MigoTokens.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MigoTokens.Primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MigoTokens.White, fontSize = 12.sp)
    }
}

@Composable
private fun SecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFFDCE2D6), RoundedCornerShape(10.dp))
            .background(MigoTokens.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MigoTokens.Ink, fontSize = 12.sp)
    }
}
