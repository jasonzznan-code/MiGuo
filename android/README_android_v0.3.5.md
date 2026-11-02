# 米果 Android v0.3.5（外网默认 + API Key 默认持有）

- **APK**：`miguo-android-debug-v0.3.5.apk`
- **MD5**：`9aba66c19f5134463b2bee9db12326c2`
- **versionName**：0.3.5　**versionCode**：19
- **工程**：`/workspace/clients/work/android_v023`（未改 `android_v022`）
- **桌面副本**：`C:\Users\A\Desktop\miguo-android-debug-v0.3.5.apk`
- **镜像**：`/workspace/clients/out/miguo-android-debug-v0.3.5.apk`

## 本版改动

1. **默认服务器**：`ServerEndpoint.DEFAULT_ADDRESS` → `https://ai.njzyb.com:3010`（https→wss 已确认：`wss://ai.njzyb.com:3010/api/v1/voice/session`）。
2. **API Key 默认持有**：构建期从本机 `local.properties` / `.secrets/default_api_key`（源自 memory_core `.env` 的 `MEMORY_API_KEY`）注入 `BuildConfig.DEFAULT_API_KEY`；首次启动若 SecureStore 无 key 则写入加密存储，**已有用户自存 key 不覆盖**；设置页留空保存不修改。
3. 用户可见示例/说明优先外网 HTTPS；局域网仍可手动填 `http://192.168.x.x:8765`。
4. `versionName=0.3.5` / `versionCode=19`。

原始密钥未写入本 README 或其它聊天可见文件。

## 建议自测（中文）

1. 覆盖安装 0.3.5
2. 设置里服务器地址默认应为 https://ai.njzyb.com:3010（若以前存过旧地址，可清数据或手动改）
3. API Key 应已自动持有（设置里有钥匙/不必再粘贴）；健康检查或登录应能通外网
4. 注册/登录仍可用；语音「开始」能连上

## 冒烟（构建机）

- `GET https://ai.njzyb.com:3010/api/v1/health` + X-API-Key → **200**
- `GET http://127.0.0.1:8765/api/v1/health` + X-API-Key → **200**
- 无 Key 访问外网 health → **401**（预期）

## 构建

```bash
cd /workspace/clients/work/android_v023/src
JAVA_HOME=/workspace/toolchains/jdk-17.0.21+9 ./gradlew assembleDebug
```
