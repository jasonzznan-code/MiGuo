package com.miguo.voice.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.BuildConfig
import com.miguo.voice.ui.home.MigoAccountSheet
import com.miguo.voice.ui.home.MigoAccountSheetHost
import com.miguo.voice.ui.home.MigoAuxSheet
import com.miguo.voice.ui.home.MigoAuxSheetHost
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 主稿 settings 整页：合规入口 + 真实退出登录；内验可进旧「开发与服务器」高级页。
 * 不在此页发起登录 / OTP。
 */
@Composable
fun MigoSettingsScreen(
    model: AppModel,
    onDone: () -> Unit,
    onLogoutCurrent: () -> Unit = {},
    onLogoutEverywhere: () -> Unit = {},
) {
    var showDev by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<MigoAuxSheet.Pending?>(null) }
    var accountSheet by remember { mutableStateOf<MigoAccountSheet?>(null) }
    var showLegal by remember { mutableStateOf(false) }

    if (showLegal) {
        LegalHubScreen(model = model, onDone = { showLegal = false })
        return
    }

    if (showDev) {
        // 旧技术设置壳：仅内验入口，避免主路径继续露出 Zen 旧 UI
        SettingsScreen(model = model, onDone = { showDev = false })
        return
    }

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
                        .clickable(onClick = onDone)
                        .padding(8.dp),
                )
                Text(
                    "设置",
                    color = MigoTokens.Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { contentDescription = "设置" },
                )
            }
            Spacer(Modifier.height(8.dp))

            val rows = listOf(

                "隐私与账号" to "隐私政策 · 开源许可 · 注销",
            )
            rows.forEach { (title, sub) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            when (title) {
                                "消息通知" -> accountSheet = MigoAccountSheet.Notifications
                                "隐私与账号" -> showLegal = true
                                else -> pending = MigoAuxSheet.Pending(
                                    title = title,
                                    body = "$sub。该入口展示预期交互，当前未连接真实权限或外部服务。",
                                )
                            }
                        }
                        .semantics { contentDescription = title }
                        .padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = MigoTokens.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("$sub ›", color = MigoTokens.Muted, fontSize = 10.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
            }

            Spacer(Modifier.height(20.dp))
            Text("合规与关于", color = MigoTokens.Muted, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showLegal = true }
                    .semantics { contentDescription = "合规与关于" }
                    .padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("隐私、开源与注销", color = MigoTokens.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("GPL · 可点 ›", color = MigoTokens.Muted, fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
            Text(
                "隐私政策 · 开源许可 · 注销账号",
                color = Color(0xFF88775E),
                fontSize = 10.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (BuildConfig.IS_INTERNAL) {
                Spacer(Modifier.height(28.dp))
                Text("内验", color = MigoTokens.Muted, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MigoTokens.LineSolid, MigoTokens.ButtonShape)
                        .clickable { showDev = true }
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                        .semantics { contentDescription = "开发与服务器" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("开发与服务器", color = MigoTokens.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("高级 ›", color = MigoTokens.Muted, fontSize = 10.sp)
                }
                Text(
                    "含服务器地址 / API Key / 语音参数。正式主路径不展示。",
                    color = Color(0xFF88775E),
                    fontSize = 10.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text("账号", color = MigoTokens.Muted, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { accountSheet = MigoAccountSheet.LogoutLocal }
                    .semantics { contentDescription = "退出登录" }
                    .padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("退出登录", color = MigoTokens.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("本机", color = MigoTokens.Muted, fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { accountSheet = MigoAccountSheet.LogoutAll }
                    .semantics { contentDescription = "所有设备退出登录" }
                    .padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("所有设备退出登录", color = MigoTokens.Ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("全部", color = MigoTokens.Muted, fontSize = 10.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
            Text(
                "退出后需重新用邮箱或手机号验证码登录。",
                color = Color(0xFF88775E),
                fontSize = 10.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(28.dp))
        }

        pending?.let { p ->
            MigoAuxSheetHost(
                sheet = p,
                onDismiss = { pending = null },
                onConfirmCancel = {},
                onSaveEdit = { _, _, _ -> },
                onOpenPlans = {},
            )
        }
        accountSheet?.let { s ->
            MigoAccountSheetHost(
                sheet = s,
                demoAccountName = "",
                onDismiss = { accountSheet = null },
                onSaveDemoAccount = {},
                onOpenProfile = { /* shell 层可扩展；此处关闭即可 */ },
                onOpenSettings = { accountSheet = null },
                onConfirmLogoutLocal = {
                    accountSheet = null
                    onLogoutCurrent()
                },
                onConfirmLogoutAll = {
                    accountSheet = null
                    onLogoutEverywhere()
                },
            )
        }
    }
}
