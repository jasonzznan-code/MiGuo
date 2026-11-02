package com.miguo.voice.ui

/**
 * 米果合规常量。隐私托管 URL 已定点；应用内静态页仍可阅读，并可外链打开托管页。
 * 源码仓 URL 为产品入口；未得用户点头前不 git push。
 */
object MiguoLegal {
    const val SOURCE_URL = "https://github.com/jasonzznan-code/MiGuo"
    const val GPL_SPDX = "GPL-3.0-or-later"
    const val GPL_ASSET = "gpl-3.0-or-later.txt"
    const val OPERATOR = "米果智能"
    /** 正式联系邮箱（应用内与隐私页同步）。 */
    const val CONTACT_EMAIL = "420603523@qq.com"
    /** 隐私政策最终托管地址（用户指定 NAS :3010，2026-11-02）。 */
    const val PRIVACY_URL = "https://ai.njzyb.com:3010/privacy"
    const val PRIVACY_HOSTED = true
}