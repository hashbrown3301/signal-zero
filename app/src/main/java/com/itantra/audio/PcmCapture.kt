package com.itantra.audio

import java.util.concurrent.atomic.AtomicBoolean

/** The small Android-independent boundary used to test capture failures and shutdown. */
internal interface PcmSource {
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
    fun release()
}

internal class CaptureException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Owns one capture at a time. Each capture has its own samples and reader, so a late
 * read from a stopped source cannot leak into the next utterance. Stop/discard detach
 * ownership once, and wait at most [joinTimeoutMillis] for the reader to finish.
 * The platform source's own stop/release calls still depend on its audio driver.
 */
internal class PcmCapture(
    sampleRate: Int,
    private val maxSeconds: Int = 30,
    private val joinTimeoutMillis: Long = 500,
    private val sourceFactory: () -> PcmSource,
) {
    private val maxSamples: Int
    private val readSamples: Int
    private val ownership = Any()
    private var active: Capture? = null
    private var stopping = false

    init {
        require(sampleRate > 0 && maxSeconds > 0)
        require(joinTimeoutMillis > 0)
        maxSamples = Math.multiplyExact(sampleRate, maxSeconds)
        readSamples = maxOf(1, sampleRate / 50)
    }

    private class Capture(val source: PcmSource) {
        val samples = ArrayList<ShortArray>()
        var sampleCount = 0
        var accepting = true
        var failure: CaptureException? = null
        val stopStarted = AtomicBoolean()
        val releaseStarted = AtomicBoolean()
        @Volatile var running = true
        lateinit var reader: Thread

        fun fail(error: CaptureException) = synchronized(this) {
            if (failure == null) failure = error
            running = false
        }
    }

    val isRecording: Boolean
        get() = synchronized(ownership) { active?.running == true }

    fun start() = synchronized(ownership) {
        check(active == null && !stopping) { "Already recording or stopping microphone" }
        val source = sourceFactory()
        try {
            source.start()
        } catch (error: Exception) {
            runCatching { source.release() }
            throw error
        }
        val capture = Capture(source)
        capture.reader = Thread({ read(capture) }, "AudioRecorder").apply {
            // A broken driver must not keep a test JVM/process alive indefinitely.
            isDaemon = true
        }
        active = capture
        try {
            capture.reader.start()
        } catch (error: Exception) {
            active = null
            capture.running = false
            stopSource(capture)
            releaseSource(capture)
            throw error
        }
    }

    fun stop(): FloatArray = finish(discard = false)

    /** Discards samples without allocating a float copy or surfacing a recognition error. */
    fun discard() { finish(discard = true) }

    private fun read(capture: Capture) {
        val buffer = ShortArray(readSamples)
        try {
            while (capture.running) {
                val count = capture.source.read(buffer)
                if (count <= 0 || count > buffer.size) {
                    if (capture.running) capture.fail(
                        CaptureException("Microphone read failed (audio error $count). Try again.")
                    )
                    break
                }
                synchronized(capture) {
                    if (!capture.accepting) return
                    if (count > maxSamples - capture.sampleCount) {
                        capture.fail(CaptureException("Recording is too long. Hold to talk for up to $maxSeconds seconds, then try again."))
                    } else {
                        capture.samples.add(buffer.copyOf(count))
                        capture.sampleCount += count
                    }
                }
            }
        } catch (error: Exception) {
            if (capture.running) capture.fail(CaptureException("Microphone capture failed. Try again.", error))
        } finally {
            // A bound/read failure stops microphone acquisition even before button release.
            if (synchronized(capture) { capture.failure != null }) stopSource(capture)
        }
    }

    private fun stopSource(capture: Capture) {
        if (capture.stopStarted.compareAndSet(false, true)) {
            try {
                capture.source.stop()
            } catch (error: Exception) {
                capture.fail(CaptureException("Could not stop microphone. Try again.", error))
            }
        }
    }

    private fun releaseSource(capture: Capture) {
        if (capture.releaseStarted.compareAndSet(false, true)) {
            try {
                capture.source.release()
            } catch (error: Exception) {
                capture.fail(CaptureException("Could not release microphone. Try again.", error))
            }
        }
    }

    private fun finish(discard: Boolean): FloatArray {
        val capture = synchronized(ownership) {
            val current = active ?: return FloatArray(0)
            active = null
            stopping = true
            current.running = false
            current
        }
        try {
            try {
                // AudioRecord.stop normally unblocks a blocking read. Do this before join.
                stopSource(capture)
                try {
                    capture.reader.join(joinTimeoutMillis)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    capture.fail(CaptureException("Microphone shutdown was interrupted. Try again.", error))
                }
                if (capture.reader.isAlive) {
                    capture.fail(CaptureException("Microphone did not stop in time. Try again."))
                }
            } finally {
                releaseSource(capture)
            }
            return synchronized(capture) {
                capture.accepting = false
                try {
                    if (discard) return@synchronized FloatArray(0)
                    capture.failure?.let { throw it }
                    FloatArray(capture.sampleCount).also { output ->
                        var index = 0
                        for (chunk in capture.samples) for (sample in chunk) {
                            output[index++] = sample / 32768f
                        }
                    }
                } finally {
                    capture.samples.clear()
                    capture.sampleCount = 0
                }
            }
        } finally {
            synchronized(ownership) { stopping = false }
        }
    }
}
