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

/** Text → audio out. */
interface Speaker {
    /** Synthesizes [text]; the result is played separately so the ACK can go out first. */
    suspend fun prepare(text: String): Prepared
}

interface Prepared {
    val synthMs: Long

    /** Plays the audio and suspends until it has finished. */
    suspend fun play()
}
