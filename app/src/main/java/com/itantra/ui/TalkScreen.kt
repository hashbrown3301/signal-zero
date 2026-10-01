package com.itantra.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.itantra.MainViewModel
import com.itantra.MainViewModel.Link
import com.itantra.MainViewModel.Mode
import com.itantra.bluetooth.BtAvailability
import com.itantra.bluetooth.rememberBluetoothState
import com.itantra.comm.LinkState
import com.itantra.session.Direction
import com.itantra.session.InputOrigin
import com.itantra.session.Message
import com.itantra.session.Phase
import com.itantra.session.SessionManager
import com.itantra.session.Status
import com.itantra.session.TranslationOrigin
import com.itantra.translation.ReviewedPhrase
import com.itantra.ui.components.BrandBar
import com.itantra.ui.components.HoldToTalkButton
import com.itantra.ui.components.LinkMotif
import com.itantra.ui.components.LinkStatusLabel
import com.itantra.ui.components.Rule
import com.itantra.ui.components.SecondaryButton
import com.itantra.ui.components.label
import com.itantra.ui.theme.DataText
import com.itantra.ui.theme.LinkColors
import com.itantra.ui.theme.Motion
import com.itantra.ui.theme.Palette
import com.itantra.ui.theme.pressScale
import com.itantra.ui.theme.scriptFont
import kotlinx.coroutines.delay

/**
 * Talk: link status at the top, the conversation as plain lines (received on the left with a teal marker, sent on
 * the right), a small numbers row, and the hold-to-talk button in the thumb zone. Tap a line for its timings.
 */
@SuppressLint("MissingPermission") // checked in hasMicPermission() before onPressStart()
@Composable
fun TalkScreen(
    ui: MainViewModel.UiState,
    onPressStart: () -> Boolean,
    onPressEnd: () -> Unit,
    onPressCancel: () -> Unit,
    onSubmitText: (String) -> Boolean,
    onSetReviewBeforeSend: (Boolean) -> Unit,
    onConfirmDraft: (String) -> Unit,
    onDiscardDraft: () -> Unit,
    onRetryMessage: (Int) -> Boolean,
    onReplayMessage: (Int, Boolean) -> Boolean,
    onSavePhrase: (String, String, String, String, (Boolean) -> Unit) -> Unit,
    onRemovePhrase: (String) -> Unit,
    onUsePhrase: (ReviewedPhrase) -> Boolean,
    onLeave: () -> Unit,
    onInstallVoice: () -> Unit,
    onStartSolo: () -> Unit,
) {
    val session = ui.session
    val context = LocalContext.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var permissionDenied by remember { mutableStateOf(false) }
    var phrasebookOpen by rememberSaveable { mutableStateOf(false) }
    var phraseEditor by rememberSaveable(stateSaver = PhraseEditorSeedSaver) { mutableStateOf<PhraseEditorSeed?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    // Speech-model readiness only gates the microphone; typed and exact phrase input are independent.
    val canSubmit = session.phase == Phase.Ready && !session.speaking && !session.translating &&
        (ui.mode == null || !ui.networked || ui.connected)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
    }
    fun hasMicPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize().imePadding()) {
        BrandBar(trailing = {
            if (ui.mode != null) {
                val endSource = remember { MutableInteractionSource() }
                TextButton(onClick = onLeave, modifier = Modifier.pressScale(endSource), interactionSource = endSource) {
                    Text(if (ui.mode == Mode.SOLO) "End Solo" else "End link", color = Palette.Mint, style = MaterialTheme.typography.titleSmall)
                }
            }
        })
        LinkHeader(ui)
        Rule(Modifier.padding(horizontal = 20.dp))

        val btOff = ui.link == Link.BLUETOOTH && ui.networked && rememberBluetoothState().value.availability != BtAvailability.ON
        val notice = when {
            permissionDenied -> "Microphone permission is needed to hear you"
            btOff -> "Bluetooth is off or not allowed. Turn it on to reconnect."
            else -> session.notice
        }
        Text(
            "Speak / type ${englishName(ui.myLanguage)} · Hear ${englishName(ui.listenLanguage)} · Offline",
            style = MaterialTheme.typography.labelLarge,
            color = Palette.Accent,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (!ui.translation.ready) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Use reviewed phrases, or install the pack for new translations.",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextMuted,
                )
                TextButton(onClick = onInstallVoice) { Text("Languages") }
            }
        }
        listOfNotNull(ui.error, actionError).distinct().filter { it != notice }.forEach { secondaryNotice ->
            Text(secondaryNotice, style = MaterialTheme.typography.bodySmall, color = Palette.Mint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = session.reviewBeforeSend,
                    onCheckedChange = onSetReviewBeforeSend,
                    enabled = ui.mode != null && session.phase == Phase.Ready,
                    modifier = Modifier.semantics { contentDescription = "Review speech before sending or translating" },
                )
                Text("Review speech", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = { phrasebookOpen = true }) { Text("Reviewed phrases") }
        }
        val shownNotice = rememberLastNonNull(notice)
        AnimatedVisibility(visible = notice != null, enter = RevealEnter, exit = RevealExit) {
            Text(
                shownNotice.orEmpty(),
                style = MaterialTheme.typography.bodyMedium, color = Palette.Mint,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        Transcript(
            messages = session.messages,
            mode = ui.mode,
            myLanguage = ui.myLanguage,
            listenLanguage = ui.listenLanguage,
            onInstallVoice = onInstallVoice,
            onStartSolo = onStartSolo,
            canAct = canSubmit && ui.mode != null,
            onRetry = { id ->
                if (!onRetryMessage(id)) actionError = "Could not retry yet. Wait for the current task or link, then try again."
                else actionError = null
            },
            onReplay = { id, allowUnsafe ->
                if (!onReplayMessage(id, allowUnsafe)) actionError = "Could not play yet. Wait for the current task, then try again."
                else actionError = null
            },
            onSave = { phraseEditor = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        NumbersRow(ui)

        AnimatedVisibility(visible = !keyboardVisible, enter = RevealEnter, exit = RevealExit) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val labels = ui.talkLabels()
                HoldToTalkButton(
                    state = ui.talkButtonState(),
                    onPressStart = {
                        if (!hasMicPermission()) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        } else {
                            permissionDenied = false
                            onPressStart()
                        }
                    },
                    onPressEnd = onPressEnd,
                    onPressCancel = onPressCancel,
                    contentDescription = labels.first,
                )
                Crossfade(labels, animationSpec = Motion.enter(), label = "talk-labels") { (label, hint) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                        Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    }
                }
                if (session.queuedMessages > 0) {
                    Text("${session.queuedMessages} phrases waiting", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
            }
        }
        TypedComposer(
            language = ui.myLanguage,
            networked = ui.networked,
            hasSession = ui.mode != null,
            enabled = canSubmit,
            onSubmit = { text ->
                val accepted = onSubmitText(text)
                actionError = if (accepted) null else "Text kept. Wait for the conversation or current task, then try again."
                accepted
            },
        )
    }

    // The session owns the draft; its removal, rather than a local flag, closes source review.
    session.draft?.let { ReviewDraftDialog(it, ui.networked, session.notice, onConfirmDraft, onDiscardDraft) }
    if (session.draft == null) {
        phraseEditor?.let { seed ->
            ReviewedPhraseEditor(seed, ui.phrasebookError, onDismiss = { phraseEditor = null }, onSave = onSavePhrase)
        }
        if (phrasebookOpen && phraseEditor == null) {
            PhrasebookDialog(
                entries = ui.phrasebook,
                error = ui.phrasebookError,
                sourceIso = ui.myLanguage,
                targetIso = ui.listenLanguage,
                networked = ui.networked,
                hasSession = ui.mode != null,
                canUse = canSubmit,
                onDismiss = { phrasebookOpen = false },
                onEdit = { phraseEditor = it },
                onRemove = onRemovePhrase,
                onUse = onUsePhrase,
            )
        }
    }
}

@Composable
private fun TypedComposer(
    language: String,
    networked: Boolean,
    hasSession: Boolean,
    enabled: Boolean,
    onSubmit: (String) -> Boolean,
) {
    var text by rememberSaveable(language) { mutableStateOf("") }
    val validationError = SessionManager.inputError(text)
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Type · ${englishName(language)}") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = scriptFont(language)),
            minLines = 1,
            maxLines = 3,
            isError = text.isNotEmpty() && validationError != null,
            supportingText = { if (text.isNotEmpty()) validationError?.let { Text(it) } },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { if (onSubmit(text)) text = "" },
            enabled = enabled && validationError == null,
        ) { Text(if (networked) "Send" else if (hasSession) "Translate" else "Solo") }
    }
}

/** Fade + grow in / fade + shrink out, for banners that come and go inside a column. */
private val RevealEnter = fadeIn(Motion.enter()) + expandVertically(Motion.gentle(), expandFrom = Alignment.Top)
private val RevealExit = fadeOut(Motion.exit()) + shrinkVertically(Motion.gentle(), shrinkTowards = Alignment.Top)

/** The conversation. Own composable, so a link-state tick (RTT every 2 s) does not touch the lines. */
@Composable
private fun Transcript(
    messages: List<Message>,
    mode: Mode?,
    myLanguage: String,
    listenLanguage: String,
    onInstallVoice: () -> Unit,
    onStartSolo: () -> Unit,
    canAct: Boolean,
    onRetry: (Int) -> Unit,
    onReplay: (Int, Boolean) -> Unit,
    onSave: (PhraseEditorSeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // Follow new lines only while the reader is at the bottom; a line of their own always scrolls into view.
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    val settled = remember { BooleanArray(1) }
    val count = messages.size
    // Keyed on the newest line, not the count: the session keeps at most 100 lines, so the count stops changing.
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (count == 0) return@LaunchedEffect
        if (!settled[0]) {
            settled[0] = true
            listState.scrollToItem(count - 1)  // coming back to the tab: jump, don't sweep through the history
        } else if (nearBottom || messages.last().direction != Direction.INCOMING) {
            listState.animateScrollToItem(count - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Bottom),
    ) {
        if (messages.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = Motion.exit())) {
                    EmptyHint(mode, myLanguage, listenLanguage, onStartSolo)
                }
            }
        }
        items(messages, key = { it.id }) { m ->
            Box(Modifier.animateItem(fadeInSpec = Motion.enter(), placementSpec = Motion.gentle(), fadeOutSpec = Motion.exit())) {
                TranscriptLine(m, myLanguage, onInstallVoice, canAct, onRetry, onReplay, onSave)
            }
        }
    }
}

@Composable
private fun LinkHeader(ui: MainViewModel.UiState) {
    val look = ui.linkLook()
    val downSince = ui.linkDownSince
    val reconnectedAt = ui.reconnectedAt
    // Ticks once a second, but only while something on screen counts time (an outage, or the "Reconnected" word).
    val now by produceState(SystemClock.elapsedRealtime(), downSince, reconnectedAt) {
        while (downSince != null || (reconnectedAt != null && SystemClock.elapsedRealtime() - reconnectedAt < RECONNECTED_MS)) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
        value = SystemClock.elapsedRealtime()
    }
    val banner: LinkBanner? = when {
        downSince != null -> {
            val what = when (val link = ui.session.link) {
                is LinkState.Connecting -> "reconnecting, attempt ${link.attempt}"
                is LinkState.Listening -> "waiting for the other phone to come back"
                else -> "reconnecting"
            }
            LinkBanner("Link lost · $what · ${(now - downSince) / 1000} s", LinkColors.Lost, ui.session.lastDisconnect)
        }
        ui.settingUp -> LinkBanner(
            if (ui.mode == Mode.HOST) "Waiting for the other phone. Your address is on Home." else "Connecting…",
            LinkColors.Connecting, null,
        )
        else -> null
    }
    val shownBanner = rememberLastNonNull(banner)

    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LinkMotif(look)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (ui.mode) {
                        null -> "Not connected"
                        Mode.SOLO -> "Solo"
                        else -> ui.peerLabel()
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (ui.mode) {
                        null -> "no link"
                        Mode.SOLO -> "this phone only"
                        else -> ui.linkTypeLabel() +
                            (ui.session.rttMs?.takeIf { ui.connected }?.let { " · RTT $it ms" } ?: "") +
                            (ui.setupMs?.takeIf { ui.connected }?.let { " · linked in %.1f s".format(it / 1000.0) } ?: "")
                    },
                    style = DataText, color = Palette.TextMuted,
                )
            }
            if (ui.networked) {
                val recently = reconnectedAt?.let { now - it < RECONNECTED_MS } == true && ui.connected
                LinkStatusLabel(look, text = if (recently) "Reconnected" else look.label())
            }
        }
        AnimatedVisibility(visible = banner != null, enter = RevealEnter, exit = RevealExit) {
            shownBanner?.let { LinkBannerText(it) }
        }
    }
}

private data class LinkBanner(val text: String, val color: Color, val detail: String?)

@Composable
private fun LinkBannerText(banner: LinkBanner) {
    val color by animateColorAsState(banner.color, Motion.enter(), label = "banner-color")
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(banner.text, style = MaterialTheme.typography.bodyMedium, color = color)
        banner.detail?.let { Text(it, style = DataText, color = Palette.TextMuted) }
    }
}

@Composable
private fun EmptyHint(mode: Mode?, myLanguage: String, listenLanguage: String, onStartSolo: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val fade by animateFloatAsState(if (shown) 1f else 0f, Motion.enter(), label = "empty-fade")
    Crossfade(
        mode == null, Modifier.graphicsLayer { alpha = fade },
        animationSpec = Motion.enter(), label = "empty-hint",
    ) { noLink ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (noLink) {
                Text("Connect a phone on Home, or try it on this phone alone.", style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
                SecondaryButton(onClick = onStartSolo) { Text("Start Solo") }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Speak or type ", style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
                    Text(nativeName(myLanguage), fontFamily = scriptFont(myLanguage), style = MaterialTheme.typography.bodyLarge, color = Palette.Mint)
                }
                Text(
                    if (mode == Mode.SOLO) "Hold to talk, or type below. Translate into ${englishName(listenLanguage)} on this phone."
                    else "Hold to talk, or type below. Incoming messages translate into ${englishName(listenLanguage)} on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                )
            }
        }
    }
}

private val DetailEnter = fadeIn(Motion.enter()) + expandVertically(Motion.gentle(), expandFrom = Alignment.Top)
private val DetailExit = fadeOut(Motion.exit()) + shrinkVertically(Motion.gentle(), shrinkTowards = Alignment.Top)
private val MarkerWidth = 2.dp

/** One message: plain text with a thin marker line, its language in its own script, and its state. */
@Composable
private fun TranscriptLine(
    m: Message,
    myLanguage: String,
    onInstallVoice: () -> Unit,
    canAct: Boolean,
    onRetry: (Int) -> Unit,
    onReplay: (Int, Boolean) -> Unit,
    onSave: (PhraseEditorSeed) -> Unit,
) {
    val mine = m.direction != Direction.INCOMING
    val iso = isoOf(m.langCode) ?: myLanguage
    val outputIso = isoOf(m.outputLangCode) ?: iso
    val translationFailed = m.status == Status.TRANSLATION_FAILED || m.translationError != null
    val translatedText = m.translatedText?.takeIf { it.isNotBlank() && !translationFailed }
    val requestedTranslation = m.outputLangCode != null && m.outputLangCode != m.langCode
    val localOrReceived = m.direction != Direction.OUTGOING
    val retryEligible = localOrReceived && m.status in listOf(Status.FAILED, Status.TRANSLATION_FAILED, Status.NO_VOICE)
    val hasReplayText = translatedText != null || (m.outputLangCode != null && m.outputLangCode == m.langCode && !translationFailed)
    val replayEligible = localOrReceived && hasReplayText && m.status in listOf(Status.PLAYED, Status.FAILED, Status.NO_VOICE, Status.NEEDS_REVIEW)
    val explicitPlayback = m.warnings.isNotEmpty() && m.translationOrigin != TranslationOrigin.REVIEWED_PHRASE
    val canSave = localOrReceived && requestedTranslation && isoOf(m.langCode) != null && isoOf(m.outputLangCode) != null
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val tapSource = remember { MutableInteractionSource() }
    val markerColor = if (mine) Palette.Teal else Palette.Accent
    val align = if (mine) Alignment.End else Alignment.Start
    val textAlign = if (mine) TextAlign.End else TextAlign.Start

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        // The marker is drawn at the outer edge (no intrinsic measuring); the padding on that side is marker + 12 dp gap.
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .pressScale(tapSource)
                .clickable(interactionSource = tapSource, indication = null, onClickLabel = "Show timings") { expanded = !expanded }
                .drawBehind {
                    val w = MarkerWidth.toPx()
                    drawRoundRect(
                        markerColor,
                        topLeft = Offset(if (mine) size.width - w else 0f, 0f),
                        size = Size(w, size.height),
                        cornerRadius = CornerRadius(w / 2),
                    )
                }
                .padding(start = if (mine) 0.dp else 14.dp, end = if (mine) 14.dp else 0.dp),
            horizontalAlignment = align,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!mine) Text("← ", style = DataText, color = Palette.TextMuted)
                Text(
                    (if (translatedText != null || requestedTranslation || translationFailed) "Original · " else "") +
                        if (m.langCode != null && isoOf(m.langCode) == null) "code ${m.langCode}" else nativeName(iso),
                    fontFamily = scriptFont(iso), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted,
                )
                LineStatus(" · ${statusWord(m)}" + if (mine) " →" else "", failed = m.status == Status.FAILED || translationFailed)
            }
            Text(
                m.text,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = scriptFont(iso),
                color = if (mine) Palette.Mint else Palette.OffWhite,
                textAlign = textAlign,
                modifier = Modifier.padding(top = 4.dp),
            )
            m.originalTranscript?.takeIf { it != m.text }?.let { original ->
                Text(
                    "Heard before correction: $original",
                    fontFamily = scriptFont(iso),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextMuted,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (translatedText != null) {
                Text(
                    (if (m.translationOrigin == TranslationOrigin.REVIEWED_PHRASE) "Reviewed phrase · " else "Translation · ") + nativeName(outputIso),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = scriptFont(outputIso),
                    color = Palette.Accent,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    translatedText,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = scriptFont(outputIso),
                    color = Palette.OffWhite,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (m.warnings.isNotEmpty()) {
                Text("Check numeric details", style = MaterialTheme.typography.labelLarge, color = Palette.Mint, modifier = Modifier.padding(top = 8.dp))
                m.warnings.forEach { warning ->
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = Palette.Mint, textAlign = textAlign, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    if (m.status == Status.NEEDS_REVIEW) "Automatic speech paused. Compare both texts before playing. This check can miss meaning errors."
                    else "This detail check can miss meaning errors. Compare the source and translation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextMuted,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            AnimatedVisibility(visible = m.status == Status.NO_VOICE && !translationFailed, enter = DetailEnter, exit = DetailExit) {
                val installSource = remember { MutableInteractionSource() }
                TextButton(
                    onClick = onInstallVoice, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(top = 4.dp).pressScale(installSource), interactionSource = installSource,
                ) {
                    Text("Install ${englishName(outputIso)} voice →", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                }
            }
            if (translationFailed) {
                Text(
                    "Could not translate into ${englishName(outputIso)}. Original text is shown.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.Mint,
                    modifier = Modifier.padding(top = 4.dp), textAlign = textAlign,
                )
                (m.translationError ?: m.error)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, modifier = Modifier.padding(top = 4.dp), textAlign = textAlign)
                }
            }
            AnimatedVisibility(visible = m.status == Status.FAILED && m.error != null, enter = DetailEnter, exit = DetailExit) {
                Text(m.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.Mint, modifier = Modifier.padding(top = 4.dp), textAlign = textAlign)
            }
            if (retryEligible || replayEligible || canSave) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (retryEligible) TextButton(onClick = { onRetry(m.id) }, enabled = canAct, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("Retry", style = MaterialTheme.typography.labelLarge)
                    }
                    if (replayEligible) TextButton(onClick = { onReplay(m.id, explicitPlayback) }, enabled = canAct, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(if (explicitPlayback) "Play anyway" else "Replay", style = MaterialTheme.typography.labelLarge)
                    }
                    if (canSave) TextButton(
                        onClick = { onSave(PhraseEditorSeed(iso, outputIso, m.text, translatedText.orEmpty())) },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text("Save phrase", style = MaterialTheme.typography.labelLarge) }
                }
            }
            AnimatedVisibility(visible = expanded, enter = DetailEnter, exit = DetailExit) {
                Column(Modifier.padding(top = 4.dp), horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    remember(m) { detailLines(m) }.forEach {
                        Text(it, style = DataText, color = Palette.TextMuted, textAlign = textAlign)
                    }
                }
            }
        }
    }
}

/** " · sent →" and friends: cross-fades when the word changes, and eases to a brighter colour on failure. */
@Composable
private fun LineStatus(text: String, failed: Boolean) {
    val color by animateColorAsState(if (failed) Palette.Mint else Palette.TextMuted, Motion.enter(), label = "status-color")
    Crossfade(text, animationSpec = Motion.enter(), label = "status-word") {
        Text(it, style = DataText, color = color)
    }
}

private fun statusWord(m: Message): String = when (m.status) {
    Status.QUEUED -> "queued"
    Status.SENT -> "sent"
    Status.ACKED -> "delivered"
    Status.FAILED -> if (m.direction == Direction.OUTGOING) "not confirmed" else "could not play"
    Status.PLAYING -> "speaking"
    Status.PLAYED -> if (m.direction == Direction.LOCAL) "played back" else "played"
    Status.NO_VOICE -> "text only"
    Status.TRANSLATION_FAILED -> "translation unavailable"
    Status.NEEDS_REVIEW -> "review before speech"
}

/** The latest numbers: bytes, RTT and a delivery/readiness estimate; the stage timings in Solo. */
@Composable
private fun NumbersRow(ui: MainViewModel.UiState) {
    val messages = ui.session.messages
    val items = remember(ui.mode, messages, ui.session.rttMs) { numberItems(ui) }
    val shown = rememberLastNonNull(items.takeIf { it.isNotEmpty() })
    AnimatedVisibility(visible = items.isNotEmpty(), enter = RevealEnter, exit = RevealExit) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Rule()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                shown?.forEach { Text(it, style = DataText, color = Palette.TextMuted) }
            }
        }
    }
}

private fun numberItems(ui: MainViewModel.UiState): List<String> = when {
    ui.networked -> {
        val last = ui.session.messages.lastOrNull { it.direction == Direction.OUTGOING && it.wireBytes != null }
        listOfNotNull(
            last?.wireBytes?.let { "$it B sent" },
            "RTT " + (ui.session.rttMs?.let { "$it ms" } ?: "–"),
            ui.session.messages.lastOrNull { it.endToEndMs != null }?.endToEndMs?.let { "delivery est. %.2f s".format(it / 1000.0) },
        )
    }
    ui.mode == Mode.SOLO -> ui.session.messages.lastOrNull { it.direction == Direction.LOCAL && it.ttsMs != null }?.let { m ->
        listOf("speech ${m.sttMs ?: "–"} ms", "translate ${m.translationMs ?: "–"} ms", "voice ${m.ttsMs} ms")
    } ?: emptyList()
    else -> emptyList()
}

private fun detailLines(m: Message): List<String> {
    val lines = mutableListOf<String>()
    if (m.inputOrigin == InputOrigin.TYPED) lines += "Typed source"
    if (m.translationOrigin == TranslationOrigin.REVIEWED_PHRASE) lines += "Exact user-reviewed translation · no model inference"
    val bytes = m.wireBytes
    val audioBytes = m.recordedSec?.let { (it * SAMPLE_RATE * BYTES_PER_SAMPLE).toLong() }
    when {
        bytes != null && audioBytes != null -> lines += "$bytes B vs ${formatBytes(audioBytes)} audio (${audioBytes / bytes}× smaller)"
        bytes != null -> lines += "$bytes B"
    }
    when (m.direction) {
        Direction.OUTGOING -> when {
            m.endToEndMs != null -> {
                lines += "VAD ${m.vadMs ?: "–"} + STT ${m.sttMs ?: "–"} + other ${m.otherMs} + net ${m.networkMs}"
                lines += "+ peer queue ${m.peerQueueMs} + peer TTS ${m.peerTtsMs} = ${m.endToEndMs} ms"
                lines += "Delivery/readiness estimate; receipt does not prove audible playback"
            }
            m.ackAfterMs != null -> {
                lines += "VAD ${m.vadMs ?: "–"} · STT ${m.sttMs ?: "–"} ms"
                lines += "release → ACK ${m.ackAfterMs} ms (no RTT yet)"
            }
            else -> lines += "VAD ${m.vadMs ?: "–"} · STT ${m.sttMs ?: "–"} ms"
        }
        Direction.INCOMING -> if (m.ttsMs != null) {
            lines += "TTS ${m.ttsMs} ms" + (m.queueMs?.takeIf { it >= MIN_SHOWN_WAIT_MS }?.let { " · waited $it ms" } ?: "")
        }
        Direction.LOCAL -> if (m.ttsMs != null) {
            val total = (m.vadMs ?: 0) + (m.sttMs ?: 0) + (m.translationMs ?: 0) + m.ttsMs
            lines += "VAD ${m.vadMs ?: "–"} · STT ${m.sttMs ?: "–"} · TTS ${m.ttsMs} · stages $total ms"
        }
    }
    m.translationMs?.let { lines += "Translation $it ms" }
    if (m.voiceChunks > 1) {
        lines += "First voice chunk ${m.ttsMs ?: "–"} ms · ${m.voiceChunks} chunks"
        m.totalTtsMs?.let { lines += "All voice synthesis $it ms" }
    }
    return lines
}

private fun formatBytes(n: Long): String = if (n >= 1024 * 1024) "%.1f MB".format(n / 1048576.0) else "${n / 1024} KB"

private const val SAMPLE_RATE = 16_000
private const val BYTES_PER_SAMPLE = 2
private const val MIN_SHOWN_WAIT_MS = 50L
private const val RECONNECTED_MS = 5_000L
