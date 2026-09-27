package com.itantra.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.itantra.MainViewModel
import com.itantra.MainViewModel.Mode
import com.itantra.bluetooth.Bluetooth
import com.itantra.bluetooth.BtAvailability
import com.itantra.bluetooth.rememberBluetoothState
import com.itantra.comm.isValidIpv4

@Composable
fun HomeScreen(ui: MainViewModel.UiState, onStart: (Mode, String) -> Unit) {
    var peer by rememberSaveable(ui.lastPeer) { mutableStateOf(ui.lastPeer) }
    val peerValid = isValidIpv4(peer)
    var useBluetooth by rememberSaveable { mutableStateOf(false) }

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

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !useBluetooth,
                onClick = { useBluetooth = false },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Wi-Fi") }
            SegmentedButton(
                selected = useBluetooth,
                onClick = { useBluetooth = true },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Bluetooth") }
        }

        if (useBluetooth) {
            BluetoothSection()
        } else {
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

@SuppressLint("MissingPermission") // ACTION_REQUEST_ENABLE is only offered once BLUETOOTH_CONNECT is granted
@Composable
private fun BluetoothSection() {
    val context = LocalContext.current
    val bt by rememberBluetoothState()
    var denied by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> denied = !granted }
    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* the ACTION_STATE_CHANGED receiver refreshes the state */ }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (bt.availability) {
                BtAvailability.UNSUPPORTED -> Text("This phone has no Bluetooth.")

                BtAvailability.NO_PERMISSION -> {
                    Text("Bluetooth", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("iTantra needs the \"Nearby devices\" permission to see your paired phones and connect to them.")
                    if (denied) {
                        Text(
                            "Permission denied. If Android no longer asks, allow it in Settings → Apps → iTantra → Permissions.",
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open app settings") }
                    }
                    Button(
                        onClick = { Bluetooth.connectPermission?.let { permissionLauncher.launch(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow Bluetooth") }
                }

                BtAvailability.OFF -> {
                    Text("Bluetooth", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Bluetooth is off.")
                    Button(
                        onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Turn on Bluetooth") }
                }

                BtAvailability.ON -> {
                    Text("Host", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("The other phone picks this phone from its paired list.")
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Start as Host (Bluetooth) · coming next step")
                    }
                }
            }
        }
    }

    if (bt.availability == BtAvailability.ON) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Join", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (bt.devices.none { it.isPhone }) {
                    Text(
                        "No paired phone yet. Pair the two phones once in Settings → Connections → Bluetooth, then come back.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("Pick the host phone:")
                }
                bt.devices.forEach { d ->
                    val alpha = if (d.isPhone) 1f else 0.4f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == d.address, enabled = d.isPhone) { selected = d.address },
                    ) {
                        RadioButton(selected = selected == d.address, onClick = null, enabled = d.isPhone)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(d.name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                            Text(
                                if (d.isPhone) "phone" else "not a phone",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                            )
                        }
                    }
                }
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("Join (Bluetooth) · coming next step")
                }
            }
        }
    }
}
