package com.miguo.voice.ui.immersive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.R
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.ui.components.uiChipLabel
import com.miguo.voice.ui.home.MigoAuxSheet
import com.miguo.voice.ui.home.MigoAuxSheetHost
import com.miguo.voice.conversation.ConversationState
import com.miguo.voice.ui.AppModel
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** 通话 UI 状态：与 task.status / AgentStatus 分离。 */
enum class CallUiStatus { Idle, Listening, Muted, Ended }

/**
 * 定稿沉浸语音整页（vc47 重画，对齐 store-v1 / 20261031 + Jason 11/2 两键口径）。
 *
 * - 背景渐变 + 米果剪影按稿归一坐标；问候「嗨，我是米果。」/「今天想聊些什么？」
 * - waiting 胶囊「任务 · 等待确认 ›」；波形：聆听接 engine.level，回答无 RMS 则状态动画
 * - 底栏麦克风 + 结束 + 视频；触控 ≥48dp
 */
@Composable
fun ImmersiveVoiceScreen(
    model: AppModel,
    agentStatus: AgentStatus,
    waitingTask: Task? = null,
    onOpenDrawer: () -> Unit,
    onShowTranscript: () -> Unit,
    onEndToChat: () -> Unit,
    onOpenWaitingTask: () -> Unit,
    onVideoNotice: () -> Unit,
) {
    val state by model.engine.state.collectAsState()
    val micMuted by model.engine.micMuted.collectAsState()
    val level by model.engine.level.collectAsState()
    val chatEntries by model.chat.entries.collectAsState()

    var callStatus by remember { mutableStateOf(CallUiStatus.Listening) }
    var showCaptions by remember { mutableStateOf(false) }
    var showVideoSheet by remember { mutableStateOf(false) }
    var showVoiceTaskSheet by remember { mutableStateOf(false) }

    // 进入沉浸页即接真实 engine；否则麦克风只改 mute 标志，会话未开。
    LaunchedEffect(Unit) {
        if (!model.engine.state.value.isActive) {
            model.engine.toggle()
        }
        model.engine.setMicMuted(false)
        callStatus = CallUiStatus.Listening
    }

    val callLabel = when {
        callStatus == CallUiStatus.Ended -> "通话已结束"
        micMuted || callStatus == CallUiStatus.Muted -> "麦克风已关闭"
        state is ConversationState.Listening || state is ConversationState.UserSpeaking -> "正在聆听"
        state is ConversationState.Thinking || state is ConversationState.Recognizing -> "整理中"
        state is ConversationState.Speaking || state is ConversationState.Synthesizing -> "米果正在回答"
        else -> "正在聆听"
    }

    val waveMode = when {
        callStatus == CallUiStatus.Ended || micMuted || callStatus == CallUiStatus.Muted -> WaveMode.Flat
        state is ConversationState.Listening || state is ConversationState.UserSpeaking -> WaveMode.ListeningRms
        state is ConversationState.Speaking || state is ConversationState.Synthesizing -> WaveMode.AnsweringSoft
        state is ConversationState.Thinking || state is ConversationState.Recognizing -> WaveMode.Flat
        else -> WaveMode.ListeningRms
    }

    val density = LocalDensity.current
    var rootW by remember { mutableIntStateOf(0) }
    var rootH by remember { mutableIntStateOf(0) }
    var waveCenterY by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { rootW = it.width; rootH = it.height }
            .drawBehind { drawImmersiveBackdrop() }
            .semantics { contentDescription = "沉浸语音模式" },
    ) {
        ImmersiveSilhouetteLayer(
            rootW = rootW,
            rootH = rootH,
            waveCenterY = waveCenterY,
            density = density,
        )

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            ImmersiveTopBar(
                onOpenDrawer = onOpenDrawer,
                captionsOn = showCaptions,
                onToggleCaptions = {
                    showCaptions = !showCaptions
                    onShowTranscript()
                },
            )

            if (!showCaptions) {
                ImmersiveGreeting(
                    showWaitingCapsule = agentStatus == AgentStatus.waiting || waitingTask != null,
                    onOpenWaitingTask = {
                        if (waitingTask != null) showVoiceTaskSheet = true
                        else onOpenWaitingTask()
                    },
                )
            }

            Spacer(Modifier.weight(1f))

            ImmersiveWaveform(
                mode = waveMode,
                rms = level,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 16.dp)
                    .onGloballyPositioned { c ->
                        waveCenterY = c.positionInRoot().y + c.size.height / 2f
                    },
            )

            Spacer(Modifier.height(10.dp))

            ImmersiveCallStatusRow(
                label = callLabel,
                muted = micMuted || callStatus == CallUiStatus.Muted,
                ended = callStatus == CallUiStatus.Ended,
            )

            Spacer(Modifier.height(28.dp))

            // 主稿三项：麦克风 / 结束 / 视频
            ImmersiveBottomActions(
                micMuted = micMuted,
                onToggleMic = {
                    if (!state.isActive) {
                        model.engine.toggle()
                        model.engine.setMicMuted(false)
                        callStatus = CallUiStatus.Listening
                    } else {
                        val next = !micMuted
                        model.engine.setMicMuted(next)
                        callStatus = if (next) CallUiStatus.Muted else CallUiStatus.Listening
                    }
                },
                onEnd = {
                    callStatus = CallUiStatus.Ended
                    if (state.isActive) model.engine.toggle()
                    onEndToChat()
                },
                onVideo = {
                    showVideoSheet = true
                    onVideoNotice()
                },
            )
        }

        if (showVideoSheet) {
            MigoAuxSheetHost(
                sheet = MigoAuxSheet.Video,
                onDismiss = { showVideoSheet = false },
                onConfirmCancel = { showVideoSheet = false },
                onSaveEdit = { _, _, _ -> showVideoSheet = false },
                onOpenPlans = { showVideoSheet = false },
            )
        }
        if (showVoiceTaskSheet && waitingTask != null) {
            MigoAuxSheetHost(
                sheet = MigoAuxSheet.VoiceTask(waitingTask),
                onDismiss = { showVoiceTaskSheet = false },
                onConfirmCancel = { showVoiceTaskSheet = false },
                onSaveEdit = { _, _, _ -> showVoiceTaskSheet = false },
                onOpenPlans = {
                    showVoiceTaskSheet = false
                    onOpenWaitingTask()
                },
            )
        }

        AnimatedVisibility(
            visible = showCaptions,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 56.dp, bottom = 120.dp),
        ) {
            ImmersiveCaptionsPanel(
                entries = chatEntries,
                waitingTask = waitingTask,
                agentStatus = agentStatus,
                onDismiss = { showCaptions = false },
                onOpenVoiceTask = {
                    if (waitingTask != null) showVoiceTaskSheet = true
                    else onOpenWaitingTask()
                },
                onBackToChat = onEndToChat,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImmersiveBackdrop() {
    drawRect(
        Brush.linearGradient(
            colors = listOf(Color(0xFFF6F4EC), Color(0xFFE3E4D8), Color(0xFFCDD6C6)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0x99C9C4B6), Color(0x00C9C4B6)),
            center = Offset(size.width * 0.18f, size.height * 0.30f),
            radius = size.width * 0.75f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xCCEAE3CC), Color(0x00EAE3CC)),
            center = Offset(0f, size.height * 0.72f),
            radius = size.width * 0.85f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xFFF7F5EE), Color(0x00F7F5EE)),
            center = Offset(0f, 0f),
            radius = size.width * 0.9f,
        ),
    )
}

@Composable
private fun ImmersiveSilhouetteLayer(
    rootW: Int,
    rootH: Int,
    waveCenterY: Float,
    density: androidx.compose.ui.unit.Density,
) {
    if (rootW <= 0 || rootH <= 0) return
    val tipMin = rootH * DESIGN_SPROUT_TIP
    val bottomMax = when {
        waveCenterY > 0f -> minOf(
            waveCenterY - with(density) { 6.dp.toPx() },
            rootH * DESIGN_BODY_BOTTOM,
        )
        else -> rootH * DESIGN_BODY_BOTTOM
    }
    val contentHFrac = (SIL_BOT_Y - SIL_TIP_Y).coerceAtLeast(0.5f)
    val swIdeal = rootW * DESIGN_EYE_SPACE / SIL_EYE_SPACE
    val shIdeal = swIdeal * SILHOUETTE_RATIO
    val shFit = ((bottomMax - tipMin).coerceAtLeast(rootH * 0.28f) / contentHFrac)
    val shPx = minOf(shIdeal, shFit)
    val swPx = shPx / SILHOUETTE_RATIO
    val topPx = if (shPx < shIdeal - 1f) {
        bottomMax - SIL_BOT_Y * shPx
    } else {
        tipMin - SIL_TIP_Y * shPx
    }
    val leftPx = rootW * DESIGN_LEFT - SIL_LEFT_X * swPx

    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(leftPx + swPx * 0.55f, topPx + shPx * 0.68f)
        val r = swPx * 0.70f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x00FFFFFF), Color(0x00FFFFFF), Color(0x1AFFFFFF), Color(0x00FFFFFF)),
                center = c,
                radius = r * 1.08f,
            ),
            radius = r * 1.08f,
            center = c,
        )
        val ringBrush = Brush.verticalGradient(
            colors = listOf(Color(0x80FFFFFF), Color(0x55FFFFFF), Color(0x00FFFFFF)),
            startY = c.y - r,
            endY = c.y + r * 0.55f,
        )
        drawCircle(brush = ringBrush, radius = r, center = c, style = Stroke(width = 1.2.dp.toPx()))
        drawCircle(brush = ringBrush, radius = r * 1.10f, center = c, alpha = 0.45f, style = Stroke(width = 1.dp.toPx()))
    }

    Image(
        painter = painterResource(R.drawable.immersive_migo_silhouette),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .offset { IntOffset(leftPx.roundToInt(), topPx.roundToInt()) }
            .requiredSize(with(density) { swPx.toDp() }, with(density) { shPx.toDp() }),
    )
}

@Composable
private fun ImmersiveTopBar(
    onOpenDrawer: () -> Unit,
    captionsOn: Boolean,
    onToggleCaptions: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MigoIcons.Menu,
            contentDescription = "菜单",
            tint = ImmInk,
            modifier = Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenDrawer)
                .padding(12.dp),
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .requiredSize(50.dp)
                .clip(CircleShape)
                .background(Color(0xFFFCFCF8))
                .clickable(onClick = onToggleCaptions)
                .semantics { contentDescription = if (captionsOn) "关闭字幕" else "显示字幕" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MigoIcons.Chat,
                contentDescription = null,
                tint = ImmInk,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun ImmersiveGreeting(
    showWaitingCapsule: Boolean,
    onOpenWaitingTask: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(
            "嗨，我是米果。",
            color = ImmInk,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "今天想聊些什么？",
            color = Color(0xFF7D8877),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (showWaitingCapsule) {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .sizeIn(minHeight = 48.dp)
                    .clip(MigoTokens.PillShape)
                    .clickable(onClick = onOpenWaitingTask)
                    .semantics { contentDescription = "打开等待确认任务" },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "任务 · 等待确认 ›",
                    color = ImmInk,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(MigoTokens.PillShape)
                        .background(Color(0xCCF6F6EF))
                        .border(1.dp, Color(0xFFDADDD0), MigoTokens.PillShape)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun ImmersiveCallStatusRow(
    label: String,
    muted: Boolean,
    ended: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    when {
                        muted -> MigoTokens.Muted
                        ended -> MigoTokens.Error
                        else -> Color(0xFF5F7458)
                    },
                ),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            label,
            color = Color(0xFF5F6B59),
            fontSize = 13.sp,
            modifier = Modifier.semantics { contentDescription = "通话状态：$label" },
        )
    }
}

@Composable
private fun ImmersiveBottomActions(
    micMuted: Boolean,
    onToggleMic: () -> Unit,
    onEnd: () -> Unit,
    onVideo: () -> Unit,
) {
    // 主稿：麦克风 / 结束通话 / 视频通话 三项
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        CircleAction(
            label = if (micMuted) "麦克风关" else "麦克风开",
            bg = Color(0xFFFCFDF8),
            fg = ImmInk,
            icon = if (micMuted) MigoIcons.MicOff else MigoIcons.Mic,
            diameter = 58.dp,
            onClick = onToggleMic,
        )
        CircleAction(
            label = "结束通话",
            bg = Color(0xFFB16A55),
            fg = MigoTokens.White,
            icon = MigoIcons.EndCall,
            diameter = 58.dp,
            onClick = onEnd,
        )
        CircleAction(
            label = "视频通话",
            bg = Color(0xFFFCFDF8),
            fg = ImmInk,
            icon = MigoIcons.Video,
            diameter = 58.dp,
            onClick = onVideo,
        )
    }
}

@Composable
/** vc58：UI 对齐主稿全屏字幕；条目来自 chat 演示，非实时转写流。 */
private fun ImmersiveCaptionsPanel(
    entries: List<com.miguo.voice.conversation.ChatEntry>,
    waitingTask: Task?,
    agentStatus: AgentStatus,
    onDismiss: () -> Unit,
    onOpenVoiceTask: () -> Unit,
    onBackToChat: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xBDF4F5EB))
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "当前通话 · 字幕演示（真流转待接入）",
                    color = Color(0xFF68745D),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "回到会话 ›",
                    color = Color(0xFF526B50),
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable(onClick = onBackToChat)
                        .semantics { contentDescription = "回到会话" }
                        .padding(8.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 13.dp),
            ) {
                if (entries.isEmpty()) {
                    CaptionAssistant("嗨，我是米果。\n今天想聊些什么？")
                    CaptionUser("周六早上九点，\n提醒我准备出行。")
                    val demo = when {
                        agentStatus == AgentStatus.waiting || waitingTask?.status == AgentStatus.waiting ->
                            "好呀，我已经整理好了。\n你确认一下，我再继续。"
                        agentStatus == AgentStatus.done -> "好，这次已经完成。"
                        else -> "我在听。你说完我再整理。"
                    }
                    CaptionAssistant(demo)
                } else {
                    entries.takeLast(12).forEach { e ->
                        if (e.user.isNotBlank()) CaptionUser(e.user)
                        if (e.assistant.isNotBlank()) CaptionAssistant(e.assistant)
                    }
                }
                waitingTask?.let { task ->
                    Spacer(Modifier.height(8.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0x9CFFFFFF))
                            .border(1.dp, Color(0xFFD6DFCB), RoundedCornerShape(13.dp))
                            .padding(horizontal = 15.dp, vertical = 13.dp),
                    ) {
                        Text(task.title, color = MigoTokens.Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "${(task.remindAt ?: "").replace('T', ' ')} · ${task.status.uiChipLabel()}",
                            color = Color(0xFF68765D),
                            fontSize = 11.sp,
                            lineHeight = 20.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (task.status == AgentStatus.waiting) "核对并确认 ›" else "查看任务进展 ›",
                            color = MigoTokens.Primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable(onClick = onOpenVoiceTask)
                                .semantics { contentDescription = "字幕任务入口" }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 18.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFFFBFCF5))
                .clickable(onClick = onDismiss)
                .semantics { contentDescription = "收起全屏对话字幕" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MigoIcons.Close, contentDescription = null, tint = MigoTokens.Ink, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun CaptionAssistant(text: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 25.dp)) {
        Text("米果", color = Color(0xFF818D77), fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
        Text(
            text,
            color = Color(0xFF374331),
            fontSize = 19.sp,
            lineHeight = (19 * 1.9f).sp,
            letterSpacing = 0.2.sp,
        )
    }
}

@Composable
private fun CaptionUser(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 25.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text("你", color = Color(0xFF818D77), fontSize = 10.sp, modifier = Modifier.padding(bottom = 5.dp))
        Text(
            text,
            color = Color(0xFF738169),
            fontSize = 15.sp,
            lineHeight = (15 * 1.9f).sp,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(0.9f),
        )
    }
}

private enum class WaveMode { Flat, ListeningRms, AnsweringSoft }

private const val SILHOUETTE_RATIO = 1517f / 1400f
private const val SIL_LEFT_X = 0.0050f
private const val SIL_TIP_Y = 0.0046f
private const val SIL_BOT_Y = 0.9987f
private const val SIL_EYE_SPACE = 0.2709f
private const val DESIGN_LEFT = 0.311f
private const val DESIGN_SPROUT_TIP = 0.315f
private const val DESIGN_BODY_BOTTOM = 0.738f
private const val DESIGN_EYE_SPACE = 0.254f
private val ImmInk = Color(0xFF4F6352)

@Composable
private fun ImmersiveWaveform(
    mode: WaveMode,
    rms: Float,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "immersiveWave")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )
    // 回答态：无真实 RMS 时用状态脉冲抬幅
    val answerPulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "answerPulse",
    )
    val smoothRms by animateFloatAsState(
        targetValue = rms.coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "rms",
    )
    val lineColor = Color(0xFF8FA184)
    val scales = floatArrayOf(1f, 0.68f, 0.42f)
    val freqs = floatArrayOf(3.2f, 4.3f, 2.5f)
    val offsets = floatArrayOf(0f, 1.4f, 2.7f)
    val alphas = floatArrayOf(0.95f, 0.62f, 0.45f)

    Canvas(modifier) {
        val midY = size.height / 2f
        if (mode == WaveMode.Flat) {
            drawLine(
                color = lineColor.copy(alpha = 0.85f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        val maxAmp = size.height / 2f - 1.dp.toPx()
        val baseAmp = when (mode) {
            WaveMode.ListeningRms -> (5.dp.toPx() + (smoothRms * 14.dp.toPx())).coerceAtMost(maxAmp)
            WaveMode.AnsweringSoft -> (6.dp.toPx() * answerPulse).coerceAtMost(maxAmp)
            WaveMode.Flat -> 0f
        }
        val steps = 96
        for (k in 0 until 3) {
            val path = Path()
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val x = t * size.width
                val d = (t - 0.5f) / 0.24f
                val envelope = 0.82f * exp(-(d * d)) + 0.18f * sin(t * PI).toFloat()
                val dir = if (k == 1) -1f else 1f
                val y = midY + baseAmp * scales[k] * envelope *
                    sin((t * freqs[k] * 2f * PI + dir * phase + offsets[k]).toDouble()).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = lineColor.copy(alpha = alphas[k]),
                style = Stroke(width = (if (k == 0) 1.6f else 1.1f).dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun CircleAction(
    label: String,
    bg: Color,
    fg: Color,
    icon: ImageVector,
    diameter: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .requiredSize(diameter)
                .border(3.dp, Color(0x99FFFFFF), CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable(onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, color = Color(0xFF5F6B59), fontSize = 13.sp)
    }
}
