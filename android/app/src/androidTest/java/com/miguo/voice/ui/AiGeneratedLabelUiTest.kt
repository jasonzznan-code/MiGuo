package com.miguo.voice.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.conversation.AiGeneratedLabel
import com.miguo.voice.conversation.ChatEntry
import com.miguo.voice.ui.components.TimelineMessageText
import com.miguo.voice.ui.home.ChatEntryBlock
import com.miguo.voice.ui.immersive.ImmersiveCaptionMessages
import com.miguo.voice.ui.theme.MigoTokens
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在已连接设备上渲染生产气泡。截图写到应用私有目录，由 adb 拉出。
 */
@RunWith(AndroidJUnit4::class)
class AiGeneratedLabelUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun labeledAssistantStates() {
        composeRule.setContent {
            PhoneColumn {
                Section("历史恢复") {
                    ChatEntryBlock(ChatEntry(key = "h", user = "我叫杰森", assistant = "你好杰森", history = true))
                }
                Section("流式片段") {
                    ChatEntryBlock(ChatEntry(key = "s", user = "在吗", assistant = "你好", pending = true, userSource = "text"))
                }
                Section("生成完成") {
                    ChatEntryBlock(ChatEntry(key = "d", user = "第二句", assistant = "你好呀", pending = false, userSource = "text"))
                }
                Section("重试替换") {
                    ChatEntryBlock(ChatEntry(key = "r", user = "再答一次", assistant = "新的回答", pending = false))
                }
            }
        }
        composeRule.onNodeWithText("你好杰森").assertExists()
        composeRule.onNodeWithText("你好").assertExists()
        composeRule.onNodeWithText("你好呀").assertExists()
        composeRule.onNodeWithText("新的回答").assertExists()
        composeRule.onNodeWithText("旧回答").assertDoesNotExist()
        composeRule.onNodeWithText("你好呀${AiGeneratedLabel.TEXT}").assertDoesNotExist()
        composeRule.onAllNodesWithText(AiGeneratedLabel.TEXT).assertCountEquals(4)
        savePng("compose_chat_labeled_states.png")
    }

    @Test
    fun userThinkingAndErrorsAreUnlabeled() {
        composeRule.setContent {
            PhoneColumn {
                Section("用户消息") {
                    ChatEntryBlock(ChatEntry(key = "u", user = "这是我的话"))
                }
                Section("思考中") {
                    ChatEntryBlock(ChatEntry(key = "p", user = "等一下", assistant = "", pending = true, userSource = "text"))
                }
                Section("错误提示") {
                    ChatEntryBlock(ChatEntry(key = "e", user = "登录试试", assistant = "", failed = "登录已失效，请重新登录"))
                }
                Section("半句加错误") {
                    ChatEntryBlock(ChatEntry(key = "pe", user = "问", assistant = "半句", failed = "这一轮处理失败"))
                }
            }
        }
        composeRule.onNodeWithText("这是我的话").assertExists()
        composeRule.onNodeWithText(AiGeneratedLabel.PENDING_PLACEHOLDER).assertExists()
        composeRule.onNodeWithText("登录已失效，请重新登录").assertExists()
        composeRule.onNodeWithText("半句").assertExists()
        composeRule.onNodeWithText("这一轮处理失败").assertExists()
        composeRule.onAllNodesWithText(AiGeneratedLabel.TEXT).assertCountEquals(1)
        savePng("compose_chat_unlabeled_states.png")
    }

    @Test
    fun immersiveRealAssistantCaptionIsLabeled() {
        composeRule.setContent {
            PhoneColumn(background = Color(0xFFF4F5EB)) {
                ImmersiveCaptionMessages(
                    entries = listOf(ChatEntry(key = "v1", user = "周六早上九点提醒我", assistant = "好的，我记下了")),
                    agentStatus = AgentStatus.idle,
                    waitingTask = null,
                )
            }
        }
        composeRule.onNodeWithText("好的，我记下了").assertExists()
        composeRule.onNodeWithText("周六早上九点提醒我").assertExists()
        composeRule.onNodeWithText("嗨，我是米果。\n今天想聊些什么？").assertDoesNotExist()
        composeRule.onAllNodesWithText(AiGeneratedLabel.TEXT).assertCountEquals(1)
        savePng("compose_immersive_real_caption.png")
    }

    @Test
    fun immersiveDemoScriptIsNotLabeled() {
        composeRule.setContent {
            PhoneColumn(background = Color(0xFFF4F5EB)) {
                ImmersiveCaptionMessages(entries = emptyList(), agentStatus = AgentStatus.idle, waitingTask = null)
            }
        }
        composeRule.onNodeWithText("嗨，我是米果。\n今天想聊些什么？").assertExists()
        composeRule.onNodeWithText("我在听。你说完我再整理。").assertExists()
        composeRule.onAllNodesWithText(AiGeneratedLabel.TEXT).assertCountEquals(0)
        savePng("compose_immersive_demo_unlabeled.png")
    }

    @Test
    fun planTimelineLabelsAssistantRoleOnly() {
        composeRule.setContent {
            PhoneColumn {
                TimelineMessageText("user", "周六早上九点提醒我准备出行。")
                TimelineMessageText("assistant", "好呀，我先把内容整理好了。你确认一下，我再继续。")
                TimelineMessageText("system", "【联调】已注入等待确认。")
            }
        }
        composeRule.onNodeWithText("周六早上九点提醒我准备出行。").assertExists()
        composeRule.onNodeWithText("好呀，我先把内容整理好了。你确认一下，我再继续。").assertExists()
        composeRule.onNodeWithText("【联调】已注入等待确认。").assertExists()
        composeRule.onAllNodesWithText(AiGeneratedLabel.TEXT).assertCountEquals(1)
        savePng("compose_plan_timeline_roles.png")
    }

    private fun savePng(name: String) {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val out = File(dir, name)
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(out.length() > 0L) { "截图没有写出来：$out" }
    }
}

@androidx.compose.runtime.Composable
private fun PhoneColumn(
    background: Color = MigoTokens.White,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    Box(
        Modifier
            .width(360.dp)
            .background(background)
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = { content() })
    }
}

@androidx.compose.runtime.Composable
private fun Section(
    title: String,
    content: @androidx.compose.runtime.Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = MigoTokens.Muted, fontSize = 11.sp)
        content()
    }
}
