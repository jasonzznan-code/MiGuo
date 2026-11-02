# 米果 Android 客户端（Phase 1）

包名 `com.miguo.voice`，App 名 **米果**，minSdk 26（Android 8.0），targetSdk 35。
Kotlin + Jetpack Compose + OkHttp 4 + Coroutines + kotlinx.serialization。
结构对照 iOS 端（MiguoVoiceKit）：`protocol / transport / audio / conversation / ui`，另有 `support`（Keystore 加密存储）。

> ⚠️ 本 App **还没有在真机上运行过**。它能编译、单元测试全部通过，但录音、回声消除、外放路由这些只有在真机上才能验证。第一次用请按下面“看日志”一节抓 `[miguo-audio]` 日志。

---

## 1. 安装

### 方式 A：直接装现成的 APK

文件：`miguo-android-debug.apk`（debug 签名）

- **用 adb 装**（电脑装好 Android platform-tools，手机打开“开发者选项 → USB 调试”）：
  ```bash
  adb install -r miguo-android-debug.apk
  ```
- **不用电脑**：把 APK 发到手机上（微信文件传输助手 / QQ / 数据线拷贝都行），在文件管理里点开。
  系统提示“禁止安装未知来源应用”时，按提示允许**当前这个 App（文件管理/微信等）安装未知应用**，再点安装。
  - 小米/华为/OPPO/vivo 可能还会弹“安全检测”，选“继续安装”即可。

### 方式 B：自己编译

1. 安装 Android Studio（Koala 2024.1 或更新），用它打开 `clients/android` 这个文件夹（就是有 `settings.gradle.kts` 的那一层）。
2. 等 Gradle 同步完成（第一次会下载依赖，国内网络慢的话可以把 `settings.gradle.kts` 里注释掉的阿里云镜像打开）。
3. 菜单 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**，或者顶部绿色 ▶ 直接装到连着的手机上。

命令行编译（需要 **完整 JDK 17 或 21**，只有 JRE 不行——缺 `jlink` 会在 `compileDebugJavaWithJavac` 报错；Android Studio 自带的 JBR 就可以）：
```bash
cd clients/android
# 第一次需要 local.properties 里写 sdk.dir=你的 Android SDK 路径，或设置 ANDROID_HOME
./gradlew assembleDebug testDebugUnitTest      # Windows 用 gradlew.bat
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. 第一次运行

1. 打开米果，点右上角 **设置**。
2. **地址**：默认 `https://ai.njzyb.com:3010`（外网 HTTPS）。局域网可改为 `http://192.168.x.x:8765`。输入时打成全角冒号/句号/空格也没关系，会自动换成半角。
3. **API Key**：新安装已内置默认并写入加密存储，一般**不必再粘贴**。若需换钥匙：把 memory_core `.env` 里 `MEMORY_API_KEY=` 后面那一串粘贴到设置页（留空 = 不修改）。不要把 Key 截图、贴到群里或写进文档。
4. **user_id**：默认 `jason`。
5. 点 **保存并检查连接**：第一次会让服务器加载语音识别模型，最多约 30 秒。看到 ✅ 就好了。
   出 ❌ 时会显示原因和“怎么办”。
   - “保存并检查连接”和“完成”是两个独立按钮，各做各的事。
6. 点 **完成** 回到主界面，点大按钮开始；第一次会弹出**麦克风权限**，选“允许”。

### 使用

- **点一下开始，再点一下结束**。中间像打电话一样直接说话：
  点一下开始 → 连接中… → 正在听 → 你在说话 → 米果在想… → 米果在说 → 自动回到“正在听”。
- Phase 1 是**轮流说话**（半双工）：米果说话时麦克风不上传，要等它说完。
- 声音走**通话音量**：说话时按音量键调的是通话音量，默认外放；插耳机/蓝牙后走耳机。
- 切到后台、锁屏或来电时对话会自动结束（Phase 1 没有前台服务，见“已知限制”）。对话进行中屏幕保持常亮。
- 主界面下方的小字是指标浮层：首音延迟、服务端 stt/llm/tts 耗时、RTT、音频模式和路由。可以在设置里关掉。

---

## 3. 网络要求（连不上先看这里）

- **手机和电脑必须连同一个局域网**（同一个 Wi-Fi/路由器）。手机用 4G/5G 流量或连“访客 Wi-Fi”都连不上 192.168.x.x。
- Windows 防火墙规则 **“Miguo memory_core 8765”只对“专用”网络放行**。如果电脑当前的 Wi-Fi 被 Windows 识别成“公用网络”，手机会连接超时：
  到 **设置 → 网络和 Internet → Wi-Fi → 当前网络属性 → 网络配置文件类型** 改成 **专用**。
- 外网：手机浏览器打开 `https://ai.njzyb.com:3010/api/v1/health`（无 Key 时常见 401，属正常）。局域网可测 `http://192.168.x.x:8765/api/v1/health`。
- App 仍允许明文 HTTP（`res/xml/network_security_config.xml`），以便可选连局域网无 TLS 的服务端。

---

## 4. 服务端下发配置（/api/v1/client/config）

memory_core 是唯一的配置来源。App **启动时**和**每次点开始前**都会请求：

```
GET /api/v1/client/config?platform=android&version=0.1.0-phase1
X-API-Key: …        X-User-Id: …
```

- 返回的 `voice{…}`（输出格式、采样率、帧长、VAD、音色、记忆、`resume_listen_delay_ms`）、
  `playback{watchdog_interval_ms, stall_timeout_s, status_log_interval_s}`、`labels{…}`（状态文案）、`errors{code:{title,fix}}`（错误文案）
  会**覆盖在内置默认值之上**；没给的字段、未知字段一律忽略。
- 拉取成功的那份会缓存起来；下次服务器连不上或返回 404（旧版本服务端没有这个接口）时，用缓存，没有缓存就用内置默认。
- 文案里的占位符会被代入：`{server_message}` `{code}` `{status}` `{reason}` `{description}`（错误），`{n}` `{min}` `{latest}`（状态/更新横幅）。
  错误文案规则与服务端 `api/client_config.py` 注释一致：title 直接替换；message 含占位符才代入，不含占位符只在没有运行时消息时使用；服务端 error 事件自带的 fix 优先。
  服务端出厂文案里麦克风权限 / 127.0.0.1 这几条是 iOS 措辞，Android 只取标题、正文保留 Android 版本；服务端可以写 `errors.mic_denied.android = {title, message, fix}` 单独给 Android 文案。
- 版本低于 `min_client_version` → 顶部红色横幅“App 版本过低…”；低于 `latest_version` → 橙色“有新版本…”。点“去更新”：
  - `update_url` 指向本服务器（如 `/api/v1/client/download/miguo-0.2.0.apk`，需要 X-API-Key 头）→ App 用系统下载器带头下载，下载完点通知栏安装；
  - 其他地址 → 用浏览器打开（不会把 API Key 发给第三方）。
  - `update_url` 为空时只提示，不显示按钮。
- `notice` 非空（字符串，或 `{"text": "..."}`）时主界面顶部显示公告。
- 设置页里的语音选项默认是“**跟随服务器**”；只有你在设置里**明确选了** mp3 / pcm_s16le（或 VAD、帧长、记忆、音色）才覆盖服务器的值。
- 打断（barge-in）Phase 1 客户端固定关闭，即使服务器下发 `barge_in: true`。
- 设置页底部显示当前配置来源（服务端 vN / 缓存 / 内置默认）。

---

## 5. 看日志（排查没声音、回声、断连）

手机连电脑、打开 USB 调试后：

```bash
# 只看音频引擎（对应 iOS 的 [miguo-audio] 日志）
adb logcat -v time | grep "\[miguo-audio\]"
# Windows PowerShell：
adb logcat -v time | Select-String "miguo-audio"

# 音频 + 会话状态 + WebSocket
adb logcat -v time -s miguo-audio:* miguo:* miguo-ws:*
```

关键日志（每行都以 `[miguo-audio]` 开头）：
- `会话启动：mode=MODE_IN_COMMUNICATION STREAM_VOICE_CALL 音量=… 输出=… AEC 可用=… 启用=… NS=… AGC=…` —— 启动时的音频状态；
- `turn N 首个 buffer 入队` / `turn N 首个 buffer 开始播放` / `播放排空：turn=N …` —— 每轮第一个音频块入队、开始出声、播完；
- `状态：track=PLAYING 通话音量=… 排队中=…s 已写=… 已播=…` —— 播放中每 2 秒一行（间隔可由服务端 `status_log_interval_s` 调）；
- `⚠ 发现 AudioTrack 不在播放 … 正在自动恢复…` / `AudioTrack 已创建（…）` —— 看门狗（每 0.5 s）发现播放器停了并自动拉起/重建；
- `✗ 播放卡住：已超过应播完时刻 …，清空队列并重启播放器` —— 5 秒没有进展，强制清空并回到“正在听”，防止卡在“米果在说”；
- `音频设备变化` / `AudioManager 模式变化` / `通信设备变化` / `音频焦点变化` —— 插拔耳机、蓝牙、别的 App 改了音频模式、来电；
- `⚠ 通话音量为 0` —— 没声音时先看这一行，对话中按音量键调大。

会话状态机日志 tag 是 `miguo`（ready / vad_start / vad_end / transcript / response_end / metrics …），WebSocket 是 `miguo-ws`，服务端配置拉取是 `miguo-config`。

---

## 6. 已知限制（Phase 1）

- **从未在真机上跑过**：只验证了编译、lint（0 error）和 40 个 JVM 单元测试（协议编解码、mp3 分帧、音频头+二进制配对、配置合并、MockWebServer 模拟服务端、状态机）。AEC 效果、外放音量、各厂商 ROM 的音频路由都需要真机确认。
- **没有前台服务**：所以不能锁屏/后台持续对话（Android 14+ 后台录音需要 `FOREGROUND_SERVICE_MICROPHONE` 前台服务和常驻通知）。切后台即结束会话，和 iOS Phase 1 行为一致。需要时阶段 2 再加。
- 只支持**一个**服务器配置（iOS 端可以保存多个）。
- 没有断线自动重连 / 会话续接；断了点一下重新开始。
- 不支持打断（barge-in）。
- 蓝牙耳机：通话模式下系统一般会自动走 SCO/LE Audio，但没有专门处理蓝牙 SCO 的建立失败、音质切换等情况。
- mp3 用系统 MediaCodec 解码；个别低端机的解码器有起播延迟。遇到问题可以在设置里切到 `pcm_s16le`（服务端直接给 24 kHz PCM，最稳，但流量大一些）。
- debug 签名，不能上架；覆盖安装时签名要一致（换一台电脑编译的 debug 包需要先卸载旧版）。
