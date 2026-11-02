package com.miguo.voice.ui

import android.Manifest
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import com.miguo.voice.ui.shell.MigoShell
import com.miguo.voice.ui.theme.MigoTokens

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    // 在 Activity 构造期注册（早于 STARTED，符合 Activity Result API 要求）。
    // fix1：等待结果的 Deferred 存在 ViewModel 里（audio.pendingPermission），Activity 被重建时结果会投递到新实例，
    // 旧会话不会永远卡在“连接中…”。
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        android.util.Log.i("miguo", "[miguo] RECORD_AUDIO 授权结果：$granted")
        model.audio.pendingPermission?.complete(granted)
        model.audio.pendingPermission = null
    }

    // 定位权限（合并自 0.1.2：同时申请 FINE + COARSE；Android 12+ 用户可以只给“大致位置”）
    private var pendingLocation: CompletableDeferred<Unit>? = null
    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        android.util.Log.i("miguo", "[miguo] 定位授权结果：$r")
        pendingLocation?.complete(Unit)
        pendingLocation = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 音量键调节“通话音量”（USAGE_VOICE_COMMUNICATION 走 STREAM_VOICE_CALL，和媒体音量分开）
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        model.audio.permissionRequester = {
            val d = CompletableDeferred<Boolean>()
            model.audio.pendingPermission?.complete(false)
            model.audio.pendingPermission = d
            try {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                d.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("miguo", "[miguo] 无法弹出麦克风权限请求", e)
                model.audio.pendingPermission = null
                false
            }
        }

        model.locationPermissionRequester = {
            val d = CompletableDeferred<Unit>()
            pendingLocation?.complete(Unit)
            pendingLocation = d
            model.locationPermissionPending = true
            try {
                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                d.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("miguo", "[miguo] 无法弹出定位权限请求", e)
            } finally {
                model.locationPermissionPending = false
            }
            model.locator.permissionLevel()
        }

        // 对话中不锁屏（Phase 1 没有前台服务，息屏/切后台即结束对话）
        model.scope.launch {
            model.engine.state.collect { s ->
                if (s.isActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = MigoTokens.Primary,
                secondary = MigoTokens.Error,
                background = MigoTokens.White,
                surface = MigoTokens.White,
                onPrimary = Color.White,
                onBackground = MigoTokens.Ink,
                onSurface = MigoTokens.Ink,
            )) {
                val auth by model.auth.state.collectAsState()
                var showChangeServer by remember { mutableStateOf(false) }
                var showLegal by remember { mutableStateOf(false) }
                when {
                    auth.canUseApp -> {
                        showChangeServer = false
                        MigoShell(model = model)
                    }
                    showLegal -> LegalHubScreen(
                        model = model,
                        onDone = { showLegal = false },
                    )
                    showChangeServer -> ChangeServerScreen(
                        model = model,
                        onBack = { showChangeServer = false },
                    )
                    else -> LoginScreen(
                        model = model,
                        onOpenServer = { showChangeServer = true },
                        onOpenLegal = { showLegal = true },
                    )
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 与 iOS 一致：切到后台即结束会话（没有前台服务时后台录音会被系统静音/限制）
        // fix1：权限弹窗期间不结束（部分国产 ROM 的权限弹窗是全屏 Activity，会触发 onStop，
        // 原来会把“连接中”的会话直接停掉，授权回来后点击无反应）
        if (!isChangingConfigurations && model.audio.pendingPermission == null && !model.locationPermissionPending && model.engine.state.value.isActive) {
            model.scope.launch { model.engine.stop() }
        }
    }
}
