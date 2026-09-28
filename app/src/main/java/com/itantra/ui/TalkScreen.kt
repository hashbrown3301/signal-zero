package com.itantra.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.itantra.session.Message
import com.itantra.session.Status
import com.itantra.ui.components.BrandBar
import com.itantra.ui.components.HoldToTalkButton
import com.itantra.ui.components.LinkMotif
import com.itantra.ui.components.LinkStatusLabel
import com.itantra.ui.components.label
import com.itantra.ui.components.Rule
import com.itantra.ui.components.SecondaryButton
import com.itantra.ui.theme.DataText
import com.itantra.ui.theme.LinkColors
import com.itantra.ui.theme.Palette
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
    onLeave: () -> Unit,
    onInstallVoice: () -> Unit,
    onStartSolo: () -> Unit,
) {
    val session = ui.session
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
    }
    fun hasMicPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxSize()) {
        BrandBar(trailing = {
            if (ui.mode != null) {
                TextButton(onClick = onLeave) {
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
            ui.error != null -> ui.error
            else -> session.notice
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Mint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }

        val listState = rememberLazyListState()
        LaunchedEffect(session.messages.size) {
            if (session.messages.isNotEmpty()) listState.animateScrollToItem(session.messages.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.Bottom),
        ) {
            if (session.messages.isEmpty()) {
                item { EmptyHint(ui, onStartSolo) }
            }
            items(session.messages, key = { it.id }) { TranscriptLine(it, ui.myLanguage, onInstallVoice) }
        }

        NumbersRow(ui)

        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val (label, hint) = ui.talkLabels()
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
                contentDescription = label,
            )
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun LinkHeader(ui: MainViewModel.UiState) {
    val look = ui.linkLook()
    val now by produceState(SystemClock.elapsedRealtime(), ui.linkDownSince, ui.reconnectedAt) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
                val recently = ui.reconnectedAt?.let { now - it < RECONNECTED_MS } == true && ui.connected
                LinkStatusLabel(look, text = if (recently) "Reconnected" else look.label())
            }
        }
        val downSince = ui.linkDownSince
        if (downSince != null) {
            val what = when (val link = ui.session.link) {
                is LinkState.Connecting -> "reconnecting, attempt ${link.attempt}"
                is LinkState.Listening -> "waiting for the other phone to come back"
                else -> "reconnecting"
            }
            Text("Link lost · $what · ${(now - downSince) / 1000} s", style = MaterialTheme.typography.bodyMedium, color = LinkColors.Lost)
            ui.session.lastDisconnect?.let { Text(it, style = DataText, color = Palette.TextMuted) }
        } else if (ui.settingUp) {
            Text(
                if (ui.mode == Mode.HOST) "Waiting for the other phone. Your address is on Home." else "Connecting…",
                style = MaterialTheme.typography.bodyMedium, color = LinkColors.Connecting,
            )
        }
    }
}

@Composable
private fun EmptyHint(ui: MainViewModel.UiState, onStartSolo: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.mode == null) {
            Text("Connect a phone on Home, or try it on this phone alone.", style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
            SecondaryButton(onClick = onStartSolo) { Text("Start Solo") }
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Hold the button and speak ", style = MaterialTheme.typography.bodyLarge, color = Palette.TextMuted)
                Text(nativeName(ui.myLanguage), fontFamily = scriptFont(ui.myLanguage), style = MaterialTheme.typography.bodyLarge, color = Palette.Mint)
            }
        }
    }
}

/** One message: plain text with a thin marker line, its language in its own script, and its state. */
@Composable
private fun TranscriptLine(m: Message, myLanguage: String, onInstallVoice: () -> Unit) {
    val mine = m.direction != Direction.INCOMING
    val iso = isoOf(m.langCode) ?: myLanguage
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val marker = @Composable { Box(Modifier.width(2.dp).fillMaxHeight().background(if (mine) Palette.Teal else Palette.Accent, RoundedCornerShape(1.dp))) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Row(
            Modifier
                .widthIn(max = 320.dp)
                .height(IntrinsicSize.Min)
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!mine) marker()
            Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!mine) Text("← ", style = DataText, color = Palette.TextMuted)
                    Text(
                        if (m.langCode != null && isoOf(m.langCode) == null) "code ${m.langCode}" else nativeName(iso),
                        fontFamily = scriptFont(iso), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted,
                    )
                    Text(" · ${statusWord(m)}" + if (mine) " →" else "", style = DataText, color = if (m.status == Status.FAILED) Palette.Mint else Palette.TextMuted)
                }
                Text(
                    m.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = scriptFont(iso),
                    color = if (mine) Palette.Mint else Palette.OffWhite,
                    textAlign = if (mine) TextAlign.End else TextAlign.Start,
                )
                if (m.status == Status.NO_VOICE) {
                    TextButton(onClick = onInstallVoice, contentPadding = PaddingValues(0.dp)) {
                        Text("Install ${englishName(iso)} voice →", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                    }
                }
                if (m.status == Status.FAILED && m.error != null) {
                    Text(m.error, style = MaterialTheme.typography.bodySmall, color = Palette.Mint)
                }
                if (expanded) {
                    detailLines(m).forEach {
                        Text(it, style = DataText, color = Palette.TextMuted, textAlign = if (mine) TextAlign.End else TextAlign.Start)
                    }
                }
            }
            if (mine) marker()
        }
    }
}

private fun statusWord(m: Message): String = when (m.status) {
    Status.QUEUED -> "queued"
    Status.SENT -> "sent"
    Status.ACKED -> "heard"
    Status.FAILED -> "not sent"
    Status.PLAYING -> "speaking"
    Status.PLAYED -> if (m.direction == Direction.LOCAL) "played back" else "played"
    Status.NO_VOICE -> "text only"
}

/** The latest numbers: bytes, RTT and end-to-end over a link; the stage timings in Solo. */
@Composable
private fun NumbersRow(ui: MainViewModel.UiState) {
    val items: List<String> = when {
        ui.networked -> {
            val last = ui.session.messages.lastOrNull { it.direction == Direction.OUTGOING && it.wireBytes != null }
            listOfNotNull(
                last?.wireBytes?.let { "$it B sent" },
                "RTT " + (ui.session.rttMs?.let { "$it ms" } ?: "–"),
                ui.session.messages.lastOrNull { it.endToEndMs != null }?.endToEndMs?.let { "end-to-end %.2f s".format(it / 1000.0) },
            )
        }
        ui.mode == Mode.SOLO -> ui.session.messages.lastOrNull { it.direction == Direction.LOCAL && it.ttsMs != null }?.let { m ->
            listOf("speech→text ${m.sttMs ?: "–"} ms", "voice ${m.ttsMs} ms", "total %.2f s".format(((m.vadMs ?: 0) + (m.sttMs ?: 0) + (m.ttsMs ?: 0)) / 1000.0))
        } ?: emptyList()
        else -> emptyList()
    }
    if (items.isEmpty()) return
    Column(Modifier.padding(horizontal = 20.dp)) {
        Rule()
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            items.forEach { Text(it, style = DataText, color = Palette.TextMuted) }
        }
    }
}

private fun detailLines(m: Message): List<String> {
    val lines = mutableListOf<String>()
    val bytes = m.wireBytes
    val audioBytes = m.recordedSec?.let { (it * SAMPLE_RATE * BYTES_PER_SAMPLE).toLong() }
    when {
        bytes != null && audioBytes != null -> lines += "$bytes B vs ${formatBytes(audioBytes)} audio (${audioBytes / bytes}× smaller)"
        bytes != null -> lines += "$bytes B"
    }
    when (m.direction) {
        Direction.OUTGOING -> when {
            m.endToEndMs != null -> {
                lines += "VAD ${m.vadMs} + STT ${m.sttMs} + other ${m.otherMs} + net ${m.networkMs}"
                lines += "+ peer queue ${m.peerQueueMs} + peer TTS ${m.peerTtsMs} = ${m.endToEndMs} ms"
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
            val total = (m.vadMs ?: 0) + (m.sttMs ?: 0) + m.ttsMs
            lines += "VAD ${m.vadMs} · STT ${m.sttMs} · TTS ${m.ttsMs} · total $total ms"
        }
    }
    return lines
}

private fun formatBytes(n: Long): String = if (n >= 1024 * 1024) "%.1f MB".format(n / 1048576.0) else "${n / 1024} KB"

private const val SAMPLE_RATE = 16_000
private const val BYTES_PER_SAMPLE = 2
private const val MIN_SHOWN_WAIT_MS = 50L
private const val RECONNECTED_MS = 5_000L

