package com.miguo.voice.support

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密存储（对应 iOS KeychainStore.swift）：Android Keystore 里生成不可导出的 AES-256 密钥，
 * AES/GCM 加密后把 iv+密文 base64 存进私有 SharedPreferences。密钥永不离开 Keystore（有 StrongBox/TEE 时在硬件里）。
 * 不依赖已弃用的 androidx.security:security-crypto。
 */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("miguo_secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun get(name: String): String? {
        val v = prefs.getString(name, null) ?: return null
        return try {
            val all = Base64.decode(v, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("miguo", "SecureStore 解密失败（密钥被清除？）：${e.javaClass.simpleName}")
            null
        }
    }

    fun set(name: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
    }

    companion object {
        private const val ALIAS = "miguo_api_key_aes"
        const val API_KEY = "api_key"
    }
}

/** v0.2.0：登录 token 加密保存（与 API Key 同一把 Keystore 密钥） */
class SecureTokenStore(private val secure: SecureStore) : com.miguo.voice.conversation.TokenStore {
    override fun load(): com.miguo.voice.protocol.AuthSession? =
        com.miguo.voice.protocol.AuthSession.decode(secure.get(AUTH_SESSION))

    override fun save(s: com.miguo.voice.protocol.AuthSession?) {
        try { secure.set(AUTH_SESSION, s?.encode()) } catch (e: Exception) {
            android.util.Log.w("miguo", "保存登录状态失败：${e.javaClass.simpleName}")
        }
    }

    companion object { const val AUTH_SESSION = "auth_session" }
}
