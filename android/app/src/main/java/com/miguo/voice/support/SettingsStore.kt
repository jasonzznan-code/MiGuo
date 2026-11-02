package com.miguo.voice.support

import android.content.Context
import com.miguo.voice.BuildConfig
import com.miguo.voice.conversation.AppSettings
import com.miguo.voice.conversation.TtsProsody
import com.miguo.voice.conversation.VADPreset

/** 非机密设置 + /client/config 缓存（SharedPreferences） */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("miguo_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val chatDefault = BuildConfig.DEFAULT_CHAT_BASE.ifBlank {
            com.miguo.voice.protocol.ServerEndpoint.DEFAULT_ADDRESS
        }
        // store：AUTH 空时不得回退到局域网 DEFAULT_AUTH_ADDRESS
        val authDefault = when {
            BuildConfig.DEFAULT_AUTH_BASE.isNotBlank() -> BuildConfig.DEFAULT_AUTH_BASE
            BuildConfig.IS_INTERNAL || BuildConfig.ALLOW_SKIP_LOGIN ->
                com.miguo.voice.protocol.ServerEndpoint.DEFAULT_AUTH_ADDRESS
            else -> ""
        }
        var address = p.getString("address", null) ?: chatDefault
        var authAddress = p.getString("auth_address", null) ?: authDefault
        if (!BuildConfig.IS_INTERNAL) {
            if (looksLikePrivateOrNonHttpsAuth(authAddress)) {
                authAddress = authDefault
            }
            if (looksLikePrivateCleartext(address)) {
                address = chatDefault
            }
        }
        return AppSettings(
            address = address,
            authAddress = authAddress,
            userId = p.getString("user_id", null) ?: AppSettings().userId,
            outputFormat = p.getString("output_format", null),
            vadPreset = p.getString("vad_preset", null)?.let { runCatching { VADPreset.valueOf(it) }.getOrNull() },
            frameMs = p.getInt("frame_ms", 0).takeIf { it > 0 },
            voice = p.getString("voice", "") ?: "",
            ttsRatePercent = TtsProsody.clampRate(p.getInt("tts_rate_percent", 0)),
            ttsPitchHz = TtsProsody.clampPitch(p.getInt("tts_pitch_hz", 0)),
            ttsVolumePercent = TtsProsody.clampVolume(p.getInt("tts_volume_percent", 0)),
            useMemory = if (p.contains("use_memory")) p.getBoolean("use_memory", true) else null,
            showMetrics = p.getBoolean("show_metrics", true),
            bargeIn = if (p.contains("barge_in")) p.getBoolean("barge_in", true) else null,
            speakTextReplies = p.getBoolean("speak_text_replies", false),
            // store 禁止残留「跳过登录」态
            authSkipped = if (BuildConfig.ALLOW_SKIP_LOGIN) p.getBoolean("auth_skipped", false) else false,
        )
    }

    fun save(s: AppSettings) {
        p.edit().apply {
            putString("address", s.address)
            putString("auth_address", s.authAddress)
            putString("user_id", s.userId)
            if (s.outputFormat != null) putString("output_format", s.outputFormat) else remove("output_format")
            if (s.vadPreset != null) putString("vad_preset", s.vadPreset.name) else remove("vad_preset")
            if (s.frameMs != null) putInt("frame_ms", s.frameMs) else remove("frame_ms")
            putString("voice", s.voice)
            putInt("tts_rate_percent", TtsProsody.clampRate(s.ttsRatePercent))
            putInt("tts_pitch_hz", TtsProsody.clampPitch(s.ttsPitchHz))
            putInt("tts_volume_percent", TtsProsody.clampVolume(s.ttsVolumePercent))
            if (s.useMemory != null) putBoolean("use_memory", s.useMemory) else remove("use_memory")
            putBoolean("show_metrics", s.showMetrics)
            if (s.bargeIn != null) putBoolean("barge_in", s.bargeIn) else remove("barge_in")
            putBoolean("speak_text_replies", s.speakTextReplies)
            putBoolean("auth_skipped", if (BuildConfig.ALLOW_SKIP_LOGIN) s.authSkipped else false)
        }.apply()
    }

    /** v0.2.0：每个账号一个稳定的对话 ID（key = "u:<user_id>" 登录 / "id:<user_id>" user_id 模式） */
    fun conversationId(account: String): String? = p.getString("conv:$account", null)

    fun setConversationId(account: String, id: String?) {
        p.edit().apply { if (id == null) remove("conv:$account") else putString("conv:$account", id) }.apply()
    }

    var cachedConfigJson: String?
        get() = p.getString("client_config_cache", null)
        set(v) { p.edit().putString("client_config_cache", v).apply() }

    /** 未登录时本机注销申请时间戳；非服务端已注销证明。 */
    fun saveDeletionRequest(atMs: Long = System.currentTimeMillis()): Long {
        p.edit().putLong("deletion_request_at_ms", atMs).apply()
        return atMs
    }

    fun deletionRequestAtMs(): Long = p.getLong("deletion_request_at_ms", 0L)

    companion object {
        fun looksLikePrivateOrNonHttpsAuth(url: String): Boolean {
            val u = url.trim()
            if (u.isEmpty()) return false
            if (!u.startsWith("https://", ignoreCase = true)) return true
            return looksLikePrivateHost(u)
        }

        fun looksLikePrivateCleartext(url: String): Boolean {
            val u = url.trim()
            if (u.startsWith("http://", ignoreCase = true)) return true
            return looksLikePrivateHost(u)
        }

        private fun looksLikePrivateHost(url: String): Boolean {
            val rest = url.substringAfter("://", missingDelimiterValue = url)
            val host = rest.substringBefore('/').substringBefore(':').lowercase()
            if (host == "localhost" || host == "127.0.0.1") return true
            val parts = host.split(".")
            if (parts.size == 4) {
                val a = parts[0].toIntOrNull() ?: return false
                val b = parts[1].toIntOrNull() ?: return false
                if (a == 10) return true
                if (a == 172 && b in 16..31) return true
                if (a == 192 && b == 168) return true
            }
            return false
        }
    }
}
