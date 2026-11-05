package com.miguo.voice.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 客户端与尚未入库的 memory_core 之间，关于 DeepSeek **官方** API 的不含密钥契约。
 *
 * 公开仓库只有 Android。文字/语音的模型调用发生在服务端（客户端注释中的
 * `api/text_chat.py`、`voice/session.py`）。本类型不发起 HTTP，也不读取密钥。
 *
 * 默认关闭：配置里没有 `llm`，或 `enabled` 不为 true 时，聊天仍只走
 * `POST /api/v1/chat/text`（NDJSON），鉴权与中继不变。
 *
 * `DEEPSEEK_API_KEY` 只允许存在于服务端环境变量。这里拒绝把该值放进数据模型、
 * 配置缓存或日志。官方根地址是 `https://api.deepseek.com`，不是第三方代理。
 * 核对日期 2026-11-05：https://api-docs.deepseek.com/
 */
object DeepSeekProviderContract {
    const val DOCS_URL = "https://api-docs.deepseek.com/"
    /** OpenAI 兼容官方根地址。Anthropic 兼容地址是另一条路径，本契约不使用。 */
    const val BASE_URL = "https://api.deepseek.com"
    const val CHAT_COMPLETIONS_PATH = "/chat/completions"
    /**
     * 官方文档当前默认对话模型（DeepSeek-V4.1-Flash）。
     * `deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 停用，不能再当默认。
     * 另一官方模型名是 `deepseek-v4-pro`，只允许服务端用环境变量覆盖。
     */
    const val DEFAULT_MODEL = "deepseek-flash"
    const val ENV_API_KEY = "DEEPSEEK_API_KEY"
    const val ENV_BASE_URL = "DEEPSEEK_BASE_URL"
    const val ENV_MODEL = "DEEPSEEK_MODEL"
    /** 官方 curl 示例的 `--max-time 60`。只给未来的服务端实现作参考，客户端超时不变。 */
    const val UPSTREAM_TIMEOUT_HINT_MS = 60_000L

    private val SECRET_FIELDS = setOf(
        "apikey",
        "authorization",
        "deepseekapikey",
        "bearer",
        "secret",
        "accesstoken",
        "refreshtoken",
        "password",
        "xapikey",
    )

    /** 整个字段值像供应商密钥时才丢弃，避免误伤普通文案。 */
    private val KEY_VALUE = Regex("""sk-[A-Za-z0-9_\-]{16,}""")
    private val LOG_KEY = Regex("""sk-[A-Za-z0-9_\-]{8,}""")
    private val LOG_BEARER = Regex("""(?i)bearer\s+\S+""")
    private val LOG_ASSIGN = Regex("""(?i)\b(deepseek_api_key|api_key|authorization)\b\s*[=:]\s*\S+""")

    fun isSecretFieldName(name: String): Boolean {
        val normalized = name.lowercase().filter { it.isLetterOrDigit() }
        return normalized in SECRET_FIELDS
    }

    fun looksLikeProviderKey(value: String): Boolean = KEY_VALUE.matches(value.trim())

    /**
     * 日志/错误摘要脱敏。没有密钥样式时原样返回（null 仍是 null），避免改变现有日志文本。
     */
    fun redactLog(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        val noAssign = LOG_ASSIGN.replace(text) { m -> "${m.groupValues[1]}=[redacted]" }
        val noBearer = LOG_BEARER.replace(noAssign, "Bearer [redacted]")
        return LOG_KEY.replace(noBearer, "[redacted]")
    }

    /**
     * 配置原文里没有密钥字段时，返回同一个字符串（缓存字节不变）。
     * 有密钥字段或 `llm` 里出现 `sk-…` 值时，删掉这些字段再序列化，值不会留下。
     * 非法 JSON 原样返回，调用方仍按原来的解析失败处理。
     */
    fun redactConfigSecrets(raw: String): String {
        val root = try {
            ClientConfig.parseJson(raw)
        } catch (_: Exception) {
            return raw
        }
        if (!containsSecret(root, inLlm = false)) return raw
        return stripElement(root, inLlm = false).toString()
    }

    private fun containsSecret(el: JsonElement, inLlm: Boolean): Boolean = when (el) {
        is JsonObject -> el.any { (k, v) ->
            isSecretFieldName(k) || containsSecret(v, inLlm || k == "llm")
        }
        is JsonArray -> el.any { containsSecret(it, inLlm) }
        is JsonPrimitive -> inLlm && el.isString && looksLikeProviderKey(el.content)
        else -> false
    }

    private fun stripElement(el: JsonElement, inLlm: Boolean): JsonElement = when (el) {
        is JsonObject -> buildJsonObject {
            for ((k, v) in el) {
                if (isSecretFieldName(k)) continue
                val childInLlm = inLlm || k == "llm"
                if (childInLlm && v is JsonPrimitive && v.isString && looksLikeProviderKey(v.content)) continue
                put(k, stripElement(v, childInLlm))
            }
        }
        is JsonArray -> buildJsonArray {
            for (item in el) add(stripElement(item, inLlm))
        }
        else -> el
    }
}

/**
 * 服务端下发的模型提供方通告。不含地址、不含密钥。
 * 客户端不根据它改请求 URL。`serverReportsDeepSeek == true` 只表示服务端自己声明已经切换。
 */
data class LlmProviderNotice(
    /** [PROVIDER_UNSET]：没通告；[PROVIDER_DEFAULT]：仍是现有服务端模型；[PROVIDER_DEEPSEEK]：声明为官方 DeepSeek。 */
    val provider: String = PROVIDER_UNSET,
    val enabled: Boolean = false,
    /** 展示用模型名。缺省时客户端不擅自填官方默认模型，避免看起来像客户端已经在调用。 */
    val model: String? = null,
    /** 服务端通告其上游是否走 SSE。客户端自己的 `/chat/text` 仍是 NDJSON。 */
    val upstreamStream: Boolean = false,
    /** 这份 JSON 里出现过密钥字段或密钥样式的值，且已被丢弃。值本身不保留。 */
    val droppedSecretFields: Boolean = false,
) {
    val serverReportsDeepSeek: Boolean
        get() = enabled && provider == PROVIDER_DEEPSEEK

    companion object {
        const val PROVIDER_UNSET = "unset"
        const val PROVIDER_DEFAULT = "default"
        const val PROVIDER_DEEPSEEK = "deepseek"
        val DISABLED = LlmProviderNotice()

        fun fromJson(o: JsonObject): LlmProviderNotice {
            val droppedName = o.keys.any { DeepSeekProviderContract.isSecretFieldName(it) }
            val provider = when (o.str("provider")?.trim()?.lowercase()) {
                PROVIDER_DEEPSEEK -> PROVIDER_DEEPSEEK
                PROVIDER_DEFAULT, "ollama", "builtin" -> PROVIDER_DEFAULT
                else -> PROVIDER_UNSET
            }
            val enabled = jsonBool(o, "enabled") == true && provider != PROVIDER_UNSET
            val modelRaw = o.str("model")?.trim()?.takeIf { it.isNotEmpty() }
            val modelLeaksKey = modelRaw != null && DeepSeekProviderContract.looksLikeProviderKey(modelRaw)
            return LlmProviderNotice(
                provider = provider,
                enabled = enabled,
                model = if (modelLeaksKey) null else modelRaw,
                upstreamStream = jsonBool(o, "upstream_stream") == true,
                droppedSecretFields = droppedName || modelLeaksKey,
            )
        }

        /** 只接受 JSON 布尔。字符串 "true" 视为类型不对，保持关闭。 */
        private fun jsonBool(o: JsonObject, key: String): Boolean? {
            val p = o.prim(key) ?: return null
            if (p.isString) return null
            return p.booleanOrNull
        }
    }
}
