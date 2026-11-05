package com.miguo.voice.ui.home

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.R
import androidx.compose.ui.layout.ContentScale
import com.miguo.voice.support.PersonaPrefs
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.conversation.ChatEntry
import com.miguo.voice.conversation.ChatMessageKind
import com.miguo.voice.conversation.TextChatState
import com.miguo.voice.conversation.messagePresentation
import com.miguo.voice.ui.AppModel
import com.miguo.voice.ui.components.AssistantMessageBubble
import com.miguo.voice.ui.components.SessionEdgeFailed
import com.miguo.voice.ui.components.SessionEdgeLoading
import com.miguo.voice.ui.components.SessionTaskCard
import com.miguo.voice.BuildConfig
import com.miguo.voice.ui.components.uiChipLabel
import com.miguo.voice.ui.drawer.MigoDrawer
import com.miguo.voice.ui.plan.PlanViewModel
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 定稿主会话页。
 * 产品主路径：idle 空态 → 用户提出需求 → waiting 确认卡 → 确认推进 → 计划页找回。
 * 确认卡只在当前会话存在真实 waiting 时渲染。
 */
@Composable
fun HomeChatScreen(
    model: AppModel,
    planVm: PlanViewModel,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenTaskDetail: (String) -> Unit,
    onOpenImmersive: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenPlan: () -> Unit = {},
    onOpenLife: () -> Unit = {},
) {
    val entries by model.chat.entries.collectAsState()
    val textState by model.text.state.collectAsState()
    val planUi by planVm.ui.collectAsState()
    val sessionId = planUi.primarySessionId
    // 当前会话焦点任务（六态卡）；无非 idle 任务时顶栏「空闲」
    val sessionTasks = planUi.tasks.filter { it.sessionId == sessionId }
    val focusTask = sessionTasks.firstOrNull { it.status == AgentStatus.waiting }
        ?: sessionTasks.firstOrNull { it.status == AgentStatus.running }
        ?: sessionTasks.firstOrNull { it.status == AgentStatus.error }
        ?: sessionTasks.firstOrNull { it.status == AgentStatus.recover }
        ?: sessionTasks.firstOrNull { it.status == AgentStatus.done }
    val waitingTask = focusTask?.takeIf { it.status == AgentStatus.waiting }
    val confirming = planUi.confirming
    val ctx = LocalContext.current

    var drawerOpen by remember { mutableStateOf(false) }
    var personaSheet by remember { mutableStateOf<MigoPersonaSheet?>(null) }
    var auxSheet by remember { mutableStateOf<MigoAuxSheet?>(null) }
    var accountSheet by remember { mutableStateOf<MigoAccountSheet?>(null) }
    var attachOpen by remember { mutableStateOf(false) }
    val personaPrefs = remember { PersonaPrefs(ctx) }
    val settings by model.settings.collectAsState()
    val authState by model.auth.state.collectAsState()
    val demoAccount = personaPrefs.demoAccountName
    val accountName = remember(authState, settings.userId, demoAccount) {
        when {
            demoAccount.isNotBlank() -> demoAccount
            else -> {
                val session = model.auth.session
                when {
                    session != null && session.displayName.isNotBlank() -> session.displayName
                    session != null && session.username.isNotBlank() -> session.username
                    else -> settings.userId.ifBlank { "当前账号" }
                }
            }
        }
    }
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    /**
     * 主会话文字：必须走 TextChatController → ChatApi（真实 CHAT）。
     * 示例 chip 另走 [spawnWaitingDemo]：仅 LocalStore waiting，不伪造助手成功回复。
     */
    fun send() {
        val t = textInput.trim()
        if (t.isEmpty()) return
        if (model.text.send(t)) {
            textInput = ""
            attachOpen = false
        }
    }

    // 文字轮回到 Idle 且本地已有用户消息时，刷新侧栏「最近会话」
    LaunchedEffect(textState, entries.size) {
        if (textState is TextChatState.Idle && entries.any { !it.history && it.user.isNotBlank() }) {
            model.refreshHistory()
        }
    }

    /** 空态示例：创建本地 waiting 六态卡（LocalStore），不冒充 CHAT 成功。 */
    fun spawnWaitingDemo(raw: String) {
        if (!BuildConfig.FEATURE_PLAN) return
        val t = raw.trim()
        if (t.isEmpty()) return
        planVm.createWaitingFromRequest(t)
    }

    LaunchedEffect(entries.size, focusTask?.id) {
        val last = (entries.size + if (waitingTask != null) 2 else 0).coerceAtLeast(0)
        if (last > 0) listState.animateScrollToItem(last)
    }

    Box(Modifier.fillMaxSize().background(MigoTokens.White).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(
                statusLabel = focusTask?.status?.uiChipLabel() ?: "空闲",
                onOpenDrawer = { drawerOpen = true },
                onMigoClick = { personaSheet = if (personaSheet == MigoPersonaSheet.Menu) null else MigoPersonaSheet.Menu },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (entries.isEmpty()) {
                    item {
                        when {
                            planUi.loading -> SessionEdgeLoading()
                            planUi.pageFailed -> SessionEdgeFailed(
                                onReload = { planVm.refresh() },
                                onBack = { planVm.clearPageFailed() },
                            )
                            else -> EmptyChatHints(
                                onExample = { tip -> spawnWaitingDemo(tip) },
                                onOpenLife = onOpenLife,
                                showDemoExample = BuildConfig.IS_INTERNAL && BuildConfig.FEATURE_PLAN,
                            )
                        }
                    }
                } else {
                    items(entries, key = { it.key }) { entry ->
                        ChatEntryBlock(entry)
                    }
                }

                if (BuildConfig.FEATURE_PLAN && focusTask != null && focusTask.status != AgentStatus.idle) {
                    item(key = "task-card-${focusTask.id}-${focusTask.status}") {
                        SessionTaskCard(
                            task = focusTask,
                            confirming = confirming,
                            onConfirm = {
                                val t = planVm.confirmWaiting(focusTask.id, true)
                                if (t != null) toast("已确认创建")
                                else toast(planVm.ui.value.error ?: "确认失败")
                            },
                            onModify = {
                                auxSheet = MigoAuxSheet.Edit(focusTask)
                            },
                            onCancel = {
                                auxSheet = MigoAuxSheet.Cancel(focusTask)
                            },
                            onStop = {
                                planVm.stopRunning(focusTask.id)
                                toast("已停止任务")
                            },
                            onRetry = {
                                planVm.retryTask(focusTask.id)
                                toast("已重试")
                            },
                            onRestart = {
                                planVm.restartAfterReview(focusTask.id)
                                toast("核对后重新开始：请再次确认")
                            },
                            onViewResult = {
                                auxSheet = MigoAuxSheet.Result(focusTask)
                            },
                            onViewDetail = { onOpenTaskDetail(focusTask.id) },
                        )
                    }
                    item(key = "actions-${focusTask.id}") {
                        MessageActionRow(
                            onSpeak = { toast("朗读待接入") },
                            onCopy = { toast("已复制") },
                            onShare = { toast("转发待接入") },
                            onStar = { toast("收藏待接入") },
                            onRefresh = { toast("重新回答待接入") },
                        )
                    }
                }

                if (textState is TextChatState.Failed) {
                    item {
                        Text(
                            (textState as TextChatState.Failed).message,
                            color = MigoTokens.Error,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { model.text.dismissError() }
                                .padding(4.dp),
                        )
                    }
                }
            }

            if (attachOpen) {
                AttachPanel(
                    onCamera = {
                        attachOpen = false
                        auxSheet = MigoAuxSheet.Pending(
                            title = "能力待接入",
                            body = "该入口展示预期交互。当前未连接相机，暂无真实执行能力，不会模拟上传成功。",
                        )
                    },
                    onAlbum = {
                        attachOpen = false
                        auxSheet = MigoAuxSheet.Pending(
                            title = "能力待接入",
                            body = "该入口展示预期交互。当前未连接相册，暂无真实执行能力，不会模拟上传成功。",
                        )
                    },
                    onFile = {
                        attachOpen = false
                        auxSheet = MigoAuxSheet.Pending(
                            title = "能力待接入",
                            body = "该入口展示预期交互。当前未连接文件解析，暂无真实执行能力，不会模拟上传成功。",
                        )
                    },
                    onCall = {
                        attachOpen = false
                        if (BuildConfig.FEATURE_VOICE) onOpenImmersive()
                        else auxSheet = MigoAuxSheet.Pending(
                            title = "能力待接入",
                            body = "沉浸语音未启用。持续通话能力需 FEATURE_VOICE。",
                        )
                    },
                )
            }

            HomeInputBar(
                value = textInput,
                onValueChange = { textInput = it },
                busy = textState is TextChatState.Sending,
                attachOpen = attachOpen,
                onSend = { send() },
                onAttach = { attachOpen = !attachOpen },
                onVoiceClick = {
                    if (!BuildConfig.FEATURE_VOICE) {
                        auxSheet = MigoAuxSheet.Pending(
                            title = "能力待接入",
                            body = "语音入口未启用。录一条语音消息需 FEATURE_VOICE。",
                        )
                    } else {
                        auxSheet = MigoAuxSheet.VoiceInput
                    }
                },
                onCamera = {
                    auxSheet = MigoAuxSheet.Pending(
                        title = "能力待接入",
                        body = "该入口展示预期交互。当前未连接相机，暂无真实执行能力。",
                    )
                },
            )
        }

        personaSheet?.let { sheet ->
            MigoPersonaFlow(
                sheet = sheet,
                accountName = accountName,
                prefs = personaPrefs,
                onSheetChange = { personaSheet = it },
                onRequestRealLogin = {
                    model.logout(everywhere = false)
                    model.showLogin()
                    toast("已退出当前身份，请登录其他账号")
                },
            )
        }

        accountSheet?.let { sheet ->
            MigoAccountSheetHost(
                sheet = sheet,
                demoAccountName = accountName,
                onDismiss = { accountSheet = null },
                onSaveDemoAccount = { personaPrefs.demoAccountName = it },
                onOpenProfile = onOpenProfile,
                onOpenSettings = onOpenSettings,
            )
        }

        auxSheet?.let { sheet ->
            MigoAuxSheetHost(
                sheet = sheet,
                onDismiss = { auxSheet = null },
                onConfirmCancel = { task ->
                    when (task.status) {
                        AgentStatus.waiting -> {
                            val t = planVm.cancelWaiting(task.id)
                            if (t == null) toast(planVm.ui.value.error ?: "取消失败")
                        }
                        else -> planVm.cancelTask(task.id)
                    }
                    auxSheet = null
                },
                onSaveEdit = { task, title, time ->
                    val normalized = time.replace(' ', 'T')
                    val t = planVm.editWaitingContent(task.id, remindAt = normalized, title = title)
                    if (t == null) toast(planVm.ui.value.error ?: "修改失败")
                    auxSheet = null
                },
                onOpenPlans = onOpenPlan,
                onInsertVoiceDemo = { demo ->
                    textInput = demo
                },
            )
        }

        if (drawerOpen) {
            MigoDrawer(
                model = model,
                onDismiss = { drawerOpen = false },
                onNewConversation = {
                    drawerOpen = false
                    model.newConversation()
                    planVm.beginFreshSession()
                    toast("已开始新对话")
                },
                onOpenMainChat = { drawerOpen = false },
                onOpenVault = {
                    drawerOpen = false
                    onOpenVault()
                },
                onOpenDevice = {
                    drawerOpen = false
                    onOpenDevice()
                },
                onOpenSettings = {
                    drawerOpen = false
                    onOpenSettings()
                },
                onOpenPlan = {
                    drawerOpen = false
                    if (BuildConfig.FEATURE_PLAN) onOpenPlan() else toast("计划未启用")
                },
                onScan = {
                    auxSheet = MigoAuxSheet.Pending(
                        title = "扫一扫",
                        body = "该入口展示预期交互。当前未连接相机或扫码服务，暂无真实执行能力。",
                    )
                },
                onNotify = { accountSheet = MigoAccountSheet.Notifications },
                onProfile = {
                    drawerOpen = false
                    onOpenProfile()
                },
            )
        }
    }
}

@Composable
private fun HomeTopBar(
    statusLabel: String,
    onOpenDrawer: () -> Unit,
    onMigoClick: () -> Unit,
) {
    // 主稿 .home-topbar：高约 108、顶垫 23；左圆形菜单，右进球 42×47 + 状态点
    Row(
        Modifier
            .fillMaxWidth()
            .height(108.dp)
            .padding(start = 18.dp, end = 18.dp, top = 23.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(1.dp, MigoTokens.LineSolid, CircleShape)
                .background(MigoTokens.White)
                .clickable(onClick = onOpenDrawer)
                .semantics { contentDescription = "打开侧边栏" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MigoIcons.Menu,
                contentDescription = null,
                tint = MigoTokens.Ink,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onMigoClick)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { contentDescription = "米果个性化设置与切换账号" },
        ) {
            Image(
                painter = painterResource(R.drawable.migo),
                contentDescription = "米果形象",
                modifier = Modifier.size(width = 42.dp, height = 47.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            when (statusLabel) {
                                "等待确认" -> MigoTokens.WaitingText
                                "空闲" -> MigoTokens.Primary
                                else -> MigoTokens.Muted
                            },
                        ),
                )
                Spacer(Modifier.width(5.dp))
                Text(statusLabel, color = MigoTokens.Primary, fontSize = 9.sp)
            }
        }
    }
}

@Composable
internal fun ChatEntryBlock(entry: ChatEntry) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (msg in entry.messagePresentation()) {
            when (msg.kind) {
                ChatMessageKind.USER -> UserBubble(msg.text, meta = "你 · 刚刚")
                ChatMessageKind.ASSISTANT, ChatMessageKind.PENDING ->
                    AssistantMessageBubble(msg.text, showAiGeneratedLabel = msg.showAiGeneratedLabel)
                ChatMessageKind.ERROR ->
                    Text(msg.text, color = MigoTokens.Error, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, meta: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (meta != null) {
            Text(meta, color = MigoTokens.Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp, end = 4.dp))
        }
        Text(
            text,
            color = MigoTokens.Ink,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(MigoTokens.UserBubble, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun EmptyChatHints(onExample: (String) -> Unit, onOpenLife: () -> Unit = {}, showDemoExample: Boolean = true) {
    // 主稿 .welcome：h3 24/1.5/500；p 13/1.8 muted；.suggestion sage + 底部分割线
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 16.dp, start = 4.dp, end = 4.dp)
            .semantics { contentDescription = "主会话空态欢迎" },
    ) {
        Text(
            "嗨，我是米果。\n今天想做些什么？",
            color = MigoTokens.Ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 36.sp,
        )
        Text(
            "有什么想聊的，直接告诉我。",
            color = MigoTokens.Muted,
            fontSize = 13.sp,
            lineHeight = 23.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        if (showDemoExample) {
            Text(
                "试试：周六早上提醒我准备出行 →",
                color = MigoTokens.Primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExample("周六早上提醒我准备出行") }
                    .padding(vertical = 15.dp)
                    .semantics { contentDescription = "示例需求" },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
        }
        Text(
            "看看生活服务 →",
            color = MigoTokens.Primary,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenLife)
                .padding(vertical = 15.dp)
                .semantics { contentDescription = "看看生活服务" },
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.LineSolid))
    }
}

@Composable
private fun MessageActionRow(
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onStar: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(MigoIcons.Volume, "朗读", onSpeak)
        ActionIcon(MigoIcons.Copy, "复制", onCopy)
        ActionIcon(MigoIcons.Share, "转发", onShare)
        ActionIcon(MigoIcons.Star, "收藏", onStar)
        ActionIcon(MigoIcons.Refresh, "重新回答", onRefresh)
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = MigoTokens.Muted,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(onClick = onClick)
            .padding(13.dp)
            .size(22.dp)
            .semantics { contentDescription = label },
    )
}

@Composable
private fun AttachPanel(
    onCamera: () -> Unit,
    onAlbum: () -> Unit,
    onFile: () -> Unit,
    onCall: () -> Unit,
) {
    // 主稿 .attachpanel：4 列 tile 45×45 / 圆角 19 / 背景 #f5f7f1
    val items = buildList {
        add(Triple("拍照", MigoIcons.Camera, onCamera))
        add(Triple("相册", MigoIcons.Image, onAlbum))
        add(Triple("本地文件", MigoIcons.File, onFile))
        if (BuildConfig.FEATURE_VOICE) add(Triple("打电话", MigoIcons.Phone, onCall))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(19.dp))
            .border(1.dp, MigoTokens.LineSolid, RoundedCornerShape(19.dp))
            .background(Color(0xFFF5F7F1))
            .padding(horizontal = 10.dp, vertical = 16.dp)
            .semantics { contentDescription = "附件面板" },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEach { (label, icon, action) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(onClick = action)
                        .semantics { contentDescription = label }
                        .padding(horizontal = 6.dp),
                ) {
                    Box(
                        Modifier
                            .size(45.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .border(1.dp, Color(0xFFE5EADF), RoundedCornerShape(13.dp))
                            .background(MigoTokens.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = MigoTokens.Ink, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(label, color = MigoTokens.Ink, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun HomeInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    attachOpen: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onVoiceClick: () -> Unit,
    onCamera: () -> Unit,
) {
    // 主稿 .composer：白底描边胶囊；左波形 / 输入 / 相机 / +或发送；下方 inputhint
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 55.dp)
                .border(1.dp, Color(0xFFDFE4DA), RoundedCornerShape(28.dp))
                .background(MigoTokens.White, RoundedCornerShape(28.dp))
                .padding(horizontal = 7.dp, vertical = 5.dp)
                .semantics { contentDescription = "发送消息" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 策略A：删除输入栏语音波形
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                enabled = !busy,
                textStyle = TextStyle(color = MigoTokens.Ink, fontSize = 12.sp),
                cursorBrush = SolidColor(MigoTokens.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .semantics { contentDescription = "消息内容" },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text("发消息…", color = Color(0xFF8A9387), fontSize = 12.sp)
                    }
                    inner()
                },
            )
            // 策略A：隐藏相机
            val canSend = value.isNotBlank()
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(33.dp)
                    .clip(CircleShape)
                    .background(if (canSend) MigoTokens.Primary else Color.Transparent)
                    .clickable {
                        if (canSend) onSend()
                        // 策略A：隐藏附件+
                    }
                    .semantics {
                        contentDescription = when {
                            canSend -> "发送"
                            attachOpen -> "收起附件"
                            else -> "添加附件"
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (canSend) MigoIcons.Send else MigoIcons.Add,
                    contentDescription = null,
                    tint = if (canSend) MigoTokens.White else MigoTokens.Ink,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
