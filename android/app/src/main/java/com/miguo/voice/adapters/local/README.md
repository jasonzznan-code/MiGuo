# 米果本地适配层 + 三流程演示（下午第 2–4 项）

> 契约源：Page「米果：页面↔模块映射与 UI 技术契约」  
> canvas id：`19a6b65c-b4ab-406f-ad13-b243c89af83c`  
> **未改服务器** / `.env` / Docker / cordis / 官方默认 `baseURL`。GPL 干净室：本目录均为米果自建类型与 mock，无 nano 源码依赖。

## 模块路径

| 路径 | 内容 |
|------|------|
| `app/src/main/java/com/miguo/voice/adapters/local/` | 类型、六态、六类、`MiguoLocalAdapter`、`LocalStore`（内存种子） |
| `app/src/main/java/com/miguo/voice/feature/demo/` | Compose 演示屏 `PlanDemoScreen` + `DemoViewModel` + 六态色块 |
| `app/src/test/java/com/miguo/voice/adapters/local/LocalStoreTest.kt` | 非法迁移 / 确认回写 / 枚举冒烟 |

## 如何运行 / 手动验收三流程

1. Android Studio 打开 `F:\miguo\android`，Run `app`（debug）。
2. 登录或「跳过登录」进入主界面。
3. 左上角打开个人抽屉 → 点 **「计划演示」**（或设置旁入口，见 `PersonalDrawer` / `MainActivity`）。
4. 进入演示屏后：

| 流程 | 怎么点 | 期望 |
|------|--------|------|
| **A 打开会话** | Hub 会话列表点「主会话 · 今日计划」或「上周出行备忘」 | 进入主会话区；顶部 `AgentStatusChip` 为 `idle`；可见时间线 |
| **B 查看任务详情** | Hub「进入计划页」→ 展开「出行」→ 点「确认高铁订票」 | 详情展示 id/title/category/status/recoverable 等字段 |
| **C 等待确认回会话** | 在 waiting 任务详情点「确认」或「拒绝」 | 状态 → `done` / `error`；自动回到主会话；时间线多一条 system 回写 |

六态色块：灰 idle / 蓝 running / 琥珀 waiting / 朱红 error / 绿 done。

## 状态枚举位置

- `AgentStatus`：`adapters/local/AgentStatus.kt` → `idle | running | waiting | error | done | recover`
- `TaskCategory`：`adapters/local/TaskCategory.kt` → `travel \| work \| life \| learn \| social \| ideas`（无 `family`/`more`）
- UI 使用：`feature/demo/AgentStatusChip.kt`、`DemoViewModel`、`PlanDemoScreen`

## 接口列表（相对契约 §4）

| 接口 | 实现 |
|------|------|
| `listSessions` | [x] |
| `openSession` / `getSession` | [x]（getSession 只读，不重置 Agent） |
| `createSession` / `renameSession` / `archiveSession` / `deleteSession` | [x]（删会话不断任务，断引用） |
| `listTasks` / `getTask` / `upsertTask` | [x] |
| `setTaskStatus` | [x] + `StatusTransitions` 非法迁移抛 `IllegalStatusTransitionException` |
| `listServices` | [x] stub |
| `getAgentStatus` / `setAgentStatus` | [x] |
| `confirmWaiting(approve\|reject)` | [x] SessionPlanBridge 回写 system 事件 |

## 单元测试

```bat
cd F:\miguo\android
gradlew.bat :app:testDebugUnitTest --tests com.miguo.voice.adapters.local.LocalStoreTest
```

## 编译说明

- 2026-10-29（Asia/Shanghai）：本机 `local.properties` 的 `sdk.dir` 已指向 `F:\AI assistant1\.tools\android-sdk`（原为无效的 `/workspace/...` 路径）。
- `:app:compileDebugKotlin` / `:app:assembleDebug` / `LocalStoreTest`（8 tests, 0 failures）均 **BUILD SUCCESSFUL**。
- Debug APK：`F:\miguo\android\app\build\outputs\apk\debug\app-debug.apk`
- **未改** relay / `.env` / Docker / cordis / 官方默认 baseURL。

## 与契约 Page 对应

- §3.3 六类 → `TaskCategory`
- §3.4 任务字段 → `Task`
- §3.5 服务 stub → `ServiceCard`
- §3.6 六态 → `AgentStatus` + `StatusTransitions`
- §3.7 流程 A/B/C → `PlanDemoScreen` 三路由
- §4 适配层 → `MiguoLocalAdapter` / `LocalStore`
