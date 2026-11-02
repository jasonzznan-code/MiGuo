package com.miguo.voice.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.miguo.voice.conversation.ReplyAudioPlayer
import java.io.File

/**
 * v0.2.0：朗读文字回复（/chat/text speak=true 返回的整段 mp3）。不在语音会话里时才用；
 * 用 MediaPlayer + 缓存目录里的临时文件（MediaPlayer 不能直接播 ByteArray）。所有异常只记录不抛。
 */
class ReplyPlayer(context: Context) : ReplyAudioPlayer {
    private val dir = context.cacheDir
    private var mp: MediaPlayer? = null

    @Synchronized
    override fun play(mp3: ByteArray) {
        stop()
        try {
            val f = File(dir, "reply.mp3")
            f.writeBytes(mp3)
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.absolutePath)
                setOnCompletionListener { p -> try { p.release() } catch (_: Throwable) {}; synchronized(this@ReplyPlayer) { if (mp === p) mp = null } }
                setOnErrorListener { _, what, extra -> Log.w("miguo-audio", "[miguo-audio] 文字回复播放失败 what=$what extra=$extra"); true }
                prepare()
                start()
            }
            Log.i("miguo-audio", "[miguo-audio] 朗读文字回复 ${mp3.size} B")
        } catch (e: Exception) {
            Log.w("miguo-audio", "[miguo-audio] 朗读文字回复失败：$e")
            stop()
        }
    }

    @Synchronized
    override fun stop() {
        stop(0)
    }

    /** 打断时短促淡出（MediaPlayer.setVolume），默认硬停 */
    @Synchronized
    fun stop(fadeOutMs: Int) {
        val p = mp ?: return
        mp = null
        if (fadeOutMs <= 0) {
            try { p.stop() } catch (_: Throwable) {}
            try { p.release() } catch (_: Throwable) {}
            return
        }
        val steps = 4
        val stepMs = (fadeOutMs / steps).coerceAtLeast(1).toLong()
        Thread({
            try {
                for (step in 0 until steps) {
                    val vol = (steps - 1 - step).toFloat() / steps
                    try { p.setVolume(vol, vol) } catch (_: Throwable) {}
                    try { Thread.sleep(stepMs) } catch (_: InterruptedException) { break }
                }
            } catch (_: Throwable) {
            } finally {
                try { p.stop() } catch (_: Throwable) {}
                try { p.release() } catch (_: Throwable) {}
            }
        }, "miguo-reply-fade").apply { isDaemon = true; start() }
    }
}
