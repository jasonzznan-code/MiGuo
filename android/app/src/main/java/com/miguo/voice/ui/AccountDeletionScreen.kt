package com.miguo.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.conversation.AuthState
import com.miguo.voice.protocol.AuthSession
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 注销账号：说明后果 → 确认语 → 已登录调用注销接口；未登录记录本机申请并给出联系渠道。
 */
@Composable
fun AccountDeletionScreen(model: AppModel, onDone: () -> Unit) {
    val auth by model.auth.state.collectAsState()
    val loggedIn = auth is AuthState.LoggedIn
    val session = (auth as? AuthState.LoggedIn)?.session
    val nano = AuthSession.isNanoApiKey(session?.accessToken)
    var step by remember { mutableStateOf(0) } // 0 explain, 1 confirm, 2 done/msg
    var phrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var doneMsg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MigoTokens.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        ZenTopBar(title = "注销账号", onDone = onDone)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                doneMsg != null -> {
                    Text(doneMsg!!, color = MigoTokens.Ink, fontSize = 14.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(8.dp))
                    ZenOutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("完成")
                    }
                }
                step == 0 -> {
                    Text("注销后果", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
                    Text(
                        "· 将删除或停用当前登录账号的访问能力，此操作通常不可恢复。\n" +
                            "· 本机登录态会被清除；已登录设备可能需要重新验证。\n" +
                            "· 服务端对话/记忆的保留或删除范围以服务端策略为准；客户端会调用鉴权侧注销接口（若已登录）。\n" +
                            "· 未登录时，可先在本机提交「注销申请」记录，并通过联系渠道 " +
                            MiguoLegal.CONTACT_EMAIL + " 跟进（不伪造已完成注销）。",
                        color = MigoTokens.Ink, fontSize = 13.sp, lineHeight = 20.sp,
                    )
                    Text(
                        if (loggedIn) "当前状态：已登录，确认后将注销你的账号。"
                        else "当前状态：未登录，确认后仅记录本机注销申请。",
                        color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                    ZenOutlinedButton(
                        onClick = { step = 1; err = null },
                        modifier = Modifier.fillMaxWidth(),
                        danger = true,
                    ) { Text("我已了解后果，继续") }
                    ZenOutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("取消")
                    }
                }
                else -> {
                    Text("确认注销", fontWeight = FontWeight.SemiBold, color = MigoTokens.Ink, fontSize = 15.sp)
                    Text(
                        "请输入确认语「注销账号」四字。",
                        color = MigoTokens.Ink, fontSize = 13.sp, lineHeight = 20.sp,
                    )
                    ZenField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        label = "确认语：注销账号",
                    )
                    if (loggedIn && !nano) {
                        ZenField(
                            value = password,
                            onValueChange = { password = it },
                            label = "当前密码",
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    } else if (loggedIn && nano) {
                        Text(
                            "确认后，你的账号和相关数据将被删除，且无法恢复。",
                            color = MigoTokens.Muted, fontSize = 12.sp, lineHeight = 18.sp,
                        )
                    }
                    err?.let { Text(it, color = MigoTokens.Error, fontSize = 13.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZenOutlinedButton(
                            enabled = !busy,
                            onClick = {
                                if (phrase.trim() != "注销账号") {
                                    err = "确认语须为「注销账号」四字"
                                    return@ZenOutlinedButton
                                }
                                if (loggedIn && !nano && password.isEmpty()) {
                                    err = "请输入当前密码"
                                    return@ZenOutlinedButton
                                }
                                busy = true
                                err = null
                                if (loggedIn) {
                                    model.deleteAccount(if (nano) "otp" else password) { e ->
                                        busy = false
                                        if (e != null) err = e
                                        else doneMsg = "账号已注销，本地会话已清除。"
                                    }
                                } else {
                                    val ts = model.recordDeletionRequest()
                                    busy = false
                                    doneMsg = "已在本机记录注销申请（" +
                                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
                                            .format(java.util.Date(ts)) +
                                        "）。请通过 " + MiguoLegal.CONTACT_EMAIL +
                                        " 联系运营主体跟进。此状态不是服务端已注销证明。"
                                }
                            },
                            modifier = Modifier.weight(1f),
                            danger = true,
                        ) { Text(if (busy) "提交中…" else if (loggedIn) "确认注销" else "提交注销申请") }
                        ZenOutlinedButton(
                            enabled = !busy,
                            onClick = { step = 0 },
                            modifier = Modifier.weight(1f),
                        ) { Text("返回") }
                    }
                }
            }
        }
    }
}
