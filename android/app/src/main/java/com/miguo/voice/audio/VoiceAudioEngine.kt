package com.miguo.voice.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.miguo.voice.conversation.AudioIOEvent
import com.miguo.voice.conversation.AudioRouteInfo
import com.miguo.voice.conversation.VoiceAudioIO
import com.miguo.voice.protocol.AudioPayloadFormat
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.Mp3FrameSplitter
import com.miguo.voice.protocol.PCMFramer
import com.miguo.voice.protocol.PCMMath
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Android 音频引擎（对应 iOS MiguoAudio/VoiceAudioEngine.swift，含 fix3 的全部思路）：
 *
 * 采集：AudioRecord(VOICE_COMMUNICATION, 16 kHz, mono, s16le) + AcousticEchoCanceler / NoiseSuppressor /
 *       AutomaticGainControl（设备支持时）→ PCMFramer 按 20/40 ms 分帧 → 上传闸门 → Channel。
 * 播放：AudioTrack(USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH, 24 kHz mono s16le, MODE_STREAM)，
 *       音量走“通话音量”（STREAM_VOICE_CALL，与媒体音量分开）。
 *       mp3 → Mp3FrameSplitter 帧同步 → MediaCodec(audio/mpeg)；pcm_s16le 一帧一整句直接写。
 *
 * fix3 等价实现：
 *  ① 所有监听（AudioDeviceCallback / 模式 / 通信设备 / AudioTrack 路由）在 setMode 与 AudioTrack.play() 之前注册；
 *  ② ensurePlaying()：发现 AudioTrack 不是 PLAYING / 未初始化 / write() 返回错误（含 ERROR_DEAD_OBJECT）
 *     就自动 play() 或整条重建，然后继续写，而不是静默丢音频；
 *  ③ 看门狗（默认 0.5 s，可由服务端 playback.watchdog_interval_ms 下发）：重启死掉的 track；有音频排队时
 *     每 2 s 打一行状态；排队音频超过“应播完时刻” 5 s（stall_timeout_s）仍未播完（head 位置 < 已写帧数）
 *     → 清空队列、发 PlaybackDrained，让状态机回到「正在听」。
 *  所有日志带 [miguo-audio] 前缀（logcat tag 也是 miguo-audio）。
 */
class VoiceAudioEngine(private val context: Context) : VoiceAudioIO {

    override var onEvent: ((AudioIOEvent) -> Unit)? = null
    /** 由 Activity 注入：弹出 RECORD_AUDIO 运行时权限请求 */
    var permissionRequester: (suspend () -> Boolean)? = null
    /** 正在等待的权限请求结果（存在 ViewModel 生命周期里，Activity 重建也不丢） */
    @Volatile var pendingPermission: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    private val playRate = 24_000           // 服务端两种输出都是 24 kHz 单声道（session.py OUT_FORMATS）

    // ---- 线程
    private var monitorThread: HandlerThread? = null
    private var monitor: Handler? = null
    private var recordThread: Thread? = null
    private var playThread: Thread? = null

    // ---- 会话
    @Volatile private var running = false
    @Volatile private var gateOpen = false
    @Volatile private var recActive = false
    private var session: AudioSessionController? = null
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var micChannel: Channel<ByteArray>? = null
    private var tuning = ClientConfig.PlaybackTuning()
    private var sendRate = 16_000

    // ---- 播放：track 只在持有 trackLock 时访问
    private val trackLock = Any()
    private var track: AudioTrack? = null
    private var trackBufferFrames = 0
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var framesWritten = 0L          // 自 track 创建 / flush 起写入的帧数
    private var framesSinceStart = 0L       // 自 play() 起写入的帧数（用于判断是否需要补静音越过启动阈值）
    private var lastRestartAttempt = 0L
    private var restartCount = 0

    // ---- 播放计数：stateLock 保护
    private val stateLock = Any()
    private var generation = 0
    private var pendingCmds = 0             // 已入队但工作线程还没处理完的命令（含待解码数据）
    private var expectedEndAt = 0L          // 已写入音频“应当播完”的时刻（elapsedRealtime ms）
    private var lastEnqueueAt = 0L
    private var lastHeadPos = -1L
    private var lastHeadMoveAt = 0L

    // 每轮诊断
    private var curTurn = -1
    private var firstQueuedTurn = -1
    private var firstPlayedTurn = -1
    private var firstHeadTarget = -1L
    private var firstWriteAt = 0L
    private var turnChunks = 0
    private var turnFrames = 0L
    private var wasBusy = false
    private var lastStatusLog = 0L
    private var lastWatchdogAt = 0L

    // ---- 工作线程私有
    private sealed class Cmd(val gen: Int) {
        class Data(gen: Int, val bytes: ByteArray, val format: AudioPayloadFormat, val sampleRate: Int, val turn: Int, val seq: Int) : Cmd(gen)
        class EndTurn(gen: Int, val turn: Int) : Cmd(gen)
        class Quit : Cmd(-1)
    }
    private val queue = LinkedBlockingQueue<Cmd>()
    private val splitter = Mp3FrameSplitter()
    private var decoder: Mp3StreamDecoder? = null
    private var decoderKey: Pair<Int, Int>? = null
    private var workerGen = 0

    private fun alog(msg: String) {
        Log.i(TAG, "[miguo-audio] $msg")
    }

    // MARK: - VoiceAudioIO

    override suspend fun requestRecordPermission(): Boolean {
        if (hasRecordPermission()) return true
        val req = permissionRequester ?: run { alog("✗ 没有 permissionRequester（Activity 未就绪）"); return false }
        return try {
            req.invoke()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            alog("✗ 申请 RECORD_AUDIO 失败：$e")
            false
        } && hasRecordPermission()
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** fix1：所有回调/后台线程里的代码都包一层，异常只记日志，不让进程退出 */
    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) { alog("✗ $what 异常（已忽略）：$e") }
    }

    @SuppressLint("MissingPermission")
    override fun startSession(sendSampleRate: Int, frameMs: Int, tuning: ClientConfig.PlaybackTuning): ReceiveChannel<ByteArray> {
        stopSession()
        this.tuning = tuning
        this.sendRate = sendSampleRate
        val ch = Channel<ByteArray>(50, BufferOverflow.DROP_OLDEST)
        micChannel = ch
        val mt = HandlerThread("miguo-audio-monitor").also { it.start() }
        monitorThread = mt
        val h = Handler(mt.looper)
        monitor = h
        try {
            val sc = AudioSessionController(context, h, ::alog)
            session = sc
            // ① fix3：监听器先注册，再改模式 / 建 track / play()
            sc.registerListeners(sessionListener)
            sc.activate()

            synchronized(stateLock) {
                generation++
                pendingCmds = 0
                expectedEndAt = 0
                resetTurnDiagLocked()
            }
            restartCount = 0
            synchronized(trackLock) { createTrackLocked("session start") }

            startRecorder(sendSampleRate, frameMs, ch)
            running = true
            playThread = Thread({ workerLoop() }, "miguo-audio-play").also {
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
            logStartupState()
            lastWatchdogAt = 0
            h.post(tick)
        } catch (e: Exception) {
            alog("✗ startSession 失败：$e")
            stopSession()
            throw e
        }
        return ch
    }

    override fun stopSession() {
        val wasRunning = running
        running = false
        gateOpen = false
        monitor?.removeCallbacksAndMessages(null)
        queue.clear()
        queue.offer(Cmd.Quit())
        playThread?.let { it.join(800) }
        playThread = null
        recActive = false
        recordThread?.let { t ->
            try { record?.stop() } catch (_: Exception) {}
            t.join(800)
        }
        recordThread = null
        try { record?.release() } catch (_: Exception) {}
        record = null
        guard("release aec") { aec?.release() }; aec = null
        guard("release ns") { ns?.release() }; ns = null
        guard("release agc") { agc?.release() }; agc = null
        guard("release track") { synchronized(trackLock) { releaseTrackLocked() } }
        guard("release decoder") { decoder?.release() }; decoder = null; decoderKey = null
        splitter.reset()
        session?.let { sc -> guard("deactivate") { sc.deactivate() }; guard("unregister") { sc.unregisterListeners() } }
        session = null
        monitorThread?.quitSafely()
        monitorThread = null
        monitor = null
        micChannel?.close()
        micChannel = null
        queue.clear()
        synchronized(stateLock) { generation++; pendingCmds = 0 }
        if (wasRunning) alog("stopSession：本次会话播放器自动重启 $restartCount 次")
    }

    override fun setUploadGate(open: Boolean) {
        gateOpen = open
    }

    override val isPlaybackIdle: Boolean
        get() {
            if (synchronized(stateLock) { pendingCmds } > 0) return false
            return queuedFrames() <= IDLE_SLACK_FRAMES
        }

    override fun enqueue(data: ByteArray, format: AudioPayloadFormat, sampleRate: Int, turn: Int, seq: Int) {
        val gen = synchronized(stateLock) {
            pendingCmds++
            lastEnqueueAt = SystemClock.elapsedRealtime()
            generation
        }
        queue.offer(Cmd.Data(gen, data, format, sampleRate, turn, seq))
    }

    override fun endOfTurnAudio(turn: Int) {
        val gen = synchronized(stateLock) { pendingCmds++; generation }
        queue.offer(Cmd.EndTurn(gen, turn))
    }

    override fun flushPlayback(fadeOutMs: Int) {
        synchronized(stateLock) {
            generation++
            pendingCmds = 0
            expectedEndAt = 0
            resetTurnDiagLocked()
        }
        queue.removeIf { it !is Cmd.Quit }
        splitter.reset()
        try { decoder?.reset() } catch (_: Exception) {}
        decoderKey = null
        val fade = fadeOutMs.coerceAtLeast(0)
        synchronized(trackLock) {
            val t = track ?: return
            if (fade <= 0) {
                try {
                    t.pause()
                    t.flush()
                } catch (e: Exception) {
                    alog("flush 失败：$e")
                }
                try { t.setVolume(1f) } catch (_: Exception) {}
                framesWritten = 0
                framesSinceStart = 0
                ensurePlayingLocked("flush", force = true)
                return
            }
            // 打断淡出：短促降音量再 pause/flush（对齐 ref_assistant1 AndroidPcmPlayer）
            val trackRef = t
            val steps = 4
            val stepMs = (fade / steps).coerceAtLeast(1).toLong()
            Thread({
                try {
                    for (step in 0 until steps) {
                        val vol = (steps - 1 - step).toFloat() / steps
                        try { trackRef.setVolume(vol) } catch (_: Exception) {}
                        try { Thread.sleep(stepMs) } catch (_: InterruptedException) { break }
                    }
                } catch (_: Exception) {
                } finally {
                    synchronized(trackLock) {
                        if (track === trackRef) {
                            try { trackRef.pause(); trackRef.flush() } catch (_: Exception) {}
                            try { trackRef.setVolume(1f) } catch (_: Exception) {}
                            framesWritten = 0
                            framesSinceStart = 0
                            ensurePlayingLocked("flush-fade", force = true)
                        } else {
                            try { trackRef.pause(); trackRef.flush() } catch (_: Exception) {}
                        }
                    }
                }
            }, "miguo-audio-fade").apply { isDaemon = true; start() }
            alog("flushPlayback fade=${fade}ms")
        }
    }

    override fun routeInfo(): AudioRouteInfo? {
        val sc = session ?: return null
        val (out, bufMs) = synchronized(trackLock) {
            val t = track
            listOfNotNull(t?.routedDevice?.let { AudioSessionController.describe(it) }) to
                (if (t != null) trackBufferFrames * 1000 / playRate else 0)
        }
        val inp = listOfNotNull(record?.routedDevice?.let { AudioSessionController.describe(it) })
        return AudioRouteInfo(
            inputs = inp, outputs = out,
            audioMode = AudioSessionController.modeName(sc.am.mode),
            voiceCallVolume = sc.voiceCallVolume, voiceCallMax = sc.voiceCallMax,
            aecAvailable = AcousticEchoCanceler.isAvailable(), aecEnabled = aec?.enabled == true,
            nsEnabled = ns?.enabled == true, agcEnabled = agc?.enabled == true,
            inputSampleRate = record?.sampleRate ?: 0, outputSampleRate = playRate, trackBufferMs = bufMs,
        )
    }

    // MARK: - 采集

    @SuppressLint("MissingPermission")
    private fun startRecorder(rate: Int, frameMs: Int, ch: Channel<ByteArray>) {
        // fix1：没有权限时直接给出可读错误（不去碰 AudioRecord，避免部分 ROM 抛 SecurityException）
        if (!hasRecordPermission()) throw SecurityException("没有麦克风权限（RECORD_AUDIO 未授予）")
        val frameBytes = PCMFramer.frameBytes(rate, frameMs)
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("设备不支持 $rate Hz 单声道录音（getMinBufferSize=$minBuf）")
        val bufBytes = maxOf(minBuf, frameBytes * 8)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufBytes)
        } catch (e: Exception) {
            throw IllegalStateException("AudioRecord 创建失败：$e", e)
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            try { r.release() } catch (_: Exception) {}
            throw IllegalStateException("AudioRecord 初始化失败（麦克风被其他 App 占用？）")
        }
        record = r
        val sid = r.audioSessionId
        // 回声消除/降噪/自动增益：可选能力，失败只记日志
        guard("AcousticEchoCanceler") { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sid)?.also { it.enabled = true } }
        guard("NoiseSuppressor") { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sid)?.also { it.enabled = true } }
        guard("AutomaticGainControl") { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sid)?.also { it.enabled = true } }
        try {
            r.startRecording()
        } catch (e: Exception) {
            throw IllegalStateException("AudioRecord.startRecording 失败：$e", e)
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("AudioRecord.startRecording 失败（recordingState=${r.recordingState}）")
        }
        val framer = PCMFramer(frameBytes)
        recActive = true
        recordThread = Thread({
          try {
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
            val buf = ByteArray(frameBytes)
            var lastGate = false
            while (recActive) {
                val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n < 0) {
                    if (recActive) alog("AudioRecord.read 错误 $n")
                    break
                }
                if (n == 0) continue
                onEvent?.invoke(AudioIOEvent.Level(PCMMath.rms(buf, n)))
                val g = gateOpen
                if (g != lastGate) { framer.reset(); lastGate = g }    // 关闸丢半帧，避免旧音频拼进新一轮
                if (!g) continue
                for (f in framer.append(buf, n)) ch.trySend(f)
            }
          } catch (e: Throwable) {
            // fix1：录音线程里的异常以前会直接杀掉整个进程；现在通知状态机结束会话并显示错误
            alog("✗ 录音线程异常：$e")
            if (recActive) onEvent?.invoke(AudioIOEvent.CaptureFailed(e.toString()))
          }
        }, "miguo-audio-record").also { it.start() }
    }

    // MARK: - 播放：AudioTrack 管理（持有 trackLock 调用）

    private fun createTrackLocked(reason: String) {
        releaseTrackLocked()
        val sc = session ?: return
        val minBuf = AudioTrack.getMinBufferSize(playRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf * 2, playRate * 2 * 120 / 1000)       // ≥120 ms
        val t = AudioTrack.Builder()
            .setAudioAttributes(sc.voiceAttributes)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(playRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufBytes)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            throw IllegalStateException("AudioTrack 初始化失败")
        }
        trackBufferFrames = t.bufferSizeInFrames
        if (Build.VERSION.SDK_INT >= 31) {
            // 默认启动阈值 = 整个 buffer：短句可能一直等不到起播。降到 ~40 ms。
            try { t.setStartThresholdInFrames(minOf(trackBufferFrames, playRate * 40 / 1000)) } catch (_: Exception) {}
        }
        // ① fix3：路由监听在 play() 之前挂上
        val rl = AudioRouting.OnRoutingChangedListener { router ->
            alog("AudioTrack 路由变化 → ${AudioSessionController.describe(router.routedDevice)}")
            monitor?.post { guard("routing-changed") { synchronized(trackLock) { ensurePlayingLocked("routing-changed") } } }
        }
        t.addOnRoutingChangedListener(rl, monitor)
        routingListener = rl
        track = t
        framesWritten = 0
        framesSinceStart = 0
        t.play()
        alog("AudioTrack 已创建（$reason）：${playRate} Hz buffer=${trackBufferFrames} 帧(${trackBufferFrames * 1000 / playRate} ms) playState=${playStateName(t.playState)} session=${t.audioSessionId}")
    }

    private fun releaseTrackLocked() {
        val t = track ?: return
        routingListener?.let { t.removeOnRoutingChangedListener(it) }
        routingListener = null
        try { t.pause(); t.flush() } catch (_: Exception) {}
        try { t.release() } catch (_: Exception) {}
        track = null
        framesWritten = 0
        framesSinceStart = 0
    }

    /**
     * ② fix3 ensurePlaying()：track 不在 PLAYING 就 play()；play() 无效或 track 已失效就整条重建。
     * 重建限流 1 s，避免来电期间每次写都重试。返回 track 是否处于可写的 PLAYING 状态。
     */
    private fun ensurePlayingLocked(reason: String, force: Boolean = false): Boolean {
        if (!running && !force) return false
        val t = track
        if (t != null && t.state == AudioTrack.STATE_INITIALIZED && t.playState == AudioTrack.PLAYSTATE_PLAYING) return true
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRestartAttempt < 1000) return false
        lastRestartAttempt = now
        alog("⚠ 发现 AudioTrack 不在播放（$reason）：state=${t?.state} playState=${t?.let { playStateName(it.playState) }}，正在自动恢复…")
        var ok = false
        if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
            try {
                t.play()
                framesSinceStart = 0
                ok = t.playState == AudioTrack.PLAYSTATE_PLAYING
                alog("track.play()（$reason）→ playState=${playStateName(t.playState)}")
            } catch (e: Exception) {
                alog("track.play() 失败：$e")
            }
        }
        if (!ok) {
            try {
                createTrackLocked("restart: $reason")
                ok = track?.playState == AudioTrack.PLAYSTATE_PLAYING
            } catch (e: Exception) {
                alog("✗ AudioTrack 重建失败：$e")
            }
        }
        restartCount++
        alog("恢复后：playState=${track?.let { playStateName(it.playState) }}（本会话第 $restartCount 次）")
        onEvent?.invoke(AudioIOEvent.PlaybackRestarted(reason, ok))
        return ok
    }

    private fun queuedFrames(): Long = synchronized(trackLock) {
        val t = track ?: return 0
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        maxOf(0L, framesWritten - head)
    }

    // MARK: - 播放工作线程

    private fun workerLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) } catch (_: Exception) {}
        while (true) {
            val cmd = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { break } ?: continue
            if (cmd is Cmd.Quit) break
            try {
                process(cmd)
            } catch (e: Throwable) {
                alog("✗ 播放命令处理异常：$e")
                onEvent?.invoke(AudioIOEvent.DecodeError(e.toString()))
            } finally {
                synchronized(stateLock) { if (cmd.gen == generation && pendingCmds > 0) pendingCmds-- }
            }
        }
    }

    private fun currentGen() = synchronized(stateLock) { generation }

    private fun process(cmd: Cmd) {
        if (cmd.gen != currentGen()) return
        if (cmd.gen != workerGen) {
            // 有过 flush：丢掉解码器里的旧数据
            workerGen = cmd.gen
            splitter.reset()
            decoder?.reset()
            decoderKey = null
        }
        when (cmd) {
            is Cmd.Data -> when (cmd.format) {
                AudioPayloadFormat.PcmS16LE -> writePcm(cmd.bytes, cmd.sampleRate, cmd.turn, cmd.gen)
                AudioPayloadFormat.Mp3 -> {
                    val key = cmd.turn to cmd.seq
                    if (decoderKey != key) {
                        // 换句：上一句的残留不完整字节丢弃（每句是独立 mp3 流），解码器继续复用
                        val tail = splitter.flush()
                        if (tail.isNotEmpty()) decodeFrames(tail, cmd.turn, cmd.gen)
                        decoderKey = key
                    }
                    decodeFrames(splitter.feed(cmd.bytes), cmd.turn, cmd.gen)
                }
                else -> onEvent?.invoke(AudioIOEvent.DecodeError("不支持的音频格式 ${cmd.format}"))
            }
            is Cmd.EndTurn -> {
                if (decoderKey?.first == cmd.turn) {
                    val tail = splitter.flush()
                    if (tail.isNotEmpty()) decodeFrames(tail, cmd.turn, cmd.gen)
                    decoder?.endOfStream()
                    decoderKey = null
                }
                padIfBelowStartThreshold(cmd.gen)
            }
            is Cmd.Quit -> {}
        }
    }

    private fun decodeFrames(frames: List<Mp3FrameSplitter.Frame>, turn: Int, gen: Int) {
        if (frames.isEmpty()) return
        val d = decoder ?: Mp3StreamDecoder(
            onPcm = { pcm, sr -> writePcm(pcm, sr, curTurnForDecoder, workerGen) },
            log = ::alog,
        ).also { decoder = it }
        curTurnForDecoder = turn
        d.feed(frames)
    }
    private var curTurnForDecoder = -1

    /** 一轮结束时如果自 play() 以来写入的数据还不到 track 的起播阈值，补静音，保证最后一点音频真的播出来 */
    private fun padIfBelowStartThreshold(gen: Int) {
        val need = synchronized(trackLock) { trackBufferFrames - framesSinceStart }
        if (need > 0 && Build.VERSION.SDK_INT < 31) {
            writePcm(ByteArray((need * 2).toInt()), playRate, -1, gen)
        }
    }

    /** 写 PCM 到 AudioTrack（非阻塞写 + 小睡，随时响应 flush）；写失败自动重建 track 后继续写 */
    private fun writePcm(raw: ByteArray, sampleRate: Int, turn: Int, gen: Int) {
        val pcm = if (sampleRate != playRate && sampleRate > 0) resample(raw, sampleRate, playRate) else raw
        if (pcm.isEmpty()) return
        if (turn >= 0) noteFirstQueued(turn, pcm)
        var off = 0
        var errors = 0
        while (off < pcm.size) {
            if (!running || gen != currentGen()) return
            val n: Int = synchronized(trackLock) {
                if (!ensurePlayingLocked("write")) {
                    -100
                } else {
                    track!!.write(pcm, off, pcm.size - off, AudioTrack.WRITE_NON_BLOCKING)
                }
            }
            when {
                n == -100 -> SystemClock.sleep(50)        // 恢复被限流，稍后再试
                n < 0 -> {
                    errors++
                    alog("✗ AudioTrack.write 返回 ${writeErrorName(n)}，重建 track 后继续（第 $errors 次）")
                    synchronized(trackLock) {
                        try { createTrackLocked("write error ${writeErrorName(n)}") } catch (e: Exception) { alog("重建失败：$e") }
                    }
                    restartCount++
                    onEvent?.invoke(AudioIOEvent.PlaybackRestarted("write=${writeErrorName(n)}", track != null))
                    if (errors >= 3) { alog("✗ 连续写失败 3 次，丢弃本段 ${pcm.size - off} 字节"); return }
                    SystemClock.sleep(20)
                }
                n == 0 -> SystemClock.sleep(10)
                else -> {
                    val frames = (n / 2).toLong()
                    off += n
                    synchronized(trackLock) { framesWritten += frames; framesSinceStart += frames }
                    val now = SystemClock.elapsedRealtime()
                    synchronized(stateLock) {
                        expectedEndAt = maxOf(expectedEndAt, now) + frames * 1000 / playRate
                        if (turn >= 0) { turnFrames += frames }
                    }
                }
            }
        }
    }

    private fun noteFirstQueued(turn: Int, pcm: ByteArray) {
        var first = false
        synchronized(stateLock) {
            if (turn != curTurn) { curTurn = turn; turnChunks = 0; turnFrames = 0 }
            turnChunks++
            if (firstQueuedTurn != turn) {
                firstQueuedTurn = turn
                firstWriteAt = SystemClock.elapsedRealtime()
                first = true
            }
        }
        if (!first) return
        val (st, written, queued) = synchronized(trackLock) {
            val t = track
            val head = (t?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
            Triple(t?.let { playStateName(it.playState) } ?: "null", framesWritten, maxOf(0L, framesWritten - head))
        }
        synchronized(stateLock) { firstHeadTarget = written + 1 }
        val sc = session
        alog(String.format(Locale.US, "turn %d 首个 buffer 入队：track=%s 通话音量=%d/%d 峰值=%.3f 时长=%.2fs 排队中=%.2fs",
            turn, st, sc?.voiceCallVolume ?: -1, sc?.voiceCallMax ?: -1, PCMMath.peak(pcm),
            pcm.size / 2.0 / playRate, queued.toDouble() / playRate))
        if ((sc?.voiceCallVolume ?: 1) == 0) alog("⚠ 通话音量为 0：对话进行中按音量键调大（通话音量和媒体音量是分开的）")
        val delay = (queued + trackBufferFrames / 2).toDouble() / playRate
        onEvent?.invoke(AudioIOEvent.FirstBufferScheduled(turn, delay))
    }

    // MARK: - 监控线程：排空检测（50 ms）+ ③ 看门狗（默认 500 ms）

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val h = monitor ?: return
            try {
                checkDrain()
                val now = SystemClock.elapsedRealtime()
                if (now - lastWatchdogAt >= tuning.watchdogIntervalMs) {
                    lastWatchdogAt = now
                    watchdogTick(now)
                }
            } catch (e: Exception) {
                alog("monitor 异常：$e")
            }
            h.postDelayed(this, 50)
        }
    }

    private fun checkDrain() {
        val pending = synchronized(stateLock) { pendingCmds }
        val (head, written) = synchronized(trackLock) {
            ((track?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL) to framesWritten
        }
        val now = SystemClock.elapsedRealtime()
        if (head != lastHeadPos) { lastHeadPos = head; lastHeadMoveAt = now }
        // 首次出声
        val logFirstPlayed = synchronized(stateLock) {
            if (firstQueuedTurn >= 0 && firstPlayedTurn != firstQueuedTurn && firstHeadTarget > 0 && head >= firstHeadTarget) {
                firstPlayedTurn = firstQueuedTurn
                firstQueuedTurn to (now - firstWriteAt)
            } else null
        }
        logFirstPlayed?.let { (t, ms) -> alog("turn $t 首个 buffer 开始播放（写入后 $ms ms）") }

        val busy = pending > 0 || written - head > IDLE_SLACK_FRAMES
        if (wasBusy && !busy) {
            val (t, chunks, frames) = synchronized(stateLock) { Triple(curTurn, turnChunks, turnFrames) }
            alog(String.format(Locale.US, "播放排空：turn=%d 本轮 %d 段 %.2fs，track 已写 %d 帧 / 已播 %d 帧",
                t, chunks, frames.toDouble() / playRate, written, head))
            onEvent?.invoke(AudioIOEvent.PlaybackDrained)
        }
        wasBusy = busy
    }

    private fun watchdogTick(now: Long) {
        // 重启死掉的 track
        synchronized(trackLock) {
            val t = track
            if (t == null || t.state != AudioTrack.STATE_INITIALIZED || t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                ensurePlayingLocked("watchdog")
            }
        }
        val (pending, expEnd, lastEnq) = synchronized(stateLock) { Triple(pendingCmds, expectedEndAt, lastEnqueueAt) }
        val queued = queuedFrames()
        val busy = pending > 0 || queued > IDLE_SLACK_FRAMES
        if (!busy) return
        if (now - lastStatusLog >= (tuning.statusLogIntervalS * 1000).toLong()) {
            lastStatusLog = now
            val (st, written, head) = synchronized(trackLock) {
                val t = track
                Triple(t?.let { playStateName(it.playState) } ?: "null", framesWritten,
                    (t?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL)
            }
            alog(String.format(Locale.US, "状态：track=%s 通话音量=%d 待处理命令=%d 排队中=%.2fs 已写=%d 已播=%d head 停滞=%dms",
                st, session?.voiceCallVolume ?: -1, pending, queued.toDouble() / playRate, written, head, now - lastHeadMoveAt))
        }
        // 卡死判定：排队音频应在 ref 时刻播完；超过 stall_timeout_s 仍没播完 → 清空并发 drained
        val ref = maxOf(expEnd, lastEnq)
        val stallMs = (tuning.stallTimeoutS * 1000).toLong()
        if (ref > 0 && now - ref > stallMs) {
            alog(String.format(Locale.US, "✗ 播放卡住：已超过应播完时刻 %.1fs（排队中 %.2fs，待处理命令 %d），清空队列并重启播放器",
                (now - ref) / 1000.0, queued.toDouble() / playRate, pending))
            synchronized(stateLock) {
                generation++
                pendingCmds = 0
                expectedEndAt = 0
                lastEnqueueAt = 0
                resetTurnDiagLocked()
            }
            queue.removeIf { it !is Cmd.Quit }
            synchronized(trackLock) {
                try { track?.pause(); track?.flush() } catch (_: Exception) {}
                framesWritten = 0
                framesSinceStart = 0
                lastRestartAttempt = 0
                if (!ensurePlayingLocked("stall-recover", force = true)) {
                    try { createTrackLocked("stall-recover") } catch (e: Exception) { alog("重建失败：$e") }
                }
            }
            wasBusy = false
            onEvent?.invoke(AudioIOEvent.DecodeError("播放卡住 ${(now - ref) / 1000} 秒，已清空队列（详见 [miguo-audio] 日志）"))
            onEvent?.invoke(AudioIOEvent.PlaybackDrained)
        }
    }

    private fun resetTurnDiagLocked() {
        curTurn = -1
        firstQueuedTurn = -1
        firstPlayedTurn = -1
        firstHeadTarget = -1
        turnChunks = 0
        turnFrames = 0
    }

    // MARK: - 设备 / 模式监听（在 monitor 线程回调）

    private val sessionListener = object : AudioSessionController.Listener {
        override fun onDevicesChanged(added: List<AudioDeviceInfo>, removed: List<AudioDeviceInfo>) = guard("onDevicesChanged") {
            val a = added.filter { it.isSink }.map { AudioSessionController.describe(it) }
            val r = removed.filter { it.isSink }.map { AudioSessionController.describe(it) }
            if (a.isEmpty() && r.isEmpty()) return@guard
            alog("音频设备变化：+$a -$r")
            if (!running) return@guard
            session?.applyRoute("device-change")
            synchronized(trackLock) { ensurePlayingLocked("device-change") }
            val headsetGone = removed.any { it.isSink && AudioSessionController.isHeadset(it) }
            onEvent?.invoke(AudioIOEvent.RouteChanged("+$a -$r", headsetGone))
        }

        override fun onModeChanged(mode: Int) = guard("onModeChanged") {
            alog("AudioManager 模式变化 → ${AudioSessionController.modeName(mode)}")
            if (!running) return@guard
            if (mode == AudioManager.MODE_NORMAL) {
                // 部分 ROM 会把模式改回 NORMAL（AEC 失效 / 路由跳到听筒）：恢复
                session?.am?.mode = AudioManager.MODE_IN_COMMUNICATION
                session?.applyRoute("mode-restore")
            }
            synchronized(trackLock) { ensurePlayingLocked("mode-change") }
        }

        override fun onCommunicationDeviceChanged(device: AudioDeviceInfo?) = guard("onCommunicationDeviceChanged") {
            alog("通信设备变化 → ${AudioSessionController.describe(device)}")
            if (running) synchronized(trackLock) { ensurePlayingLocked("comm-device-change") }
        }

        override fun onFocusChanged(change: Int) = guard("onFocusChanged") {
            alog("音频焦点变化：$change")
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                    if (running) onEvent?.invoke(AudioIOEvent.InterruptionBegan)
                else -> if (running) synchronized(trackLock) { ensurePlayingLocked("focus-change") }
            }
        }
    }

    private fun logStartupState() {
        val sc = session ?: return
        val am = sc.am
        val parts = mutableListOf<String>()
        parts += "会话启动：mode=${AudioSessionController.modeName(am.mode)}"
        parts += "STREAM_VOICE_CALL 音量=${sc.voiceCallVolume}/${sc.voiceCallMax}"
        parts += "输出=${sc.outputDeviceDescription()}"
        parts += "track 路由=${synchronized(trackLock) { AudioSessionController.describe(track?.routedDevice) }}"
        parts += "AEC 可用=${AcousticEchoCanceler.isAvailable()} 启用=${aec?.enabled == true}"
        parts += "NS=${ns?.enabled == true} AGC=${agc?.enabled == true}"
        parts += "采集=${record?.sampleRate} Hz(VOICE_COMMUNICATION) 播放=$playRate Hz 设备原生=${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Hz"
        parts += "上行=$sendRate Hz 看门狗=${tuning.watchdogIntervalMs}ms 卡死阈值=${tuning.stallTimeoutS}s"
        alog(parts.joinToString(" "))
        if (sc.voiceCallVolume == 0) alog("⚠ 通话音量为 0：对话进行中按音量键调大（通话音量和媒体音量是分开的）")
    }

    companion object {
        const val TAG = "miguo-audio"
        private const val IDLE_SLACK_FRAMES = 240L    // 10 ms 以内视为播完

        fun playStateName(s: Int) = when (s) {
            AudioTrack.PLAYSTATE_PLAYING -> "PLAYING"
            AudioTrack.PLAYSTATE_PAUSED -> "PAUSED"
            AudioTrack.PLAYSTATE_STOPPED -> "STOPPED"
            else -> "STATE_$s"
        }

        fun writeErrorName(n: Int) = when (n) {
            AudioTrack.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            AudioTrack.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioTrack.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioTrack.ERROR -> "ERROR"
            else -> "ERR_$n"
        }

        /** 线性插值重采样（s16le mono），仅在服务端采样率与 24 kHz 不一致时兜底 */
        fun resample(src: ByteArray, from: Int, to: Int): ByteArray {
            val n = src.size / 2
            if (n == 0) return ByteArray(0)
            val m = (n.toLong() * to / from).toInt()
            val out = ByteArray(m * 2)
            fun s(i: Int) = ((src[2 * i].toInt() and 0xff) or (src[2 * i + 1].toInt() shl 8)).toShort().toInt()
            for (j in 0 until m) {
                val pos = j.toDouble() * from / to
                val i = pos.toInt().coerceAtMost(n - 1)
                val f = pos - i
                val v = (s(i) * (1 - f) + s(minOf(i + 1, n - 1)) * f).toInt()
                out[2 * j] = (v and 0xff).toByte()
                out[2 * j + 1] = ((v shr 8) and 0xff).toByte()
            }
            return out
        }
    }
}
