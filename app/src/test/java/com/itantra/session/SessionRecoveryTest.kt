package com.itantra.session

import com.itantra.comm.Language
import com.itantra.comm.LinkState
import com.itantra.comm.Packet
import com.itantra.comm.PacketType
import com.itantra.comm.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Fault-controlled application flows; no Android hardware or native model accuracy is simulated. */
class SessionRecoveryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableListOf<SessionManager>()

    @After fun close() { sessions.forEach { it.close() }; scope.cancel() }

    private class TestListener : Listener {
        val starts = AtomicInteger()
        val finishes = AtomicInteger()
        val cancels = AtomicInteger()
        var finishGate: CompletableDeferred<Unit>? = null
        override fun start() { starts.incrementAndGet() }
        override suspend fun finish(): Heard {
            finishes.incrementAndGet()
            finishGate?.await()
            return Heard("नमस्ते", 2f, 1.5f, 7, 11)
        }
        override fun cancel() { cancels.incrementAndGet() }
    }

    private class TestTransport : Transport {
        override val state = MutableStateFlow<LinkState>(LinkState.Connected("peer"))
        val packets = Channel<Packet>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<Packet>()
        override val incoming: Flow<Packet> = packets.receiveAsFlow()
        override suspend fun send(packet: Packet): Int { sent += packet; return 0 }
        override fun close() { state.value = LinkState.Closed }
    }

    private class TestTranslator : Translator {
        val sources = CopyOnWriteArrayList<String>()
        var result = Translated("Hello", 19)
        var failure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override suspend fun translate(text: String, source: Language, target: Language): Translated {
            sources += text
            entered.complete(Unit)
            gate?.await()
            failure?.let { throw it }
            return result
        }
    }

    private class TestSpeaker : Speaker {
        val prepared = CopyOnWriteArrayList<Pair<String, Int>>()
        val played = CopyOnWriteArrayList<String>()
        var hasVoice = true
        var playGate: CompletableDeferred<Unit>? = null
        val enteredPlay = CompletableDeferred<Unit>()
        override suspend fun prepare(text: String, langCode: Int): Prepared? {
            prepared += text to langCode
            if (!hasVoice) return null
            return object : Prepared {
                override val synthMs = 13L
                override suspend fun play() {
                    enteredPlay.complete(Unit)
                    playGate?.await()
                    played += text
                }
            }
        }
    }

    private fun session(listener: TestListener = TestListener(), translator: TestTranslator = TestTranslator(),
                        speaker: TestSpeaker = TestSpeaker(), transport: TestTransport? = null,
                        review: Boolean = false, queue: Int = 16): SessionManager = SessionManager(
        scope, listener, speaker, transport, language = Language.HINDI, minPressMs = 0, pingIntervalMs = 0,
        translator = translator, targetLanguage = Language.ENGLISH, reviewBeforeSend = review,
        maxQueuedMessages = queue,
    ).also { sessions += it; it.start() }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(5_000) { block() } }
    private suspend fun SessionManager.done(status: Status): Message = state.first {
        it.messages.lastOrNull()?.status == status && !it.speaking && it.phase == Phase.Ready
    }.messages.last()

    @Test fun speechReviewWithholdsTranslationAndRetainsCorrectedSourceProvenance() = test {
        val listener = TestListener()
        val translator = TestTranslator()
        val speaker = TestSpeaker()
        val session = session(listener, translator, speaker, review = true)
        assertTrue(session.pressStart())
        session.pressEnd()
        val draft = session.state.first { it.phase == Phase.Reviewing }.draft!!
        assertEquals("नमस्ते", draft.originalText)
        assertTrue(session.state.value.messages.isEmpty())
        assertTrue(translator.sources.isEmpty())
        assertTrue(speaker.prepared.isEmpty())
        assertFalse(session.pressStart())
        assertTrue(session.confirmDraft("मुझे मदद चाहिए।"))
        assertFalse(session.confirmDraft("Must not submit twice"))
        val message = session.done(Status.PLAYED)
        assertEquals("मुझे मदद चाहिए।", message.text)
        assertEquals("नमस्ते", message.originalTranscript)
        assertEquals(InputOrigin.SPEECH, message.inputOrigin)
        assertEquals(7L, message.vadMs)
        assertEquals(listOf("मुझे मदद चाहिए।"), translator.sources)
    }

    @Test fun peerDraftSendsOnlyConfirmedTextAndCanBeDiscardedWithoutPackets() = test {
        val transport = TestTransport()
        val session = session(transport = transport, review = true)
        assertTrue(session.pressStart()); session.pressEnd()
        session.state.first { it.draft != null }
        assertTrue(transport.sent.isEmpty())
        assertFalse(session.confirmDraft(" "))
        assertNotNull(session.state.value.draft)
        assertTrue(session.discardDraft())
        assertFalse(session.discardDraft())
        assertTrue(session.state.value.messages.isEmpty())
        assertTrue(session.pressStart()); session.pressEnd()
        session.state.first { it.draft != null }
        assertTrue(session.confirmDraft("सही शब्द"))
        session.state.first { it.messages.isNotEmpty() && it.phase == Phase.Ready }
        val packet = transport.sent.filter { it.type == PacketType.TEXT }.distinct().single()
        assertEquals("सही शब्द", packet.text)
        assertEquals(Language.HINDI, packet.language)
    }

    @Test fun typedSoloTextDoesNotUseCaptureOrRecognition() = test {
        val listener = TestListener()
        val translator = TestTranslator()
        val session = session(listener = listener, translator = translator, review = true)
        assertTrue(session.submitText("नमस्ते"))
        val message = session.done(Status.PLAYED)
        assertEquals(InputOrigin.TYPED, message.inputOrigin)
        assertEquals(0, listener.starts.get())
        assertEquals(0, listener.finishes.get())
        assertNull(message.sttMs)
        assertNull(message.recordedSec)
        assertNull(message.originalTranscript)
        assertNull(session.state.value.draft)
    }

    @Test fun typedPeerTextPreservesWhitespaceAndDoesNotTranslateOnSender() = test {
        val transport = TestTransport()
        val translator = TestTranslator()
        val session = session(transport = transport, translator = translator)
        assertTrue(session.submitText("  नमस्ते\nदुनिया  "))
        session.state.first { it.messages.isNotEmpty() && it.phase == Phase.Ready }
        // The initial Connected collector can resend the same pending packet before it gets an ACK.
        // Packet equality includes the wire identity, language, and exact payload bytes.
        val packets = transport.sent.filter { it.type == PacketType.TEXT }
        assertEquals(1, packets.distinct().size)
        assertTrue(packets.all { it.text == "  नमस्ते\nदुनिया  " })
        assertEquals(session.state.value.messages.single().seq, packets.first().seq)
        assertTrue(translator.sources.isEmpty())
        assertEquals(InputOrigin.TYPED, session.state.value.messages.single().inputOrigin)
    }

    @Test fun cancelledPressNeverFinishesOrSubmitsAndClosedSessionRejectsActions() = test {
        val listener = TestListener()
        val translator = TestTranslator()
        val session = session(listener, translator)
        assertTrue(session.pressStart())
        assertTrue(session.cancelPress())
        assertFalse(session.cancelPress())
        session.pressEnd()
        assertEquals(0, listener.finishes.get())
        assertEquals(1, listener.cancels.get())
        assertTrue(session.state.value.messages.isEmpty())
        session.close()
        assertFalse(session.pressStart())
        assertFalse(session.submitText("Text after close"))
        assertFalse(session.confirmDraft("Stale draft"))
        assertTrue(translator.sources.isEmpty())
    }

    @Test fun incomingVoiceWaitsForCaptureProcessingAndDraftDecision() = test {
        val listener = TestListener().also { it.finishGate = CompletableDeferred() }
        val speaker = TestSpeaker()
        val transport = TestTransport()
        val session = session(listener = listener, speaker = speaker, transport = transport, review = true)
        assertTrue(session.pressStart()); session.pressEnd()
        transport.packets.send(Packet.text(1, 0, "नमस्ते"))
        session.state.first { it.messages.isNotEmpty() }
        assertEquals(Phase.Processing, session.state.value.phase)
        assertTrue(speaker.prepared.isEmpty())
        assertFalse(session.pressStart())
        assertFalse(session.submitText("Must not interrupt capture shutdown"))
        listener.finishGate!!.complete(Unit)
        session.state.first { it.phase == Phase.Reviewing }
        assertTrue(speaker.prepared.isEmpty())
        assertTrue(session.discardDraft())
        session.done(Status.PLAYED)
        assertEquals(listOf("Hello"), speaker.played)
    }

    @Test fun flaggedModelTranslationWaitsForExplicitConsentAndReplaySkipsModel() = test {
        val translator = TestTranslator().also { it.result = Translated("Buy 2 tickets", 19) }
        val speaker = TestSpeaker()
        val transport = TestTransport()
        val session = session(translator = translator, speaker = speaker, transport = transport)
        transport.packets.send(Packet.text(1, 0, "मुझे 3 टिकट चाहिए।"))
        val message = session.done(Status.NEEDS_REVIEW)
        assertFalse(message.warnings.isEmpty())
        assertEquals(TranslationOrigin.MODEL, message.translationOrigin)
        assertTrue(speaker.prepared.isEmpty())
        assertEquals(0L, transport.sent.single { it.type == PacketType.ACK }.ackTtsMs)
        assertFalse(session.replayMessage(message.id))
        speaker.playGate = CompletableDeferred()
        assertTrue(session.replayMessage(message.id, allowUnsafe = true))
        assertFalse(session.replayMessage(message.id, allowUnsafe = true))
        speaker.enteredPlay.await()
        speaker.playGate!!.complete(Unit)
        val played = session.done(Status.PLAYED)
        assertEquals(message.warnings, played.warnings)
        assertEquals(listOf("Buy 2 tickets"), speaker.played)
        assertEquals(1, translator.sources.size)
        assertEquals(1, transport.sent.count { it.type == PacketType.ACK })
    }

    @Test fun reviewedPhraseAutoplaysWithItsOriginAndWarningsVisible() = test {
        val translator = TestTranslator().also {
            it.result = Translated("Buy 2 tickets", 0, origin = TranslationOrigin.REVIEWED_PHRASE)
        }
        val speaker = TestSpeaker()
        val session = session(translator = translator, speaker = speaker)
        assertTrue(session.submitText("मुझे 3 टिकट चाहिए।"))
        val message = session.done(Status.PLAYED)
        assertEquals(TranslationOrigin.REVIEWED_PHRASE, message.translationOrigin)
        assertFalse(message.warnings.isEmpty())
        assertEquals(listOf("Buy 2 tickets"), speaker.played)
    }

    @Test fun missingVoiceRecoveryCallsTranslationFreshAndRetainsOriginalMessage() = test {
        val translator = TestTranslator()
        val speaker = TestSpeaker().also { it.hasVoice = false }
        val transport = TestTransport()
        val session = session(translator = translator, speaker = speaker, transport = transport)
        val packet = Packet.text(5, 10, "नमस्ते")
        transport.packets.send(packet)
        val original = session.done(Status.NO_VOICE)
        speaker.hasVoice = true
        translator.result = Translated("User reviewed replacement", 0, origin = TranslationOrigin.REVIEWED_PHRASE)
        assertTrue(session.retryMessage(original.id))
        assertFalse(session.retryMessage(original.id))
        val recovered = session.done(Status.PLAYED)
        assertEquals(original.id, recovered.id)
        assertEquals(original.text, recovered.text)
        assertEquals("User reviewed replacement", recovered.translatedText)
        assertEquals(TranslationOrigin.REVIEWED_PHRASE, recovered.translationOrigin)
        assertEquals(2, translator.sources.size)
        transport.packets.send(packet)
        transport.packets.send(Packet.ping(90, 0))
        session.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }
        withTimeout(1_000) {
            while (transport.sent.none { it.type == PacketType.PONG && it.seq == 90 }) kotlinx.coroutines.yield()
        }
        assertEquals(1, speaker.played.size)
        assertEquals(2, transport.sent.count { it.type == PacketType.ACK })
    }

    @Test fun retryAfterTranslationFailureUsesSourceAndReplayUsesSavedTarget() = test {
        val translator = TestTranslator().also { it.failure = IllegalStateException("Missing model") }
        val speaker = TestSpeaker()
        val session = session(translator = translator, speaker = speaker)
        assertTrue(session.submitText("नमस्ते"))
        val failed = session.done(Status.TRANSLATION_FAILED)
        translator.failure = null
        assertTrue(session.retryMessage(failed.id))
        val recovered = session.done(Status.PLAYED)
        assertNull(recovered.translationError)
        speaker.playGate = CompletableDeferred()
        assertTrue(session.replayMessage(recovered.id))
        speaker.enteredPlay.await()
        assertFalse(session.replayMessage(recovered.id))
        speaker.playGate!!.complete(Unit)
        session.done(Status.PLAYED)
        assertEquals(listOf("Hello", "Hello"), speaker.played)
        assertEquals(listOf("नमस्ते", "नमस्ते"), translator.sources)
        assertEquals(1, session.state.value.messages.size)
    }

    @Test fun packMaintenanceRetainsIncomingTextAndControlWithoutStartingSpeech() = test {
        val translator = TestTranslator()
        val speaker = TestSpeaker()
        val transport = TestTransport()
        val session = session(translator = translator, speaker = speaker, transport = transport)
        assertTrue(session.beginPackMaintenance())
        assertEquals(Phase.Maintenance, session.state.value.phase)
        assertFalse(session.beginPackMaintenance())
        assertFalse(session.pressStart())
        assertFalse(session.submitText("Must not load an engine being replaced"))
        val packet = Packet.text(7, 10, "नमस्ते")
        transport.packets.send(packet)
        transport.packets.send(packet)
        transport.packets.send(Packet.ping(90, 0))
        withTimeout(1_000) {
            while (transport.sent.none { it.type == PacketType.PONG && it.seq == 90 }) kotlinx.coroutines.yield()
        }
        assertEquals(Status.QUEUED, session.state.value.messages.single().status)
        assertTrue(translator.sources.isEmpty())
        assertTrue(speaker.prepared.isEmpty())
        assertEquals(0, transport.sent.count { it.type == PacketType.ACK })
        assertTrue(session.endPackMaintenance())
        session.done(Status.PLAYED)
        assertEquals(listOf("नमस्ते"), translator.sources)
        assertEquals(listOf("Hello"), speaker.played)
        assertEquals(1, transport.sent.count { it.type == PacketType.ACK })
        assertFalse(session.endPackMaintenance())
        assertTrue(session.beginPackMaintenance())
        session.close()
        assertFalse(session.endPackMaintenance())
        assertFalse(session.beginPackMaintenance())
    }

    @Test fun packMaintenanceRejectsCaptureTranslationAndQueuedPlayback() = test {
        val translator = TestTranslator().also { it.gate = CompletableDeferred() }
        val transport = TestTransport()
        val session = session(translator = translator, transport = transport, queue = 1)
        assertTrue(session.pressStart())
        assertFalse(session.beginPackMaintenance())
        assertTrue(session.cancelPress())
        transport.packets.send(Packet.text(1, 1, "नमस्ते"))
        translator.entered.await()
        assertTrue(session.state.value.translating)
        assertFalse(session.beginPackMaintenance())
        transport.packets.send(Packet.text(2, 2, "नमस्ते"))
        session.state.first { it.queuedMessages == 1 }
        assertFalse(session.beginPackMaintenance())
        translator.gate!!.complete(Unit)
        session.state.first { it.messages.count { message -> message.status == Status.PLAYED } == 2 && !it.speaking }
        assertTrue(session.beginPackMaintenance())
        assertTrue(session.endPackMaintenance())
    }

    @Test fun floodCannotHideActiveSourceOrExceedHistoryAndControlStillResponds() = test {
        val translator = TestTranslator().also { it.gate = CompletableDeferred() }
        val transport = TestTransport()
        val session = session(translator = translator, transport = transport, queue = 1)
        transport.packets.send(Packet.text(1, 1, "पहला"))
        translator.entered.await()
        for (index in 2..200) transport.packets.send(Packet.text(index, index.toLong(), "नमस्ते"))
        transport.packets.send(Packet.text(1, 1, "पहला"))
        transport.packets.send(Packet.ping(999, 0))
        withTimeout(1_000) {
            while (transport.sent.none { it.type == PacketType.PONG && it.seq == 999 }) kotlinx.coroutines.yield()
        }
        assertEquals(100, session.state.value.messages.size)
        assertTrue(session.state.value.messages.any { it.text == "पहला" })
        assertEquals(1, session.state.value.queuedMessages)
        assertEquals(1, translator.sources.size)
        assertNotNull(session.state.value.notice)
        translator.gate!!.complete(Unit)
        session.state.first { it.messages.count { message -> message.status == Status.PLAYED } == 2 && !it.speaking }
        assertEquals(2, translator.sources.size)
    }

    @Test fun pendingOutgoingAdmissionIsBoundedAndUnconfirmedRowsStayVisible() = test {
        val transport = TestTransport()
        val session = session(transport = transport)
        repeat(32) { index ->
            assertTrue(session.submitText("message $index"))
            session.state.first { it.phase == Phase.Ready && it.messages.size == index + 1 }
        }
        assertFalse(session.submitText("overflow"))
        assertEquals(32, transport.sent.filter { it.type == PacketType.TEXT }.distinct().size)
        assertNotNull(session.state.value.notice)
        transport.packets.send(Packet.ack(transport.sent.first(), 0, 0))
        session.state.first { it.messages.first().status == Status.ACKED }
        assertTrue(session.submitText("after acknowledgement"))
        session.state.first { it.phase == Phase.Ready && it.messages.size == 33 }
    }

    @Test fun validationRejectsBlankControlsAndOversizeWithoutChangingValidUnicode() = test {
        val listener = TestListener()
        val session = session(listener = listener)
        for (text in listOf(" ", "x".repeat(1_001), "hello\u0000world", "abc\u007f")) {
            assertNotNull(SessionManager.inputError(text))
            assertFalse(session.submitText(text))
        }
        assertNull(SessionManager.inputError("क़ल -3.5 रुपये\nதமிழ் 😀"))
        assertEquals(0, listener.starts.get())
        assertTrue(session.state.value.messages.isEmpty())
    }
}
