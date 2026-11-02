package com.miguo.voice.protocol

/**
 * store 默认鉴权基址：单公网 AUTH（memory_core /nm 反代至 cloud-relay）。
 * 禁止内网 IP / 明文 HTTP 字面量进入 store 包。
 */
object FlavorEndpoints {
    const val DEFAULT_AUTH_ADDRESS = "https://ai.njzyb.com:3010/nm"
}
