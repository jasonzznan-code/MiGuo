package com.miguo.voice.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEventDecoderTest {
    private fun d(s: String) = ServerEventDecoder.decode(s)

    @Test fun ready() {
        val e = d("""{"type":"ready","session_id":"s1","user_id":"jason","audio_in":{"sample_rate":16000,"format":"pcm_s16le"},
            "audio_out":{"format":"mp3","content_type":"audio/mpeg","sample_rate":24000,"transport":"binary"},
            "config":{"barge_in":false},"vad_engine":"silero","limits":{"idle_timeout_s":120,"max_session_s":1800},"extra":1}""")
        e as ServerEvent.ReadyEv
        assertEquals("s1", e.ready.sessionId)
        assertEquals(AudioPayloadFormat.Mp3, e.ready.audioOut.format)
        assertEquals(24000, e.ready.audioOut.sampleRate)
    }

    @Test fun turnEvents() {
        assertEquals(3, (d("""{"type":"vad_start","turn_id":3}""") as ServerEvent.VadStartEv).v.turnId)
        assertEquals("你好", (d("""{"type":"transcript","turn_id":1,"text":"你好","stt_ms":120.5}""") as ServerEvent.TranscriptEv).t.text)
        val a = (d("""{"type":"audio","turn_id":1,"seq":2,"format":"pcm_s16le","sample_rate":24000,"bytes":4800}""") as ServerEvent.AudioHeaderEv).h
        assertEquals(AudioPayloadFormat.PcmS16LE, a.format)
        assertEquals(4800, a.bytes)
        val m = (d("""{"type":"metrics","turn_id":1,"stt_ms":100,"llm_ms":800,"tts_ms":300,"first_audio_ms":950}""") as ServerEvent.MetricsEv).m
        assertEquals(950.0, m.firstAudioMs!!, 0.0)
        val err = (d("""{"type":"error","code":"bad_user","message":"x","fatal":true}""") as ServerEvent.ErrorEv).e
        assertEquals("bad_user", err.code)
        assertTrue(d("""{"type":"pong","t":5}""") is ServerEvent.PongEv)
        assertTrue(d("""{"type":"session_end","reason":"client_stop"}""") is ServerEvent.SessionEndEv)
    }

    @Test fun transcriptPartial() {
        val e = d("""{"type":"transcript_partial","turn_id":2,"text":"你好啊","partial":true}""") as ServerEvent.TranscriptPartialEv
        assertEquals(2, e.t.turnId)
        assertEquals("你好啊", e.t.text)
        assertTrue(e.t.partial)
    }

    @Test fun unknownIsIgnored() {
        assertEquals("future_thing", (d("""{"type":"future_thing","x":1}""") as ServerEvent.Unknown).type)
    }

    @Test(expected = ProtocolDecodeException::class) fun notJson() { d("hello") }

    @Test(expected = ProtocolDecodeException::class) fun noType() { d("{}") }
}
