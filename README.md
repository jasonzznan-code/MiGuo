# 米果（Miguo）Android · 开源精简树（1.0.0 / VC102）

> 时区：Asia/Shanghai · 导出日：2026-11-02  
> 公开源码：https://github.com/jasonzznan-code/MiGuo  
> Tag：`v1.0.0` · versionName **1.0.0** · versionCode **102**  
> 许可证：**GPL-3.0-or-later** · 版权：**米果 / Miguo contributors** · 2026  
> 展示主体：**米果智能（个人开发者）**

## 项目简介

米果是面向个人助手场景的 Android 客户端（包名 `com.miguo.voice`）。本仓库为**商店策略 A 公开源码**：与商店 APK **同源**（本树对齐 **1.0.0 / versionCode 102** storeRelease），不以「源码藏核心、APK 带核心」方式分叉。

本树仅含 **Android 客户端**可编译源码与随包资源；**不含**服务端、其它端工程、密钥、`.env`、正式签名 keystore。

## 功能开关（FEATURE）

| Flag | 对应能力 | store / 开源默认 | internal 默认 | 说明 |
|------|----------|------------------|---------------|------|
| `FEATURE_VOICE` | 沉浸语音 + 转写/通话入口 | **false** | true | 源码可保留相关目录；商店/开源默认关闭入口 |
| `FEATURE_PLAN` | 计划底栏 + 确认卡推进 | **false** | true | 源码可保留；商店/开源默认无「计划」底栏 |
| 生活 / Vault | 底栏「生活」、抽屉「个人仓库」 | **保留** | 保留 | 本轮不剔除 |

商店精简 UI 预期：**底栏仅「主会话」+「生活」**；无沉浸通话主入口、无计划底栏。

## 如何编译商店包

```bash
cd android
# 可选：复制示例后填写本机 SDK 路径（勿提交真实 local.properties）
cp local.properties.example local.properties

# 可选：发布签名（勿提交 keystore.properties / *.jks）
cp keystore.properties.example keystore.properties

./gradlew :app:assembleStoreRelease
```

Windows 可用 `gradlew.bat :app:assembleStoreRelease`。

- **store** flavor：`DEFAULT_API_KEY` 强制为空；`FEATURE_VOICE`/`FEATURE_PLAN` 为 false；`ALLOW_SKIP_LOGIN` 为 false。  
- **internal** flavor：仅本地/内验；可注入开发用默认 Key（来自本机 `local.properties` 或未纳入本仓的 secrets 文件）。**切勿**分发带生产 Key 的 internal 包，也**不要**把生产 Key 提交进本仓库。

## 与正式内验版差异

| 项 | 开源 / store | internal 内验 |
|----|--------------|---------------|
| 沉浸语音 / 通话入口 | 关闭 | 开启 |
| 计划底栏与确认卡主路径 | 关闭 | 开启 |
| 跳过登录 | 否 | 是 |
| 默认打进包内的 API Key | 空 | 可本机注入（不进仓） |
| 生活 / Vault | 保留 | 保留 |

## 鉴权与账号

主会话鉴权路径为：**OTP 登录 → 会话令牌（`nm_` 前缀）**。商店包不内置 API Key；需登录后使用。

## 隐私与联系

- 隐私政策：https://ai.njzyb.com:3010/privacy  
- 联系邮箱：420603523@qq.com  
- 运营展示主体：米果智能（个人开发者）  
- 源码：https://github.com/jasonzznan-code/MiGuo  

应用内法律页常量见 `android/app/src/main/java/com/miguo/voice/ui/MiguoLegal.kt`。

## 重要限制（请先读）

- 本导出**不包含**后端 / 中继；自建服务需自行对接。  
- DeepSeek 官方模型要由服务端保管 `DEEPSEEK_API_KEY` 后才会生效。本仓库没有 memory_core 源码，不能在这里完成真实调用。不含密钥的客户端契约和启用说明见 `docs/deepseek-启用说明.md`。不要把 DeepSeek Key 填进 App。  
- **internal** 仅为内验调试；请勿将带密钥的内验包对外分发。  
- 换服/调试后门仅用于 internal；**store 默认关闭**，请勿改成商店默认开启。

## 许可与第三方

- 本项目：`LICENSE`（GNU GPL v3 or later）。  
- 第三方摘录见 `NOTICE` 与 `android/app/src/main/assets/opensource_licenses.md`。  
- 米果形象与启动图标：Jason 原创，**不**随本 NOTICE 授权第三方再许可使用。

## 目录结构

```
./
  LICENSE
  NOTICE
  README.md
  .gitignore
  android/          # Gradle 工程根（可编译）
```