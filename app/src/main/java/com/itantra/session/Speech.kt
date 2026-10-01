package com.itantra.session

/** What the microphone side produced for one button press. Empty [text] = no speech. */
data class Heard(
    val text: String,
    val recordedSec: Float,
    val speechSec: Float,
    val vadMs: Long,
    val sttMs: Long?,
)

/** Audio in → text. [SessionManager] only sees this interface, never the engines. */
interface Listener {
    /** Starts capture when the talk button goes down. May throw if the mic is unavailable. */
    fun start()

    /** Stops capture and runs VAD + STT. */
    suspend fun finish(): Heard

    /** Stops capture and discards it (accidental tap, session closed). */
    fun cancel()
}

/** Text → audio out, in the language of the message. */
interface Speaker {
    /**
     * Synthesizes [text] with the voice for [langCode] (comm.Language code); the result is played separately so
     * the ACK can go out first. Returns null if this phone has no voice for that language.
     */
    suspend fun prepare(text: String, langCode: Int): Prepared?

    /**
     * Loads the voice for [langCode] ahead of its first message (the peer announced its language). Returns false if
     * this phone has no voice for it yet, so the caller can try again later (e.g. after the pack is downloaded).
     */
    suspend fun preload(langCode: Int): Boolean = true
}

interface Prepared {
    /** Synthesis needed before the first audio can start (also the value sent in the ACK). */
    val synthMs: Long

    /** Total synthesis across all chunks, final after [play] completes. */
    val totalSynthMs: Long get() = synthMs

    val chunkCount: Int get() = 1

    /** Plays the audio and suspends until it has finished. */
    suspend fun play()
}
