package com.miguo.voice.ui.home

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 开发主稿 2026-10-30 账号类 sheet：
 * accountedit / passwordedit / logoutlocal / logoutall / deleteaccount /
 * vaultdetail / notifications / profilemore。
 * 退出 / 注销确认由调用方接线真实 logout / 注销流程；勿再写「仅演示不会真退」。
 */
sealed class MigoAccountSheet {
    data object AccountEdit : MigoAccountSheet()
    data object PasswordEdit : MigoAccountSheet()
    data object LogoutLocal : MigoAccountSheet()
    data object LogoutAll : MigoAccountSheet()
    data object DeleteAccount : MigoAccountSheet()
    data class VaultDetail(val name: String, val kind: String, val meta: String) : MigoAccountSheet()
    data object Notifications : MigoAccountSheet()
    data object ProfileMore : MigoAccountSheet()
}

@Composable
fun MigoAccountSheetHost(
    sheet: MigoAccountSheet,
    demoAccountName: String,
    onDismiss: () -> Unit,
    onSaveDemoAccount: (String) -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onConfirmLogoutLocal: () -> Unit = {},
    onConfirmLogoutAll: () -> Unit = {},
    onConfirmDeleteAccount: () -> Unit = {},
) {
    val ctx = LocalContext.current
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    BackHandler(onBack = onDismiss)
    when (sheet) {
        MigoAccountSheet.AccountEdit -> AccountEditSheet(
            initial = demoAccountName,
            onDismiss = onDismiss,
            onSave = { name ->
                if (name.isBlank()) toast("请输入演示账号名称")
                else {
                    onSaveDemoAccount(name)
                    toast("演示账号名称已保存（仅本地预览）")
                    onDismiss()
                }
            },
        )
        MigoAccountSheet.PasswordEdit -> PasswordEditSheet(
            onDismiss = onDismiss,
            onValidate = { a, b ->
                when {
                    a.length < 8 || a != b -> toast("密码至少 8 位且两次一致（演示校验，不保存）")
                    else -> {
                        toast("演示校验通过。正式接入需身份验证，当前未保存密码。")
                        onDismiss()
                    }
                }
            },
        )
        MigoAccountSheet.LogoutLocal -> AccountConfirmSheet(
            kind = sheet,
            onDismiss = onDismiss,
            onConfirm = onConfirmLogoutLocal,
        )
        MigoAccountSheet.LogoutAll -> AccountConfirmSheet(
            kind = sheet,
            onDismiss = onDismiss,
            onConfirm = onConfirmLogoutAll,
        )
        MigoAccountSheet.DeleteAccount -> AccountConfirmSheet(
            kind = sheet,
            onDismiss = onDismiss,
            onConfirm = onConfirmDeleteAccount,
        )
        is MigoAccountSheet.VaultDetail -> VaultDetailSheet(
            name = sheet.name,
            kind = sheet.kind,
            meta = sheet.meta,
            onDismiss = onDismiss,
        )
        MigoAccountSheet.Notifications -> NotificationsSheet(onDismiss = onDismiss)
        MigoAccountSheet.ProfileMore -> ProfileMoreSheet(
            onDismiss = onDismiss,
            onOpenProfile = { onDismiss(); onOpenProfile() },
            onOpenSettings = { onDismiss(); onOpenSettings() },
        )
    }
}

@Composable
private fun AccountEditSheet(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AccountSheetScaffold(title = "设置登录账号", onDismiss = onDismiss) {
        Text(
            "仅用于演示账号字段，不创建真实登录账号。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        AccountFieldLabel("账号名称")
        AccountTextField(
            value = value,
            onValueChange = { value = it.take(40) },
            placeholder = "输入演示账号名称",
            contentDescription = "演示账号名称",
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountPrimaryButton("保存演示名称", Modifier.weight(1f)) { onSave(value.trim()) }
            AccountSecondaryButton("取消", onClick = onDismiss)
        }
    }
}

@Composable
private fun PasswordEditSheet(
    onDismiss: () -> Unit,
    onValidate: (String, String) -> Unit,
) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AccountSheetScaffold(title = "设置 / 修改密码", onDismiss = onDismiss) {
        Text(
            "仅演示输入校验，不保存或发送密码。正式接入需身份验证。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        AccountFieldLabel("新密码")
        AccountTextField(
            value = a,
            onValueChange = { a = it.take(64) },
            placeholder = "至少 8 位",
            contentDescription = "新密码",
            password = true,
        )
        AccountFieldLabel("确认新密码")
        AccountTextField(
            value = b,
            onValueChange = { b = it.take(64) },
            placeholder = "再次输入",
            contentDescription = "确认新密码",
            password = true,
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountPrimaryButton("校验演示", Modifier.weight(1f)) { onValidate(a, b) }
            AccountSecondaryButton("取消", onClick = onDismiss)
        }
    }
}

@Composable
private fun AccountConfirmSheet(
    kind: MigoAccountSheet,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (kind) {
        MigoAccountSheet.LogoutLocal -> "退出登录？"
        MigoAccountSheet.LogoutAll -> "退出所有设备？"
        MigoAccountSheet.DeleteAccount -> "注销账号？"
        else -> "确认"
    }
    val body = when (kind) {
        MigoAccountSheet.DeleteAccount ->
            "注销后账号与相关数据将删除且无法恢复。请确认后继续。"
        MigoAccountSheet.LogoutAll ->
            "将退出全部已登录设备，之后需要重新验证码登录。"
        else ->
            "退出后需要重新用邮箱或手机号验证码登录。"
    }
    AccountSheetScaffold(title = title, onDismiss = onDismiss) {
        Text(body, color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccountPrimaryButton(if (kind == MigoAccountSheet.DeleteAccount) "注销账号" else "退出登录", Modifier.weight(1f), onClick = onConfirm)
            AccountSecondaryButton("取消", onClick = onDismiss)
        }
    }
}

@Composable
private fun VaultDetailSheet(
    name: String,
    kind: String,
    meta: String,
    onDismiss: () -> Unit,
) {
    AccountSheetScaffold(title = "仓库内容", onDismiss = onDismiss) {
        Text(name.ifBlank { "未命名" }, color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            "${kind.ifBlank { "文件" }} · ${meta.ifBlank { "摘要待接入" }}",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "此演示只收录名称或收藏摘要。文件读取、预览与安全存储待接入。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        AccountSecondaryButton("返回仓库", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}

@Composable
private fun NotificationsSheet(onDismiss: () -> Unit) {
    AccountSheetScaffold(title = "消息通知", onDismiss = onDismiss) {
        Text(
            "暂无新消息。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(20.dp))
        AccountSecondaryButton("知道了", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}

@Composable
private fun ProfileMoreSheet(
    onDismiss: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AccountSheetScaffold(title = "更多", onDismiss = onDismiss) {
        AccountNavRow("个人主页 →", onClick = onOpenProfile)
        AccountNavRow("账号与设置 →", onClick = onOpenSettings)
        Spacer(Modifier.height(12.dp))
        AccountSecondaryButton("知道了", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}

@Composable
private fun AccountNavRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MigoTokens.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(MigoIcons.ChevronRight, contentDescription = null, tint = Color(0xFF98A38E), modifier = Modifier.size(14.dp))
    }
}

@Composable
fun AccountSheetScaffold(
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
private fun AccountFieldLabel(text: String) {
    Text(text, color = MigoTokens.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp, bottom = 7.dp))
}

@Composable
private fun AccountTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    contentDescription: String,
    password: Boolean = false,
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
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.contentDescription = contentDescription },
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = MigoTokens.Muted, fontSize = 13.sp)
                inner()
            },
        )
    }
}

@Composable
fun AccountPrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
fun AccountSecondaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
