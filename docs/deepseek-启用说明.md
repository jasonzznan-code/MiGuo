# 启用 DeepSeek（只在服务端）

公开仓库只有 Android 客户端（`main` 上 `e3270a2` 仅增加了「内容由 AI 生成」标识）。模型调用在未入库的 memory_core。本页说明以后怎样只配一把服务端密钥来启用 **DeepSeek 官方 API**。这里没有接上真实调用，也没有可运行的假服务。

## 现在已经做到的

- 客户端增加不含密钥的 `llm` 通告解析，**默认关闭**。
- 未下发 `llm`，或 `enabled` 不是 `true` 时，文字聊天、登录、中继的请求与现在相同。
- 聊天仍只请求米果自己的 `POST /api/v1/chat/text`（NDJSON），不会请求 `api.deepseek.com`。
- 如果服务端误把密钥放进 `/api/v1/client/config`，客户端丢掉密钥字段和 `sk-…` 值，不写入配置缓存，日志里也不保留。

设置页里已有的 API Key 是米果会话凭证（`MEMORY_API_KEY` 或登录后的可吊销凭证）。**不要把 DeepSeek Key 粘贴到 App。** Key 不进 APK，不进 git。

## 唯一必需秘密

`DEEPSEEK_API_KEY`，只放在运行 memory_core 的服务器环境变量或该机的密钥管理里。示例（值保持注释，不要填真 key 后提交）：[`docs/server-deepseek.env.example`](server-deepseek.env.example)。

只配这一项即可。可选覆盖：

| 变量 | 不设置时 |
| --- | --- |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` |
| `DEEPSEEK_MODEL` | `deepseek-flash` |

核对日期 2026-11-05，官方文档：<https://api-docs.deepseek.com/>

- 接口：`POST https://api.deepseek.com/chat/completions`（OpenAI 兼容）
- 鉴权：`Authorization: Bearer $DEEPSEEK_API_KEY`（只存在于服务端内存）
- 流式：`stream: true` 时响应为 `text/event-stream`，以 `data: [DONE]` 结束
- 当前默认模型名是 `deepseek-flash`。`deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 停用
- 另一官方模型名是 `deepseek-v4-pro`，用 `DEEPSEEK_MODEL` 覆盖
- Anthropic 兼容地址 `https://api.deepseek.com/anthropic` 不是这条链路的默认

**第三方代理不是官方 API。** 默认主机必须是 `api.deepseek.com`。不要把中转站、OneAPI、OpenRouter 写成默认值。只有在你明确信任某个 OpenAI 兼容端点时才设置 `DEEPSEEK_BASE_URL`，并记明它不是 DeepSeek 官方。

在服务端代码真正读取这些变量之前，只在机器上导出 `DEEPSEEK_API_KEY` **不会**改变现有回复。本仓库没有那段代码，也没有部署权限。

## 客户端通告（默认不要打开）

服务端以后可以在 `GET /api/v1/client/config` 里加上下面这段。客户端只认 `provider`、`enabled`、`model`、`upstream_stream`，不认 `base_url` 和任何密钥字段。可合并的示例：[`android/app/src/test/resources/deepseek_llm_notice.json`](../android/app/src/test/resources/deepseek_llm_notice.json)。

```json
{
  "llm": {
    "provider": "deepseek",
    "enabled": false,
    "model": "deepseek-flash",
    "upstream_stream": true
  }
}
```

`enabled` 保持 `false`，直到服务端真的把 `/api/v1/chat/text` 和语音轮次转到官方 `/chat/completions`。即便改成 `true`，App 也只是记下「服务端声明已切换」，仍然不会自己去调 DeepSeek。

米果现有流式是 NDJSON（`start` / `reply_text` / `done`），不是 DeepSeek 的 SSE。服务端应自己读 SSE，再翻译成现有 NDJSON。不要把 `data: {...}` 原样回给 App。

`GET /api/v1/voice/health` 里已有的 `llm.model`、`llm.reachable` 可以照旧。可以多给 `provider` 和 `enabled`，不要给密钥。

## 服务端还要改哪里

下面这些路径来自客户端注释，**git 树里不存在**，名字以你们私有的 memory_core 为准：

- 进程环境或 `.env`：上面的三个变量
- `api/text_chat.py`：文字对话的模型调用
- `voice/session.py`：语音轮次里的 LLM
- `config/client_config.json` 与 `api/client_config.py`：可选的不含密钥通告
- `voice/router.py` 的 `/api/v1/voice/health`

建议的上游错误映射（客户端已有 `llm_unavailable`，本改动没有换文案）：

| 上游 | 给客户端 |
| --- | --- |
| 401 | `llm_unavailable`。message / fix / 日志里不要出现 key |
| 402 | `llm_unavailable`，只提示余额不足 |
| 400 / 422 | `llm_unavailable`，去掉请求头后再保留错误类型 |
| 429 / 500 / 503 | `llm_unavailable`，可重试 |
| 超时（官方示例 60 秒） | `llm_unavailable` |
| SSE 中途断开 | 沿用现有「回复没有完整收到」 |

日志可以记模型名、HTTP 状态和耗时。不要记 `Authorization`、`DEEPSEEK_API_KEY` 或 `sk-`。

官方快速开始默认开启思考模式。米果现在是逐句出字。服务端若要保持这个体验，应显式发送 `thinking.type = disabled`。本仓库不会发出这个请求。

未配置 Key 时，服务端应继续现在的模型。客户端错误文案仍按 Ollama / `qwen3:8b` 描述，没有改。

## 重启后怎么确认（不打印密钥）

在服务器上：

```bash
if [ -n "${DEEPSEEK_API_KEY:-}" ]; then
  echo "DEEPSEEK_API_KEY=set length=${#DEEPSEEK_API_KEY}"
else
  echo "DEEPSEEK_API_KEY=unset (DeepSeek 保持关闭)"
fi
```

不要 `echo` 变量值，不要把值写进文件或贴到聊天里。用你们现有的方式重启 memory_core（本仓库没有重启脚本；客户端注释里的服务端日志是 `logs/server.err.log`）。

重启之后：

1. 在服务端日志里确认没有 `DEEPSEEK_API_KEY=`、`Authorization:`、`sk-`。不要把日志发到公开渠道。
2. 按现在的方式调用 `GET /api/v1/voice/health`（头里仍是米果自己的 `X-API-Key`，不是 DeepSeek Key）。看 `llm.model` 和 `llm.reachable`。响应里不应出现密钥。服务端切换代码还没部署时，模型名仍是原来的，这是预期。
3. 用 App 发一条文字。成功标准仍是现有 NDJSON 回复。`done.llm_error` 为空只说明这一轮模型调用成功，不能单独证明已经换成 DeepSeek；要看健康检查或 metrics 里的 `llm_model` 是否已是 `deepseek-flash`（或你设置的 `DEEPSEEK_MODEL`）。
4. 再拉一次 `GET /api/v1/client/config`，确认正文没有 `api_key` / `authorization`。

未配置 Key，或服务端还没读这个变量时，第 2、3 步应与现在完全一致。

## 测试

`DeepSeekProviderContractTest` 使用本地 JSON 和 MockWebServer，不访问 `api.deepseek.com`，不需要真实 Key。

```bash
cd android
./gradlew :app:testStoreDebugUnitTest --tests com.miguo.voice.protocol.DeepSeekProviderContractTest
```
