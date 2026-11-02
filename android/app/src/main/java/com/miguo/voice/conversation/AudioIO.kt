package com.miguo.voice.conversation

import com.miguo.voice.protocol.AudioPayloadFormat
import com.miguo.voice.protocol.ClientConfig
import kotlinx.coroutines.channels.ReceiveChannel

/** 音频层发给状态机的事件（任意线程回调，ConversationEngine 会切回主线程）。对应 iOS AudioIO.swift。 */
sealed class AudioIOEvent {
    /** 麦克风 RMS（0…1），驱动 UI 波形 */
    data class Level(val rms: Float) : AudioIOEvent()
    /** 某 turn 的第一个 buffer 已写入 AudioTrack；audibleDelay = 预计还要多少秒才真正出声 */
    data class FirstBufferScheduled(val turn: Int, val audibleDelaySec: Double) : AudioIOEvent()
    /** 所有已入队的音频（含待解码数据）都已播完 */
    object PlaybackDrained : AudioIOEvent()
    /** 音频焦点被抢走（来电等） */
    object InterruptionBegan : AudioIOEvent()
    data class RouteChanged(val reason: String, val oldDeviceUnavailable: Boolean) : AudioIOEvent()
    data class PlaybackRestarted(val reason: String, val ok: Boolean) : AudioIOEvent()
    data class DecodeError(val message: String) : AudioIOEvent()
    /** fix1：录音线程异常退出（以前会直接杀进程） */
    data class CaptureFailed(val message: String) : AudioIOEvent()
}

/** 指标浮层显示的音频信息 */
data class AudioRouteInfo(
    val inputs: List<String>,
    val outputs: List<String>,
    val audioMode: String,
    val voiceCallVolume: Int,
    val voiceCallMax: Int,
    val aecAvailable: Boolean,
    val aecEnabled: Boolean,
    val nsEnabled: Boolean,
    val agcEnabled: Boolean,
    val inputSampleRate: Int,
    val outputSampleRate: Int,
    val trackBufferMs: Int,
)

/** 音频 I/O 抽象。Android 实现：audio.VoiceAudioEngine；测试：假实现。 */
interface VoiceAudioIO {
    var onEvent: ((AudioIOEvent) -> Unit)?
    suspend fun requestRecordPermission(): Boolean
    /** 设置通话模式、启动 AudioRecord + AudioTrack；返回按 frameMs 分好帧的 s16le 上行流 */
    fun startSession(sendSampleRate: Int, frameMs: Int, tuning: ClientConfig.PlaybackTuning): ReceiveChannel<ByteArray>
    fun stopSession()
    /** 上传闸门：轮流说话模式下 thinking/speaking 期间关闭（采集不停，AEC 状态连续） */
    fun setUploadGate(open: Boolean)
    /** 入队一段下行音频（pcm_s16le 直接写；mp3 分帧 + MediaCodec 解码后写） */
    fun enqueue(data: ByteArray, format: AudioPayloadFormat, sampleRate: Int, turn: Int, seq: Int)
    /** 服务端 response_end：把该 turn 解码器里剩余的帧冲出来 */
    fun endOfTurnAudio(turn: Int)
    /** 立即停止播放并清空队列（结束 / barge_in）。
     *  fadeOutMs>0 时先在 AudioTrack 上短促淡出再 pause/flush（打断更自然）。 */
    fun flushPlayback(fadeOutMs: Int = 0)
    /** 没有任何已入队、待解码或正在播放的音频 */
    val isPlaybackIdle: Boolean
    fun routeInfo(): AudioRouteInfo?

    companion object {
        /** 打断淡出时长（ms）：几十～一两百毫秒，对齐 ref_assistant1 AndroidPcmPlayer */
        const val BARGE_FADE_MS = 100
    }
}
