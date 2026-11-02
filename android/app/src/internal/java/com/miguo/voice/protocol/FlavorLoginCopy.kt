package com.miguo.voice.protocol

/** internal flavor 登录文案（可含内测/日志/跳过登录；手机号/邮箱切换见 AuthLoginMessages）。 */
object FlavorLoginCopy {
    const val INTERNAL_BADGE = "内测 · 验证码见服务端日志"
    const val IDENTIFIER_LABEL = "手机号"
    const val IDENTIFIER_PLACEHOLDER = "请输入手机号"
    const val IDENTIFIER_CONTENT_DESC = "请输入手机号"
    const val CODE_HINT_AFTER_SEND = "验证码已请求。请从服务端 / 中继日志读取后填入。（CODE_SENDER=log）"
    const val CODE_NOT_RECEIVED_HINT = "内测阶段不会发短信；请 Jason 在旁从中继日志读 OTP（CODE_SENDER=log）。"
    const val SKIP_LOGIN_LABEL = "先不登录，用测试身份进入"
}
