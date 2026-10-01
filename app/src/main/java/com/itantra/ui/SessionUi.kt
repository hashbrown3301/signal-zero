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

/** Display labels share the fixed language catalogue used by packets and typed input. */
fun nativeName(iso: String?): String = iso?.let { Language.fromIso(it)?.nativeName } ?: iso.orEmpty()
fun englishName(iso: String?): String = iso?.let { Language.fromIso(it)?.englishName } ?: iso.orEmpty()
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
    session.phase == Phase.Reviewing -> TalkButtonState.NoLink
    session.phase == Phase.Maintenance -> TalkButtonState.NoLink
    loadingLanguage != null -> TalkButtonState.Processing
    !modelsReady -> TalkButtonState.NoLink
    session.phase == Phase.Listening -> TalkButtonState.Listening
    session.phase == Phase.Processing || session.speaking || session.translating -> TalkButtonState.Processing
    networked && !connected -> TalkButtonState.NoLink
    else -> TalkButtonState.Idle
}

/** The two lines under the talk button. */
fun MainViewModel.UiState.talkLabels(): Pair<String, String> = when {
    mode == null -> "Hold to talk in Solo" to "Start Solo, or type a phrase below"
    session.phase == Phase.Reviewing -> "Review your words" to "Confirm or discard the draft before continuing"
    session.phase == Phase.Maintenance -> "Installing translation pack…" to "Messages are kept. Wait or cancel in Languages."
    loadingLanguage != null -> "Loading speech…" to "You can still type or use a reviewed phrase"
    !modelsReady -> "Microphone unavailable" to "Type a phrase, or install speech in Languages"
    session.phase == Phase.Listening -> "Listening…" to (if (session.reviewBeforeSend) "Release to review" else if (networked) "Release to send" else "Release to translate")
    session.phase == Phase.Processing -> "Working…" to "Processing your words on this phone"
    session.translating -> "Translating…" to "Processing this phrase offline on your phone"
    session.speaking -> "Speaking…" to "Wait for the voice to finish"
    networked && linkDownSince != null -> "No link" to "Reconnecting to ${peerLabel()}…"
    networked && !connected -> "No link" to "Waiting for the other phone"
    mode == Mode.SOLO -> "Hold to talk" to (if (session.reviewBeforeSend) "Release to review before translating" else "Release to translate into ${englishName(listenLanguage)}")
    else -> "Hold to talk" to (if (session.reviewBeforeSend) "Release to review before sending" else "Release to send")
}
