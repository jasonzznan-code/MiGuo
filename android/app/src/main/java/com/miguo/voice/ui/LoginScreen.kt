package com.miguo.voice.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.BuildConfig
import com.miguo.voice.R
import com.miguo.voice.conversation.AuthState
import com.miguo.voice.protocol.AuthLoginMessages
import com.miguo.voice.protocol.FlavorLoginCopy
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * 定稿登录页（migo-ui-freeze-20261030 · A「米果欢迎」+ C 气泡报错）。
 * 整页替换旧 Material 表单壳；契约仍走 OTP / AUTH host / token。
 */
@Composable
fun LoginScreen(
    model: AppModel,
    onOpenServer: () -> Unit,
    onOpenLegal: () -> Unit = {},
) {
    val st by model.auth.state.collectAsState()
    val settings by model.settings.collectAsState()
    var identifier by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var requestingCode by remember { mutableStateOf(false) }
    var otpCd by remember { mutableIntStateOf(0) }
    var localBubble by remember { mutableStateOf<LoginBubble?>(null) }
    var demoOverride by remember { mutableStateOf<LoginBubble?>(null) }
    // internal 默认手机号；store 默认邮箱。两通道均可切换，邮箱入口必须可见可点。
    var useEmail by remember { mutableStateOf(!BuildConfig.IS_INTERNAL) }
    val idLabel = if (useEmail) AuthLoginMessages.EMAIL_LABEL else AuthLoginMessages.PHONE_LABEL
    val idPlaceholder = if (useEmail) AuthLoginMessages.EMAIL_PLACEHOLDER else AuthLoginMessages.PHONE_PLACEHOLDER
    val idContentDesc = if (useEmail) AuthLoginMessages.EMAIL_CONTENT_DESC else AuthLoginMessages.PHONE_CONTENT_DESC
    val switchLabel = if (useEmail) AuthLoginMessages.SWITCH_TO_PHONE else AuthLoginMessages.SWITCH_TO_EMAIL

    val busy = st.busy || requestingCode
    val out = st as? AuthState.LoggedOut
    val errorRaw = out?.error
    val loadingLogin = st is AuthState.LoggingIn && !requestingCode
    val loadingCheck = st is AuthState.Unknown || st is AuthState.Checking
    val unreachable = AuthLoginMessages.isUnreachable(errorRaw)

    LaunchedEffect(otpCd) {
        if (otpCd <= 0) return@LaunchedEffect
        delay(1000)
        otpCd = otpCd - 1
    }

    val allowChangeServer = BuildConfig.IS_INTERNAL
    val bubble: LoginBubble? = when {
        demoOverride != null -> demoOverride
        requestingCode -> LoginBubble.Loading(AuthLoginMessages.BUBBLE_LOADING_CODE)
        loadingLogin -> LoginBubble.Loading(AuthLoginMessages.BUBBLE_LOADING_LOGIN)
        loadingCheck -> LoginBubble.Loading(AuthLoginMessages.LOADING_CHECK)
        errorRaw != null -> {
            val (ecode, ezh) = AuthLoginMessages.parseTagged(errorRaw)
            val code = ecode.orEmpty()
            val line = ezh.ifBlank { errorRaw }
            when {
                code == AuthLoginMessages.CODE_BAD_IDENTIFIER ||
                    line.contains("请先输入") || line.contains("格式不对") || line.contains("请输入正确的邮箱") ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_BAD_ID_TITLE,
                        line = if (line.contains("请先输入")) AuthLoginMessages.BUBBLE_EMPTY_ID_LINE else AuthLoginMessages.BUBBLE_BAD_ID_LINE,
                        warn = true,
                    )
                code == AuthLoginMessages.CODE_RATE_LIMITED || code == AuthLoginMessages.CODE_TOO_OFTEN ||
                    line.contains("频繁") ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_RATE_TITLE,
                        line = line.ifBlank { AuthLoginMessages.bubbleRateLine(null) },
                        warn = true,
                    )
                code == AuthLoginMessages.CODE_WRONG || (codeSent && line.contains("不对")) ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_CODE_WRONG_TITLE,
                        line = AuthLoginMessages.BUBBLE_CODE_WRONG_LINE,
                        warn = true,
                        showResend = true,
                    )
                code == AuthLoginMessages.CODE_EXPIRED || line.contains("过期") && codeSent ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_CODE_EXPIRED_TITLE,
                        line = AuthLoginMessages.BUBBLE_CODE_EXPIRED_LINE,
                        warn = true,
                        showResend = true,
                    )
                code == AuthLoginMessages.CODE_SEND_FAILED ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_LOGIN_FAIL_TITLE,
                        line = "验证码没能发出，请稍后再试。",
                        warn = true,
                        showRetry = true,
                    )
                AuthLoginMessages.isUnreachable(errorRaw) || code == AuthLoginMessages.CODE_AUTH_UNREACHABLE ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_UNREACHABLE_TITLE,
                        line = AuthLoginMessages.BUBBLE_UNREACHABLE_LINE,
                        warn = true,
                        showChangeServer = allowChangeServer,
                        showRetry = true,
                    )
                line.contains("登录已过期") || line.contains("需要重新登录") ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_NEED_RELOGIN_TITLE,
                        line = AuthLoginMessages.BUBBLE_NEED_RELOGIN_LINE,
                        warn = true,
                        showResend = true,
                    )
                codeSent ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_LOGIN_FAIL_TITLE,
                        line = AuthLoginMessages.BUBBLE_LOGIN_FAIL_LINE,
                        warn = true,
                        showResend = true,
                    )
                else ->
                    LoginBubble.Error(
                        title = AuthLoginMessages.BUBBLE_LOGIN_FAIL_TITLE,
                        line = AuthLoginMessages.BUBBLE_LOGIN_FAIL_LINE,
                        warn = true,
                        showRetry = true,
                        showChangeServer = allowChangeServer,
                    )
            }
        }
        localBubble != null -> localBubble
        else -> null
    }

    val authHost = remember(settings.authAddress) {
        settings.authAddress.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .ifBlank { if (BuildConfig.IS_INTERNAL) "未配置" else "未配置" }
    }
    val greet = remember {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when {
            h < 5 -> "夜深了"
            h < 11 -> "早上好"
            h < 13 -> "中午好"
            h < 18 -> "下午好"
            else -> "晚上好"
        }
    }

    val floatAnim = rememberInfiniteTransition(label = "authfloat")
    val floatY by floatAnim.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fy",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MigoTokens.AuthCreamTop,
                        MigoTokens.AuthCreamMid,
                        MigoTokens.AuthCreamBottom,
                    ),
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // 右上角服务器胶囊：仅 internal；商店版隐藏换服
        if (BuildConfig.IS_INTERNAL) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 14.dp)
                .clip(MigoTokens.PillShape)
                .background(MigoTokens.AuthChipBg)
                .border(1.dp, MigoTokens.AuthChipBorder, MigoTokens.PillShape)
                .clickable(enabled = !busy) {
                    demoOverride = null
                    onOpenServer()
                }
                .semantics { contentDescription = AuthLoginMessages.CHANGE_SERVER }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (unreachable) MigoTokens.Danger else MigoTokens.Primary),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                authHost,
                color = MigoTokens.AuthChipText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(120.dp),
            )
            Icon(
                imageVector = MigoIcons.ChevronRight,
                contentDescription = null,
                tint = MigoTokens.AuthChipText,
                modifier = Modifier.size(14.dp),
            )
        }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero
            Box(
                Modifier
                    .size(width = 170.dp, height = 140.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MigoTokens.AuthGlow,
                                    MigoTokens.AuthGlow.copy(alpha = 0.45f),
                                    MigoTokens.AuthCreamMid.copy(alpha = 0f),
                                ),
                            ),
                        ),
                )
                Image(
                    painter = painterResource(R.drawable.migo),
                    contentDescription = "米果",
                    modifier = Modifier
                        .size(108.dp)
                        .graphicsLayer { translationY = floatY },
                )
            }
            Text(
                "$greet，我是米果",
                color = MigoTokens.Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.semantics { contentDescription = "问候" },
            )
            Text(
                AuthLoginMessages.WELCOME_SUB,
                color = MigoTokens.Muted,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(8.dp))

            // Bottom sheet
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MigoTokens.SheetTopShape)
                    .background(MigoTokens.White)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(MigoTokens.PillShape)
                        .background(MigoTokens.LineSolid),
                )

                if (bubble != null) {
                    MigoAuthBubble(
                        bubble = bubble,
                        onChangeServer = {
                            demoOverride = null
                            onOpenServer()
                        },
                        onRetry = {
                            demoOverride = null
                            localBubble = null
                            model.recheckAuth()
                        },
                        onResend = {
                            demoOverride = null
                            if (identifier.isNotBlank() && otpCd == 0 && !busy) {
                                requestingCode = true
                                model.requestCode(identifier) { err ->
                                    requestingCode = false
                                    if (err == null) {
                                        codeSent = true
                                        otpCd = 60
                                        localBubble = LoginBubble.Info(AuthLoginMessages.CODE_HINT_LOG)
                                    }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(idLabel, color = MigoTokens.Muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        switchLabel,
                        color = MigoTokens.Primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable(enabled = !busy) {
                                useEmail = !useEmail
                                identifier = ""
                                code = ""
                                codeSent = false
                                otpCd = 0
                                localBubble = null
                                demoOverride = null
                            }
                            .semantics { contentDescription = switchLabel }
                            .padding(vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                AuthFieldRow {
                    if (!useEmail) {
                        Text("+86", color = MigoTokens.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Box(
                            Modifier
                                .padding(horizontal = 8.dp)
                                .width(1.dp)
                                .height(18.dp)
                                .background(MigoTokens.LineSolid),
                        )
                    }
                    BasicTextField(
                        value = identifier,
                        onValueChange = {
                            identifier = if (useEmail) {
                                it.filter { ch -> !ch.isWhitespace() }.take(254)
                            } else {
                                it.filter { ch -> ch.isDigit() }.take(11)
                            }
                            demoOverride = null
                        },
                        singleLine = true,
                        enabled = !busy,
                        textStyle = TextStyle(color = MigoTokens.Ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(MigoTokens.Primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (useEmail) KeyboardType.Email else KeyboardType.Phone,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = idContentDesc },
                        decorationBox = { inner ->
                            if (identifier.isEmpty()) {
                                Text(idPlaceholder, color = MigoTokens.Muted, fontSize = 14.sp)
                            }
                            inner()
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("验证码", color = MigoTokens.Muted, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    if (BuildConfig.SHOW_INTERNAL_OTP_HINT && AuthLoginMessages.INTERNAL_BADGE.isNotBlank()) {
                        Text(
                            AuthLoginMessages.INTERNAL_BADGE,
                            color = MigoTokens.Amber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(MigoTokens.PillShape)
                                .background(MigoTokens.AuthBetaBg)
                                .border(1.dp, MigoTokens.AuthBetaBorder, MigoTokens.PillShape)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                .semantics { contentDescription = AuthLoginMessages.INTERNAL_BADGE },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                AuthFieldRow {
                    BasicTextField(
                        value = code,
                        onValueChange = {
                            code = it.filter { ch -> ch.isDigit() }.take(6)
                            demoOverride = null
                        },
                        singleLine = true,
                        enabled = !busy,
                        textStyle = TextStyle(color = MigoTokens.Ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(MigoTokens.Primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "请输入验证码" },
                        decorationBox = { inner ->
                            if (code.isEmpty()) {
                                Text("6 位验证码", color = MigoTokens.Muted, fontSize = 14.sp)
                            }
                            inner()
                        },
                    )
                    val sendLabel = when {
                        requestingCode || (busy && !loadingLogin && !loadingCheck) -> "请求中…"
                        otpCd > 0 -> "${otpCd}s 后重发"
                        codeSent -> "重新发送"
                        else -> AuthLoginMessages.SEND_CODE
                    }
                    Text(
                        sendLabel,
                        color = MigoTokens.Primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(MigoTokens.FieldShape)
                            .background(MigoTokens.Pale)
                            .clickable(
                                enabled = !busy && identifier.isNotBlank() && otpCd == 0,
                            ) {
                                requestingCode = true
                                demoOverride = null
                                localBubble = null
                                model.requestCode(identifier) { err ->
                                    requestingCode = false
                                    if (err == null) {
                                        codeSent = true
                                        otpCd = 60
                                        localBubble = LoginBubble.Info(AuthLoginMessages.CODE_HINT_LOG)
                                    } else {
                                        codeSent = false
                                        // 错误由 AuthState 气泡承接
                                    }
                                }
                            }
                            .semantics { contentDescription = AuthLoginMessages.SEND_CODE }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))
                val loginLabel = if (loadingLogin) "登录中…" else AuthLoginMessages.LOGIN_BUTTON
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clip(MigoTokens.ButtonShape)
                        .background(if (busy && !requestingCode && !loadingCheck) MigoTokens.Primary.copy(alpha = 0.7f) else MigoTokens.Primary)
                        .clickable(
                            enabled = !busy && identifier.isNotBlank() && code.isNotBlank(),
                        ) {
                            demoOverride = null
                            localBubble = null
                            model.verifyCode(identifier, code)
                        }
                        .semantics { contentDescription = AuthLoginMessages.LOGIN_BUTTON }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(loginLabel, color = MigoTokens.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }

                Text(
                    "没收到验证码？",
                    color = MigoTokens.Muted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) {
                            demoOverride = null
                            localBubble = LoginBubble.Error(
                                title = AuthLoginMessages.BUBBLE_OTP_MISSING_TITLE,
                                line = AuthLoginMessages.BUBBLE_OTP_MISSING_LINE,
                                warn = false,
                                showResend = otpCd == 0,
                            )
                        }
                        .semantics { contentDescription = "验证码未收到" }
                        .padding(top = 10.dp, bottom = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                        Text(
            "合规与关于（GPL / 源码 / 隐私 / 注销）",
            color = MigoTokens.Primary,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(top = 14.dp, bottom = 4.dp)
                .clickable(enabled = !busy, onClick = onOpenLegal)
                .semantics { contentDescription = "合规与关于" },
        )

        if (BuildConfig.ALLOW_SKIP_LOGIN && out?.required != true && AuthLoginMessages.SKIP_LOGIN_LABEL.isNotBlank()) {
                    Text(
                        AuthLoginMessages.SKIP_LOGIN_LABEL,
                        color = MigoTokens.Muted,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { model.skipLogin() }
                            .padding(vertical = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                // 演示态：仅 internal，不进 store
                if (BuildConfig.IS_INTERNAL) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    ) {
                        DemoLink("演示：服务器不可达") {
                            demoOverride = LoginBubble.Error(
                                title = AuthLoginMessages.BUBBLE_UNREACHABLE_TITLE,
                                line = AuthLoginMessages.BUBBLE_UNREACHABLE_LINE,
                                warn = true,
                                showChangeServer = BuildConfig.IS_INTERNAL,
                                showRetry = true,
                            )
                        }
                        DemoLink("演示：验证码未收到") {
                            demoOverride = LoginBubble.Error(
                                title = AuthLoginMessages.BUBBLE_OTP_MISSING_TITLE,
                                line = AuthLoginMessages.BUBBLE_OTP_MISSING_LINE,
                                warn = false,
                                showResend = true,
                            )
                        }
                        DemoLink("演示：登录失败") {
                            demoOverride = LoginBubble.Error(
                                title = AuthLoginMessages.BUBBLE_LOGIN_FAIL_TITLE,
                                line = AuthLoginMessages.BUBBLE_LOGIN_FAIL_LINE,
                                warn = true,
                                showResend = true,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

private sealed class LoginBubble {
    data class Loading(val text: String) : LoginBubble()
    data class Info(val text: String) : LoginBubble()
    data class Error(
        val title: String,
        val line: String,
        val warn: Boolean,
        val showChangeServer: Boolean = false,
        val showRetry: Boolean = false,
        val showResend: Boolean = false,
    ) : LoginBubble()
}

@Composable
private fun MigoAuthBubble(
    bubble: LoginBubble,
    onChangeServer: () -> Unit,
    onRetry: () -> Unit,
    onResend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Image(
            painter = painterResource(R.drawable.migo),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        when (bubble) {
            is LoginBubble.Loading -> {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(MigoTokens.BubbleShape)
                        .background(MigoTokens.AuthBubbleBg)
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MigoTokens.Primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(bubble.text, color = MigoTokens.AuthBubbleText, fontSize = 11.5.sp, lineHeight = 18.sp)
                }
            }
            is LoginBubble.Info -> {
                Text(
                    bubble.text,
                    color = MigoTokens.AuthBubbleText,
                    fontSize = 11.5.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .weight(1f)
                        .clip(MigoTokens.BubbleShape)
                        .background(MigoTokens.AuthBubbleBg)
                        .padding(horizontal = 11.dp, vertical = 9.dp)
                        .semantics { contentDescription = "登录提示气泡" },
                )
            }
            is LoginBubble.Error -> {
                Column(
                    Modifier
                        .weight(1f)
                        .clip(MigoTokens.BubbleShape)
                        .background(if (bubble.warn) MigoTokens.AuthWarnBubble else MigoTokens.AuthBubbleBg)
                        .padding(horizontal = 11.dp, vertical = 9.dp)
                        .semantics { contentDescription = "登录提示气泡" },
                ) {
                    Text(
                        "${bubble.title} · ${bubble.line}",
                        color = MigoTokens.AuthBubbleText,
                        fontSize = 11.5.sp,
                        lineHeight = 18.sp,
                    )
                    if (bubble.showChangeServer || bubble.showRetry || bubble.showResend) {
                        Row(
                            Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (bubble.showChangeServer) {
                                Text(
                                    AuthLoginMessages.CHANGE_SERVER,
                                    color = MigoTokens.Primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clickable(onClick = onChangeServer)
                                        .sizeIn(minHeight = 32.dp),
                                )
                            }
                            if (bubble.showRetry) {
                                Text(
                                    "重试",
                                    color = MigoTokens.Primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clickable(onClick = onRetry)
                                        .sizeIn(minHeight = 32.dp),
                                )
                            }
                            if (bubble.showResend) {
                                Text(
                                    "重新获取",
                                    color = MigoTokens.Primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clickable(onClick = onResend)
                                        .sizeIn(minHeight = 32.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthFieldRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(MigoTokens.FieldShape)
            .background(MigoTokens.FieldBg)
            .border(1.dp, MigoTokens.FieldBorder, MigoTokens.FieldShape)
            .padding(start = 12.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun DemoLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = MigoTokens.Muted.copy(alpha = 0.75f),
        fontSize = 10.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(2.dp),
    )
}
