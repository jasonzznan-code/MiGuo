package com.miguo.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import java.util.concurrent.Executor

/**
 * 通话模式音频会话（对应 iOS AudioSessionController.swift：playAndRecord + voiceChat + defaultToSpeaker）。
 *
 * fix3 同款要点：**所有监听器（设备插拔、模式变化、通信设备变化）都在 setMode / AudioTrack.play() 之前注册**，
 * 否则 setMode(MODE_IN_COMMUNICATION) 或路由切换在启动瞬间触发的回调会被漏掉，播放停了也没人重启。
 */
class AudioSessionController(context: Context, private val handler: Handler, private val log: (String) -> Unit) {

    interface Listener {
        fun onDevicesChanged(added: List<AudioDeviceInfo>, removed: List<AudioDeviceInfo>)
        fun onModeChanged(mode: Int)
        fun onCommunicationDeviceChanged(device: AudioDeviceInfo?)
        fun onFocusChanged(change: Int)
    }

    val am: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var listener: Listener? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var modeListener: Any? = null          // AudioManager.OnModeChangedListener（API 31+）
    private var commDevListener: Any? = null       // AudioManager.OnCommunicationDeviceChangedListener（API 31+）
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private val executor = Executor { r -> handler.post(r) }

    val voiceAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 第 1 步：注册全部监听（必须早于 activate() 和 AudioTrack.play()） */
    fun registerListeners(l: Listener) {
        listener = l
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                l.onDevicesChanged(addedDevices.toList(), emptyList())
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                l.onDevicesChanged(emptyList(), removedDevices.toList())
            }
        }
        am.registerAudioDeviceCallback(cb, handler)
        deviceCallback = cb
        if (Build.VERSION.SDK_INT >= 31) {
            val ml = AudioManager.OnModeChangedListener { mode -> l.onModeChanged(mode) }
            am.addOnModeChangedListener(executor, ml)
            modeListener = ml
            val cl = AudioManager.OnCommunicationDeviceChangedListener { d -> l.onCommunicationDeviceChanged(d) }
            am.addOnCommunicationDeviceChangedListener(executor, cl)
            commDevListener = cl
        }
        log("监听器已注册（设备回调${if (Build.VERSION.SDK_INT >= 31) " + 模式 + 通信设备" else ""}），之后才设置通话模式/启动播放")
    }

    /** 第 2 步：音频焦点 + MODE_IN_COMMUNICATION + 外放路由 */
    fun activate() {
        val fr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(voiceAttributes)
            .setOnAudioFocusChangeListener({ c -> listener?.onFocusChanged(c) }, handler)
            .build()
        // fix1：焦点 / 通话模式 / 路由都是“尽力而为”，个别 ROM 会抛异常，失败只记日志，不影响录音
        try {
            val r = am.requestAudioFocus(fr)
            focusRequest = fr
            if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) log("⚠ 音频焦点请求结果=$r（未立即获得）")
        } catch (e: Exception) { log("⚠ requestAudioFocus 失败：$e") }
        try {
            previousMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) { log("⚠ setMode(IN_COMMUNICATION) 失败：$e") }
        applyRoute("activate")
    }

    /** 没有耳机（有线/USB/蓝牙）时强制外放；有耳机则交给系统默认 */
    fun applyRoute(reason: String) {
        try { applyRouteUnsafe(reason) } catch (e: Exception) { log("⚠ 路由（$reason）设置失败：$e") }
    }

    private fun applyRouteUnsafe(reason: String) {
        val headset = hasHeadset()
        if (Build.VERSION.SDK_INT >= 31) {
            if (headset) {
                am.clearCommunicationDevice()
                log("路由（$reason）：检测到耳机，交给系统默认 → ${describe(am.communicationDevice)}")
            } else {
                val spk = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val ok = spk != null && am.setCommunicationDevice(spk)
                log("路由（$reason）：setCommunicationDevice(扬声器)=$ok → ${describe(am.communicationDevice)}")
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !headset
            @Suppress("DEPRECATION")
            log("路由（$reason）：isSpeakerphoneOn=${am.isSpeakerphoneOn}（耳机=$headset）")
        }
    }

    fun hasHeadset(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadset(it) }

    fun deactivate() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
        } catch (e: Exception) { log("⚠ 清除路由失败：$e") }
        try {
            am.mode = if (previousMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else previousMode
        } catch (e: Exception) { log("⚠ 恢复 mode 失败：$e") }
        try { focusRequest?.let { am.abandonAudioFocusRequest(it) } } catch (e: Exception) { log("⚠ 释放焦点失败：$e") }
        focusRequest = null
    }

    fun unregisterListeners() {
        deviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= 31) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { am.removeOnModeChangedListener(it) }
            (commDevListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let { am.removeOnCommunicationDeviceChangedListener(it) }
        }
        modeListener = null
        commDevListener = null
        listener = null
    }

    val voiceCallVolume: Int get() = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
    val voiceCallMax: Int get() = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)

    fun outputDeviceDescription(): String =
        if (Build.VERSION.SDK_INT >= 31) "comm=" + describe(am.communicationDevice)
        else @Suppress("DEPRECATION") "speakerphone=${am.isSpeakerphoneOn}"

    companion object {
        fun modeName(m: Int) = when (m) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            4 -> "CALL_SCREENING"
            else -> "MODE_$m"
        }

        fun isHeadset(d: AudioDeviceInfo) = when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
            else -> Build.VERSION.SDK_INT >= 31 && (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        fun describe(d: AudioDeviceInfo?): String {
            if (d == null) return "null"
            val t = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机(无麦)"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙 A2DP"
                AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
                else -> "type${d.type}"
            }
            return "$t(${d.productName})"
        }
    }
}
