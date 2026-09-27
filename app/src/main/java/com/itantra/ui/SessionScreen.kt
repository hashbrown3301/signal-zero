package com.itantra.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.itantra.MainViewModel
import com.itantra.MainViewModel.Link
import com.itantra.MainViewModel.Mode
import com.itantra.comm.AddressKind
import com.itantra.comm.LinkState
import com.itantra.session.Direction
import com.itantra.session.Message
import com.itantra.session.Phase
import com.itantra.session.Status

@SuppressLint("MissingPermission") // checked in hasMicPermission() before onPressStart()
@Composable
fun SessionScreen(
    ui: MainViewModel.UiState,
    onLeave: () -> Unit,
    onPressStart: () -> Boolean,
    onPressEnd: () -> Unit,
) {
    val session = ui.session
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionDenied = !granted }

    fun hasMicPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // With the screen off the app goes to the background and Android cuts its network
    // after about a minute, which drops the link. Keep the screen on during Host/Join sessions.
    val view = LocalView.current
    val networked = ui.mode != Mode.SOLO
    DisposableEffect(networked) {
        view.keepScreenOn = networked
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TopBar(ui, onLeave)

        if (ui.mode == Mode.HOST && session.link !is LinkState.Connected) {
            if (ui.link == Link.BLUETOOTH) BluetoothHostCard() else HostAddressCard(ui)
        }

        val listState = rememberLazyListState()
        LaunchedEffect(session.messages.size) {
            if (session.messages.isNotEmpty()) listState.animateScrollToItem(session.messages.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (session.messages.isEmpty()) {
                item {
                    Text(
                        "दबाकर रखें और हिंदी में बोलें",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(session.messages, key = { it.id }) { MessageBubble(it) }
        }

        val notice = when {
            permissionDenied -> "Microphone permission is needed to hear you"
            ui.error != null -> ui.error
            else -> session.notice
        }
        if (notice != null) {
            Text(notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }

        Text(statusText(ui), style = MaterialTheme.typography.titleMedium)
        val enabled = ui.modelsReady && session.canTalk
        val buttonColor = when {
            session.phase == Phase.Listening -> Color(0xFFD32F2F)
            enabled -> MaterialTheme.colorScheme.primary
            else -> Color.Gray
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .size(112.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (!hasMicPermission()) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        permissionDenied = false
                        if (onPressStart()) {
                            tryAwaitRelease()
                            onPressEnd()
                        }
                    })
                },
        ) {
            Text("🎤", fontSize = 42.sp)
        }
    }
}

@Composable
private fun TopBar(ui: MainViewModel.UiState, onLeave: () -> Unit) {
    val (color, label) = linkLabel(ui)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onLeave) { Text("← Leave") }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "iTantra · " + when (ui.mode) {
                    Mode.HOST -> "Host"
                    Mode.JOIN -> "Join ${ui.peerName.ifEmpty { ui.peer }}"
                    else -> "Solo"
                } + if (ui.mode != Mode.SOLO) " · " + (if (ui.link == Link.BLUETOOTH) "Bluetooth" else "Wi-Fi") else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(
                    " $label" + (ui.session.rttMs?.let { " · RTT $it ms" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val last = ui.session.lastDisconnect
            if (last != null && ui.session.link !is LinkState.Connected) {
                Text(
                    "last drop: $last",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BluetoothHostCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Waiting over Bluetooth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("On the other phone: iTantra → Bluetooth → pick this phone → Join. The phones must be paired.")
        }
    }
}

@Composable
private fun HostAddressCard(ui: MainViewModel.UiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Other phone: join this IP", style = MaterialTheme.typography.bodyMedium)
            val lan = ui.hostAddresses.filter { it.isLan } // hotspot first (sorted by kind)
            val main = lan.firstOrNull()
            if (main == null) {
                Text(
                    "No hotspot or Wi-Fi address yet – turn on the hotspot",
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(main.ip, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${main.kind.label} (${main.iface}) · port 5005" +
                        if (main.kind == AddressKind.WIFI) " – only for phones on the same Wi-Fi" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lan.drop(1).forEach {
                Text(
                    "also ${it.kind.label}: ${it.ip} (${it.iface})" +
                        if (it.kind == AddressKind.WIFI) " – only for phones on the same Wi-Fi" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(m: Message) {
    val mine = m.direction != Direction.INCOMING
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(m.text, fontSize = 20.sp, lineHeight = 28.sp)
            m.endToEndMs?.let {
                Text("end-to-end $it ms", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            detailLines(m).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun detailLines(m: Message): List<String> {
    val status = when (m.status) {
        Status.SENT -> "✓ sent"
        Status.ACKED -> "✓✓ heard"
        Status.FAILED -> "✗ failed" + (m.error?.let { ": $it" } ?: "")
        Status.QUEUED -> "queued"
        Status.PLAYING -> "▶ playing"
        Status.PLAYED -> "played"
    }
    val lines = mutableListOf(status)
    val bytes = m.wireBytes
    val audioBytes = m.recordedSec?.let { (it * SAMPLE_RATE * BYTES_PER_SAMPLE).toLong() }
    when {
        bytes != null && audioBytes != null ->
            lines += "$bytes B vs ${formatBytes(audioBytes)} audio (${audioBytes / bytes}× smaller)"
        bytes != null -> lines += "$bytes B"
    }
    when (m.direction) {
        Direction.OUTGOING -> when {
            m.endToEndMs != null -> {
                lines += "VAD ${m.vadMs} + STT ${m.sttMs} + other ${m.otherMs} + net ${m.networkMs}"
                lines += "+ peer queue ${m.peerQueueMs} + peer TTS ${m.peerTtsMs} ms"
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

private fun formatBytes(n: Long): String =
    if (n >= 1024 * 1024) "%.1f MB".format(n / 1048576.0) else "${n / 1024} KB"

private fun linkLabel(ui: MainViewModel.UiState): Pair<Color, String> = when (val link = ui.session.link) {
    null -> if (ui.mode == Mode.SOLO) Color(0xFF9E9E9E) to "offline · this phone only"
    else Color(0xFFFFA000) to "starting…"
    LinkState.Idle -> Color(0xFFFFA000) to "starting…"
    is LinkState.Listening -> Color(0xFFFFA000) to "waiting for the other phone"
    is LinkState.Connecting -> Color(0xFFFFA000) to "connecting (attempt ${link.attempt})"
    is LinkState.Connected -> Color(0xFF2E7D32) to "connected · ${link.peer}"
    is LinkState.Disconnected -> Color(0xFFD32F2F) to "disconnected"
    LinkState.Closed -> Color(0xFF9E9E9E) to "closed"
}

private fun statusText(ui: MainViewModel.UiState): String = when {
    !ui.modelsReady -> "Loading models…"
    ui.session.phase == Phase.Listening -> "Listening…"
    ui.session.phase == Phase.Processing -> "Processing…"
    ui.session.speaking -> "Speaking…"
    else -> "Hold to talk"
}

private const val SAMPLE_RATE = 16_000
private const val BYTES_PER_SAMPLE = 2
private const val MIN_SHOWN_WAIT_MS = 50L
