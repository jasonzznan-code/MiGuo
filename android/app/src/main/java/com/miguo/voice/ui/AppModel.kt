package com.miguo.voice.ui

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miguo.voice.BuildConfig
import com.miguo.voice.audio.VoiceAudioEngine
import com.miguo.voice.conversation.AppSettings
import com.miguo.voice.conversation.ConversationEngine
import com.miguo.voice.conversation.makeParams
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.DeepSeekProviderContract
import com.miguo.voice.protocol.ReadableError
import com.miguo.voice.protocol.ServerEndpoint
import com.miguo.voice.support.SecureStore
import com.miguo.voice.support.SettingsStore
import com.miguo.voice.transport.ClientConfigApi
import com.miguo.voice.transport.HealthResult
import com.miguo.voice.transport.VoiceHealthApi
import com.miguo.voice.transport.VoiceSocket
import com.miguo.voice.conversation.validateIdentity
import com.miguo.voice.protocol.HeaderValues
import com.miguo.voice.support.CrashReporter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.miguo.voice.audio.ReplyPlayer
import com.miguo.voice.conversation.AuthState
import com.miguo.voice.conversation.AuthStateMachine
import com.miguo.voice.conversation.ChatLog
import com.miguo.voice.conversation.ConversationState
import com.miguo.voice.conversation.Identity
import com.miguo.voice.conversation.TextChatController
import com.miguo.voice.protocol.ApiResult
import com.miguo.voice.protocol.ConversationSummary
import com.miguo.voice.protocol.SessionIdFactory
import com.miguo.voice.support.SecureTokenStore
import com.miguo.voice.transport.AuthBackendFactory
import com.miguo.voice.transport.AuthHealthApi
import com.miguo.voice.transport.ChatApi
import com.miguo.voice.location.DeviceLocator
import com.miguo.voice.location.GeoFix
import com.miguo.voice.location.LocateOutcome
import com.miguo.voice.location.LocationPolicy
import com.miguo.voice.location.LocationWire
import com.miguo.voice.location.PermissionLevel
import com.miguo.voice.protocol.WireLocation
import com.miguo.voice.transport.MapApi
import com.miguo.voice.transport.MapResult
import com.miguo.voice.transport.ReverseAddress

enum class LocationAction { APP_SETTINGS, LOCATION_SETTINGS }

/** “我的位置”卡片的状态（合并自 0.1.2 location） */
data class LocationUi(
    val busy: Boolean = false,
    val step: String? = null,
    val permission: PermissionLevel? = null,
    val fix: GeoFix? = null,
    /** 拿到 [fix] 时的墙钟（毫秒），用于判断多旧 */
    val fixAtMs: Long = 0,
    val address: ReverseAddress? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val action: LocationAction? = null,
    val requestUrl: String? = null,
)

/** App 级状态（对应 iOS AppModel.swift）：设置 + API Key（Keystore）+ 服务端配置 + 唯一的 ConversationEngine。 */
class AppModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val secure = SecureStore(app)

    init {
        seedDefaultApiKeyIfNeeded()
    }

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<AppSettings> = _settings

    /** 内置默认 ← 上次成功的缓存；之后每次拉取成功再覆盖 */
    private val _config = MutableStateFlow(loadCachedConfig())
    val config: StateFlow<ClientConfig> = _config
    private val _configStatus = MutableStateFlow("未拉取")
    val configStatus: StateFlow<String> = _configStatus

    /**
     * fix1：所有协程都跑在带 CoroutineExceptionHandler 的作用域里。以前 viewModelScope 没有 handler，
     * 任何未捕获异常（例如非 ASCII 请求头）都会冒到主线程直接闪退；现在记录到 crash.txt 并在界面上显示错误。
     */
    private val crashHandler = CoroutineExceptionHandler { _, e ->
        Log.e("miguo", "[miguo] 协程未捕获异常", e)
        CrashReporter.record(e, "coroutine (non-fatal)")
        _crashReport.value = CrashReporter.read(app)
        try { engine.onUncaught(e) } catch (_: Throwable) {}
    }
    val scope: CoroutineScope = CoroutineScope(viewModelScope.coroutineContext + crashHandler)

    private val _crashReport = MutableStateFlow(CrashReporter.read(app))
    /** 上次崩溃 / 本次非致命异常的报告（null = 无） */
    val crashReport: StateFlow<String?> = _crashReport
    fun clearCrashReport() {
        CrashReporter.clear(getApplication())
        _crashReport.value = null
    }

    val audio = VoiceAudioEngine(app)
    /** v0.2.0：聊天气泡（语音 + 文字 + 历史共用） */
    val chat = ChatLog()

    /** 对话/语音（memory_core） */
    private fun chatEndpointOrNull(): ServerEndpoint? = try { ServerEndpoint.parse(_settings.value.address) } catch (e: Exception) { null }
    /** 登录（nano relay / 或用户改成的其它 auth 基址） */
    private fun authEndpointOrNull(): ServerEndpoint? = try { ServerEndpoint.parse(_settings.value.authAddress) } catch (e: Exception) { null }
    @Deprecated("use chatEndpointOrNull", ReplaceWith("chatEndpointOrNull()"))
    private fun endpointOrNull(): ServerEndpoint? = chatEndpointOrNull()


    /** v0.2.0：登录状态机（token 加密存 SecureStore） */
    val auth = AuthStateMachine(
        backend = AuthBackendFactory.create({ authEndpointOrNull() }, { apiKey() }),
        store = SecureTokenStore(secure),
        skippedProvider = { _settings.value.authSkipped },
        setSkipped = { v -> if (_settings.value.authSkipped != v) updateSettings { it.copy(authSkipped = v) } },
        log = { Log.i("miguo", "[miguo] $it") },
    )
    private val chatApi = ChatApi({ chatEndpointOrNull() }, { apiKey() })
    private val replyPlayer = ReplyPlayer(app)

    private val _conversationId = MutableStateFlow<String?>(null)
    /** 当前账号的稳定对话 ID（null = 还没有，第一次开始时由服务器续接最近对话 / 新建） */
    val conversationId: StateFlow<String?> = _conversationId

    val engine = ConversationEngine(
        audio = audio,
        makeTransport = { VoiceSocket() },
        paramsProvider = {
            replyPlayer.stop()
            refreshConfig(timeoutMs = 2000)          // 每次开始前刷新一次（失败就用缓存/内置）
            auth.ensureFresh()                       // access token 快过期就先续期
            val loc = currentWireLocation()
            refreshLocationQuietly()                 // 已授权且定位旧了 → 后台静默定位，拿到后用 {type:"location"} 更新
            Log.i("miguo", "[miguo] start.location=" + (loc?.let { "±${it.accuracyM ?: "?"}m ${it.provider}" } ?: "无"))
            _settings.value.makeParams(apiKey(), _config.value, sessionId = currentConversationId(), auth = sessionAuth(), location = loc)
        },
        scope = scope,
        chat = chat,
    ).also { e ->
        e.lastConfig = _config.value
        e.onLog = { Log.i("miguo", "[miguo] $it") }
        e.onReady = { r -> setConversationId(r.sessionId) }
        e.onUnauthorized = { tok -> scope.launch { onVoiceUnauthorized(tok) } }
    }

    /** v0.2.0：文字输入（会话中走 WS，否则 HTTP /chat/text） */
    val text = TextChatController(
        backend = chatApi,
        chat = chat,
        scope = scope,
        identity = { identity() },
        conversationId = { currentConversationId() },
        onConversationId = { setConversationId(it) },
        voice = { t -> engine.sendText(t) },
        speak = { _settings.value.speakTextReplies },
        useMemory = { _settings.value.useMemory ?: _config.value.voice.useMemory },
        ttsVoice = { _settings.value.voice.trim().ifEmpty { null } },
        ttsRate = { com.miguo.voice.conversation.TtsProsody.formatPercent(_settings.value.ttsRatePercent) },
        ttsVolume = { com.miguo.voice.conversation.TtsProsody.formatPercent(_settings.value.ttsVolumePercent) },
        ttsPitch = { com.miguo.voice.conversation.TtsProsody.formatPitchHz(_settings.value.ttsPitchHz) },
        player = replyPlayer,
        refreshAuth = { tok -> auth.onUnauthorized(tok) },
        log = { Log.i("miguo", "[miguo] $it") },
        location = { currentWireLocation() },
    )

    /**
     * 当前请求身份：登录后一律带用户 access token（Authorization Bearer）。
     * store 永不内置生产 Key。CHAT 的 X-API-Key 见 [apiKey]：优先 SecureStore/内测默认，
     * 否则用登录下发的可吊销凭证（nano verify 的 api_key，已映射为 accessToken）。
     */
    fun identity(): Identity {
        val s = auth.session
        return if (s != null) {
            Identity(s.userId, s.accessToken)
        } else {
            Identity(_settings.value.userId.trim(), null)
        }
    }

    /** 语音/会话参数：登录态把用户 token 交给 memory_core（含 nano OTP 下发的 access） */
    private fun sessionAuth() = auth.session

    private fun accountKey(): String = auth.session?.let { "u:${it.userId}" } ?: "id:${_settings.value.userId.trim()}"

    fun currentConversationId(): String? = store.conversationId(accountKey())?.takeIf { SessionIdFactory.isValid(it) }

    private fun setConversationId(id: String?) {
        if (id != null && !SessionIdFactory.isValid(id)) return
        store.setConversationId(accountKey(), id)
        _conversationId.value = id
    }

    private var lastAutoRetryAt = 0L

    /** WS 被拒（token 失效）：刷新 token；成功则自动重连一次（30 秒内最多一次，防止死循环） */
    private suspend fun onVoiceUnauthorized(tok: String?) {
        val refreshed = auth.onUnauthorized(tok)
        if (!refreshed) return
        val now = System.currentTimeMillis()
        if (now - lastAutoRetryAt < 30_000) return
        lastAutoRetryAt = now
        withTimeoutOrNull(8000) { engine.state.first { it is ConversationState.Error || it is ConversationState.Idle } } ?: return
        Log.i("miguo", "[miguo] token 已续期，自动重新开始会话")
        engine.dismissError()
        engine.start()
    }

    val appVersion: String = BuildConfig.VERSION_NAME

    // 我的位置：属性必须在 init 之前初始化（init 里会调 refreshLocationQuietly）
    val locator = DeviceLocator(app)
    private val _location = MutableStateFlow(LocationUi())
    val location: StateFlow<LocationUi> = _location

    /** 由 MainActivity 注入：弹出 FINE+COARSE 权限请求，返回授权后的级别 */
    var locationPermissionRequester: (suspend () -> PermissionLevel)? = null
    /** 权限弹窗进行中（MainActivity.onStop 据此不结束语音会话） */
    @Volatile var locationPermissionPending = false

    init {
        scope.launch {
            refreshConfig()
            auth.bootstrap()
            if (auth.state.value.canUseApp) loadConversation()
            refreshHealth(loadStt = true)
            refreshLocationQuietly()                 // 已授权才定位；不弹权限框
        }
    }

    // MARK: - v0.2.0 登录 / 对话

    fun login(username: String, password: String) {
        scope.launch { if (auth.login(username, password)) afterAuth() }
    }

    fun requestCode(identifier: String, onDone: (String?) -> Unit = {}) {
        scope.launch { onDone(auth.requestCode(identifier)) }
    }

    fun verifyCode(identifier: String, code: String) {
        scope.launch { if (auth.verifyCode(identifier, code)) afterAuth() }
    }

    fun register(username: String, password: String, displayName: String?, claimUserId: String?) {
        scope.launch { if (auth.register(username, password, displayName, claimUserId)) afterAuth() }
    }

    fun skipLogin() {
        // store / release：ALLOW_SKIP_LOGIN=false，路由不可达
        if (!BuildConfig.ALLOW_SKIP_LOGIN) return
        if (auth.skipLogin()) scope.launch { afterAuth() }
    }

    fun showLogin() {
        scope.launch {
            if (engine.state.value.isActive) engine.stop()
            text.cancel()
            chat.clear()
            auth.showLogin()
        }
    }

    fun logout(everywhere: Boolean = false) {
        scope.launch { clearLocalAfterAccountLeave { auth.logout(everywhere) } }
    }

    /**
     * 修改密码。成功 → 清本地并回登录页（服务端已吊销全部 token）；失败 → 返回错误文案，保持登录。
     * UI 用回调拿结果（Settings 对话框）。
     */
    fun changePassword(oldPassword: String, newPassword: String, onDone: (String?) -> Unit) {
        scope.launch {
            val err = auth.changePassword(oldPassword, newPassword)
            if (err == null) clearLocalSessionOnly()
            onDone(err)
        }
    }

        /** 未登录注销申请：写本机时间戳。 */
    fun recordDeletionRequest(): Long = store.saveDeletionRequest()

    fun deletionRequestAtMs(): Long = store.deletionRequestAtMs()

/** 注销账号。成功 → 清本地；失败 → 错误文案。 */
    fun deleteAccount(password: String, onDone: (String?) -> Unit) {
        scope.launch {
            val err = auth.deleteAccount(password)
            if (err == null) clearLocalSessionOnly()
            onDone(err)
        }
    }

    private suspend fun clearLocalAfterAccountLeave(remote: suspend () -> Unit) {
        if (engine.state.value.isActive) engine.stop()
        text.cancel()
        replyPlayer.stop()
        remote()
        chat.clear()
        _conversationId.value = null
    }

    /** 密码已改 / 账号已注销后：状态机已 LoggedOut，只清对话本地态 */
    private suspend fun clearLocalSessionOnly() {
        if (engine.state.value.isActive) try { engine.stop() } catch (_: Exception) {}
        text.cancel()
        replyPlayer.stop()
        chat.clear()
        _conversationId.value = null
    }

    /** 重新检查登录（换了服务器地址 / API Key 后，或登录页点“重试”） */
    fun recheckAuth() {
        scope.launch {
            auth.bootstrap()
            if (auth.state.value.canUseApp) loadConversation()
        }
    }

    private suspend fun afterAuth() {
        loadConversation()
        refreshHealth()
    }

    // ---- 对话记录（汉堡菜单）----
    private val _historyOpen = MutableStateFlow(false)
    val historyOpen: StateFlow<Boolean> = _historyOpen
    private val _historyItems = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val historyItems: StateFlow<List<ConversationSummary>> = _historyItems
    private val _historyBusy = MutableStateFlow(false)
    val historyBusy: StateFlow<Boolean> = _historyBusy
    private val _historyError = MutableStateFlow<String?>(null)
    val historyError: StateFlow<String?> = _historyError

    fun openHistory() {
        _historyOpen.value = true
        refreshHistory()
    }

    fun dismissHistory() {
        _historyOpen.value = false
        _historyError.value = null
    }

    fun refreshHistory() {
        scope.launch {
            _historyBusy.value = true
            _historyError.value = null
            try {
                when (val r = withAuthRetry { chatApi.listConversations(identity(), 50) }) {
                    is ApiResult.Ok -> _historyItems.value = r.value
                    is ApiResult.Unauthorized -> _historyError.value = "登录已失效，请重新登录"
                    ApiResult.NotSupported -> _historyError.value = "服务器还不支持对话列表"
                    is ApiResult.HttpError -> _historyError.value = "加载失败：HTTP ${r.status}"
                    is ApiResult.Network -> _historyError.value = "网络：${r.message}"
                }
            } catch (e: Exception) {
                _historyError.value = e.message ?: e.toString()
            } finally {
                _historyBusy.value = false
            }
        }
    }

    /** 用此会话继续：切到该 conversation_id，拉消息；下次点开始会 resume 该会话 */
    fun openConversation(id: String) {
        if (!SessionIdFactory.isValid(id)) {
            _historyError.value = "会话 ID 无效"
            return
        }
        scope.launch {
            if (engine.state.value.isActive) engine.stop()
            text.cancel()
            replyPlayer.stop()
            chat.clear()
            setConversationId(id)
            _historyOpen.value = false
            when (val r = withAuthRetry { chatApi.messages(id, identity()) }) {
                is ApiResult.Ok -> chat.setHistory(r.value)
                else -> Log.i("miguo", "[miguo] 打开会话后拉消息失败：$r")
            }
        }
    }

    /** “新对话”：结束当前会话，清空本地气泡，申请/生成新 conversation_id（resume=false）。
     *  跨会话摘要只在「新会话且当前会话 <2 条」时由服务端注入；旧会话日期事实不会带进新 ID。 */
    fun newConversation() {
        scope.launch {
            if (engine.state.value.isActive) engine.stop()
            text.cancel()
            replyPlayer.stop()
            chat.clear()
            // 优先让服务器发新 ID；失败则本地生成（仍保证不会 resume 旧会话）
            val serverId = try {
                when (val r = withAuthRetry { chatApi.newConversation(identity()) }) {
                    is ApiResult.Ok -> r.value
                    else -> null
                }
            } catch (_: Exception) { null }
            setConversationId(serverId?.takeIf { it.isNotBlank() } ?: SessionIdFactory.make())
        }
    }

    /** 载入当前账号的对话：本地记住的 ID → 否则服务器最近一次对话；再拉聊天记录显示成气泡 */
    suspend fun loadConversation() {
        if (engine.state.value.isActive) return
        chat.clear()
        var cid = currentConversationId()
        _conversationId.value = cid
        if (cid == null) {
            when (val r = withAuthRetry { chatApi.latestConversation(identity()) }) {
                is ApiResult.Ok -> r.value?.let { setConversationId(it); cid = it }
                else -> Log.i("miguo", "[miguo] 获取最近对话失败/不支持：$r")
            }
        }
        val id = cid ?: return
        when (val r = withAuthRetry { chatApi.messages(id, identity()) }) {
            is ApiResult.Ok -> if (!engine.state.value.isActive && chat.entries.value.isEmpty()) chat.setHistory(r.value)
            else -> Log.i("miguo", "[miguo] 获取聊天记录失败/不支持：$r")
        }
    }

    private suspend fun <T> withAuthRetry(f: suspend () -> ApiResult<T>): ApiResult<T> {
        val tok = auth.accessToken
        val r = f()
        if (r is ApiResult.Unauthorized && tok != null && auth.onUnauthorized(tok)) return f()
        return r
    }

    // MARK: - 更新（min_client_version / latest_version / update_url）

    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage
    fun clearUpdateMessage() { _updateMessage.value = null }

    /**
     * 打开 update_url：
     * - 指向本服务器（例如 /api/v1/client/download/miguo.apk，需要 X-API-Key 头）→ 用系统 DownloadManager 带头下载，
     *   下载完成后点通知栏安装；
     * - 其他地址 → 交给浏览器打开（不会把 API Key 发给第三方）。
     */
    fun openUpdate(ctx: Context) {
        val raw = _config.value.updateUrl ?: return
        val ep = try { ServerEndpoint.parse(_settings.value.address) } catch (e: Exception) { null }
        val resolved = when {
            raw.startsWith("/") && ep != null -> ep.httpBase + raw
            else -> raw
        }
        val target = resolved.toHttpUrlOrNull()
        val base = ep?.httpBase?.toHttpUrlOrNull()
        val sameServer = target != null && base != null && target.host == base.host && target.port == base.port
        try {
            if (sameServer) {
                val name = "miguo-" + (_config.value.latestVersion ?: "update").replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
                val req = DownloadManager.Request(Uri.parse(resolved))
                    .setTitle("米果 新版本")
                    .setDescription(name)
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .addRequestHeader("X-User-Id", _settings.value.userId.trim())
                apiKey()?.let { req.addRequestHeader("X-API-Key", it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                } else {
                    req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
                }
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                _updateMessage.value = "正在下载 $name，完成后点通知栏安装（第一次需要允许“安装未知应用”）。"
            } else {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(resolved)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            _updateMessage.value = "无法打开更新地址：${e.message ?: e.javaClass.simpleName}（$resolved）"
        }
    }

    /**
     * 仅 internal flavor：首次启动（或 SecureStore 尚无 api_key）时，把构建期 DEFAULT_API_KEY 写入加密存储。
     * store / 非 internal / 空 key：绝不写入。已有用户自存的 key 绝不覆盖。
     */
    private fun seedDefaultApiKeyIfNeeded() {
        try {
            if (!BuildConfig.IS_INTERNAL) return
            if (!secure.get(SecureStore.API_KEY).isNullOrBlank()) return
            val def = BuildConfig.DEFAULT_API_KEY.trim()
            if (def.isEmpty()) return
            secure.set(SecureStore.API_KEY, def)
            Log.i("miguo", "[miguo] 已写入内置默认 API Key（SecureStore 此前为空；仅 internal）")
        } catch (e: Exception) {
            Log.w("miguo", "默认 API Key 写入失败：${e.javaClass.simpleName}")
        }
    }

    /**
     * 出站 X-API-Key（vc60 定稿）：
     * 1) 已登录：优先会话凭证（邮箱 OTP 下发的 nm_… → AuthSession.accessToken）；
     * 2) 否则 SecureStore 用户/内测写入的设备 Key；
     * 3) 仅 internal 才回退构建期 DEFAULT_API_KEY。
     * 同包名 internal→store 升级时，避免旧 SecureStore Key 盖住登录 token 导致主会话 401。
     * 绝不把生产 Key 写进 store APK；也不把会话凭证持久化成「内置默认 Key」。
     */
    fun apiKey(): String? {
        auth.session?.accessToken?.takeIf { it.isNotBlank() }?.let { return it }
        try {
            secure.get(SecureStore.API_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        } catch (_: Exception) {}
        if (!BuildConfig.IS_INTERNAL) return null
        return BuildConfig.DEFAULT_API_KEY.trim().takeIf { it.isNotEmpty() }
    }

    fun hasApiKey(): Boolean = apiKey() != null

    /** 保存设置；返回 null = 正常，否则是给用户看的问题说明（设置仍然保存，方便用户继续改） */
    fun saveSettings(s: AppSettings, apiKey: String?): String? {
        val clean = s.copy(address = ServerEndpoint.normalizeInput(s.address), authAddress = ServerEndpoint.normalizeInput(s.authAddress), userId = s.userId.trim())
        val before = _settings.value
        val keyChanged = HeaderValues.cleanApiKey(apiKey)?.let { it != this.apiKey() } ?: false
        val st = auth.state.value
        // 换了服务器 / API Key，或之前没登录成功 → 重新检查登录状态；user_id 模式下换了 user_id → 换对话
        if (before.address != clean.address || before.authAddress != clean.authAddress || keyChanged || st is AuthState.LoggedOut || (st is AuthState.LoggedIn && st.offline)) {
            scope.launch { kotlinx.coroutines.delay(50); recheckAuth() }
        } else if (before.userId != clean.userId && auth.session == null) {
            scope.launch { kotlinx.coroutines.delay(50); loadConversation() }
        }
        _settings.value = clean
        store.save(clean)
        val key = HeaderValues.cleanApiKey(apiKey)
        if (key != null) {
            try { secure.set(SecureStore.API_KEY, key) } catch (e: Exception) {
                Log.e("miguo", "[miguo] SecureStore 保存失败", e)
                return "API Key 保存失败（系统密钥库异常）：${e.javaClass.simpleName}"
            }
        }
        return validateSettings()
    }

    /** fix1：地址 / user_id / API Key 的可读校验结果（null = 没问题） */
    fun validateSettings(): String? {
        val s = _settings.value
        try { ServerEndpoint.parse(s.address) } catch (e: Exception) { return "对话服务器地址不对：${e.message}" }
        try { ServerEndpoint.parse(s.authAddress) } catch (e: Exception) { return "登录服务器地址不对：${e.message}" }
        return try { validateIdentity(s.userId, apiKey()); null } catch (e: ReadableError) { "${e.title}：${e.message}" }
    }

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val s = f(_settings.value)
        _settings.value = s
        store.save(s)
    }

    private fun loadCachedConfig(): ClientConfig {
        val raw = store.cachedConfigJson ?: return ClientConfig()
        // 旧缓存若被误写入供应商密钥，读出来时丢掉并覆盖。没有密钥时 safe == raw。
        val safe = DeepSeekProviderContract.redactConfigSecrets(raw)
        if (safe != raw) store.cachedConfigJson = safe
        return try {
            ClientConfig().mergedWith(ClientConfig.parseJson(safe), source = "cache")
        } catch (e: Exception) {
            ClientConfig()
        }
    }

    /** GET /api/v1/client/config：成功 → 合并到内置默认之上并缓存；404/失败 → 保持缓存/内置 */
    suspend fun refreshConfig(timeoutMs: Long = 3000) {
        val ep = try { ServerEndpoint.parse(_settings.value.address) } catch (e: Exception) { return }
        when (val r = ClientConfigApi.fetch(ep, _settings.value.userId, apiKey(), appVersion, timeoutMs)) {
            is ClientConfigApi.Result.Ok -> {
                val merged = ClientConfig().mergedWith(r.json, source = "server")
                _config.value = merged
                engine.lastConfig = merged
                store.cachedConfigJson = r.raw
                _configStatus.value = "服务端配置 v${merged.configVersion}"
            }
            ClientConfigApi.Result.NotSupported -> _configStatus.value = "服务端无 /client/config，使用${if (_config.value.source == "cache") "缓存" else "内置默认"}"
            is ClientConfigApi.Result.Failed -> _configStatus.value = "拉取配置失败（${r.reason}），使用${if (_config.value.source == "cache") "缓存" else "内置默认"}"
        }
    }

    suspend fun refreshHealth(loadStt: Boolean = false) {
        val s = _settings.value
        // fix1：不合法的 user_id / key 直接显示为健康检查失败，不再发请求（以前会抛异常闪退）
        val uid = identity().userId ?: s.userId.trim()
        try { if (auth.session == null) validateIdentity(uid, apiKey()) else com.miguo.voice.conversation.validateApiKey(apiKey()) } catch (e: ReadableError) {
            engine.setHealth(com.miguo.voice.transport.HealthResult.Failed(e)); return
        }
        val ep = try { ServerEndpoint.parse(s.address) } catch (e: Exception) { null }
        engine.refreshHealth(ep, uid, apiKey(), loadStt)
    }

    /** 设置页：检查登录基址（/healthz 或 /api/v1/health） */
    suspend fun testAuthConnection(): String {
        val ep = try {
            ServerEndpoint.parse(_settings.value.authAddress)
        } catch (e: Exception) {
            return "✗ 登录服务器地址不对：${e.message}"
        }
        return AuthHealthApi.format(AuthHealthApi.check(ep))
    }

    /** 设置页“保存并检查连接”（对话/语音 memory_core） */
    suspend fun testConnection(): String {
        val p = try {
            _settings.value.makeParams(apiKey(), _config.value, auth = auth.session)
        } catch (e: ReadableError) {
            return "❌ ${e.title}：${e.fix ?: e.message}"
        }
        refreshConfig()
        val r = VoiceHealthApi.check(p.endpoint, p.userId, p.apiKey, loadStt = true, probeTts = true)
        val cfgLine = "\n配置：${_configStatus.value}"
        return when (r) {
            is HealthResult.Ok -> "✅ " + VoiceHealthApi.summary(r) + cfgLine
            is HealthResult.Degraded -> "⚠️ " + VoiceHealthApi.summary(r) + cfgLine
            is HealthResult.Failed -> {
                val e = _config.value.apply(r.e)
                "❌ ${e.title}\n原始错误：${e.message}\n请求地址：${p.endpoint.voiceHealthUrl()}\n${e.fix ?: ""}"
            }
        }
    }

    // MARK: - 我的位置（合并自 0.1.2：LocationManager → /api/v1/map/reverse；v0.2.0 再发给语音/文字对话）

    // （locator / location 等属性在 init 之前声明，见上）

    /** 发给服务端的当前位置；没有 / 太旧（>2 小时）→ null（服务端会让模型如实说不知道） */
    fun currentWireLocation(): WireLocation? = _location.value.let { LocationWire.toWire(it.fix, it.fixAtMs, System.currentTimeMillis()) }

    /** 已经有定位权限、定位比 10 分钟旧 → 后台静默定位一次（不弹权限框，失败不提示） */
    fun refreshLocationQuietly() {
        val cur = _location.value
        if (cur.busy || !LocationWire.needsRefresh(cur.fix, cur.fixAtMs, System.currentTimeMillis())) return
        if (locator.permissionLevel() == PermissionLevel.NONE) return
        scope.launch { runLocate(interactive = false) }
    }

    fun locateMe() {
        if (_location.value.busy) return
        scope.launch { runLocate(interactive = true) }
    }

    private suspend fun runLocate(interactive: Boolean) {
        if (_location.value.busy) return
        val prevError = _location.value.error
        _location.value = _location.value.copy(busy = true, step = "检查定位权限…", error = if (interactive) null else prevError, action = null)
        var level = locator.permissionLevel()
        if (level == PermissionLevel.NONE && interactive) {
            level = locationPermissionRequester?.invoke() ?: PermissionLevel.NONE
        }
        _location.value = _location.value.copy(permission = level)
        if (level == PermissionLevel.NONE) {
            _location.value = _location.value.copy(busy = false, step = null,
                error = if (interactive) "没有定位权限：米果不会用 IP 猜你的位置，所以拒绝授权就无法显示“我的位置”，语音助手也会如实说不知道你在哪。" else prevError,
                action = LocationAction.APP_SETTINGS)
            return
        }
        _location.value = _location.value.copy(step = "正在定位（GPS / 网络，最多 ${LocationPolicy.CURRENT_TIMEOUT_MS / 1000} 秒）…")
        when (val o = locator.locate(log = { Log.i("miguo-loc", "[miguo] $it") })) {
            LocateOutcome.NoPermission -> _location.value = _location.value.copy(busy = false, step = null,
                error = "没有定位权限。", action = LocationAction.APP_SETTINGS)
            LocateOutcome.LocationOff -> _location.value = _location.value.copy(busy = false, step = null,
                error = if (interactive) "手机的“位置信息/定位服务”总开关是关的。请打开后点“重新定位”。" else prevError,
                action = LocationAction.LOCATION_SETTINGS)
            is LocateOutcome.Failed -> _location.value = _location.value.copy(busy = false, step = null,
                error = if (interactive) "定位失败：${o.reason}。请到室外/窗边，确认 GPS 和 Wi-Fi 已打开后重试。（不会回退到 IP 定位）" else prevError)
            is LocateOutcome.Ok -> {
                val f = o.fix
                _location.value = _location.value.copy(fix = f, fixAtMs = System.currentTimeMillis(), permission = o.level,
                    warnings = LocationPolicy.warnings(f, o.level), address = null, step = "正在查询地址…", error = null,
                    action = if (o.level == PermissionLevel.COARSE_ONLY) LocationAction.APP_SETTINGS else null)
                // v0.2.0：语音会话进行中 → 立即把新位置发给服务端（会话外：下次 start / 文字对话会带上）
                currentWireLocation()?.let { engine.sendLocation(it) }
                val ep = try { ServerEndpoint.parse(_settings.value.address) } catch (e: Exception) { null }
                if (ep == null) {
                    _location.value = _location.value.copy(busy = false, step = null, error = "服务器地址不对，无法查询地址（坐标已显示）。")
                    return
                }
                val uid = identity().userId ?: _settings.value.userId.trim()
                when (val r = MapApi.reverse(ep, uid, apiKey(), f.lat, f.lon)) {
                    is MapResult.Ok -> _location.value = _location.value.copy(busy = false, step = null, address = r.addr, requestUrl = r.url)
                    is MapResult.Failed -> _location.value = _location.value.copy(busy = false, step = null, requestUrl = r.url,
                        error = "地址查询失败：${r.e.title}。${r.e.fix ?: r.e.message}")
                }
            }
        }
    }

    override fun onCleared() {
        try { audio.stopSession() } catch (e: Throwable) { Log.w("miguo", "[miguo] stopSession 失败：$e") }
        try { replyPlayer.stop() } catch (_: Throwable) {}
        super.onCleared()
    }
}
