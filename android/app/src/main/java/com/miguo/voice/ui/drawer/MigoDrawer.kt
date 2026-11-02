package com.miguo.voice.ui.drawer

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miguo.voice.protocol.ConversationSummary
import com.miguo.voice.ui.AppModel
import com.miguo.voice.ui.theme.MigoIcons
import com.miguo.voice.ui.theme.MigoTokens

/**
 * 定稿侧栏：替换默认 PersonalDrawer 遗留入口。
 * 侧栏多选：置顶/删除目前占位——TextChatController/ChatApi 仅有 listConversations，无 pin/delete；
 * LocalStore.deleteSession 属于本地任务演示会话，不接抽屉历史。
 * 入口仅：主会话 / 个人仓库 / 设备；设置在头部齿轮。
 * 「最近会话」数据源：AppModel.historyItems（ChatApi listConversations），
 * 不再用 LocalStore.listSessions（计划域空主会话，不会随 CHAT 发消息更新）。
 * 使用 Dialog 全屏覆盖底栏。
 */
@Composable
fun MigoDrawer(
    model: AppModel,
    onDismiss: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenMainChat: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlan: () -> Unit = {},
    onScan: () -> Unit = {},
    onNotify: () -> Unit = {},
    onProfile: () -> Unit = {},
) {
    BackHandler(onBack = onDismiss)

    LaunchedEffect(Unit) {
        model.refreshHistory()
    }

    val history by model.historyItems.collectAsState()
    val historyBusy by model.historyBusy.collectAsState()
    val historyErr by model.historyError.collectAsState()
    val currentId by model.conversationId.collectAsState()
    val chatEntries by model.chat.entries.collectAsState()

    var query by remember { mutableStateOf("") }
    var expandAll by remember { mutableStateOf(false) }
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val drawerCtx = LocalContext.current
    fun drawerToast(msg: String) = Toast.makeText(drawerCtx, msg, Toast.LENGTH_SHORT).show()

    val sessions = remember(history, currentId, chatEntries) {
        mergeRecentSessions(history, currentId, chatEntries)
    }
    val filtered = sessions.filter { row ->
        query.isBlank() ||
            row.title.contains(query, ignoreCase = true) ||
            (row.preview?.contains(query, ignoreCase = true) == true)
    }
    val recent = if (expandAll) filtered else filtered.take(3)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MigoTokens.Scrim)
                .clickable(onClick = onDismiss),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
                    .align(Alignment.CenterStart)
                    .background(MigoTokens.White)
                    .clickable(enabled = false) {}
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Migo", color = MigoTokens.Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    // 策略A：隐藏扫一扫
                    HeaderIcon(MigoIcons.Bell, "通知", onNotify)
                    HeaderIcon(MigoIcons.Settings, "设置", onOpenSettings)
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .border(1.dp, MigoTokens.Line, RoundedCornerShape(12.dp))
                        .clickable(onClick = onNewConversation)
                        .semantics { contentDescription = "新对话" }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MigoIcons.Add, contentDescription = null, tint = MigoTokens.Ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("新对话", color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .background(MigoTokens.AssistantBubble, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MigoIcons.Search, contentDescription = null, tint = MigoTokens.Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MigoTokens.Ink, fontSize = 14.sp),
                        cursorBrush = SolidColor(MigoTokens.Primary),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "搜索对话" },
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("搜索对话", color = MigoTokens.Muted, fontSize = 14.sp)
                            inner()
                        },
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("最近会话", color = MigoTokens.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        if (selectMode) "完成" else "选择",
                        color = MigoTokens.Primary,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clickable {
                                selectMode = !selectMode
                                if (!selectMode) selectedIds = emptySet()
                            }
                            .padding(3.dp)
                            .semantics { contentDescription = if (selectMode) "完成多选" else "多选会话" },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (selectMode && selectedIds.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "置顶",
                            color = MigoTokens.Primary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable {
                                    drawerToast("暂不支持置顶会话")
                                }
                                .semantics { contentDescription = "置顶所选会话" },
                        )
                        Text(
                            "删除",
                            color = MigoTokens.Danger,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable {
                                    drawerToast("暂不支持删除会话")
                                }
                                .semantics { contentDescription = "删除所选会话" },
                        )
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        historyBusy && filtered.isEmpty() -> {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    color = MigoTokens.Primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        historyErr != null && filtered.isEmpty() -> {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(historyErr ?: "", color = MigoTokens.Muted, fontSize = 12.sp)
                                Text(
                                    "重试",
                                    color = MigoTokens.Primary,
                                    fontSize = 13.sp,
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .clickable { model.refreshHistory() }
                                        .semantics { contentDescription = "重试加载会话" },
                                )
                            }
                        }
                        filtered.isEmpty() -> {
                            Text(
                                "发消息后会出现在这里",
                                color = MigoTokens.Muted,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .padding(vertical = 8.dp)
                                    .semantics { contentDescription = "最近会话为空" },
                            )
                        }
                        else -> {
                            recent.forEach { row ->
                                SessionRow(
                                    title = row.title,
                                    preview = row.preview,
                                    selected = if (selectMode) row.id in selectedIds else row.id == currentId,
                                    onClick = {
                                        if (selectMode) {
                                            selectedIds = if (row.id in selectedIds) selectedIds - row.id else selectedIds + row.id
                                        } else {
                                            model.openConversation(row.id)
                                            onDismiss()
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (filtered.isNotEmpty()) {
                        Text(
                            if (expandAll) "收起会话" else "查看全部 >",
                            color = MigoTokens.Primary,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .align(Alignment.End)
                                .clickable { expandAll = !expandAll }
                                .semantics { contentDescription = if (expandAll) "收起会话" else "查看全部" }
                                .padding(vertical = 8.dp),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    NavRow(icon = MigoIcons.Chat, label = "主会话", selected = true, onClick = onOpenMainChat)
                    Spacer(Modifier.height(8.dp))
                    NavRow(icon = MigoIcons.Device, label = "设备", selected = false, onClick = onOpenDevice)
                }

                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MigoTokens.Line))
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onProfile)
                        .semantics { contentDescription = "个人主页" }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MigoTokens.AssistantBubble),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("我", color = MigoTokens.Ink, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("个人主页", color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("我的资料与个人偏好", color = MigoTokens.Muted, fontSize = 12.sp)
                    }
                    Icon(MigoIcons.More, contentDescription = null, tint = MigoTokens.Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * 合并服务端会话列表与「当前主会话本地已有用户消息」兜底：
 * 发消息后即使 listConversations 尚未返回该项，侧栏最近会话也能反映主会话活动。
 */
internal fun mergeRecentSessions(
    history: List<ConversationSummary>,
    currentId: String?,
    chatEntries: List<com.miguo.voice.conversation.ChatEntry>,
): List<DrawerSessionItem> {
    val fromApi = history.map { row ->
        val title = row.title?.takeIf { it.isNotBlank() }
            ?: row.preview?.take(18)?.takeIf { it.isNotBlank() }
            ?: "会话"
        DrawerSessionItem(
            id = row.id,
            title = title,
            preview = row.preview?.takeIf { it.isNotBlank() && it != title },
        )
    }
    val hasLocalUserMsg = chatEntries.any { !it.history && it.user.isNotBlank() }
    if (currentId.isNullOrBlank() || !hasLocalUserMsg) return fromApi
    if (fromApi.any { it.id == currentId }) return fromApi
    val lastUser = chatEntries.lastOrNull { it.user.isNotBlank() }?.user?.trim().orEmpty()
    val title = lastUser.take(18).ifBlank { "主会话" }
    val local = DrawerSessionItem(
        id = currentId,
        title = title,
        preview = lastUser.takeIf { it.isNotBlank() && it != title },
    )
    return listOf(local) + fromApi
}

internal data class DrawerSessionItem(
    val id: String,
    val title: String,
    val preview: String?,
)

@Composable
private fun HeaderIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MigoTokens.Ink,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SessionRow(
    title: String,
    preview: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MigoTokens.UserBubble else MigoTokens.White)
            .clickable(onClick = onClick)
            .semantics { contentDescription = title }
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            color = MigoTokens.Ink,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!preview.isNullOrBlank()) {
            Text(
                preview,
                color = MigoTokens.Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MigoTokens.UserBubble else MigoTokens.White)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MigoTokens.Ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = MigoTokens.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(MigoIcons.ChevronRight, contentDescription = null, tint = MigoTokens.Muted, modifier = Modifier.size(16.dp))
    }
}