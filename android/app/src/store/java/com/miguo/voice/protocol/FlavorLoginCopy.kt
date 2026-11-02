package com.miguo.voice.protocol

/**
 * store flavor 登录文案：默认邮箱 OTP；正式用语。
 * 手机号/邮箱切换入口文案见 AuthLoginMessages，不在此隐藏。
 */
object FlavorLoginCopy {
    const val INTERNAL_BADGE = ""
    const val IDENTIFIER_LABEL = "邮箱"
    const val IDENTIFIER_PLACEHOLDER = "请输入邮箱"
    const val IDENTIFIER_CONTENT_DESC = "请输入邮箱"
    const val CODE_HINT_AFTER_SEND =
        "验证码已发送到你的邮箱，请查收后填入。若未收到，请检查垃圾邮件或稍后重试。"
    const val CODE_NOT_RECEIVED_HINT =
        "没收到邮件？请检查垃圾箱，或稍后点「发送验证码」再试一次。"
    const val SKIP_LOGIN_LABEL = ""
}
