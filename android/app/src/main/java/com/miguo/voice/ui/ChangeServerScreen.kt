package com.miguo.voice.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.BuildConfig
import com.miguo.voice.protocol.ServerEndpoint
import com.miguo.voice.support.SettingsStore
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens
import kotlinx.coroutines.launch

/**
 * 定稿「使用其他服务器」页（鉴权地址）。
 * dual-base：本页只改 authAddress；对话 address 在设置里。
 */
@Composable
fun ChangeServerScreen(
    model: AppModel,
    onBack: () -> Unit,
) {
    val cur by model.settings.collectAsState()
    var server by remember { mutableStateOf(cur.authAddress) }
    var checking by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf<HealthUi>(HealthUi.Unknown) }
    var demoFail by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(MigoTokens.White)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MigoIcons.Back,
                contentDescription = "返回",
                tint = MigoTokens.Ink,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack)
                    .padding(8.dp)
                    .semantics { contentDescription = "返回登录" },
            )
            Text(
                "使用其他服务器",
                color = MigoTokens.Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (BuildConfig.IS_INTERNAL)
                "dual-base：鉴权/登录默认内网 AUTH；对话/语音走独立对话地址。本页更换的是鉴权地址（healthz + 发码/登录）。"
            else
                "本页更换的是登录鉴权地址。对话服务器请在设置中单独配置。正式包须使用公网 HTTPS。",
            color = MigoTokens.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MigoTokens.CardShape)
                .background(MigoTokens.Pale)
                .padding(12.dp),
        ) {
            Text(
                if (BuildConfig.IS_INTERNAL)
                    "双地址说明：鉴权与对话不可混用。禁止把聊天请求打到鉴权端口。"
                else
                    "登录地址与对话地址分离保存；勿把聊天请求发到登录主机。",
                color = MigoTokens.Ink,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("服务器地址", color = MigoTokens.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(MigoTokens.FieldShape)
                .background(MigoTokens.FieldBg)
                .border(1.dp, MigoTokens.FieldBorder, MigoTokens.FieldShape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = server,
                onValueChange = {
                    server = it
                    demoFail = false
                    health = HealthUi.Unknown
                },
                singleLine = true,
                enabled = !checking,
                textStyle = TextStyle(color = MigoTokens.Ink, fontSize = 14.sp),
                cursorBrush = SolidColor(MigoTokens.Primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "服务器地址" },
                decorationBox = { inner ->
                    if (server.isEmpty()) {
                        Text(
                            if (BuildConfig.IS_INTERNAL) ServerEndpoint.DEFAULT_AUTH_ADDRESS else "https://",
                            color = MigoTokens.Muted,
                            fontSize = 14.sp,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            checking || health == HealthUi.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MigoTokens.Primary)
                    Spacer(Modifier.size(8.dp))
                    Text("请求中…", color = MigoTokens.Muted, fontSize = 13.sp)
                }
            }
            demoFail || health == HealthUi.Fail -> {
                Text("不可达：healthz 失败或超时", color = MigoTokens.Danger, fontSize = 13.sp)
            }
            health == HealthUi.Ok -> {
                Text("健康检查通过（healthz 200）", color = MigoTokens.Primary, fontSize = 13.sp)
            }
            else -> {
                Text("尚未检查", color = MigoTokens.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .clip(MigoTokens.ButtonShape)
                    .border(1.dp, MigoTokens.FieldBorder, MigoTokens.ButtonShape)
                    .clickable(enabled = !checking) {
                        checking = true
                        demoFail = false
                        health = HealthUi.Checking
                        val draft = server
                        scope.launch {
                            val normalized = ServerEndpoint.normalizeInput(draft)
                            if (!BuildConfig.IS_INTERNAL && SettingsStore.looksLikePrivateOrNonHttpsAuth(normalized)) {
                                checking = false
                                health = HealthUi.Fail
                                demoFail = true
                                return@launch
                            }
                            // 先写入再检查，检查完若失败不强制回退（用户可改）
                            val warn = model.saveSettings(
                                model.settings.value.copy(authAddress = normalized),
                                apiKey = null,
                            )
                            val result = try {
                                model.testAuthConnection()
                            } catch (e: Exception) {
                                "✗ ${e.message}"
                            }
                            checking = false
                            health = if (result.contains("✗") || result.contains("失败") || result.contains("不可达", true)) {
                                HealthUi.Fail
                            } else {
                                HealthUi.Ok
                            }
                            if (warn != null && health == HealthUi.Ok) {
                                // 保存警告不挡通过
                            }
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("检查", color = MigoTokens.Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .clip(MigoTokens.ButtonShape)
                    .background(if (health == HealthUi.Ok) MigoTokens.Primary else MigoTokens.Primary.copy(alpha = 0.45f))
                    .clickable(enabled = !checking && health == HealthUi.Ok) {
                        // 定稿：未检查通过则拦截；store 另强制公网 HTTPS
                        val normalized = ServerEndpoint.normalizeInput(server)
                        if (!BuildConfig.IS_INTERNAL && SettingsStore.looksLikePrivateOrNonHttpsAuth(normalized)) {
                            health = HealthUi.Fail
                            return@clickable
                        }
                        model.saveSettings(
                            model.settings.value.copy(authAddress = normalized),
                            apiKey = null,
                        )
                        model.recheckAuth()
                        onBack()
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("使用这个服务器", color = MigoTokens.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        if (BuildConfig.IS_INTERNAL) {
            Spacer(Modifier.height(14.dp))
            Text(
                "演示：检查失败（不可达）",
                color = MigoTokens.Muted,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable {
                        demoFail = true
                        health = HealthUi.Fail
                    }
                    .padding(vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "当前对话服务器：${cur.address}",
            color = MigoTokens.Muted,
            fontSize = 11.sp,
        )
    }
}

private enum class HealthUi { Unknown, Checking, Ok, Fail }
