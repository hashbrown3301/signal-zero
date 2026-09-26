package com.itantra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itantra.MainViewModel
import com.itantra.MainViewModel.Mode
import com.itantra.comm.isValidIpv4

@Composable
fun HomeScreen(ui: MainViewModel.UiState, onStart: (Mode, String) -> Unit) {
    var peer by rememberSaveable(ui.lastPeer) { mutableStateOf(ui.lastPeer) }
    val peerValid = isValidIpv4(peer)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("iTantra", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            when {
                ui.error != null -> ui.error
                ui.modelsReady -> "Models ready · offline Hindi voice"
                else -> "Loading models…"
            },
            color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Host", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Turn on this phone's hotspot. The other phone joins it and connects to you.")
                Button(onClick = { onStart(Mode.HOST, "") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start as Host")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Join", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Connect to the host's hotspot Wi-Fi, then enter the IP shown on the host's screen.")
                OutlinedTextField(
                    value = peer,
                    onValueChange = { v -> peer = v.filter { it.isDigit() || it == '.' }.take(15) },
                    label = { Text("Host IP, e.g. 192.168.43.1") },
                    singleLine = true,
                    isError = peer.isNotEmpty() && !peerValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onStart(Mode.JOIN, peer) },
                    enabled = peerValid,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Join") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Solo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("One phone: speak, see the transcript, hear it back.")
                OutlinedButton(onClick = { onStart(Mode.SOLO, "") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start Solo")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
