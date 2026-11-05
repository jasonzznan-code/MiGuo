package com.miguo.voice.ui

import com.miguo.voice.BuildConfig

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.conversation.TtsProsody
import com.miguo.voice.conversation.VADPreset
import com.miguo.voice.protocol.ServerEndpoint
import kotlinx.coroutines.launch

/**
 * 旧技术设置壳（对应 iOS SettingsView.swift）。
 * 主路径禁止直接导航至此；仅 MigoSettingsScreen 内验入口可达。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Deprecated("主路径用 MigoSettingsScreen；本页仅内验「开发与服务器」高级入口")
fun SettingsScreen(model: AppModel, onDone: () -> Unit) {
    val cur by model.settings.collectAsState()
    val cfg by model.config.collectAsState()
    val cfgStatus by model.configStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var address by remember { mutableStateOf(cur.address) }
    var authAddress by remember { mutableStateOf(cur.authAddress) }
    var userId by remember { mutableStateOf(cur.userId) }
    var apiKey by remember { mutableStateOf("") }          // 不回显已保存的 Key；留空 = 不修改
    var keyVisible by remember { mutableStateOf(false) }
    var outputFormat by remember { mutableStateOf(cur.outputFormat) }
    var vad by remember { mutableStateOf(cur.vadPreset) }
    var frameMs by remember { mutableStateOf(cur.frameMs) }
    var voice by remember { mutableStateOf(cur.voice) }
    var ttsRate by remember { mutableStateOf(TtsProsody.clampRate(cur.ttsRatePercent)) }
    var ttsPitch by remember { mutableStateOf(TtsProsody.clampPitch(cur.ttsPitchHz)) }
    var ttsVolume by remember { mutableStateOf(TtsProsody.clampVolume(cur.ttsVolumePercent)) }
    var useMemory by remember { mutableStateOf(cur.useMemory) }
    var showMetrics by remember { mutableStateOf(cur.showMetrics) }
    var bargeIn by remember { mutableStateOf(cur.bargeIn) }
    var speakText by remember { mutableStateOf(cur.speakTextReplies) }
    val authState by model.auth.state.collectAsState()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(model.hasApiKey()) }

    var showChangePw by remember { mutableStateOf(false) }
    var showLogoutAll by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showGpl by remember { mutableStateOf(false) }
    var showDeletePage by remember { mutableStateOf(false) }
    var showLegalHub by remember { mutableStateOf(false) }
    var accountMsg by remember { mutableStateOf<String?>(null) }

    if (showLegalHub) {
        LegalHubScreen(model = model, onDone = { showLegalHub = false })
        return
    }
    if (showPrivacy) {
        PrivacyPolicyScreen(onDone = { showPrivacy = false })
        return
    }
    if (showGpl) {
        GplFullTextScreen(onDone = { showGpl = false })
        return
    }
    if (showDeletePage) {
        AccountDeletionScreen(model = model, onDone = { showDeletePage = false })
        return
    }
    if (showLicenses) {
        OpenSourceLicensesScreen(onDone = { showLicenses = false }, onOpenGplFull = { showLicenses = false; showGpl = true })
        return
    }

    fun collect() = model.settings.value.copy(
        address = address, authAddress = authAddress, userId = userId, outputFormat = outputFormat, vadPreset = vad, frameMs = frameMs,
        voice = voice.trim(),
        ttsRatePercent = TtsProsody.clampRate(ttsRate),
        ttsPitchHz = TtsProsody.clampPitch(ttsPitch),
        ttsVolumePercent = TtsProsody.clampVolume(ttsVolume),
        useMemory = useMemory, showMetrics = showMetrics, bargeIn = bargeIn, speakTextReplies = speakText,
    )
    var saveWarning by remember { mutableStateOf<String?>(null) }
    fun save() {
        saveWarning = model.saveSettings(collect(), apiKey.takeIf { it.isNotBlank() })
        if (apiKey.isNotBlank()) { apiKey = ""; hasKey = model.hasApiKey() }
        address = model.settings.value.address
        authAddress = model.settings.value.authAddress
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ZenPalette.Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZenTopBar(title = "设置", onDone = { save(); onDone() })

        ZenSectionTitle("我的位置", eyebrow = "WHERE")
        ZenCard {
            LocationCard(model)
            ZenHint("定位会在语音/文字对话时随请求发给服务端；定位仅用于对话请求；位置卡片在此管理。")
        }

        ZenSectionTitle("服务器", eyebrow = "SERVER")
        ZenCard {
            ZenField(
                value = authAddress,
                onValueChange = { authAddress = it },
                label = if (BuildConfig.IS_INTERNAL) "登录服务器（内验可填局域网）" else "登录服务器（须公网 HTTPS）",
                placeholder = if (BuildConfig.IS_INTERNAL) ServerEndpoint.DEFAULT_AUTH_ADDRESS else "https://",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            val authNorm = ServerEndpoint.normalizeInput(authAddress)
            if (authNorm != authAddress.trim()) {
                ZenHint("登录地址将保存为：$authNorm")
            }
            ZenHint(
                if (BuildConfig.IS_INTERNAL)
                    "内验登录服务器可填局域网；保存后可点「检查连接」"
                else
                    "正式包登录服务器须公网 HTTPS；未配置时留空（发布阻塞），勿填内网地址"
            )
            ZenField(
                value = address,
                onValueChange = { address = it },
                label = "对话服务器（公网 HTTPS）",
                placeholder = ServerEndpoint.DEFAULT_ADDRESS,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            val normalized = ServerEndpoint.normalizeInput(address)
            if (normalized != address.trim()) {
                ZenHint("对话地址将保存为：$normalized（已把全角符号/空格换成半角）")
            }
            // 此框是米果会话凭证（MEMORY_API_KEY / 登录后的可吊销凭证），不是 DeepSeek。
            // DEEPSEEK_API_KEY 只允许出现在服务端环境变量，禁止写入本页、APK 或日志。
            ZenField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = if (hasKey) "API Key（已持有，留空不修改）" else if (BuildConfig.IS_INTERNAL) "API Key（内验可空，可注入本地默认）" else "API Key（正式包无内置默认，登录后使用会话凭证）",
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ZenTextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.let { apiKey = it }
                }) { Text("从剪贴板粘贴", fontSize = 13.sp) }
                ZenTextButton(onClick = { keyVisible = !keyVisible }) {
                    Text(if (keyVisible) "隐藏" else "显示", fontSize = 13.sp)
                }
            }
            if (BuildConfig.ALLOW_SKIP_LOGIN) {
                ZenField(
                    value = userId,
                    onValueChange = { userId = it },
                    label = "user_id（仅未登录/跳过登录时使用）",
                )
            }
        }

        ZenSectionTitle("账号", eyebrow = "ACCOUNT")
        ZenCard {
            when (val a = authState) {
                is com.miguo.voice.conversation.AuthState.LoggedIn -> {
                    ZenHint("已登录：${a.session.displayName}（${a.session.username}，user_id=${a.session.userId}）" + if (a.offline) " · 离线" else "")
                    accountMsg?.let {
                        Text(it, fontSize = 13.sp, color = ZenPalette.MossDeep)
                    }
                    ZenOutlinedButton(
                        onClick = { showChangePw = true; accountMsg = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("修改密码") }
                    ZenOutlinedButton(
                        onClick = { model.logout(); onDone() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出登录") }
                    ZenOutlinedButton(
                        onClick = { showLogoutAll = true; accountMsg = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("全设备退出") }
                    ZenOutlinedButton(
                        onClick = { showDeletePage = true; accountMsg = null },
                        modifier = Modifier.fillMaxWidth(),
                        danger = true,
                    ) { Text("注销账号") }
                    ZenHint("「退出登录」只退出本机；「全设备退出」会让其它已登录设备也立刻失效。改密码后所有设备都要重新登录。「注销账号」进入确认流程；已登录时调用 AUTH 注销。")
                }
                is com.miguo.voice.conversation.AuthState.UserIdMode -> {
                    ZenHint(a.notice ?: "未登录，记忆按上面的 user_id 区分。")
                    ZenOutlinedButton(onClick = { save(); model.showLogin(); onDone() }) {
                        Text("去登录 / 注册")
                    }
                }
                else -> ZenHint("状态：$a")
            }
        }


        // vc44：flavor 专属调试区（internal=UI对齐入口+三态注入；store=空实现）
        FlavorDebugSection()

        ZenSectionTitle("合规", eyebrow = "LEGAL")
        ZenCard {
            ZenOutlinedButton(
                onClick = { showLicenses = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("开源许可 / GPL / 源码") }
            ZenOutlinedButton(
                onClick = { showLegalHub = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("合规与关于（总览）") }
            ZenOutlinedButton(
                onClick = { showPrivacy = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("隐私政策（应用内）") }
            ZenOutlinedButton(
                onClick = { showGpl = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("GPL 全文") }
            ZenOutlinedButton(
                onClick = { showDeletePage = true },
                modifier = Modifier.fillMaxWidth(),
                danger = true,
            ) { Text("账号注销") }
            ZenHint("可点：开源许可 / GPL 全文 / 隐私（应用内+" + MiguoLegal.PRIVACY_URL + "）/ 注销。联系邮箱已写入应用常量。")
            accountMsg?.let {
                if ("待接入" in it) {
                    Text(it, fontSize = 13.sp, color = ZenPalette.Seal)
                }
            }
        }

        ZenSectionTitle("语音个性化", eyebrow = "PROSODY")
        ZenCard {
            ZenFieldLabel("音色")
            val presetIds = TtsProsody.VOICE_PRESETS.map { it.first }.toSet()
            // 自定义音色时不点亮任何预设芯片（避免误显「跟随服务器」）
            val chipVoice = if (voice in presetIds) voice else "__custom__"
            ZenChoices(
                TtsProsody.VOICE_PRESETS,
                chipVoice,
            ) { selected ->
                voice = selected
            }
            ZenField(
                value = voice,
                onValueChange = { voice = it },
                label = "自定义音色（留空 = 跟随服务器${cfg.voice.voice?.let { "：$it" } ?: ""}）",
                placeholder = "例如 zh-CN-XiaoxiaoNeural",
            )
            ZenSliderRow(
                label = "语速",
                valueLabel = TtsProsody.formatPercent(ttsRate),
                value = ttsRate.toFloat(),
                valueRange = TtsProsody.RATE_MIN.toFloat()..TtsProsody.RATE_MAX.toFloat(),
                steps = TtsProsody.RATE_MAX - TtsProsody.RATE_MIN - 1,
            ) { ttsRate = it.toInt() }
            ZenSliderRow(
                label = "语调 / 音高",
                valueLabel = TtsProsody.formatPitchHz(ttsPitch),
                value = ttsPitch.toFloat(),
                valueRange = TtsProsody.PITCH_MIN.toFloat()..TtsProsody.PITCH_MAX.toFloat(),
                steps = TtsProsody.PITCH_MAX - TtsProsody.PITCH_MIN - 1,
            ) { ttsPitch = it.toInt() }
            ZenSliderRow(
                label = "音量",
                valueLabel = TtsProsody.formatPercent(ttsVolume),
                value = ttsVolume.toFloat(),
                valueRange = TtsProsody.VOLUME_MIN.toFloat()..TtsProsody.VOLUME_MAX.toFloat(),
                steps = TtsProsody.VOLUME_MAX - TtsProsody.VOLUME_MIN - 1,
            ) { ttsVolume = it.toInt() }
            ZenOutlinedButton(
                onClick = { ttsRate = 0; ttsPitch = 0; ttsVolume = 0 },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("恢复默认语速 / 语调 / 音量", fontSize = 13.sp) }
            ZenHint("下次点「开始」语音会话，或开启「朗读文字回复」后发文字时生效（edge-tts 参数：rate / pitch / volume）。")
        }

        ZenSectionTitle("语音会话", eyebrow = "VOICE")
        ZenCard {
            ZenHint("不选 = 跟随服务器配置")
            ZenFieldLabel("回复音频格式")
            ZenChoices(
                listOf(null to "跟随服务器（${cfg.voice.outputFormat}）", "mp3" to "mp3", "pcm_s16le" to "pcm_s16le"),
                outputFormat,
            ) { outputFormat = it }
            ZenFieldLabel("说完判定")
            ZenChoices(
                listOf<Pair<VADPreset?, String>>(null to "跟随服务器（${cfg.voice.vad.minSilenceMs} ms）") +
                    VADPreset.entries.map { it to it.label },
                vad,
            ) { vad = it }
            ZenFieldLabel("上行帧长")
            ZenChoices(
                listOf<Pair<Int?, String>>(null to "跟随服务器（${cfg.voice.frameMs} ms）", 20 to "20 ms", 40 to "40 ms"),
                frameMs,
            ) { frameMs = it }
            ZenFieldLabel("长期记忆")
            ZenChoices(
                listOf<Pair<Boolean?, String>>(
                    null to "跟随服务器（${if (cfg.voice.useMemory) "开" else "关"}）",
                    true to "开",
                    false to "关",
                ),
                useMemory,
            ) { useMemory = it }
            ZenSwitchRow("显示指标浮层", showMetrics) { showMetrics = it }
            ZenFieldLabel("打断（barge-in）")
            ZenChoices(
                listOf<Pair<Boolean?, String>>(
                    null to "跟随服务器（${if (cfg.voice.bargeIn) "开" else "关"}）",
                    true to "开",
                    false to "关",
                ),
                bargeIn,
            ) { bargeIn = it }
            ZenHint("开：米果说话时麦克风继续上传（回声消除），你一开口就停下来听你说；外放环境嘈杂/回声大时可以关掉，改用“✋ 打断”按钮。")
            ZenSwitchRow("朗读文字回复（不在语音会话中时）", speakText) { speakText = it }
        }

        ZenHairline()
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            ZenOutlinedButton(
                enabled = !testing,
                onClick = {
                    save()
                    testing = true
                    result = "检查登录中继与对话服务器…"
                    scope.launch {
                        result = try {
                            val auth = model.testAuthConnection()
                            val chat = model.testConnection()
                            "【登录】$auth\n【对话】$chat"
                        } catch (e: Exception) {
                            "✗ 检查失败：$e"
                        } finally {
                            testing = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (testing) "检查中…" else "保存并检查连接", fontSize = 13.sp) }
            ZenPrimaryButton(
                onClick = { save(); onDone() },
                modifier = Modifier.weight(1f),
            ) { Text("完成") }
        }
        saveWarning?.let {
            Text("⚠️ $it", fontSize = 13.sp, color = ZenPalette.Seal)
        }
        result?.let { Text(it, fontSize = 13.sp, color = ZenPalette.Ink) }
        ZenHint("配置来源：$cfgStatus · App 版本 ${model.appVersion}")
        if (BuildConfig.IS_INTERNAL) {
            ZenHint("内验时手机和电脑须在同一局域网；防火墙需放行对话服务端口。")
        }
    }

    if (showChangePw) {
        ChangePasswordDialog(
            onDismiss = { showChangePw = false },
            onSubmit = { old, new, done ->
                model.changePassword(old, new) { err ->
                    done(err)
                    if (err == null) {
                        showChangePw = false
                        accountMsg = "密码已修改，请重新登录"
                        onDone()
                    }
                }
            },
        )
    }
    if (showLogoutAll) {
        AlertDialog(
            onDismissRequest = { showLogoutAll = false },
            title = {
                Text("全设备退出", color = ZenPalette.Ink)
            },
            text = {
                Text(
                    "将让此账号在所有设备上的登录立刻失效（包括本机）。确定继续？",
                    color = ZenPalette.Muted,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutAll = false
                        model.logout(everywhere = true)
                        onDone()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = ZenPalette.Seal),
                ) { Text("确定退出全部") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogoutAll = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = ZenPalette.MossDeep),
                ) { Text("取消") }
            },
            containerColor = ZenPalette.PaperSoft,
        )
    }


}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onSubmit: (String, String, (String?) -> Unit) -> Unit) {
    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var show by remember { mutableStateOf(false) }
    val vis = if (show) VisualTransformation.None else PasswordVisualTransformation()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改密码", color = ZenPalette.Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZenField(
                    value = oldPw, onValueChange = { oldPw = it },
                    label = "原密码", visualTransformation = vis,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                ZenField(
                    value = newPw, onValueChange = { newPw = it },
                    label = "新密码（至少 6 位）", visualTransformation = vis,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                ZenField(
                    value = confirm, onValueChange = { confirm = it },
                    label = "确认新密码", visualTransformation = vis,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                ZenTextButton(onClick = { show = !show }) {
                    Text(if (show) "隐藏密码" else "显示密码", fontSize = 13.sp)
                }
                err?.let { Text(it, color = ZenPalette.Seal, fontSize = 13.sp) }
                ZenHint("修改成功后所有设备都要重新登录。")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = ZenPalette.MossDeep),
                onClick = {
                    when {
                        oldPw.isEmpty() -> err = "请输入原密码"
                        newPw.length < 6 -> err = "新密码至少 6 位"
                        newPw != confirm -> err = "两次输入的新密码不一致"
                        newPw == oldPw -> err = "新密码不能与原密码相同"
                        else -> {
                            busy = true; err = null
                            onSubmit(oldPw, newPw) { e ->
                                busy = false
                                if (e != null) err = e
                            }
                        }
                    }
                },
            ) { Text(if (busy) "提交中…" else "确认修改") }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = ZenPalette.Muted),
            ) { Text("取消") }
        },
        containerColor = ZenPalette.PaperSoft,
    )
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit, onSubmit: (String, (String?) -> Unit) -> Unit) {
    var password by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("注销账号", color = ZenPalette.Seal) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "此操作不可恢复：账号与登录态会被删除。对话记忆仍按原 user_id 保留在服务器上，但无法再用此账号登录。",
                    fontSize = 13.sp,
                    color = ZenPalette.Muted,
                )
                ZenField(
                    value = password, onValueChange = { password = it },
                    label = "当前密码",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                ZenField(
                    value = phrase, onValueChange = { phrase = it },
                    label = "请输入确认语：注销账号",
                )
                err?.let { Text(it, color = ZenPalette.Seal, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = ZenPalette.Seal),
                onClick = {
                    when {
                        password.isEmpty() -> err = "请输入密码"
                        phrase.trim() != "注销账号" -> err = "确认语须为「注销账号」四字"
                        else -> {
                            busy = true; err = null
                            onSubmit(password) { e ->
                                busy = false
                                if (e != null) err = e
                            }
                        }
                    }
                },
            ) { Text(if (busy) "注销中…" else "确认注销") }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = ZenPalette.Muted),
            ) { Text("取消") }
        },
        containerColor = ZenPalette.PaperSoft,
    )
}
