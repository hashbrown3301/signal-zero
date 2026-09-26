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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.MainViewModel
import com.itantra.comm.LinkState
import com.itantra.session.Direction
import com.itantra.session.Message
import com.itantra.session.Phase
import com.itantra.session.SessionState

@SuppressLint("MissingPermission") // checked in hasMicPermission() before onPressStart()
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val ui by viewModel.state.collectAsState()
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
    val networked = session.link != null
    DisposableEffect(networked) {
        view.keepScreenOn = networked
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("iTantra · ${ui.mode}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(statusText(ui), style = MaterialTheme.typography.titleMedium)
        linkText(session)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (session.messages.isEmpty()) {
                    Text(
                        "दबाकर रखें और हिंदी में बोलें",
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (m in session.messages.takeLast(8)) MessageLine(m)
            }
        }

        val notice = when {
            permissionDenied -> "Microphone permission is needed to hear you"
            ui.error != null -> ui.error
            else -> session.notice
        }
        if (notice != null) {
            Text(notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }

        TimingsCard(session.messages.lastOrNull())

        val enabled = !ui.loading && session.canTalk
        val buttonColor = when {
            session.phase == Phase.Listening -> Color(0xFFD32F2F)
            enabled -> MaterialTheme.colorScheme.primary
            else -> Color.Gray
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (!hasMicPermission()) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        permissionDenied = false
                        if (viewModel.onPressStart()) {
                            tryAwaitRelease()
                            viewModel.onPressEnd()
                        }
                    })
                },
        ) {
            Text("🎤", fontSize = 44.sp)
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun MessageLine(m: Message) {
    val arrow = when (m.direction) {
        Direction.OUTGOING -> "→"
        Direction.INCOMING -> "←"
        Direction.LOCAL -> "•"
    }
    Column {
        Text("$arrow ${m.text}", fontSize = 22.sp, lineHeight = 30.sp)
        Text(
            listOfNotNull(m.status.name.lowercase(), m.wireBytes?.let { "$it B" }, m.error).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimingsCard(m: Message?) {
    val rows: List<Pair<String, Long?>> = when (m?.direction) {
        Direction.OUTGOING -> listOf(
            "VAD" to m.vadMs, "STT" to m.sttMs, "Peer TTS" to m.peerTtsMs,
            "Peer queue" to m.peerQueueMs, "Release → ACK" to m.ackAfterMs,
        )
        Direction.INCOMING -> listOf("Queue wait" to m.queueMs, "TTS" to m.ttsMs)
        Direction.LOCAL -> listOf(
            "VAD" to m.vadMs, "STT" to m.sttMs, "TTS" to m.ttsMs,
            "Total" to m.ttsMs?.let { (m.vadMs ?: 0) + (m.sttMs ?: 0) + it },
        )
        null -> listOf("VAD" to null, "STT" to null, "TTS" to null, "Total" to null)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEachIndexed { i, (label, ms) -> TimingRow(label, ms, bold = i == rows.lastIndex) }
            if (m?.recordedSec != null && m.speechSec != null) {
                Text(
                    "recorded %.1f s → speech %.1f s".format(m.recordedSec, m.speechSec),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TimingRow(label: String, millis: Long?, bold: Boolean = false) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = weight)
        Text(millis?.let { "$it ms" } ?: "–", fontWeight = weight)
    }
}

private fun statusText(ui: MainViewModel.UiState): String = when {
    ui.loading -> "Loading models…"
    ui.error != null -> "Error"
    ui.session.phase == Phase.Listening -> "Listening…"
    ui.session.phase == Phase.Processing -> "Processing…"
    ui.session.speaking -> "Speaking…"
    else -> "Ready – hold the button to talk"
}

private fun linkText(s: SessionState): String? = when (val link = s.link) {
    null -> null
    LinkState.Idle -> "Link: starting"
    is LinkState.Listening -> "Link: waiting for peer on port ${link.port}" +
        (s.lastDisconnect?.let { " (last: $it)" } ?: "")
    is LinkState.Connecting -> "Link: connecting to ${link.host} (attempt ${link.attempt})"
    is LinkState.Connected -> "Link: connected to ${link.peer}"
    is LinkState.Disconnected -> "Link: ${link.reason}"
    LinkState.Closed -> "Link: closed"
}
