package com.miguo.voice.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.miguo.voice.adapters.local.AgentStatus
import com.miguo.voice.adapters.local.Task
import com.miguo.voice.adapters.local.TaskCategory
import com.miguo.voice.adapters.local.TaskCheckpoint
import com.miguo.voice.feature.demo.PlanDemoScreen
import com.miguo.voice.ui.plan.PlanViewModel
import org.json.JSONObject
import java.time.Instant

/**
 * 仅 internal 源集：设置页「调试 · UI对齐」入口。
 * - 计划演示（PlanDemoScreen，六态联调）
 * - join_debug 注入 waiting / retryable error / recover（走 LocalStore 真实契约，非假完成）
 * store 源集同名函数为空实现：上架包不含此入口文案、不含 join_debug 资产。
 */
@Composable
fun FlavorDebugSection() {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    var showPlanDemo by remember { mutableStateOf(false) }

    if (showPlanDemo) {
        Dialog(
            onDismissRequest = { showPlanDemo = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            BackHandler { showPlanDemo = false }
            Box(Modifier.fillMaxSize()) {
                PlanDemoScreen(onClose = { showPlanDemo = false })
            }
        }
    }

    ZenSectionTitle("调试 · UI对齐（仅 internal）", eyebrow = "DEBUG")
    ZenCard {
        ZenOutlinedButton(
            onClick = { showPlanDemo = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开计划演示（六态 UI 对齐）") }
        ZenOutlinedButton(
            onClick = { msg = InternalDebugInjector.injectWaiting(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("调试 · 注入 waiting 确认任务") }
        ZenOutlinedButton(
            onClick = { msg = InternalDebugInjector.injectRetryableError(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("调试 · 注入可重试 error") }
        ZenOutlinedButton(
            onClick = { msg = InternalDebugInjector.injectRecover(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("调试 · 注入 recover 任务") }
        msg?.let { ZenHint(it) }
        ZenHint("来源：join_debug/*.json → LocalStore。仅 internal；store 无入口。注入后到「计划」或计划演示查看。")
    }
}

object InternalDebugInjector {
    fun injectWaiting(ctx: Context): String = runCatching {
        val o = readJson(ctx, "join_debug/waiting_confirm.json")
        val store = PlanViewModel.sharedStore
        val now = Instant.now().toString()
        val id = "task-waiting-debug-" + (System.currentTimeMillis() % 100000)
        val cp = o.optJSONObject("checkpoint")
        val seeded = Task(
            id = id,
            title = o.optString("title", "周六出行准备"),
            category = TaskCategory.travel,
            status = AgentStatus.waiting,
            sessionId = store.currentPrimarySessionId(),
            serviceIds = listOf("svc-calendar-stub"),
            summary = o.optString("summary").ifBlank { "请核对提醒内容。确认后才会进入执行。" },
            createdAt = now,
            updatedAt = now,
            recoverable = true,
            waitingReason = o.optString("waitingReason", "user_confirm"),
            remindAt = o.optString("remind_at").ifBlank { null },
            currentStep = o.optInt("current_step", 2),
            totalSteps = o.optInt("total_steps", 3),
            nextHint = o.optString("next_hint").ifBlank { null },
            nextActions = listOf("confirm", "modify", "view_detail"),
            errorCode = null,
            retryable = false,
            checkpoint = cp?.let { parseCheckpoint(it) },
            confirmToken = o.optString("confirm_token").ifBlank { "ct-debug-" + (System.currentTimeMillis() % 100000) },
            contentRevision = o.optInt("content_revision", 1),
        )
        store.upsertTask(seeded)
        "已注入 ${seeded.id} → waiting。请到「计划」或主会话确认卡查看。"
    }.getOrElse { "注入失败：${it.message}" }

    fun injectRetryableError(ctx: Context): String = runCatching {
        val o = readJson(ctx, "join_debug/error_retryable.json")
        val store = PlanViewModel.sharedStore
        val now = Instant.now().toString()
        val id = "task-error-debug-" + (System.currentTimeMillis() % 100000)
        val cp = o.optJSONObject("checkpoint")
        val seeded = Task(
            id = id,
            title = o.optString("title", "发送周报邮件"),
            category = TaskCategory.work,
            status = AgentStatus.error,
            sessionId = store.currentPrimarySessionId(),
            serviceIds = emptyList(),
            summary = o.optString("summary").ifBlank { "附件上传失败（可重复）" },
            createdAt = now,
            updatedAt = now,
            recoverable = true,
            currentStep = o.optInt("current_step", 2),
            totalSteps = o.optInt("total_steps", 3),
            nextHint = o.optString("next_hint").ifBlank { "可点「重试」" },
            nextActions = listOf("retry"),
            errorCode = o.optString("error_code", "UPLOAD_TRANSIENT"),
            retryable = true,
            checkpoint = cp?.let { parseCheckpoint(it) },
            confirmToken = null,
            contentRevision = o.optInt("content_revision", 1),
        )
        store.upsertTask(seeded)
        "已注入 ${seeded.id} → error(retryable)。请到「计划」点「重试」。"
    }.getOrElse { "注入失败：${it.message}" }

    fun injectRecover(ctx: Context): String = runCatching {
        val o = readJson(ctx, "join_debug/recover_restart.json")
        val cp = o.getJSONObject("checkpoint")
        val store = PlanViewModel.sharedStore
        val now = Instant.now().toString()
        val id = "task-recover-debug-" + (System.currentTimeMillis() % 100000)
        val seeded = Task(
            id = id,
            title = o.optString("title", "确认高铁订票"),
            category = TaskCategory.travel,
            status = AgentStatus.error,
            sessionId = store.currentPrimarySessionId(),
            serviceIds = listOf("svc-maps-stub"),
            summary = "执行中断：后端不支持从检查点续跑（调试注入）",
            createdAt = now,
            updatedAt = now,
            recoverable = true,
            currentStep = o.optInt("current_step", 2),
            totalSteps = o.optInt("total_steps", 4),
            errorCode = o.optString("error_code", "RESUME_UNSUPPORTED"),
            retryable = false,
            nextActions = listOf("restart_after_review", "cancel"),
            checkpoint = TaskCheckpoint(
                stepId = cp.getString("stepId"),
                stepIndex = cp.getInt("stepIndex"),
                label = cp.getString("label"),
                resumeSupported = false,
                payloadJson = cp.optString("payloadJson").ifBlank { null },
            ),
        )
        store.upsertTask(seeded)
        val r = store.enterRecover(id, o.optString("summary").ifBlank { null })
        "已注入 ${r.id} → ${r.status}（checkpoint=${r.checkpoint?.stepId}）。请到「计划」查看。"
    }.getOrElse { "注入失败：${it.message}" }

    private fun readJson(ctx: Context, assetPath: String): JSONObject {
        val raw = ctx.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(raw)
    }

    private fun parseCheckpoint(cp: JSONObject): TaskCheckpoint = TaskCheckpoint(
        stepId = cp.getString("stepId"),
        stepIndex = cp.getInt("stepIndex"),
        label = cp.getString("label"),
        resumeSupported = cp.optBoolean("resumeSupported", false),
        payloadJson = cp.optString("payloadJson").ifBlank { null },
    )
}
