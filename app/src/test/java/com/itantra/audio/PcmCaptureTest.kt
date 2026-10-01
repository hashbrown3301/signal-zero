package com.itantra.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PcmCaptureTest {
    private open class FakeSource(
        private val frames: List<ShortArray> = emptyList(),
        private val terminal: Int? = null,
        private val startFailure: Boolean = false,
        private val readFailure: Boolean = false,
        private val stopFailure: Boolean = false,
        private val releaseFailure: Boolean = false,
        private val unblockOnStop: Boolean = true,
        private val unblockOnRelease: Boolean = true,
        private val lateSample: Short? = null,
    ) : PcmSource {
        val reads = AtomicInteger()
        val stops = AtomicInteger()
        val releases = AtomicInteger()
        val waiting = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val returnedLateRead = CountDownLatch(1)
        private val unblock = CountDownLatch(1)

        override fun start() {
            if (startFailure) throw IOException("start failed")
        }

        override fun read(buffer: ShortArray): Int {
            val index = reads.getAndIncrement()
            if (readFailure) throw IOException("read failed")
            frames.getOrNull(index)?.let { frame ->
                check(frame.size <= buffer.size)
                frame.copyInto(buffer)
                return frame.size
            }
            terminal?.let { return it }
            waiting.countDown()
            unblock.await()
            returnedLateRead.countDown()
            lateSample?.let {
                buffer[0] = it
                return 1
            }
            return -3 // AudioRecord can report a stopped read; this is not a capture failure.
        }

        override fun stop() {
            stops.incrementAndGet()
            stopped.countDown()
            if (unblockOnStop) unblock.countDown()
            if (stopFailure) throw IOException("stop failed")
        }

        override fun release() {
            releases.incrementAndGet()
            if (unblockOnRelease) unblock.countDown()
            if (releaseFailure) throw IOException("release failed")
        }

        fun forceUnblock() = unblock.countDown()
    }

    private fun await(latch: CountDownLatch) =
        assertTrue("Expected fake source event", latch.await(2, TimeUnit.SECONDS))

    @Test(timeout = 5_000)
    fun retainsCompleteSamplesAndNormalizesPcmIncludingNegativeFullScale() {
        val source = FakeSource(listOf(shortArrayOf(0, 16_384, -32_768, 32_767)))
        val capture = PcmCapture(1_000) { source }
        capture.start()
        await(source.waiting)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f, 32_767 / 32_768f), capture.stop(), 0f)
        assertFalse(capture.isRecording)
        assertEquals(1, source.stops.get())
        assertEquals(1, source.releases.get())
        assertArrayEquals(floatArrayOf(), capture.stop(), 0f)
        capture.discard()
        assertEquals(1, source.releases.get())
    }

    @Test(timeout = 5_000)
    fun deadRecorderFailsAfterOneReadAndIsReleasedWhenStopped() {
        val source = FakeSource(terminal = -6)
        val capture = PcmCapture(1_000) { source }
        capture.start()
        await(source.stopped)
        assertFalse(capture.isRecording)
        val failure = assertThrows(CaptureException::class.java) { capture.stop() }
        assertTrue(failure.message!!.contains("-6"))
        assertEquals(1, source.reads.get())
        assertEquals(1, source.stops.get())
        assertEquals(1, source.releases.get())
    }

    @Test(timeout = 5_000)
    fun emptyReadDoesNotHotLoop() {
        val source = FakeSource(terminal = 0)
        val capture = PcmCapture(1_000) { source }
        capture.start()
        await(source.stopped)
        assertThrows(CaptureException::class.java) { capture.stop() }
        assertEquals(1, source.reads.get())
        assertEquals(1, source.releases.get())
    }

    @Test(timeout = 5_000)
    fun overlongCaptureStopsAcquisitionAndRejectsPartialRecognition() {
        val source = FakeSource(List(1_000) { shortArrayOf(1) })
        val capture = PcmCapture(10, maxSeconds = 1) { source }
        capture.start()
        await(source.stopped)
        assertEquals(11, source.reads.get()) // ten retained samples, then one over-limit read.
        val failure = assertThrows(CaptureException::class.java) { capture.stop() }
        assertTrue(failure.message!!.contains("Recording is too long"))
        assertEquals(1, source.releases.get())
    }

    @Test(timeout = 5_000)
    fun exactlyAtSampleLimitPreservesWholeRecording() {
        val source = FakeSource(List(10) { shortArrayOf(100) })
        val capture = PcmCapture(10, maxSeconds = 1) { source }
        capture.start()
        await(source.waiting)
        assertArrayEquals(FloatArray(10) { 100 / 32_768f }, capture.stop(), 0f)
    }

    @Test(timeout = 5_000)
    fun failedStartReleasesResourceAndAllowsAnotherCapture() {
        val bad = FakeSource(startFailure = true)
        val good = FakeSource(listOf(shortArrayOf(500)))
        val factoryCalls = AtomicInteger()
        val capture = PcmCapture(1_000) { if (factoryCalls.getAndIncrement() == 0) bad else good }
        assertThrows(IOException::class.java) { capture.start() }
        assertEquals(0, bad.stops.get())
        assertEquals(1, bad.releases.get())
        capture.start()
        await(good.waiting)
        assertArrayEquals(floatArrayOf(500 / 32_768f), capture.stop(), 0f)
    }

    @Test(timeout = 5_000)
    fun discardReleasesFailedCaptureWithoutReturningAudioOrRecognitionError() {
        val source = FakeSource(terminal = -6)
        val capture = PcmCapture(1_000) { source }
        capture.start()
        await(source.stopped)
        capture.discard()
        capture.discard()
        assertEquals(1, source.releases.get())
        assertArrayEquals(floatArrayOf(), capture.stop(), 0f)
    }

    @Test(timeout = 5_000)
    fun readExceptionHasExplicitFailureAndReleasesResource() {
        val source = FakeSource(readFailure = true)
        val capture = PcmCapture(1_000) { source }
        capture.start()
        await(source.stopped)
        val failure = assertThrows(CaptureException::class.java) { capture.stop() }
        assertTrue(failure.cause is IOException)
        assertEquals(1, source.reads.get())
        assertEquals(1, source.releases.get())
    }

    @Test(timeout = 5_000)
    fun stuckReadHasBoundedJoinAndAlwaysReleasesSource() {
        val source = FakeSource(unblockOnStop = false, unblockOnRelease = false)
        val capture = PcmCapture(1_000, joinTimeoutMillis = 40) { source }
        capture.start()
        await(source.waiting)
        try {
            val startedAt = System.nanoTime()
            val failure = assertThrows(CaptureException::class.java) { capture.stop() }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(failure.message!!.contains("did not stop in time"))
            assertTrue("Capture should not wait for the permanently blocked reader", elapsedMillis < 1_000)
            assertEquals(1, source.releases.get())
        } finally {
            source.forceUnblock()
        }
    }

    @Test(timeout = 5_000)
    fun lateReadFromTimedOutCaptureCannotContaminateNewCapture() {
        val old = FakeSource(
            frames = listOf(shortArrayOf(7)),
            unblockOnStop = false, unblockOnRelease = false, lateSample = 32_767,
        )
        val fresh = FakeSource(listOf(shortArrayOf(222)))
        val factoryCalls = AtomicInteger()
        val capture = PcmCapture(1_000, joinTimeoutMillis = 40) {
            if (factoryCalls.getAndIncrement() == 0) old else fresh
        }
        capture.start()
        await(old.waiting)
        try {
            assertThrows(CaptureException::class.java) { capture.stop() }
            capture.start()
            await(fresh.waiting)
            old.forceUnblock()
            await(old.returnedLateRead)
            assertArrayEquals(floatArrayOf(222 / 32_768f), capture.stop(), 0f)
            assertEquals(1, old.releases.get())
            assertEquals(1, fresh.releases.get())
        } finally {
            old.forceUnblock()
            capture.discard()
        }
    }

    @Test(timeout = 5_000)
    fun concurrentStopDetachesOnceAndBlocksNewStartUntilCleanupCompletes() {
        val stopEntered = CountDownLatch(1)
        val allowStop = CountDownLatch(1)
        val source = object : FakeSource(listOf(shortArrayOf(333))) {
            override fun stop() {
                stopEntered.countDown()
                allowStop.await()
                super.stop()
            }
        }
        val factoryCalls = AtomicInteger()
        val capture = PcmCapture(1_000) { factoryCalls.incrementAndGet(); source }
        val stoppedAudio = AtomicReference<FloatArray>()
        val stopFailure = AtomicReference<Throwable>()
        capture.start()
        await(source.waiting)
        val stopper = Thread {
            try { stoppedAudio.set(capture.stop()) } catch (failure: Throwable) { stopFailure.set(failure) }
        }
        stopper.start()
        try {
            await(stopEntered)
            capture.discard()
            assertThrows(IllegalStateException::class.java) { capture.start() }
            assertEquals(1, factoryCalls.get())
        } finally {
            allowStop.countDown()
            stopper.join(2_000)
        }
        assertFalse(stopper.isAlive)
        assertEquals(null, stopFailure.get())
        assertArrayEquals(floatArrayOf(333 / 32_768f), stoppedAudio.get(), 0f)
        assertEquals(1, source.stops.get())
        assertEquals(1, source.releases.get())
    }

    @Test(timeout = 5_000)
    fun stopFailureStillReleasesAndClearsCaptureOwnership() {
        val source = FakeSource(stopFailure = true)
        val capture = PcmCapture(1_000) { source }
        capture.start()
        await(source.waiting)
        val failure = assertThrows(CaptureException::class.java) { capture.stop() }
        assertTrue(failure.message!!.contains("Could not stop"))
        assertEquals(1, source.releases.get())
        assertArrayEquals(floatArrayOf(), capture.stop(), 0f)
    }

    @Test(timeout = 5_000)
    fun releaseFailureIsReportedAfterReaderStops() {
        val source = FakeSource(releaseFailure = true)
        val capture = PcmCapture(1_000) { source }
        capture.start()
        await(source.waiting)
        val failure = assertThrows(CaptureException::class.java) { capture.stop() }
        assertTrue(failure.message!!.contains("Could not release"))
        assertEquals(1, source.stops.get())
        assertEquals(1, source.releases.get())
    }
}
