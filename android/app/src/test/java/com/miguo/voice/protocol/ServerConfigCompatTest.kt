package com.miguo.voice.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用 memory_core 出厂的 config/client_config.json（测试资源里的副本）验证与真实服务端格式兼容，包括占位符规则。 */
class ServerConfigCompatTest {
    private val raw = javaClass.classLoader!!.getResource("server_client_config.json")!!.readText()
    private val cfg = ClientConfig().mergedWith(ClientConfig.parseJson(raw))

    @Test fun parsesFactoryFile() {
        assertEquals(1, cfg.configVersion)
        assertFalse("出厂配置没有 llm 通告，DeepSeek 保持关闭", cfg.llm.serverReportsDeepSeek)
        assertEquals(LlmProviderNotice.PROVIDER_UNSET, cfg.llm.provider)
        assertEquals("0.1.0", cfg.minClientVersion)
        assertNull("update_url 为空串 → 视为没有", cfg.updateUrl)
        assertEquals("mp3", cfg.voice.outputFormat)
        assertNull("voice: null → 不发 voice", cfg.voice.voice)
        assertEquals(700, cfg.voice.vad.minSilenceMs)
        assertEquals("正在听", cfg.label("listening"))
        assertNull(cfg.notice)
        assertFalse(cfg.needsUpdate("0.1.0-phase1"))
    }

    @Test fun platformViewShape() {
        // 服务端 platform_view(platform=android) 的形状：按平台字段已展开成字符串 + update_required
        val view = """{"config_version":2,"min_client_version":"0.2.0","latest_version":"0.3.0","update_url":"",
            "update_required":true,"update_available":true,"platform":"android","client_version":"0.1.0-phase1",
            "notice":{"text":"今晚维护","level":"info"},"etag":"\"abc\"","source":"file"}"""
        val c = ClientConfig().mergedWith(ClientConfig.parseJson(view))
        assertTrue(c.needsUpdate("0.1.0-phase1"))
        assertFalse("升级后用本地比较，不信缓存里的 update_required", c.needsUpdate("0.2.0"))
        assertTrue(c.updateAvailable("0.2.0"))
        assertEquals("App 版本过低（最低 0.2.0），请更新后再用", c.label("update_required", mapOf("min" to "0.2.0")))
        assertEquals("今晚维护", c.notice)
    }

    @Test fun titlePlaceholders() {
        val e = cfg.apply(ErrorCatalog.handshake(403))
        assertEquals("鉴权失败（HTTP 403）", e.title)
        assertTrue(e.fix!!.contains("MEMORY_API_KEY"))
        val c = cfg.apply(ErrorCatalog.close(4005, "")!!)
        assertEquals("连接已关闭（4005）", c.title)
    }

    @Test fun serverMessagePlaceholderAndEventFix() {
        val e = cfg.apply(ErrorCatalog.fromServer(ServerError("too_many_sessions", "已有 2 个会话", null, true, null)))
        assertEquals("这个 user_id 已经有 2 个进行中的语音会话（例如 PC 端和另一台设备）。已有 2 个会话", e.message)
        // 事件自带 fix → 优先
        val f = cfg.apply(ErrorCatalog.fromServer(ServerError("internal", "boom", "重启服务", true, null)))
        assertEquals("重启服务", f.fix)
        assertEquals("boom", f.message)
        // {server_message} 代入后为空 → 用内置
        val g = cfg.apply(ErrorCatalog.fromServer(ServerError("idle_timeout", null, null, true, null)))
        assertEquals("长时间无数据，服务器已结束会话", g.title)
        assertEquals("点一下重新开始。", g.fix)
        // 未知错误码：server_error 不会自动套用（只按 code 精确匹配）
        val u = cfg.apply(ErrorCatalog.fromServer(ServerError("weird", "x", null, true, null)))
        assertEquals("服务器错误（weird）", u.title)
    }

    @Test fun staticMessageOnlyWhenNoRuntimeMessage() {
        val withMsg = cfg.apply(ErrorCatalog.fromServer(ServerError("bad_user", "user_id 'a b' invalid", null, true, null)))
        assertEquals("user_id 'a b' invalid", withMsg.message)
        val noMsg = cfg.apply(ErrorCatalog.close(4400, null)!!)
        assertEquals("user_id 不合法（关闭码 4400）", noMsg.title)
    }

    @Test fun networkPlaceholders() {
        val e = cfg.apply(ErrorCatalog.network("failed to connect to /192.168.10.104:8765", ErrorCatalog.NetKind.TIMEOUT, false))
        assertEquals("连不上服务器（timeout）", e.title)
        assertEquals("failed to connect to /192.168.10.104:8765", e.message)
    }

    @Test fun iosWordedPhoneErrorsKeepAndroidBody() {
        val m = cfg.apply(ErrorCatalog.micDenied())
        assertEquals("没有麦克风权限", m.title)
        assertTrue("Android 的权限路径，而不是 iOS 的“隐私与安全性”", m.fix!!.contains("应用"))
        val l = cfg.apply(ErrorCatalog.network("x", ErrorCatalog.NetKind.OTHER, true))
        assertFalse(l.message.contains("iPhone"))
        // 服务端用 errors.mic_denied.android 单独给文案时完整生效
        val c2 = cfg.mergedWith(ClientConfig.parseJson("""{"errors":{"mic_denied":{"android":{"title":"要麦克风","fix":"去系统设置开"}}}}"""))
        val m2 = c2.apply(ErrorCatalog.micDenied())
        assertEquals("要麦克风", m2.title)
        assertEquals("去系统设置开", m2.fix)
    }

    @Test fun reconnectingLabel() {
        assertEquals("重连中（第 2 次）", com.miguo.voice.conversation.ConversationState.Reconnecting(2).title(cfg))
        assertEquals("重连中（第 2 次）", com.miguo.voice.conversation.ConversationState.Reconnecting(2).title(ClientConfig()))
    }
}
