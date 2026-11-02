package com.miguo.voice.ui.shell

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.miguo.voice.BuildConfig
import com.miguo.voice.ui.AppModel
import com.miguo.voice.ui.MigoSettingsScreen
import com.miguo.voice.ui.profile.ProfileScreen
import com.miguo.voice.ui.device.DeviceScreen
import com.miguo.voice.ui.home.HomeChatScreen
import com.miguo.voice.ui.immersive.ImmersiveVoiceScreen
import com.miguo.voice.ui.life.LifeDetailScreen
import com.miguo.voice.ui.life.LifeServicesScreen
import com.miguo.voice.ui.plan.PlanScreen
import com.miguo.voice.ui.plan.PlanViewModel
import com.miguo.voice.ui.plan.TaskDetailScreen
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens
import com.miguo.voice.ui.vault.VaultScreen
import kotlinx.coroutines.launch

enum class MainTab { Chat, Plan, Life }

sealed class ShellRoute {
    data object Tabs : ShellRoute()
    data class TaskDetail(val taskId: String) : ShellRoute()
    data object Vault : ShellRoute()
    data object Device : ShellRoute()
    data object Settings : ShellRoute()
    data object Profile : ShellRoute()
    data object Immersive : ShellRoute()
    data object LifeDetail : ShellRoute()
}

/**
 * 登录后唯一主壳。默认路由只进定稿 HomeChatScreen，禁止进入旧 Zen/ConversationScreen。
 */
@Composable
fun MigoShell(
    model: AppModel,
    planVm: PlanViewModel = remember { PlanViewModel() },
) {
    var tab by remember { mutableStateOf(MainTab.Chat) }
    var route by remember { mutableStateOf<ShellRoute>(ShellRoute.Tabs) }
    val ctx = LocalContext.current
    val planUi by planVm.ui.collectAsState()
    val scope = rememberCoroutineScope()

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    BackHandler(enabled = route !is ShellRoute.Tabs) { route = ShellRoute.Tabs }

    Box(Modifier.fillMaxSize().background(MigoTokens.White)) {
        when (val r = route) {
            is ShellRoute.Settings -> {
                MigoSettingsScreen(
                    model = model,
                    onDone = {
                        route = ShellRoute.Tabs
                        scope.launch {
                            model.refreshConfig()
                            model.refreshHealth(loadStt = true)
                        }
                    },
                    onLogoutCurrent = { model.logout(everywhere = false) },
                    onLogoutEverywhere = { model.logout(everywhere = true) },
                )
            }
            is ShellRoute.Profile -> {
                ProfileScreen(
                    onBack = { route = ShellRoute.Tabs },
                    onBackHome = { route = ShellRoute.Tabs; tab = MainTab.Chat },
                )
            }
            is ShellRoute.Vault -> VaultScreen(onBack = { route = ShellRoute.Tabs }, onNotice = ::toast)
            is ShellRoute.Device -> DeviceScreen(
                model = model,
                onBack = { route = ShellRoute.Tabs },
                onNotice = { msg -> if (msg == "OPEN_ACCOUNT_DELETION") { /* opened via settings/legal */ toast("请到 设置 → 隐私、开源与注销 办理注销") } else toast(msg) },
                onLogoutCurrent = { model.logout(everywhere = false) },
                onLogoutEverywhere = { model.logout(everywhere = true) },
            )
            is ShellRoute.TaskDetail -> TaskDetailScreen(
                vm = planVm,
                taskId = r.taskId,
                onBackToPlan = {
                    tab = MainTab.Plan
                    route = ShellRoute.Tabs
                    planVm.refresh()
                },
                onBackToConversation = { _ ->
                    tab = MainTab.Chat
                    route = ShellRoute.Tabs
                    planVm.refresh()
                },
                onModify = { toast("请修改内容或时间后重新确认") },
            )
            is ShellRoute.Immersive -> {
                if (!BuildConfig.FEATURE_VOICE) {
                    route = ShellRoute.Tabs
                    tab = MainTab.Chat
                } else {
                    ImmersiveVoiceScreen(
                        model = model,
                        agentStatus = if (planVm.waitingTaskForCurrentSession() != null) planUi.agentStatus else com.miguo.voice.adapters.local.AgentStatus.idle,
                        waitingTask = planVm.waitingTask(),
                        onOpenDrawer = { route = ShellRoute.Tabs; tab = MainTab.Chat },
                        onShowTranscript = { toast("字幕层已切换（转写流待接入）") },
                        onEndToChat = { route = ShellRoute.Tabs; tab = MainTab.Chat },
                        onOpenWaitingTask = {
                            planVm.waitingTask()?.id?.let { route = ShellRoute.TaskDetail(it) }
                                ?: toast("当前没有等待确认的任务")
                        },
                        onVideoNotice = { if (BuildConfig.IS_INTERNAL) toast("视频通话待接入：未调用摄像头，不模拟已接通") },
                    )
                }
            }
            is ShellRoute.LifeDetail -> {
                LifeDetailScreen(
                    planVm = planVm,
                    onBackToServices = {
                        tab = MainTab.Life
                        route = ShellRoute.Tabs
                    },
                    onOpenTaskDetail = { id -> route = ShellRoute.TaskDetail(id) },
                    onShowNotice = ::toast,
                )
            }
            is ShellRoute.Tabs -> {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // 主会话始终组合，切 Tab 时叠层，避免重建丢失输入。
                        HomeChatScreen(
                            model = model,
                            planVm = planVm,
                            onOpenSettings = { route = ShellRoute.Settings },
                            onOpenProfile = { route = ShellRoute.Profile },
                            onOpenTaskDetail = { id -> route = ShellRoute.TaskDetail(id) },
                            onOpenImmersive = { if (BuildConfig.FEATURE_VOICE) route = ShellRoute.Immersive else toast("沉浸语音未启用") },
                            onOpenVault = { route = ShellRoute.Vault },
                            onOpenDevice = { route = ShellRoute.Device },
                            onOpenPlan = {
                                if (BuildConfig.FEATURE_PLAN) {
                                    tab = MainTab.Plan
                                    planVm.refresh()
                                } else toast("计划未启用")
                            },
                            onOpenLife = { tab = MainTab.Life },
                        )
                        if (tab != MainTab.Chat) {
                            Box(Modifier.fillMaxSize().background(MigoTokens.White)) {
                                when (tab) {
                                    MainTab.Plan -> {
                                        if (!BuildConfig.FEATURE_PLAN) {
                                            tab = MainTab.Chat
                                        } else {
                                            PlanScreen(
                                                vm = planVm,
                                                onOpenTask = { id ->
                                                    planVm.selectTask(id)
                                                    route = ShellRoute.TaskDetail(id)
                                                },
                                                onBackToChat = { tab = MainTab.Chat },
                                                onNewPlan = { toast("新建计划：请在主会话提出需求后生成确认卡") },
                                            )
                                        }
                                    }
                                    MainTab.Life -> LifeServicesScreen(
                                        onOpenLifeDetail = { route = ShellRoute.LifeDetail },
                                        onBackToChat = { tab = MainTab.Chat },
                                        onShowNotice = ::toast,
                                    )
                                    MainTab.Chat -> Unit
                                }
                            }
                        }
                    }
                    MigoBottomBar(
                        tab = tab,
                        onSelect = {
                            if (it == MainTab.Plan && !BuildConfig.FEATURE_PLAN) return@MigoBottomBar
                            tab = it
                            if (it == MainTab.Plan) planVm.refresh()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun MigoBottomBar(tab: MainTab, onSelect: (MainTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 10.dp)
            .shadow(10.dp, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .background(MigoTokens.White)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabIcon(
            icon = MigoIcons.Home,
            label = "主会话",
            selected = tab == MainTab.Chat,
            onClick = { onSelect(MainTab.Chat) },
        )
        if (BuildConfig.FEATURE_PLAN) {
            TabIcon(
                icon = MigoIcons.Plan,
                label = "计划",
                selected = tab == MainTab.Plan,
                onClick = { onSelect(MainTab.Plan) },
            )
        }
        TabIcon(
            icon = MigoIcons.Leaf,
            label = "生活",
            selected = tab == MainTab.Life,
            onClick = { onSelect(MainTab.Life) },
        )
    }
}

@Composable
private fun TabIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 64.dp, height = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) MigoTokens.Primary else MigoTokens.White)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) MigoTokens.White else MigoTokens.Muted,
            modifier = Modifier.size(22.dp),
        )
    }
}
