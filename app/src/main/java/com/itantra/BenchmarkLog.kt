package com.itantra

import android.os.Build
import com.itantra.session.Message
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends one CSV row per finished message to `files/benchmarks.csv`, so benchmark runs survive
 * logcat's small ring buffer. Pull it with:
 *   adb shell run-as com.itantra cat files/benchmarks.csv > benchmarks.csv
 */
class BenchmarkLog(dir: File, private val mode: String) {

    private val file = File(dir, "benchmarks.csv")
    private val session = stamp("yyyyMMdd-HHmmss")

    @Synchronized
    fun append(m: Message) {
        val fresh = !file.exists()
        file.appendText(
            buildString {
                if (fresh) appendLine(HEADER.joinToString(","))
                appendLine(
                    listOf(
                        session, stamp("HH:mm:ss.SSS"), Build.MODEL, mode, m.direction, m.seq, m.status,
                        m.wireBytes, m.recordedSec?.let { "%.2f".format(Locale.US, it) },
                        m.speechSec?.let { "%.2f".format(Locale.US, it) },
                        m.vadMs, m.sttMs, m.ttsMs, m.queueMs, m.ackAfterMs, m.peerTtsMs, m.peerQueueMs,
                        m.rttMs, m.endToEndMs, m.otherMs, quote(m.text),
                    ).joinToString(",") { it?.toString() ?: "" }
                )
            }
        )
    }

    private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private companion object {
        val HEADER = listOf(
            "session", "time", "device", "mode", "direction", "seq", "status", "wire_bytes",
            "recorded_s", "speech_s", "vad_ms", "stt_ms", "tts_ms", "queue_ms", "ack_after_ms",
            "peer_tts_ms", "peer_queue_ms", "rtt_ms", "e2e_ms", "other_ms", "text",
        )
    }
}
