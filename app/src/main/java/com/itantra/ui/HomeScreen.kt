package com.itantra.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import com.itantra.bluetooth.PairedDevice
import com.itantra.bluetooth.rememberBluetoothState
import com.itantra.comm.AddressKind
import com.itantra.comm.LinkState
import com.itantra.comm.LocalAddress
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
import com.itantra.ui.theme.Motion
import com.itantra.ui.theme.Palette
import com.itantra.ui.theme.pressScale
import com.itantra.ui.theme.scriptFont

/**
 * Home: the iTantra header and language, then either the ways to connect (Wi-Fi, Bluetooth, Solo), the progress of
 * a link being set up (the host's address, "Connecting…"), or the running link with Open Talk / End.
 *
 * Motion: the three stages and the Wi-Fi / Bluetooth / Solo tabs cross-fade with a gentle size change; children
 * take narrow, equality-comparable parameters, so the once-per-2-s RTT tick doesn't recompose them.
 */
@Composable
fun HomeScreen(
    ui: MainViewModel.UiState,
    onStart: (Mode, String, Link, String) -> Unit,
    onLeave: () -> Unit,
    onOpenTalk: () -> Unit,
    onSelectLanguage: (String) -> Unit,
) {
    val stage = when {
        ui.mode == null -> Stage.Choose
        ui.settingUp -> Stage.Setup
        else -> Stage.Active
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        BrandHeader(
            trailing = { LanguageChip(ui.myLanguage, ui.languages, ui.loadingLanguage, ui.mode == null, onSelectLanguage) },
        )
        StatusLine(ui.error, ui.loadingLanguage, ui.modelsReady)
        // A stage on its way out keeps the data it had (HeldFadeSwap), so "Joining…" doesn't flash while End fades.
        HeldFadeSwap(stage, ui.toHomeState(), Modifier.fillMaxWidth(), label = "home-stage") { s, home ->
            when (s) {
                Stage.Choose -> ConnectChooser(home.lastPeer, home.lastBtAddress, onStart)
                Stage.Setup -> SetupProgress(home.setup, onLeave)
                Stage.Active -> ActiveLink(home.active, onOpenTalk, onLeave)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private enum class Stage { Choose, Setup, Active }

@Immutable
private data class HomeState(val lastPeer: String, val lastBtAddress: String, val setup: SetupInfo, val active: ActiveInfo)

@Immutable
private data class SetupInfo(
    val hosting: Boolean,
    val wifi: Boolean,
    val linkLabel: String,
    val addresses: List<LocalAddress>,
    val ownBtName: String,
    val peer: String,
    val attempt: Int?,
    val lastDisconnect: String?,
)

@Immutable
private data class ActiveInfo(val look: LinkLook, val solo: Boolean, val peer: String, val linkLabel: String)

private fun MainViewModel.UiState.toHomeState() = HomeState(
    lastPeer = lastPeer,
    lastBtAddress = lastBtAddress,
    setup = SetupInfo(
        hosting = mode == Mode.HOST,
        wifi = link == Link.WIFI,
        linkLabel = linkTypeLabel(),
        addresses = hostAddresses.filter { it.isLan }, // hotspot first
        ownBtName = ownBtName,
        peer = peerLabel(),
        attempt = (session.link as? LinkState.Connecting)?.attempt,
        lastDisconnect = session.lastDisconnect,
    ),
    active = ActiveInfo(linkLook(), mode == Mode.SOLO, peerLabel(), linkTypeLabel()),
)

/** Cross-fades between values of [state] (new content leads), and eases the size change instead of snapping. */
@Composable
internal fun <S> FadeSwap(
    state: S,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "fade-swap",
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) using SizeTransform(clip = false) { _, _ -> Motion.gentle() }
        },
        contentAlignment = contentAlignment,
        label = label,
        content = content,
    )
}

/**
 * [FadeSwap] where [value] is the data shown for [state]. A state that is fading out keeps showing the value it last
 * had while it was current, instead of the new state's data (which may not even fit it).
 */
@Suppress("UNCHECKED_CAST")
@Composable
internal fun <S, V> HeldFadeSwap(
    state: S,
    value: V,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    label: String = "held-fade-swap",
    content: @Composable (S, V) -> Unit,
) {
    val last = remember { HashMap<S, V>() }
    last[state] = value // plain map, not state: a fading-out state must not recompose with newer data
    FadeSwap(state, modifier, contentAlignment, label) { s -> content(s, last[s] as V) }
}

/** The last non-null [value] (null until there has been one), so an exit animation can still draw what was there. */
@Suppress("UNCHECKED_CAST")
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    return holder[0] as T?
}

/** Fades in once, when it first appears; nothing on exit. For list rows that arrive after the screen is up. */
@Composable
private fun FadeInItem(content: @Composable () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visible, enter = fadeIn(Motion.enter()), exit = ExitTransition.None) { content() }
}

/** Models / language / error status; the text cross-fades and a longer error eases the line taller. */
@Composable
private fun StatusLine(error: String?, loadingLanguage: String?, modelsReady: Boolean) {
    val text = when {
        error != null -> error
        loadingLanguage != null -> "Loading ${englishName(loadingLanguage)}…"
        modelsReady -> "Models ready · offline"
        else -> "Loading models…"
    }
    Crossfade(
        targetState = text to (error != null),
        modifier = Modifier.padding(top = 8.dp).animateContentSize(Motion.gentle()),
        animationSpec = Motion.enter(),
        label = "status-line",
    ) { (shown, isError) ->
        Text(shown, style = DataText, color = if (isError) Palette.Mint else Palette.TextMuted)
    }
}

@Composable
private fun ConnectChooser(lastPeer: String, lastBtAddress: String, onStart: (Mode, String, Link, String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Own Column: this sits inside an AnimatedContent, which would otherwise stack these on top of each other.
    Column {
    Text("Connect a phone", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp))
    UnderlineTabs(
        listOf(TabItem("Wi-Fi", R.drawable.ic_wifi), TabItem("Bluetooth", R.drawable.ic_bluetooth), TabItem("Solo", R.drawable.ic_phone)),
        selected = tab,
        onSelect = { tab = it },
        modifier = Modifier.padding(top = 16.dp),
    )
    FadeSwap(tab, Modifier.padding(top = 24.dp).fillMaxWidth(), label = "connect-tab") { t ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (t) {
                0 -> WifiOptions(lastPeer, onStart)
                1 -> BluetoothOptions(lastBtAddress, onStart)
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
    }
}

@Composable
private fun WifiOptions(lastPeer: String, onStart: (Mode, String, Link, String) -> Unit) {
    var peer by rememberSaveable(lastPeer) { mutableStateOf(lastPeer) }
    val valid = isValidIpv4(peer)
    val invalid = peer.isNotEmpty() && !valid

    Overline("Host · this phone")
    Text("Turn on this phone's hotspot, then start hosting. The other phone joins your address.", style = MaterialTheme.typography.bodyLarge)
    SecondaryButton(onClick = { onStart(Mode.HOST, "", Link.WIFI, "") }) { Text("Start hosting") }

    RuleWithText("or", Modifier.padding(vertical = 12.dp))

    Overline("Join · another phone")
    Text("Join the host's hotspot, then type the address its screen shows.", style = MaterialTheme.typography.bodyLarge)
    Column {
        Text("Host's address", style = MaterialTheme.typography.bodyMedium, color = Palette.Mint)
        val ruleColor by animateColorAsState(if (invalid) Palette.Mint else Palette.Accent, Motion.enter(), label = "address-rule")
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
                    Rule(color = ruleColor)
                }
            },
        )
        Crossfade(
            targetState = when {
                invalid -> "Not an address yet, e.g. 192.168.43.1"
                lastPeer.isNotEmpty() -> "last used $lastPeer"
                else -> " "
            },
            modifier = Modifier.padding(top = 8.dp),
            animationSpec = Motion.enter(),
            label = "address-hint",
        ) { hint -> Text(hint, style = DataText, color = Palette.TextMuted) }
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

    // Permission, off, on: this happens while the user watches, so the blocks swap with a fade, not a jump.
    // The list is empty once Bluetooth is off, so the fading-out ON block keeps the last list it had.
    val shownDevices = rememberLastNonNull(bt.devices.takeIf { bt.availability == BtAvailability.ON }) ?: bt.devices
    FadeSwap(bt.availability, Modifier.fillMaxWidth(), label = "bluetooth-availability") { availability ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (availability) {
                BtAvailability.UNSUPPORTED -> Text("This phone has no Bluetooth.", style = MaterialTheme.typography.bodyLarge)

                BtAvailability.NO_PERMISSION -> {
                    Text(
                        "iTantra needs the \"Nearby devices\" permission to see your paired phones and connect to them.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Column {
                        PrimaryButton(onClick = { Bluetooth.connectPermission?.let { permissionLauncher.launch(it) } }, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow Bluetooth")
                        }
                        AnimatedVisibility(
                            denied,
                            enter = fadeIn(Motion.enter()) + expandVertically(Motion.gentle()),
                            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.gentle()),
                        ) {
                            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "Permission denied. If Android no longer asks, allow it in Settings → Apps → iTantra → Permissions.",
                                    style = MaterialTheme.typography.bodyMedium, color = Palette.Mint,
                                )
                                SecondaryButton(onClick = {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                                }) { Text("Open app settings") }
                            }
                        }
                    }
                }

                BtAvailability.OFF -> {
                    Text("Bluetooth is off.", style = MaterialTheme.typography.bodyLarge)
                    PrimaryButton(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn on Bluetooth")
                    }
                }

                BtAvailability.ON -> PairedPhones(shownDevices, selected, { selected = it }, onStart)
            }
        }
    }
}

@Composable
private fun PairedPhones(
    devices: List<PairedDevice>,
    selected: String?,
    onSelect: (String) -> Unit,
    onStart: (Mode, String, Link, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Overline("Host · this phone")
        Text("The other phone picks this phone from its paired list.", style = MaterialTheme.typography.bodyLarge)
        SecondaryButton(onClick = { onStart(Mode.HOST, "", Link.BLUETOOTH, "") }) { Text("Start hosting") }

        RuleWithText("or", Modifier.padding(vertical = 12.dp))

        Overline("Paired phones")
        val phones = devices.filter { it.isPhone }
        val others = devices.filterNot { it.isPhone }
        Column {
            if (phones.isEmpty()) {
                Text("No paired phone yet.", style = MaterialTheme.typography.bodyLarge, color = Palette.Mint)
            }
            phones.forEach { d ->
                key(d.address) {
                    FadeInItem {
                        Column {
                            PhoneRow(d.name, on = selected == d.address) { onSelect(d.address) }
                            Rule()
                        }
                    }
                }
            }
        }
        if (others.isNotEmpty()) {
            Overline("Other paired devices", color = Palette.TextFaint, modifier = Modifier.padding(top = 8.dp))
            Column {
                others.forEach { d ->
                    key(d.address) {
                        FadeInItem {
                            Column {
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(d.name, style = MaterialTheme.typography.bodyLarge, color = Palette.TextFaint, modifier = Modifier.weight(1f))
                                    Text("Not a phone", style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint)
                                }
                                Rule(color = Palette.LineFaint)
                            }
                        }
                    }
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

/** One paired phone: radio mark, name, and "Selected". Eases down while pressed; no ripple. */
@Composable
private fun PhoneRow(name: String, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressScale(interaction, 0.98f)
            .selectable(selected = on, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick),
    ) {
        RadioMark(on)
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
        AnimatedVisibility(
            on,
            enter = fadeIn(Motion.enter()) + expandHorizontally(Motion.gentle()),
            exit = fadeOut(Motion.exit()) + shrinkHorizontally(Motion.gentle()),
        ) {
            Text("Selected", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
        }
    }
}

@Composable
private fun RadioMark(on: Boolean) {
    // The state is read inside the draw block, so a selection change redraws this canvas without recomposing it.
    val fill = animateFloatAsState(if (on) 1f else 0f, Motion.snappy(), label = "radio")
    Canvas(Modifier.size(24.dp)) {
        val t = fill.value
        drawCircle(lerp(Palette.LineDashed, Palette.Accent, t.coerceIn(0f, 1f)), radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        if (t > 0f) drawCircle(Palette.Accent, radius = 5.dp.toPx() * t)
    }
}

/** Hosting (show our address and wait) or joining (connecting…), before the link first comes up. */
@Composable
private fun SetupProgress(s: SetupInfo, onLeave: () -> Unit) {
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (s.hosting) "Hosting over ${s.linkLabel}" else "Joining over ${s.linkLabel}", style = MaterialTheme.typography.headlineSmall)
        Rule()
        when {
            s.hosting && s.wifi -> {
                Overline("Read this address to the other phone")
                // The address appears when the hotspot comes up (or changes): cross-fade, size eases.
                FadeSwap(s.addresses, Modifier.fillMaxWidth(), label = "host-address") { lan ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                }
            }
            s.hosting -> {
                Overline("On the other phone, pick")
                Text(s.ownBtName.ifEmpty { "this phone" }, style = MaterialTheme.typography.headlineMedium)
                Text("iTantra → Home → Bluetooth → this phone → Connect. The phones must be paired.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
            }
            else -> {
                Overline("Connecting to")
                Text(s.peer, style = MaterialTheme.typography.headlineMedium)
            }
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                LinkMotif(LinkLook.Connecting, width = 120.dp)
                LinkStatusLabel(
                    LinkLook.Connecting,
                    text = if (s.hosting) "Waiting for the other phone" else "Connecting…" + (s.attempt?.takeIf { it > 1 }?.let { " attempt $it" } ?: ""),
                )
            }
            // A failed try appears with a fade and eases the card taller (and back when the next try starts).
            val lastTry = rememberLastNonNull(s.lastDisconnect)
            AnimatedVisibility(
                s.lastDisconnect != null,
                enter = fadeIn(Motion.enter()) + expandVertically(Motion.gentle()),
                exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.gentle()),
            ) {
                Text("last try: ${lastTry.orEmpty()}", style = DataText, color = Palette.TextMuted, modifier = Modifier.padding(top = 12.dp))
            }
        }
        SecondaryButton(onClick = onLeave) { Text(if (s.hosting) "Stop hosting" else "Cancel") }
    }
}

/** The link is up (or was, and is reconnecting), or Solo is running. */
@Composable
private fun ActiveLink(a: ActiveInfo, onOpenTalk: () -> Unit, onLeave: () -> Unit) {
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Rule()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LinkMotif(a.look)
            Column(Modifier.weight(1f)) {
                Text(if (a.solo) "Solo" else a.peer, style = MaterialTheme.typography.titleMedium)
                Text(if (a.solo) "this phone only" else a.linkLabel, style = DataText, color = Palette.TextMuted)
            }
            if (a.look != LinkLook.Solo) LinkStatusLabel(a.look)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(onClick = onOpenTalk, modifier = Modifier.weight(1f)) { Text("Open Talk") }
            SecondaryButton(onClick = onLeave) { Text(if (a.solo) "End Solo" else "End link") }
        }
    }
}

/**
 * Current language in its own script; tap to switch (only between sessions). While a language loads, the chevron
 * becomes a small spinner (an animation only while something is really loading).
 */
@Composable
private fun LanguageChip(
    myLanguage: String,
    languages: List<MainViewModel.LanguageOption>,
    loadingLanguage: String?,
    canChange: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val loading = loadingLanguage != null
    val enabled = !loading && canChange
    val interaction = remember { MutableInteractionSource() }
    val dim = animateFloatAsState(if (enabled || loading) 1f else 0.6f, Motion.enter(), label = "chip-dim")
    Box {
        TextButton(
            onClick = { open = true },
            enabled = enabled,
            interactionSource = interaction,
            modifier = Modifier.pressScale(interaction).graphicsLayer { alpha = dim.value },
        ) {
            Crossfade(myLanguage, animationSpec = Motion.enter(), label = "chip-name") { iso ->
                Text(nativeName(iso), fontFamily = scriptFont(iso), style = MaterialTheme.typography.titleSmall, color = Palette.Mint)
            }
            Crossfade(loading, Modifier.padding(start = 4.dp).size(16.dp), Motion.enter(), label = "chip-icon") { busy ->
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Palette.Mint, strokeWidth = 2.dp, trackColor = Color.Transparent)
                } else {
                    Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp), tint = Palette.Mint)
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Palette.Navy)) {
            for (lang in languages) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(nativeName(lang.iso), fontFamily = scriptFont(lang.iso), style = MaterialTheme.typography.titleMedium,
                                color = if (lang.iso == myLanguage) Palette.Accent else Palette.OffWhite)
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
