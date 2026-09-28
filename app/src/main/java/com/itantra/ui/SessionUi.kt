package com.itantra.ui

import com.itantra.MainViewModel
import com.itantra.MainViewModel.Link
import com.itantra.MainViewModel.Mode
import com.itantra.comm.Language
import com.itantra.comm.LinkState
import com.itantra.session.Phase
import com.itantra.ui.components.LinkLook
import com.itantra.ui.components.TalkButtonState

/*
 * Plain mappings from the ViewModel's state to what the redesigned screens show. No Android, no Compose.
 */

/** Each language's name in its own script, and in English. */
private val NATIVE = mapOf(
    "hi" to "हिन्दी", "en" to "English", "mr" to "मराठी", "gu" to "ગુજરાતી", "bn" to "বাংলা",
    "ta" to "தமிழ்", "te" to "తెలుగు", "kn" to "ಕನ್ನಡ", "ml" to "മലയാളം", "or" to "ଓଡ଼ିଆ",
)
private val ENGLISH = mapOf(
    "hi" to "Hindi", "en" to "English", "mr" to "Marathi", "gu" to "Gujarati", "bn" to "Bengali",
    "ta" to "Tamil", "te" to "Telugu", "kn" to "Kannada", "ml" to "Malayalam", "or" to "Odia",
)

fun nativeName(iso: String?): String = NATIVE[iso] ?: iso.orEmpty()
fun englishName(iso: String?): String = ENGLISH[iso] ?: iso.orEmpty()
fun isoOf(langCode: Int?): String? = langCode?.let { Language.fromCode(it)?.iso }

val MainViewModel.UiState.networked: Boolean get() = mode == Mode.HOST || mode == Mode.JOIN
val MainViewModel.UiState.connected: Boolean get() = session.link is LinkState.Connected

/** Hosting or joining, and the link has not come up yet (after it has, a drop is "lost", not "connecting"). */
val MainViewModel.UiState.settingUp: Boolean get() = networked && !connected && linkDownSince == null

fun MainViewModel.UiState.linkLook(): LinkLook = when {
    mode == Mode.SOLO || mode == null -> LinkLook.Solo
    connected -> LinkLook.Connected
    linkDownSince != null -> LinkLook.Lost
    else -> LinkLook.Connecting
}

/** Who is on the other end: the Bluetooth name or IP we joined, or the address that connected to us. */
fun MainViewModel.UiState.peerLabel(): String =
    peerName.ifEmpty { peer }.ifEmpty { (session.link as? LinkState.Connected)?.peer.orEmpty() }.ifEmpty { "Other phone" }

fun MainViewModel.UiState.linkTypeLabel(): String = if (link == Link.BLUETOOTH) "Bluetooth" else "Wi-Fi"

fun MainViewModel.UiState.talkButtonState(): TalkButtonState = when {
    mode == null -> TalkButtonState.NoLink
    !modelsReady || loadingLanguage != null -> TalkButtonState.Processing
    session.phase == Phase.Listening -> TalkButtonState.Listening
    session.phase == Phase.Processing || session.speaking -> TalkButtonState.Processing
    networked && !connected -> TalkButtonState.NoLink
    else -> TalkButtonState.Idle
}

/** The two lines under the talk button. */
fun MainViewModel.UiState.talkLabels(): Pair<String, String> = when {
    mode == null -> "No link" to "Connect a phone on Home, or start Solo"
    !modelsReady || loadingLanguage != null -> "Loading…" to "Getting the speech models ready"
    session.phase == Phase.Listening -> "Listening…" to (if (networked) "Release to send" else "Release to hear it back")
    session.phase == Phase.Processing -> (if (networked) "Sending…" else "Working…") to "Recognizing speech on this phone"
    session.speaking -> "Speaking…" to "Wait for the voice to finish"
    networked && linkDownSince != null -> "No link" to "Reconnecting to ${peerLabel()}…"
    networked && !connected -> "No link" to "Waiting for the other phone"
    mode == Mode.SOLO -> "Hold to talk" to "Release to hear it back"
    else -> "Hold to talk" to "Release to send"
}
