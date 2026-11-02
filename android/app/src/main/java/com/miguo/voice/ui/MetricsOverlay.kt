package com.miguo.voice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguo.voice.conversation.AudioRouteInfo
import com.miguo.voice.protocol.ClientConfig
import com.miguo.voice.protocol.MetricsBoard
import kotlin.math.roundToInt

private fun ms(v: Double?): String = v?.let { "${it.roundToInt()}" } ?: "–"

/** 指标浮层（对应 iOS MetricsOverlay.swift）：首音、stt/llm/tts、RTT、音频路由。 */
@Composable
fun MetricsOverlay(m: MetricsBoard, route: AudioRouteInfo?, sessionId: String?, cfg: ClientConfig) {
    val t = m.last
    val s = t?.server
    val lines = buildList {
        add("首音(客户端) ${ms(t?.clientFirstAudioMs)} ms · 出声 ${ms(t?.clientFirstAudibleMs)} ms · p50 ${ms(m.p50 { it.clientFirstAudioMs })} / p90 ${ms(m.p90 { it.clientFirstAudioMs })}")
        add("服务端 首音 ${ms(s?.firstAudioMs)} · stt ${ms(s?.sttMs)} · llm首字 ${ms(s?.llmFirstTokenMs)} / ${ms(s?.llmMs)} · tts ${ms(s?.ttsMs)} ms")
        add("rtt ${ms(m.rttMs)} ms · 连接 ${ms(m.connectMs)} ms · 配置 ${cfg.source} v${cfg.configVersion}")
        route?.let {
            add("模式 ${it.audioMode} · 输出 ${it.outputs.joinToString()} · 通话音量 ${it.voiceCallVolume}/${it.voiceCallMax}")
            add("输入 ${it.inputs.joinToString()} ${it.inputSampleRate}Hz · AEC ${if (it.aecEnabled) "开" else if (it.aecAvailable) "关" else "无"} NS ${if (it.nsEnabled) "开" else "关"} AGC ${if (it.agcEnabled) "开" else "关"} · 缓冲 ${it.trackBufferMs}ms")
        }
        sessionId?.let { add("session $it") }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x11000000)).padding(8.dp)
    ) {
        lines.forEach { Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.DarkGray, lineHeight = 13.sp) }
    }
}
