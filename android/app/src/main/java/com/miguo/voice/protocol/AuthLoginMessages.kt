package com.miguo.voice.protocol

/**
 * 登录页文案 / 正式错误码小表（策略 A · 2026-11-02）。
 * 实测服务端 code：bad_identifier / send_failed / code_wrong / code_expired /
 * code_too_often(=rate_limited) / auth_unreachable；界面只露中文一句话。
 */
object AuthLoginMessages {
    const val WELCOME = "米果欢迎"
    const val WELCOME_SUB = "登录后，和米果聊聊天、记记事。"
    val INTERNAL_BADGE: String get() = FlavorLoginCopy.INTERNAL_BADGE
    const val CHANGE_SERVER = "使用其他服务器"
    val CODE_HINT_LOG: String get() = FlavorLoginCopy.CODE_HINT_AFTER_SEND
    val CODE_HINT_STORE_PENDING: String get() = FlavorLoginCopy.CODE_HINT_AFTER_SEND
    const val CODE_NOT_RECEIVED = "验证码未收到"
    val CODE_NOT_RECEIVED_HINT: String get() = FlavorLoginCopy.CODE_NOT_RECEIVED_HINT
    const val LOADING_CHECK = "正在检查登录状态…"
    const val LOADING_CODE = "我在帮你请求验证码…"
    const val LOADING_LOGIN = "正在登录，稍等我一下～"

    const val BUBBLE_LOADING_CODE = "我在帮你请求验证码…"
    const val BUBBLE_LOADING_LOGIN = "正在登录，稍等我一下～"

    const val BUBBLE_UNREACHABLE_TITLE = "网络好像断了"
    const val BUBBLE_UNREACHABLE_LINE = "暂时连不上米果，请检查网络后再试。"

    const val BUBBLE_OTP_MISSING_TITLE = "没收到验证码？"
    val BUBBLE_OTP_MISSING_LINE: String get() = FlavorLoginCopy.CODE_NOT_RECEIVED_HINT

    const val BUBBLE_LOGIN_FAIL_TITLE = "登录没成功"
    const val BUBBLE_LOGIN_FAIL_LINE = "刚才没能登录，我们再试一次吧。"

    const val BUBBLE_CODE_WRONG_TITLE = "验证码不对"
    const val BUBBLE_CODE_WRONG_LINE = "这个验证码好像不对，请核对邮件或短信里的 6 位数字。"

    const val BUBBLE_CODE_EXPIRED_TITLE = "验证码过期了"
    const val BUBBLE_CODE_EXPIRED_LINE = "验证码已经过期，重新获取一个吧。"

    const val BUBBLE_RATE_TITLE = "发送太频繁了"
    fun bubbleRateLine(seconds: Int?): String =
        if (seconds != null && seconds > 0) "验证码发得有点频繁，请 ${seconds} 秒后再试。"
        else "验证码发得有点频繁，请稍后再试。"

    const val BUBBLE_BAD_ID_TITLE = "账号格式不对"
    const val BUBBLE_BAD_ID_LINE = "请输入正确的邮箱，或 11 位大陆手机号。"
    const val BUBBLE_EMPTY_ID_LINE = "请先输入邮箱或手机号。"

    const val BUBBLE_NEED_RELOGIN_TITLE = "需要重新登录"
    const val BUBBLE_NEED_RELOGIN_LINE = "登录状态已过期，请重新获取验证码登录。"

    const val SESSION_EXPIRED_CHAT = "登录已失效，请重新登录"

    const val SERVER_UNREACHABLE = "服务器不可达"
    const val LOGIN_FAILED = "登录失败"
    const val RETRY_CONNECT = "重试连接"
    const val API_NOTE = "内验接口说明：发码 / 校验 / 健康检查"
    val SKIP_LOGIN_LABEL: String get() = FlavorLoginCopy.SKIP_LOGIN_LABEL
    const val SEND_CODE = "发送验证码"
    const val LOGIN_BUTTON = "登录"

    const val PHONE_LABEL = "手机号"
    const val PHONE_PLACEHOLDER = "请输入手机号"
    const val PHONE_CONTENT_DESC = "请输入手机号"
    const val EMAIL_LABEL = "邮箱"
    const val EMAIL_PLACEHOLDER = "请输入邮箱"
    const val EMAIL_CONTENT_DESC = "请输入邮箱"
    const val SWITCH_TO_EMAIL = "使用邮箱登录"
    const val SWITCH_TO_PHONE = "使用手机号登录"

    const val CODE_BAD_IDENTIFIER = "bad_identifier"
    const val CODE_SEND_FAILED = "send_failed"
    const val CODE_WRONG = "code_wrong"
    const val CODE_EXPIRED = "code_expired"
    const val CODE_RATE_LIMITED = "rate_limited"
    const val CODE_TOO_OFTEN = "code_too_often"
    const val CODE_AUTH_UNREACHABLE = "auth_unreachable"
    const val CODE_AUTH_CODE_TIMEOUT = "auth_code_timeout"
    const val CODE_AUTH_CODE_MISSING = "auth_code_missing"
    const val CODE_AUTH_VERIFY_FAILED = "auth_verify_failed"

    /** 错误串前缀：code|中文，LoginScreen 按 code 分支，界面不露英文。 */
    fun tagged(code: String, zh: String): String = "${code.trim().lowercase()}|$zh"

    fun parseTagged(raw: String?): Pair<String?, String> {
        val m = raw?.trim().orEmpty()
        if (m.isEmpty()) return null to ""
        val idx = m.indexOf('|')
        if (idx <= 0) return null to m
        val code = m.substring(0, idx).trim().lowercase()
        val zh = m.substring(idx + 1).trim()
        if (code.any { it == ' ' || it == '：' || it == ':' }) return null to m
        return code to zh.ifBlank { m }
    }

    fun zhForServerCode(code: String?, fallbackStatus: Int? = null, retryAfterS: Int? = null): String {
        val c = code?.trim()?.lowercase().orEmpty()
        return when (c) {
            CODE_BAD_IDENTIFIER, "invalid_identifier" -> BUBBLE_BAD_ID_LINE
            CODE_SEND_FAILED -> "验证码没能发出，请稍后再试。"
            CODE_WRONG, "invalid_code" -> BUBBLE_CODE_WRONG_LINE
            CODE_EXPIRED -> BUBBLE_CODE_EXPIRED_LINE
            CODE_RATE_LIMITED, CODE_TOO_OFTEN -> bubbleRateLine(retryAfterS)
            CODE_AUTH_UNREACHABLE, "server_unreachable", "network" -> BUBBLE_UNREACHABLE_LINE
            "unauthorized" -> BUBBLE_NEED_RELOGIN_LINE
            "account_disabled" -> "账号不可用，请联系客服。"
            else -> when (fallbackStatus) {
                429 -> bubbleRateLine(retryAfterS)
                else -> BUBBLE_LOGIN_FAIL_LINE
            }
        }
    }

    private val loginFailMap = mapOf(
        CODE_AUTH_UNREACHABLE to SERVER_UNREACHABLE,
        CODE_AUTH_CODE_TIMEOUT to CODE_NOT_RECEIVED,
        CODE_AUTH_CODE_MISSING to CODE_NOT_RECEIVED,
        CODE_AUTH_VERIFY_FAILED to LOGIN_FAILED,
        "server_unreachable" to SERVER_UNREACHABLE,
        "network" to SERVER_UNREACHABLE,
        CODE_WRONG to LOGIN_FAILED,
        "invalid_code" to LOGIN_FAILED,
        "unauthorized" to LOGIN_FAILED,
        CODE_BAD_IDENTIFIER to LOGIN_FAILED,
        CODE_RATE_LIMITED to LOGIN_FAILED,
        CODE_TOO_OFTEN to LOGIN_FAILED,
        CODE_EXPIRED to LOGIN_FAILED,
        CODE_SEND_FAILED to LOGIN_FAILED,
        "account_disabled" to LOGIN_FAILED,
    )

    fun mapLoginFailure(code: String?, rawMessage: String?): String {
        val c = code?.trim()?.lowercase().orEmpty()
        if (c.isNotEmpty()) loginFailMap[c]?.let { return it }
        val msg = rawMessage?.trim().orEmpty()
        if (msg.isBlank()) return LOGIN_FAILED
        if (isLoadingMessage(msg)) return msg
        if (isUnreachable(msg)) return SERVER_UNREACHABLE
        if (msg.contains("验证码未收到") || msg.contains("未收到验证码") ||
            msg.contains("code timeout", true) || msg.contains("code_missing", true)
        ) {
            return CODE_NOT_RECEIVED
        }
        if (msg.contains("连不上") || msg.contains("超时") || msg.contains("unreachable", true)) {
            return SERVER_UNREACHABLE
        }
        return LOGIN_FAILED
    }

    fun isUnreachable(message: String?): Boolean {
        val m = message.orEmpty()
        val (code, zh) = parseTagged(m)
        if (code == CODE_AUTH_UNREACHABLE) return true
        val s = zh.ifBlank { m }
        return s.contains("连不上") || s.contains("服务器不可达") || s.contains("网络好像断了") ||
            s.contains("暂时连不上") || s.contains("unreachable", true)
    }

    fun isLoadingMessage(message: String?): Boolean {
        val m = message.orEmpty()
        return m == LOADING_CHECK || m == LOADING_CODE || m == LOADING_LOGIN ||
            m == BUBBLE_LOADING_CODE || m == BUBBLE_LOADING_LOGIN ||
            m.contains("正在检查登录状态") || m.contains("稍等我一下") ||
            m.contains("我在帮你请求验证码")
    }

    fun bubbleFor(code: String?, rawMessage: String?, loading: Boolean): String? {
        if (loading) return null
        if (code.isNullOrBlank() && rawMessage.isNullOrBlank()) return null
        return mapLoginFailure(code, rawMessage)
    }
}

