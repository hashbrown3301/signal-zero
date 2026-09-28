package com.itantra.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.MainViewModel
import com.itantra.MainViewModel.Link
import com.itantra.MainViewModel.Mode
import com.itantra.R
import com.itantra.bluetooth.Bluetooth
import com.itantra.bluetooth.BtAvailability
import com.itantra.bluetooth.rememberBluetoothState
import com.itantra.comm.AddressKind
import com.itantra.comm.LinkState
import com.itantra.comm.isValidIpv4
import com.itantra.ui.components.BrandHeader
import com.itantra.ui.components.LinkLook
import com.itantra.ui.components.LinkMotif
import com.itantra.ui.components.LinkStatusLabel
import com.itantra.ui.components.Overline
import com.itantra.ui.components.PrimaryButton
import com.itantra.ui.components.Rule
import com.itantra.ui.components.RuleWithText
import com.itantra.ui.components.SecondaryButton
import com.itantra.ui.components.TabItem
import com.itantra.ui.components.Tag
import com.itantra.ui.components.UnderlineTabs
import com.itantra.ui.theme.DataLarge
import com.itantra.ui.theme.DataText
import com.itantra.ui.theme.Palette
import com.itantra.ui.theme.scriptFont

/**
 * Home: the iTantra header and language, then either the ways to connect (Wi-Fi, Bluetooth, Solo), the progress of
 * a link being set up (the host's address, "Connecting…"), or the running link with Open Talk / End.
 */
@Composable
fun HomeScreen(
    ui: MainViewModel.UiState,
    onStart: (Mode, String, Link, String) -> Unit,
    onLeave: () -> Unit,
    onOpenTalk: () -> Unit,
    onSelectLanguage: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        BrandHeader(trailing = { LanguageChip(ui, onSelectLanguage) })
        Text(
            when {
                ui.error != null -> ui.error
                ui.loadingLanguage != null -> "Loading ${englishName(ui.loadingLanguage)}…"
                ui.modelsReady -> "Models ready · offline"
                else -> "Loading models…"
            },
            style = DataText,
            color = if (ui.error != null) Palette.Mint else Palette.TextMuted,
            modifier = Modifier.padding(top = 10.dp),
        )
        when {
            ui.mode == null -> ConnectChooser(ui, onStart)
            ui.settingUp -> SetupProgress(ui, onLeave)
            else -> ActiveLink(ui, onOpenTalk, onLeave)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ConnectChooser(ui: MainViewModel.UiState, onStart: (Mode, String, Link, String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Text("Connect a phone", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 26.dp))
    UnderlineTabs(
        listOf(TabItem("Wi-Fi", R.drawable.ic_wifi), TabItem("Bluetooth", R.drawable.ic_bluetooth), TabItem("Solo", R.drawable.ic_phone)),
        selected = tab,
        onSelect = { tab = it },
        modifier = Modifier.padding(top = 16.dp),
    )
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (tab) {
            0 -> WifiOptions(ui, onStart)
            1 -> BluetoothOptions(ui.lastBtAddress, onStart)
            else -> {
                Overline("Solo · this phone only")
                Text(
                    "Speak and hear it back on this phone. Checks your microphone, language and voice. Nothing leaves this phone.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                PrimaryButton(onClick = { onStart(Mode.SOLO, "", Link.WIFI, "") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start Solo")
                }
            }
        }
    }
}

@Composable
private fun WifiOptions(ui: MainViewModel.UiState, onStart: (Mode, String, Link, String) -> Unit) {
    var peer by rememberSaveable(ui.lastPeer) { mutableStateOf(ui.lastPeer) }
    val valid = isValidIpv4(peer)

    Overline("Host · this phone")
    Text("Turn on this phone's hotspot, then start hosting. The other phone joins your address.", style = MaterialTheme.typography.bodyLarge)
    SecondaryButton(onClick = { onStart(Mode.HOST, "", Link.WIFI, "") }) { Text("Start hosting") }

    RuleWithText("or", Modifier.padding(vertical = 12.dp))

    Overline("Join · another phone")
    Text("Join the host's hotspot, then type the address its screen shows.", style = MaterialTheme.typography.bodyLarge)
    Column {
        Text("Host's address", style = MaterialTheme.typography.bodyMedium, color = Palette.Mint)
        BasicTextField(
            value = peer,
            onValueChange = { v -> peer = v.filter { it.isDigit() || it == '.' }.take(15) },
            singleLine = true,
            textStyle = DataLarge.copy(fontSize = 22.sp, lineHeight = 30.sp, color = Palette.OffWhite),
            cursorBrush = SolidColor(Palette.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
            decorationBox = { field ->
                Column {
                    Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) {
                        if (peer.isEmpty()) Text("192.168.43.1", style = DataLarge.copy(fontSize = 22.sp), color = Palette.TextFaint)
                        field()
                    }
                    Rule(color = if (peer.isNotEmpty() && !valid) Palette.Mint else Palette.Accent)
                }
            },
        )
        Text(
            when {
                peer.isNotEmpty() && !valid -> "Not an address yet, e.g. 192.168.43.1"
                ui.lastPeer.isNotEmpty() -> "last used ${ui.lastPeer}"
                else -> " "
            },
            style = DataText, color = Palette.TextMuted, modifier = Modifier.padding(top = 6.dp),
        )
    }
    PrimaryButton(onClick = { onStart(Mode.JOIN, peer, Link.WIFI, peer) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
        Text("Join")
    }
}

@SuppressLint("MissingPermission") // ACTION_REQUEST_ENABLE is only offered once BLUETOOTH_CONNECT is granted
@Composable
private fun BluetoothOptions(lastBtAddress: String, onStart: (Mode, String, Link, String) -> Unit) {
    val context = LocalContext.current
    val bt by rememberBluetoothState()
    var denied by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(lastBtAddress) { mutableStateOf(lastBtAddress.ifEmpty { null }) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> denied = !granted }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        /* the ACTION_STATE_CHANGED receiver refreshes the state */
    }

    when (bt.availability) {
        BtAvailability.UNSUPPORTED -> Text("This phone has no Bluetooth.", style = MaterialTheme.typography.bodyLarge)

        BtAvailability.NO_PERMISSION -> {
            Text(
                "iTantra needs the \"Nearby devices\" permission to see your paired phones and connect to them.",
                style = MaterialTheme.typography.bodyLarge,
            )
            PrimaryButton(onClick = { Bluetooth.connectPermission?.let { permissionLauncher.launch(it) } }, modifier = Modifier.fillMaxWidth()) {
                Text("Allow Bluetooth")
            }
            if (denied) {
                Text(
                    "Permission denied. If Android no longer asks, allow it in Settings → Apps → iTantra → Permissions.",
                    style = MaterialTheme.typography.bodyMedium, color = Palette.Mint,
                )
                SecondaryButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                }) { Text("Open app settings") }
            }
        }

        BtAvailability.OFF -> {
            Text("Bluetooth is off.", style = MaterialTheme.typography.bodyLarge)
            PrimaryButton(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Turn on Bluetooth")
            }
        }

        BtAvailability.ON -> {
            Overline("Host · this phone")
            Text("The other phone picks this phone from its paired list.", style = MaterialTheme.typography.bodyLarge)
            SecondaryButton(onClick = { onStart(Mode.HOST, "", Link.BLUETOOTH, "") }) { Text("Start hosting") }

            RuleWithText("or", Modifier.padding(vertical = 12.dp))

            Overline("Paired phones")
            val phones = bt.devices.filter { it.isPhone }
            val others = bt.devices.filterNot { it.isPhone }
            Column {
                if (phones.isEmpty()) {
                    Text("No paired phone yet.", style = MaterialTheme.typography.bodyLarge, color = Palette.Mint)
                }
                phones.forEach { d ->
                    val on = selected == d.address
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .selectable(selected = on, role = Role.RadioButton) { selected = d.address },
                    ) {
                        RadioMark(on)
                        Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (on) Text("Selected", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
                    }
                    Rule()
                }
            }
            if (others.isNotEmpty()) {
                Overline("Other paired devices", color = Palette.TextFaint, modifier = Modifier.padding(top = 8.dp))
                Column {
                    others.forEach { d ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, style = MaterialTheme.typography.bodyLarge, color = Palette.TextFaint, modifier = Modifier.weight(1f))
                            Text("Not a phone", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                        }
                        Rule(color = Palette.LineFaint)
                    }
                }
            }
            Text(
                "Not listed? Pair the phones once in Android Settings → Bluetooth.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
            )
            val host = phones.firstOrNull { it.address == selected }
            PrimaryButton(
                onClick = { host?.let { onStart(Mode.JOIN, it.address, Link.BLUETOOTH, it.name) } },
                enabled = host != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(host?.let { "Connect to ${it.name}" } ?: "Pick a phone to connect") }
        }
    }
}

@Composable
private fun RadioMark(on: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        drawCircle(if (on) Palette.Accent else Palette.LineDashed, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        if (on) drawCircle(Palette.Accent, radius = 5.dp.toPx())
    }
}

/** Hosting (show our address and wait) or joining (connecting…), before the link first comes up. */
@Composable
private fun SetupProgress(ui: MainViewModel.UiState, onLeave: () -> Unit) {
    val hosting = ui.mode == Mode.HOST
    Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (hosting) "Hosting over ${ui.linkTypeLabel()}" else "Joining over ${ui.linkTypeLabel()}", style = MaterialTheme.typography.headlineSmall)
        Rule()
        when {
            hosting && ui.link == Link.WIFI -> {
                Overline("Read this address to the other phone")
                val lan = ui.hostAddresses.filter { it.isLan } // hotspot first
                val main = lan.firstOrNull()
                if (main == null) {
                    Text("No hotspot or Wi-Fi address yet. Turn on this phone's hotspot.", style = MaterialTheme.typography.bodyLarge, color = Palette.Mint)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(main.ip, style = DataLarge, color = Palette.OffWhite)
                        Tag(if (main.kind == AddressKind.HOTSPOT) "Hotspot" else main.kind.label)
                    }
                    lan.drop(1).forEach {
                        Text("${it.ip} · ${it.kind.label}" + if (it.kind == AddressKind.WIFI) " (same Wi-Fi only)" else "", style = DataText.copy(fontSize = 13.sp), color = Palette.TextMuted)
                    }
                }
            }
            hosting -> {
                Overline("On the other phone, pick")
                Text(ui.ownBtName.ifEmpty { "this phone" }, style = MaterialTheme.typography.headlineMedium)
                Text("iTantra → Home → Bluetooth → this phone → Connect. The phones must be paired.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
            }
            else -> {
                Overline("Connecting to")
                Text(ui.peerLabel(), style = MaterialTheme.typography.headlineMedium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            LinkMotif(LinkLook.Connecting, width = 120.dp)
            val attempt = (ui.session.link as? LinkState.Connecting)?.attempt
            LinkStatusLabel(
                LinkLook.Connecting,
                text = if (hosting) "Waiting for the other phone" else "Connecting…" + (attempt?.takeIf { it > 1 }?.let { " attempt $it" } ?: ""),
            )
        }
        ui.session.lastDisconnect?.let { Text("last try: $it", style = DataText, color = Palette.TextMuted) }
        SecondaryButton(onClick = onLeave) { Text(if (hosting) "Stop hosting" else "Cancel") }
    }
}

/** The link is up (or was, and is reconnecting), or Solo is running. */
@Composable
private fun ActiveLink(ui: MainViewModel.UiState, onOpenTalk: () -> Unit, onLeave: () -> Unit) {
    val look = ui.linkLook()
    Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Rule()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LinkMotif(look)
            Column(Modifier.weight(1f)) {
                Text(if (ui.mode == Mode.SOLO) "Solo" else ui.peerLabel(), style = MaterialTheme.typography.titleMedium)
                Text(if (ui.mode == Mode.SOLO) "this phone only" else ui.linkTypeLabel(), style = DataText, color = Palette.TextMuted)
            }
            if (look != LinkLook.Solo) LinkStatusLabel(look)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(onClick = onOpenTalk, modifier = Modifier.weight(1f)) { Text("Open Talk") }
            SecondaryButton(onClick = onLeave) { Text(if (ui.mode == Mode.SOLO) "End Solo" else "End link") }
        }
    }
}

/** Current language in its own script; tap to switch (only between sessions). */
@Composable
private fun LanguageChip(ui: MainViewModel.UiState, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val enabled = ui.loadingLanguage == null && ui.mode == null
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.alpha(if (enabled) 1f else 0.6f)) {
            Text(nativeName(ui.myLanguage), fontFamily = scriptFont(ui.myLanguage), style = MaterialTheme.typography.titleSmall, color = Palette.Mint)
            Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.padding(start = 4.dp).size(16.dp), tint = Palette.Mint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Palette.Navy)) {
            for (lang in ui.languages) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(nativeName(lang.iso), fontFamily = scriptFont(lang.iso), style = MaterialTheme.typography.titleMedium,
                                color = if (lang.iso == ui.myLanguage) Palette.Accent else Palette.OffWhite)
                            Text(
                                englishName(lang.iso) + when {
                                    !lang.hasSpeak -> " · install its speak pack first"
                                    !lang.hasListen -> " · no voice"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
                            )
                        }
                    },
                    enabled = lang.hasSpeak,
                    onClick = { open = false; onSelect(lang.iso) },
                )
            }
        }
    }
}
