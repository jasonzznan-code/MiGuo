package com.miguo.voice.support

import android.content.Context

/**
 * 主稿「个性化设置」本地预览偏好（称呼 / 回复风格）。
 * 对齐开发主稿：偏好可在本机保存并反映在进球菜单，不宣称已接入真实回复能力。
 */
class PersonaPrefs(context: Context) {
    private val p = context.getSharedPreferences("miguo_persona_prefs", Context.MODE_PRIVATE)

    var nickname: String
        get() = p.getString(KEY_NICKNAME, "") ?: ""
        set(v) { p.edit().putString(KEY_NICKNAME, v.trim().take(20)).apply() }

    var replyStyle: String
        get() {
            val v = p.getString(KEY_REPLY_STYLE, DEFAULT_REPLY_STYLE) ?: DEFAULT_REPLY_STYLE
            return if (v in REPLY_STYLES) v else DEFAULT_REPLY_STYLE
        }
        set(v) {
            val next = if (v in REPLY_STYLES) v else DEFAULT_REPLY_STYLE
            p.edit().putString(KEY_REPLY_STYLE, next).apply()
        }

    /** 主稿 accountedit 演示账号名称（仅本地预览，非真实登录身份）。 */
    var demoAccountName: String
        get() = p.getString(KEY_DEMO_ACCOUNT, "") ?: ""
        set(v) { p.edit().putString(KEY_DEMO_ACCOUNT, v.trim().take(40)).apply() }

    companion object {
        const val DEFAULT_REPLY_STYLE = "自然亲切"
        val REPLY_STYLES = listOf("自然亲切", "简洁直接", "细致耐心")
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_REPLY_STYLE = "reply_style"
        private const val KEY_DEMO_ACCOUNT = "demo_account_name"
    }
}
