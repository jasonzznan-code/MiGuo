package com.miguo.voice.ui.device

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.conversation.AuthState
import com.miguo.voice.support.PersonaPrefs
import com.miguo.voice.ui.AppModel
import com.miguo.voice.ui.home.MigoAccountSheet
import com.miguo.voice.ui.home.MigoAccountSheetHost
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 主稿 devices：设备与账号。整页按主稿替换旧表单壳；
 * 退出登录接真实 logout；注销跳转注销页。
 */
@Composable
fun DeviceScreen(
    model: AppModel,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
    onLogoutCurrent: () -> Unit = {},
    onLogoutEverywhere: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val prefs = remember { PersonaPrefs(ctx) }
    val authState by model.auth.state.collectAsState()
    val settings by model.settings.collectAsState()
    var demoName by remember {
        mutableStateOf(
            prefs.demoAccountName.ifBlank {
                when (val st = authState) {
                    is AuthState.LoggedIn -> st.session.displayName.ifBlank { st.session.username }
                    is AuthState.Refreshing -> st.session.displayName.ifBlank { st.session.username }
                    else -> settings.userId.ifBlank { "Migo 用户" }
                }
            },
        )
    }
    var sheet by remember { mutableStateOf<MigoAccountSheet?>(null) }
    val deviceLabel = Build.MODEL?.takeIf { it.isNotBlank() } ?: "当前手机"

    Box(Modifier.fillMaxSize().background(MigoTokens.White)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
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
                    "设备与账号",
                    color = MigoTokens.Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { contentDescription = "设备与账号" },
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFEDF2E7))
                    .padding(17.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFD9E4CF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("我", color = MigoTokens.Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(demoName.ifBlank { "Migo 用户" }, color = MigoTokens.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(5.dp))
                    Text("账号与设备", color = Color(0xFF78866C), fontSize = 10.sp)
                }
                Icon(MigoIcons.Device, contentDescription = null, tint = Color(0xFF859876), modifier = Modifier.size(20.dp))
            }

            SectionLabel("登录信息")
            SettingRow(
                title = "当前账号",
                trailing = demoName.ifBlank { "未设置" },
                onClick = {},
            )

            SectionLabel("账号操作")
            AccountAction("退出登录") { sheet = MigoAccountSheet.LogoutLocal }
            AccountAction("所有设备退出登录") { sheet = MigoAccountSheet.LogoutAll }
            AccountAction("注销账号", danger = true) { sheet = MigoAccountSheet.DeleteAccount }
            Text(
                "退出登录后需重新获取验证码。注销请确认后果。",
                color = Color(0xFF88775E),
                fontSize = 10.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            Spacer(Modifier.height(24.dp))

        }

        sheet?.let { s ->
            MigoAccountSheetHost(
                sheet = s,
                demoAccountName = demoName,
                onDismiss = { sheet = null },
                onSaveDemoAccount = {
                    demoName = it
                    prefs.demoAccountName = it
                },
                onConfirmLogoutLocal = {
                    sheet = null
                    onLogoutCurrent()
                },
                onConfirmLogoutAll = {
                    sheet = null
                    onLogoutEverywhere()
                },
                onConfirmDeleteAccount = {
                    sheet = null
                    onNotice("OPEN_ACCOUNT_DELETION")
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = MigoTokens.Muted,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    trailing: String? = null,
    trailingIcon: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
            .padding(vertical = 18.dp)
            .border(width = 0.dp, color = Color.Transparent, shape = RoundedCornerShape(0)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = MigoTokens.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, color = MigoTokens.Muted, fontSize = 10.sp)
        } else if (trailingIcon) {
            Icon(MigoIcons.ChevronRight, contentDescription = null, tint = Color(0xFF98A38E), modifier = Modifier.size(14.dp))
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
}

@Composable
private fun DeviceRow(title: String, meta: String, badge: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MigoIcons.Device, contentDescription = null, tint = Color(0xFF839773), modifier = Modifier.size(25.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = MigoTokens.Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(meta, color = Color(0xFF87917E), fontSize = 10.sp)
        }
        if (badge != null) {
            Text(
                badge,
                color = MigoTokens.Primary,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MigoTokens.Pale)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
}

@Composable
private fun AccountAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (danger) MigoTokens.Danger else MigoTokens.Ink,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(MigoIcons.ChevronRight, contentDescription = null, tint = Color(0xFF98A38E), modifier = Modifier.size(15.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
}
