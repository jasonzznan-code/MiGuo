package com.miguo.voice.protocol

import com.miguo.voice.conversation.AppSettings
import com.miguo.voice.conversation.Identity
import com.miguo.voice.conversation.makeParams
import com.miguo.voice.transport.ChatApi
import com.miguo.voice.transport.ClientConfigApi
import com.miguo.voice.transport.HealthResult
import com.miguo.voice.transport.VoiceHealthApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本地 mock。不访问 api.deepseek.com，不使用真实密钥。
 * 样例值只为断言「被丢弃」，不是可用的 DeepSeek Key。
 */
class DeepSeekProviderContractTest {
    private val secret = "sk-" + "a".repeat(24)

    @Test fun officialDefaultsAreDeepSeekNotAProxy() {
        assertEquals("https://api.deepseek.com", DeepSeekProviderContract.BASE_URL)
        assertEquals("/chat/completions", DeepSeekProviderContract.CHAT_COMPLETIONS_PATH)
        assertEquals("deepseek-flash", DeepSeekProviderContract.DEFAULT_MODEL)
        assertEquals("DEEPSEEK_API_KEY", DeepSeekProviderContract.ENV_API_KEY)
        assertEquals("DEEPSEEK_BASE_URL", DeepSeekProviderContract.ENV_BASE_URL)
        assertEquals("DEEPSEEK_MODEL", DeepSeekProviderContract.ENV_MODEL)
        assertEquals(60_000L, DeepSeekProviderContract.UPSTREAM_TIMEOUT_HINT_MS)
        assertFalse(DeepSeekProviderContract.BASE_URL.contains("openrouter"))
        assertFalse(DeepSeekProviderContract.BASE_URL.contains("/anthropic"))
        assertFalse(DeepSeekProviderContract.DEFAULT_MODEL == "deepseek-chat")
    }

    @Test fun missingNoticeStaysOffAndCacheBytesUnchanged() {
        val raw = """{"config_version":1,"notice":"hi"}"""
        assertEquals(raw, DeepSeekProviderContract.redactConfigSecrets(raw))
        val cfg = ClientConfig().mergedWith(ClientConfig.parseJson(raw))
        assertFalse(cfg.llm.enabled)
        assertFalse(cfg.llm.serverReportsDeepSeek)
        assertEquals(LlmProviderNotice.PROVIDER_UNSET, cfg.llm.provider)
        assertNull(cfg.llm.model)
        assertEquals("hi", cfg.notice)
        assertEquals("not-json", DeepSeekProviderContract.redactConfigSecrets("not-json"))
    }

    @Test fun factoryClientConfigHasNoProviderSwitch() {
        val raw = javaClass.classLoader!!.getResource("server_client_config.json")!!.readText()
        assertEquals(raw, DeepSeekProviderContract.redactConfigSecrets(raw))
        val cfg = ClientConfig().mergedWith(ClientConfig.parseJson(raw))
        assertFalse(cfg.llm.serverReportsDeepSeek)
        assertEquals("mp3", cfg.voice.outputFormat)
    }

    @Test fun sampleNoticeIsOffUntilServerEnablesIt() {
        val raw = javaClass.classLoader!!.getResource("deepseek_llm_notice.json")!!.readText()
        assertFalse(raw.contains("api_key"))
        assertFalse(raw.contains("sk-"))
        assertEquals(raw, DeepSeekProviderContract.redactConfigSecrets(raw))
        val cfg = ClientConfig().mergedWith(ClientConfig.parseJson(raw))
        assertEquals(LlmProviderNotice.PROVIDER_DEEPSEEK, cfg.llm.provider)
        assertEquals("deepseek-flash", cfg.llm.model)
        assertTrue(cfg.llm.upstreamStream)
        assertFalse(cfg.llm.enabled)
        assertFalse(cfg.llm.serverReportsDeepSeek)
    }

    @Test fun enabledNoticeDoesNotChangeChatOrSessionTarget() {
        val raw = """{"llm":{"provider":"DeepSeek","enabled":true,"model":"deepseek-flash","upstream_stream":true,"base_url":"https://proxy.example/v1"}}"""
        val cfg = ClientConfig().mergedWith(ClientConfig.parseJson(raw))
        assertTrue(cfg.llm.serverReportsDeepSeek)
        assertEquals("deepseek-flash", cfg.llm.model)
        assertFalse(cfg.llm.toString().contains("proxy.example"))
        assertFalse(cfg.llm.toString().contains("base_url"))

        val body = TextChatRequest("你好", null).encode()
        assertTrue(body.contains("\"stream\":true"))
        assertFalse(body.contains("deepseek"))
        assertFalse(body.contains("api.deepseek.com"))
        assertNull(TextChatProtocol.parseLine("""data: {"choices":[{"delta":{"content":"hi"}}]}"""))
        val reply = TextChatProtocol.parseLine("""{"type":"reply_text","seq":1,"text":"你好"}""") as TextChatEvent.Reply
        assertEquals("你好", reply.text)

        val params = AppSettings().makeParams("k", cfg, "android-x")
        assertEquals("wss://ai.njzyb.com:3010/api/v1/voice/session", params.endpoint.sessionUrl)
        assertEquals("k", params.apiKey)
    }

    @Test fun stringEnabledAndUnknownProviderDoNotTurnOn() {
        val asText = LlmProviderNotice.fromJson(ClientConfig.parseJson("""{"provider":"deepseek","enabled":"true"}"""))
        assertFalse(asText.enabled)
        val proxy = LlmProviderNotice.fromJson(
            ClientConfig.parseJson("""{"provider":"openrouter","enabled":true,"base_url":"https://openrouter.ai/api/v1"}""")
        )
        assertEquals(LlmProviderNotice.PROVIDER_UNSET, proxy.provider)
        assertFalse(proxy.enabled)
        assertFalse(proxy.serverReportsDeepSeek)
        assertFalse(proxy.toString().contains("openrouter"))
        assertFalse(proxy.toString().contains("http"))

        val keepDefault = LlmProviderNotice.fromJson(ClientConfig.parseJson("""{"provider":"ollama","enabled":true}"""))
        assertEquals(LlmProviderNotice.PROVIDER_DEFAULT, keepDefault.provider)
        assertTrue(keepDefault.enabled)
        assertFalse(keepDefault.serverReportsDeepSeek)
    }

    @Test fun secretsAreDroppedFromNoticeCacheAndLogs() {
        val raw = """{"notice":"你好","labels":{"listening":"在听"},"llm":{"provider":"deepseek","enabled":true,"model":"$secret","DEEPSEEK_API_KEY":"$secret","authorization":"Bearer $secret"}}"""
        val out = DeepSeekProviderContract.redactConfigSecrets(raw)
        assertFalse(out.contains(secret))
        assertFalse(out.contains("DEEPSEEK_API_KEY"))
        assertFalse(out.contains("authorization"))
        val cfg = ClientConfig().mergedWith(ClientConfig.parseJson(out))
        assertEquals("你好", cfg.notice)
        assertEquals("在听", cfg.label("listening"))
        assertEquals("米果在说", cfg.label("speaking"))
        assertNull(cfg.llm.model)
        assertFalse(cfg.toString().contains(secret))

        val direct = LlmProviderNotice.fromJson(
            ClientConfig.parseJson("""{"provider":"deepseek","enabled":true,"api_key":"$secret","model":"deepseek-flash"}""")
        )
        assertTrue(direct.droppedSecretFields)
        assertTrue(direct.serverReportsDeepSeek)
        assertEquals("deepseek-flash", direct.model)
        assertFalse(direct.toString().contains(secret))

        assertEquals("连接服务器超时", DeepSeekProviderContract.redactLog("连接服务器超时"))
        assertNull(DeepSeekProviderContract.redactLog(null))
        assertFalse(DeepSeekProviderContract.redactLog("api_key=$secret")!!.contains(secret))
        assertFalse(DeepSeekProviderContract.redactLog("Authorization: Bearer $secret")!!.contains(secret))
        assertTrue(DeepSeekProviderContract.redactLog("DEEPSEEK_API_KEY=$secret")!!.contains("[redacted]"))
    }

    @Test fun topLevelApiKeyIsRemovedWithoutTouchingOtherFields() {
        val raw = """{"config_version":2,"api_key":"$secret","notice":"维护"}"""
        val out = DeepSeekProviderContract.redactConfigSecrets(raw)
        assertFalse(out.contains(secret))
        assertFalse(out.contains("api_key"))
        val cfg = ClientConfig().mergedWith(ClientConfig.parseJson(out))
        assertEquals(2, cfg.configVersion)
        assertEquals("维护", cfg.notice)
        assertFalse(cfg.llm.serverReportsDeepSeek)
    }

    @Test fun healthNoticeIgnoresKeyAndKeepsSummary() {
        val legacy = VoiceHealthApi.parse("""{"status":"ok","llm":{"model":"qwen3:8b","reachable":true}}""")
        assertEquals("qwen3:8b", legacy.llmModel)
        assertEquals(true, legacy.llmReachable)
        assertNull(legacy.llmProvider)
        assertNull(legacy.llmEnabled)
        assertEquals("服务器正常（12 ms）", VoiceHealthApi.summary(HealthResult.Ok(legacy, 12.0)))

        val announced = VoiceHealthApi.parse(
            """{"status":"ok","llm":{"model":"deepseek-flash","reachable":true,"provider":"deepseek","enabled":false,"api_key":"$secret"}}"""
        )
        assertEquals("deepseek-flash", announced.llmModel)
        assertEquals("deepseek", announced.llmProvider)
        assertEquals(false, announced.llmEnabled)
        assertFalse(announced.toString().contains(secret))
    }

    @Test fun configFetchStripsSecretAndStaysOnMiguoHost() = runBlocking {
        val body = """{"config_version":4,"notice":"今晚维护","llm":{"provider":"deepseek","enabled":true,"model":"deepseek-flash","upstream_stream":true,"api_key":"$secret","base_url":"https://proxy.example/v1"}}"""
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))
        srv.start()
        try {
            val ep = ServerEndpoint.parse("http://127.0.0.1:${srv.port}")
            val result = ClientConfigApi.fetch(ep, "jason", null, "1.0.0", client = OkHttpClient())
            assertTrue(result.toString(), result is ClientConfigApi.Result.Ok)
            val ok = result as ClientConfigApi.Result.Ok
            assertFalse(ok.raw.contains(secret))
            assertFalse(ok.raw.contains("api_key"))
            val cfg = ClientConfig().mergedWith(ok.json)
            assertTrue(cfg.llm.serverReportsDeepSeek)
            assertEquals("deepseek-flash", cfg.llm.model)
            assertEquals("今晚维护", cfg.notice)
            assertEquals(4, cfg.configVersion)
            assertFalse(cfg.llm.toString().contains(secret))
            assertFalse(cfg.llm.toString().contains("proxy.example"))
            val req = srv.takeRequest()
            assertTrue(req.path!!.startsWith("/api/v1/client/config"))
            assertFalse(req.requestUrl.toString().contains("deepseek.com"))
        } finally {
            srv.shutdown()
        }
    }

    @Test fun textChatStillPostsNdjsonToMiguo() = runBlocking {
        val ndjson = """
            {"type":"start","conversation_id":"c1"}
            {"type":"reply_text","seq":1,"text":"你好"}
            {"type":"done","conversation_id":"c1","reply":"你好","llm_error":null}
        """.trimIndent() + "\n"
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setBody(ndjson).setHeader("Content-Type", "application/x-ndjson"))
        srv.start()
        try {
            val ep = ServerEndpoint.parse("http://127.0.0.1:${srv.port}")
            val result = ChatApi({ ep }, { null }).sendText(
                TextChatRequest("你好", conversationId = null),
                Identity("jason", null),
            ) {}
            assertTrue(result.toString(), result is ApiResult.Ok)
            val ok = (result as ApiResult.Ok).value
            assertEquals("c1", ok.conversationId)
            assertEquals("你好", ok.reply)
            assertNull(ok.llmError)
            val req = srv.takeRequest()
            assertEquals("/api/v1/chat/text", req.path)
            val posted = req.body.readUtf8()
            assertTrue(posted.contains("\"stream\":true"))
            assertFalse(posted.contains("deepseek"))
            assertFalse(posted.contains(secret))
            assertNull(req.getHeader("Authorization"))
            assertFalse(req.requestUrl.toString().contains("deepseek.com"))
        } finally {
            srv.shutdown()
        }
    }

    @Test fun serverEnvExampleHasNoRealKey() {
        val file = repoFile("docs/server-deepseek.env.example")
        val text = file.readText()
        assertTrue(text.contains("DEEPSEEK_API_KEY"))
        assertTrue(text.contains("https://api.deepseek.com"))
        assertTrue(text.contains("deepseek-flash"))
        assertFalse(text.contains("sk-"))
        assertFalse(text.contains("openrouter"))
        val assigned = text.lineSequence().filter { line ->
            val t = line.trim()
            t.isNotEmpty() && !t.startsWith("#") && t.contains('=')
        }.toList()
        assertTrue(assigned.toString(), assigned.isEmpty())
    }

    private fun repoFile(relative: String): File {
        val starts = listOf(File("."), File(".."), File("../.."), File("../../.."))
        return starts.map { File(it, relative) }.firstOrNull { it.isFile }
            ?: error("找不到 $relative ，已试：${starts.joinToString { it.absolutePath }}")
    }
}
