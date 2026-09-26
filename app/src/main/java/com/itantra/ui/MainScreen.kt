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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.MainViewModel
import com.itantra.MainViewModel.Phase

@SuppressLint("MissingPermission") // checked in hasMicPermission() before onPressStart()
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionDenied = !granted }

    fun hasMicPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("iTantra", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(statusText(state.phase), style = MaterialTheme.typography.titleMedium)

        Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = state.transcript.ifEmpty { "दबाकर रखें और हिंदी में बोलें" },
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    color = if (state.transcript.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }

        val message = if (permissionDenied) "Microphone permission is needed to hear you" else state.message
        if (message != null) {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }

        TimingsCard(state.timings)

        val enabled = state.phase == Phase.Ready || state.phase == Phase.Listening
        val buttonColor = when {
            state.phase == Phase.Listening -> Color(0xFFD32F2F)
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
                        viewModel.onPressStart()
                        tryAwaitRelease()
                        viewModel.onPressEnd()
                    })
                },
        ) {
            Text("🎤", fontSize = 44.sp)
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun TimingsCard(timings: MainViewModel.Timings?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TimingRow("VAD", timings?.vadMs)
            TimingRow("STT", timings?.sttMs)
            TimingRow("TTS", timings?.ttsMs)
            TimingRow("Total", timings?.totalMs, bold = true)
            if (timings != null) {
                Text(
                    "recorded %.1f s → speech %.1f s".format(timings.recordedSec, timings.speechSec),
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

private fun statusText(phase: Phase) = when (phase) {
    Phase.Loading -> "Loading models…"
    Phase.Ready -> "Ready – hold the button to talk"
    Phase.Listening -> "Listening…"
    Phase.Processing -> "Processing…"
    Phase.Speaking -> "Speaking…"
    Phase.Error -> "Error"
}
