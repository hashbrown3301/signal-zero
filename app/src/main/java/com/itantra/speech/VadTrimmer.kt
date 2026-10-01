package com.itantra.speech

import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Cuts silence out of a push-to-talk recording with Silero VAD, so STT only sees speech.
 * Each detected segment keeps [PAD_SAMPLES] of context on both sides so word onsets survive.
 */
class VadTrimmer(assets: AssetManager) {

    data class Result(val speech: FloatArray, val segments: Int, val millis: Long) {
        val isEmpty: Boolean get() = speech.isEmpty()
    }

    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                maxSpeechDuration = 30f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    fun trim(samples: FloatArray): Result {
        val start = SystemClock.elapsedRealtime()
        vad.reset()

        val ranges = mutableListOf<IntRange>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                ranges += (seg.start - PAD_SAMPLES).coerceAtLeast(0) until
                    (seg.start + seg.samples.size + PAD_SAMPLES).coerceAtMost(samples.size)
                vad.pop()
            }
        }

        var i = 0
        while (i + WINDOW <= samples.size) {
            vad.acceptWaveform(samples.copyOfRange(i, i + WINDOW))
            drain()
            i += WINDOW
        }
        // Silero needs full windows. Keep the last partial frame instead of dropping up to
        // 31 ms of speech; ranges below stay clamped to the original recording length.
        if (i < samples.size) {
            vad.acceptWaveform(samples.copyOfRange(i, samples.size).copyOf(WINDOW))
            drain()
        }
        vad.flush()
        drain()

        val merged = mergeOverlapping(ranges)
        val speech = FloatArray(merged.sumOf { it.last - it.first + 1 })
        var pos = 0
        for (r in merged) {
            samples.copyInto(speech, pos, r.first, r.last + 1)
            pos += r.last - r.first + 1
        }
        return Result(speech, merged.size, SystemClock.elapsedRealtime() - start)
    }

    fun release() = vad.release()

    private fun mergeOverlapping(ranges: List<IntRange>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (r in ranges.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                out[out.lastIndex] = last.first..maxOf(last.last, r.last)
            } else {
                out += r
            }
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val WINDOW = 512 // 32 ms, Silero's native window at 16 kHz
        private const val PAD_SAMPLES = SAMPLE_RATE / 10 // 100 ms
    }
}
