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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TranslationSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = mutableListOf<SessionManager>()
    private val original = "नमस्ते"
    private val translated = "Hello"

    @After
    fun close() {
        sessions.forEach { it.close() }
        scope.cancel()
    }

    private class FakeTransport : Transport {
        override val state = MutableStateFlow<LinkState>(LinkState.Connected("fake"))
        val inbox = Channel<Packet>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<Packet>()
        override val incoming: Flow<Packet> = inbox.receiveAsFlow()
        override suspend fun send(packet: Packet): Int { sent += packet; return 0 }
        override fun close() { state.value = LinkState.Closed }
    }

    private class FakeSpeaker(private val hasVoice: Boolean = true, private val failFirstPrepare: Boolean = false) : Speaker {
        val prepared = CopyOnWriteArrayList<Pair<String, Int>>()
        val played = CopyOnWriteArrayList<String>()
        val preloaded = CopyOnWriteArrayList<Int>()
        override suspend fun preload(langCode: Int): Boolean { preloaded += langCode; return true }
        override suspend fun prepare(text: String, langCode: Int): Prepared? {
            prepared += text to langCode
            if (failFirstPrepare && prepared.size == 1) error("Voice could not load")
            if (!hasVoice) return null
            return object : Prepared {
                override val synthMs = 11L
                override suspend fun play() { played += text }
            }
        }
    }

    private class FakeTranslator(
        private val action: suspend (String, Language, Language) -> Translated = { _, _, _ -> Translated("Hello", 37) },
    ) : Translator {
        val calls = CopyOnWriteArrayList<Triple<String, Language, Language>>()
        override suspend fun translate(text: String, source: Language, target: Language): Translated {
            calls += Triple(text, source, target)
            return action(text, source, target)
        }
    }

    private fun session(
        transport: FakeTransport? = FakeTransport(),
        speaker: FakeSpeaker = FakeSpeaker(),
        translator: Translator? = FakeTranslator(),
        target: Language? = Language.ENGLISH,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
        listener: Listener? = null,
        minPressMs: Long = 0,
        maxQueuedMessages: Int = 16,
    ): SessionManager = SessionManager(
        scope,
        listener = listener ?: object : Listener {
            override fun start() = Unit
            override suspend fun finish() = Heard(original, 1f, 1f, 3, 7)
            override fun cancel() = Unit
        },
        speaker = speaker,
        transport = transport,
        language = Language.HINDI,
        clock = clock,
        minPressMs = minPressMs,
        pingIntervalMs = 0,
        translator = translator,
        targetLanguage = target,
        maxQueuedMessages = maxQueuedMessages,
    ).also { sessions += it; it.start() }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(5_000) { block() } }

    private suspend fun FakeTransport.ack(count: Int = 1): Packet {
        while (sent.count { it.type == PacketType.ACK } < count) delay(5)
        return sent.last { it.type == PacketType.ACK }
    }

    @Test
    fun incomingTranslationPreservesOriginalAndPreparesTargetVoice() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val translator = FakeTranslator()
        val session = session(transport, speaker, translator)
        transport.inbox.send(Packet.text(3, 5, original, Language.HINDI))
        val message = session.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }.messages.single()
        assertEquals(original, message.text)
        assertEquals(translated, message.translatedText)
        assertEquals(Language.HINDI.code, message.langCode)
        assertEquals(Language.ENGLISH.code, message.outputLangCode)
        assertEquals(37L, message.translationMs)
        assertEquals(listOf(Triple(original, Language.HINDI, Language.ENGLISH)), translator.calls)
        assertEquals(listOf(translated to Language.ENGLISH.code), speaker.prepared)
        assertEquals(listOf(translated), speaker.played)
        assertEquals(Language.HINDI.code, transport.ack().langCode)
    }

    @Test
    fun sameLanguageBypassesTranslatorEvenWhenRuntimeIsMissing() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val translator = FakeTranslator { _, _, _ -> error("Must not translate") }
        val session = session(transport, speaker, translator, target = Language.HINDI)
        transport.inbox.send(Packet.text(1, 0, original))
        val message = session.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }.messages.single()
        assertTrue(translator.calls.isEmpty())
        assertNull(message.translatedText)
        assertEquals(0L, message.translationMs)
        assertEquals(listOf(original to Language.HINDI.code), speaker.prepared)

        val withoutRuntime = session(transport = null, translator = null, target = Language.HINDI)
        assertTrue(withoutRuntime.pressStart())
        withoutRuntime.pressEnd()
        withoutRuntime.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }
    }

    @Test
    fun soloHoldToTalkTranslatesBeforePlayback() = test {
        val speaker = FakeSpeaker()
        val translator = FakeTranslator()
        val session = session(transport = null, speaker = speaker, translator = translator)
        assertTrue(session.pressStart())
        session.pressEnd()
        val message = session.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }.messages.single()
        assertEquals(Direction.LOCAL, message.direction)
        assertEquals(original, message.text)
        assertEquals(translated, message.translatedText)
        assertNull(message.wireBytes)
        assertEquals(listOf(translated to Language.ENGLISH.code), speaker.prepared)
        while (speaker.preloaded.isEmpty()) delay(5)
        assertEquals(listOf(Language.ENGLISH.code), speaker.preloaded)
    }

    @Test
    fun outgoingTextRemainsOriginalForTheReceiverToTranslate() = test {
        val transport = FakeTransport()
        val translator = FakeTranslator()
        val session = session(transport = transport, translator = translator)
        assertTrue(session.pressStart())
        session.pressEnd()
        session.state.first { it.phase == Phase.Ready }
        val packet = transport.sent.single { it.type == PacketType.TEXT }
        assertEquals(original, packet.text)
        assertEquals(Language.HINDI, packet.language)
        assertTrue(translator.calls.isEmpty())
    }

    @Test
    fun failedTranslationIsAckedAsOriginalTextWithoutSpeakingWrongLanguage() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val translator = FakeTranslator { _, _, _ -> throw IllegalStateException("Model is missing") }
        val session = session(transport, speaker, translator)
        transport.inbox.send(Packet.text(8, 123, original))
        val ack = transport.ack()
        val message = session.state.first { it.messages.singleOrNull()?.status == Status.TRANSLATION_FAILED }.messages.single()
        assertEquals(original, message.text)
        assertNull(message.translatedText)
        assertEquals("Model is missing", message.translationError)
        assertEquals(Language.ENGLISH.code, message.outputLangCode)
        assertEquals(0L, ack.ackTtsMs)
        assertEquals(123L, ack.timestamp)
        assertTrue(speaker.prepared.isEmpty())
        assertTrue(speaker.played.isEmpty())
        transport.inbox.send(Packet.text(8, 123, original))
        transport.ack(count = 2)
        assertEquals(1, translator.calls.size)
        assertEquals(1, session.state.value.messages.size)
    }

    @Test
    fun missingRuntimeAndUnknownSourceFailExplicitly() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val session = session(transport, speaker, translator = null)
        transport.inbox.send(Packet.text(1, 0, original))
        transport.ack()
        val missing = session.state.value.messages.single()
        assertEquals(Status.TRANSLATION_FAILED, missing.status)
        assertTrue(missing.translationError!!.contains("not available"))
        transport.inbox.send(Packet(PacketType.TEXT, 2, 250, 0, "Unknown text".encodeToByteArray()))
        transport.ack(count = 2)
        val unknown = session.state.value.messages.last()
        assertEquals(Status.TRANSLATION_FAILED, unknown.status)
        assertEquals("Unknown text", unknown.text)
        assertTrue(unknown.translationError!!.contains("Unknown source language"))
        assertTrue(speaker.prepared.isEmpty())
    }

    @Test
    fun emptyTranslationFailsWithoutPreparingVoiceAndQueueContinues() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val session = session(transport, speaker, FakeTranslator { text, _, _ ->
            Translated(if (text == original) "  " else "Second", 2)
        })
        transport.inbox.send(Packet.text(1, 0, original))
        transport.inbox.send(Packet.text(2, 0, "दूसरा"))
        session.state.first { it.messages.lastOrNull()?.status == Status.PLAYED }
        assertEquals(Status.TRANSLATION_FAILED, session.state.value.messages.first().status)
        assertEquals(listOf("Second"), speaker.played)
        assertEquals(2, transport.sent.count { it.type == PacketType.ACK })
    }

    @Test
    fun translatedTextIsStillDeliveredWhenTargetVoiceIsMissing() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker(hasVoice = false)
        val session = session(transport, speaker)
        transport.inbox.send(Packet.text(1, 0, original))
        val message = session.state.first { it.messages.singleOrNull()?.status == Status.NO_VOICE }.messages.single()
        assertEquals(original, message.text)
        assertEquals(translated, message.translatedText)
        assertEquals(Language.ENGLISH.code, message.outputLangCode)
        assertNull(message.translationError)
        assertEquals(0L, transport.ack().ackTtsMs)
        assertEquals(listOf(translated to Language.ENGLISH.code), speaker.prepared)
        assertTrue(speaker.played.isEmpty())
    }

    @Test
    fun translationTimeIsIncludedInTheExistingAckQueueStage() = test {
        val transport = FakeTransport()
        val clock = AtomicLong(100)
        val translator = FakeTranslator { _, _, _ -> clock.addAndGet(75); Translated(translated, 75) }
        val session = session(transport, translator = translator, clock = clock::get)
        transport.inbox.send(Packet.text(1, 0, original))
        val ack = transport.ack()
        val message = session.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }.messages.single()
        assertEquals(75L, ack.ackQueueMs)
        assertEquals(11L, ack.ackTtsMs)
        assertEquals(75L, message.queueMs)
        assertEquals(75L, message.translationMs)
    }

    @Test
    fun queuedTranslationRemainsSerialAndDuplicatesTranslateOnlyOnce() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val session = session(transport, speaker, FakeTranslator { text, _, _ ->
            assertEquals(1, active.incrementAndGet())
            if (text == "first") { started.complete(Unit); release.await() }
            active.decrementAndGet()
            Translated("translated $text", 1)
        })
        val first = Packet.text(1, 11, "first")
        transport.inbox.send(first)
        started.await()
        transport.inbox.send(first)
        transport.inbox.send(Packet.text(2, 12, "second"))
        release.complete(Unit)
        session.state.first { it.messages.size == 2 && it.messages.all { message -> message.status == Status.PLAYED } }
        assertEquals(listOf("translated first", "translated second"), speaker.played)
        transport.inbox.send(first)
        transport.ack(count = 3)
        assertEquals(2, session.state.value.messages.size)
        assertEquals(2, speaker.prepared.size)
    }

    @Test
    fun leavingDuringTranslationPropagatesCancellationWithoutAFailureOrVoice() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val session = session(transport, speaker, FakeTranslator { _, _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        transport.inbox.send(Packet.text(1, 0, original))
        started.await()
        assertTrue(session.state.value.translating)
        session.close()
        cancelled.await()
        session.state.first { !it.speaking && !it.translating }
        assertEquals(Status.QUEUED, session.state.value.messages.single().status)
        assertNull(session.state.value.messages.single().translationError)
        assertTrue(speaker.prepared.isEmpty())
        assertTrue(transport.sent.none { it.type == PacketType.ACK })
    }

    @Test
    fun voiceFailureKeepsReceiptAndExplicitRetryDoesNotEnableDuplicatePlayback() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker(failFirstPrepare = true)
        val translator = FakeTranslator()
        val session = session(transport, speaker, translator)
        val packet = Packet.text(1, 10, original)
        transport.inbox.send(packet)
        session.state.first { it.messages.singleOrNull()?.status == Status.FAILED && !it.speaking }
        assertEquals(0L, transport.ack().ackTtsMs)
        assertTrue(session.retryMessage(session.state.value.messages.single().id))
        val message = session.state.first { it.messages.singleOrNull()?.status == Status.PLAYED && !it.speaking }.messages.single()
        transport.inbox.send(packet)
        transport.ack(count = 2)
        assertEquals(2, translator.calls.size)
        assertEquals(37L, message.translationMs)
        assertEquals(translated, message.translatedText)
        assertEquals(listOf(translated), speaker.played)
        transport.ack()
    }

    @Test
    fun targetVoiceIsPreloadedFromPeerPingInsteadOfSourceVoice() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        session(transport, speaker)
        transport.inbox.send(Packet.ping(1, 0, Language.TAMIL))
        transport.inbox.send(Packet.ping(2, 0, Language.HINDI))
        while (transport.sent.count { it.type == PacketType.PONG } < 2 || speaker.preloaded.isEmpty()) delay(5)
        assertEquals(listOf(Language.ENGLISH.code), speaker.preloaded)
    }

    @Test
    fun noTargetKeepsOriginalPlaybackAndDoesNotTranslate() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val translator = FakeTranslator { _, _, _ -> error("Must not translate") }
        val session = session(transport, speaker, translator, target = null)
        transport.inbox.send(Packet.text(1, 0, original))
        val message = session.state.first { it.messages.singleOrNull()?.status == Status.PLAYED }.messages.single()
        assertNull(message.translatedText)
        assertNull(message.translationMs)
        assertFalse(translator.calls.isNotEmpty())
        assertEquals(listOf(original to Language.HINDI.code), speaker.prepared)
    }

    @Test
    fun pendingIdentitySurvivesMoreThan64OverflowReceipts() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val translator = FakeTranslator { text, _, _ ->
            if (text == "first") { started.complete(Unit); release.await() }
            Translated("translated $text", 1)
        }
        val session = session(transport, speaker, translator, maxQueuedMessages = 1)
        val first = Packet.text(1, 1, "first")
        transport.inbox.send(first)
        started.await()
        for (index in 2..70) transport.inbox.send(Packet.text(index, index.toLong(), "item $index"))
        transport.inbox.send(first)
        transport.inbox.send(Packet.ping(900, 0))
        while (transport.sent.none { it.type == PacketType.PONG && it.seq == 900 }) delay(5)
        assertEquals(70, session.state.value.messages.size)
        assertEquals(1, session.state.value.queuedMessages)
        release.complete(Unit)
        session.state.first { it.messages.count { message -> message.status == Status.PLAYED } == 2 && !it.speaking }
        assertEquals(2, translator.calls.size)
        assertEquals(1, translator.calls.count { it.first == "first" })
        assertEquals(1, speaker.played.count { it == "translated first" })
    }

    @Test
    fun microphoneStopFailureDoesNotPreventClosingOrCancellingTranslation() = test {
        val transport = FakeTransport()
        val speaker = FakeSpeaker()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val listener = object : Listener {
            override fun start() = Unit
            override suspend fun finish() = error("Unused")
            override fun cancel() = error("Stop failed")
        }
        val session = session(transport, speaker, translator = FakeTranslator { _, _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }, listener = listener)
        transport.inbox.send(Packet.text(1, 0, original))
        started.await()
        session.close()
        cancelled.await()
        session.state.first { !it.speaking }
        assertEquals(LinkState.Closed, transport.state.value)
        assertEquals("Could not stop microphone: Stop failed", session.state.value.notice)
        assertTrue(speaker.prepared.isEmpty())
    }

    @Test
    fun shortTapWithMicrophoneStopFailureReturnsToReady() = test {
        val listener = object : Listener {
            override fun start() = Unit
            override suspend fun finish() = error("Short tap must not be processed")
            override fun cancel() = error("Stop failed")
        }
        val session = session(transport = null, listener = listener, clock = { 0 }, minPressMs = 300)
        assertTrue(session.pressStart())
        session.pressEnd()
        assertEquals(Phase.Ready, session.state.value.phase)
        assertEquals("Could not stop microphone: Stop failed", session.state.value.notice)
        assertTrue(session.state.value.messages.isEmpty())
    }
}
