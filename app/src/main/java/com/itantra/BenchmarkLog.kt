package com.itantra

import android.os.Build
import com.itantra.session.Message
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds only: appends one CSV row per finished message to `files/benchmarks.csv`, so runs survive
 * logcat's small ring buffer. Pull it with:
 *   adb shell run-as com.itantra cat files/benchmarks.csv > benchmarks.csv
 *
 * Current and previous logs are bounded to about 2 MiB each. Disabled in release builds;
 * diagnostic CSVs contain transcripts and must be treated as private test artifacts.
 */
class BenchmarkLog(dir: File, private val mode: String, private val link: String, private val enabled: Boolean = false) {

    private val file = File(dir, "benchmarks.csv")
    private val linkFile = File(dir, "links.csv")
    private val session = stamp("yyyyMMdd-HHmmss")

    init {
        val header = HEADER.joinToString(",")
        if (enabled && file.exists() && file.useLines { it.firstOrNull() } != header) {
            val previous = File(dir, "benchmarks-previous.csv")
            previous.delete()
            file.renameTo(previous)
        }
    }

    /** [setupMs]: how long the current connection took to establish (joining phone only). */
    @Synchronized
    fun append(m: Message, setupMs: Long?) {
        if (!enabled) return
        rotateIfNeeded(file)
        val fresh = !file.exists()
        file.appendText(
            buildString {
                if (fresh) appendLine(HEADER.joinToString(","))
                appendLine(
                    listOf(
                        session, stamp("HH:mm:ss.SSS"), Build.MODEL, mode, link, setupMs,
                        m.direction, m.seq, m.status,
                        m.wireBytes, m.recordedSec?.let { "%.2f".format(Locale.US, it) },
                        m.speechSec?.let { "%.2f".format(Locale.US, it) },
                        m.vadMs, m.sttMs, m.ttsMs, m.queueMs, m.ackAfterMs, m.peerTtsMs, m.peerQueueMs,
                        m.rttMs, m.endToEndMs, m.otherMs, quote(m.text), m.totalTtsMs, m.voiceChunks,
                        m.langCode, m.outputLangCode, m.translationMs, m.translatedText?.let(::quote), m.translationError?.let(::quote),
                    ).joinToString(",") { it?.toString() ?: "" }
                )
            }
        )
    }

    /** One row per link event in files/links.csv: "lost" (with the reason) or "reconnected" (with the outage length). */
    @Synchronized
    fun linkEvent(event: String, durationMs: Long?, detail: String) {
        if (!enabled) return
        rotateIfNeeded(linkFile)
        val fresh = !linkFile.exists()
        linkFile.appendText(
            buildString {
                if (fresh) appendLine(LINK_HEADER.joinToString(","))
                appendLine(
                    listOf(session, stamp("HH:mm:ss.SSS"), Build.MODEL, mode, link, event, durationMs, quote(detail))
                        .joinToString(",") { it?.toString() ?: "" }
                )
            }
        )
    }

    private fun stamp(pattern: String) = SimpleDateFormat(pattern, Locale.US).format(Date())

    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun rotateIfNeeded(target: File) {
        if (target.length() < MAX_BYTES) return
        val previous = File(target.parentFile, target.nameWithoutExtension + "-previous.csv")
        previous.delete()
        if (!target.renameTo(previous)) throw IllegalStateException("Could not rotate diagnostic log")
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        val HEADER = listOf(
            "session", "time", "device", "mode", "link", "setup_ms", "direction", "seq", "status", "wire_bytes",
            "recorded_s", "speech_s", "vad_ms", "stt_ms", "tts_ms", "queue_ms", "ack_after_ms",
            "peer_tts_ms", "peer_queue_ms", "rtt_ms", "e2e_ms", "other_ms", "text", "tts_total_ms", "voice_chunks",
            "source_lang", "output_lang", "translation_ms", "translated_text", "translation_error",
        )
        val LINK_HEADER = listOf("session", "time", "device", "mode", "link", "event", "duration_ms", "detail")
    }
}
