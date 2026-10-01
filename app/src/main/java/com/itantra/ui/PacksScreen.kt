package com.itantra.ui

import android.net.ConnectivityManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.itantra.MainViewModel
import com.itantra.comm.Language
import com.itantra.packs.CatalogEntry
import com.itantra.packs.PackManifest
import com.itantra.ui.components.SecondaryButton
import com.itantra.ui.components.BrandBar
import com.itantra.ui.theme.Motion
import com.itantra.ui.theme.Palette
import com.itantra.ui.theme.pressScale
import com.itantra.ui.theme.scriptFont

/**
 * Every language iTantra supports, with a speak and a listen pack each: built in, installed (Delete),
 * downloading (progress + Cancel) or available (Download). Sideloading and file import stay available offline.
 *
 * Motion and cost: rows animate into place, a pack's state (installed, downloading, available) cross-fades, and
 * the progress bar glides between the throttled updates. A progress tick only changes one language's [PackRow], so
 * only that card recomposes; the other cards compare equal and are skipped.
 */
@Composable
fun PacksScreen(
    packs: MainViewModel.PacksUi,
    onImport: (Uri) -> Unit,
    onRescan: () -> Unit,
    onDelete: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onRefreshCatalog: () -> Unit,
    selectedLanguage: String,
    listenLanguage: String,
    translation: MainViewModel.TranslationUi,
    loadingLanguage: String?,
    sessionActive: Boolean,
    onSelectLanguage: (String) -> Unit,
    onSelectListenLanguage: (String) -> Unit,
    onDownloadTranslation: () -> Unit,
    onImportTranslation: (Uri) -> Unit,
    onCancelTranslationDownload: () -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val translationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportTranslation(uri)
    }
    // Ask before a big download on mobile data; on Wi-Fi just start.
    val confirm = remember { mutableStateOf<Pair<String, CatalogEntry>?>(null) }
    val confirmTranslation = remember { mutableStateOf(false) }
    confirm.value?.let { (id, entry) ->
        AlertDialog(
            onDismissRequest = { confirm.value = null },
            title = { Text("Download on mobile data?") },
            text = { Text("${entry.name} ${entry.kind} is %.0f MB. Wi-Fi is recommended.".format(entry.zipSize / 1e6)) },
            confirmButton = { TextButton(onClick = { confirm.value = null; onDownload(id) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirm.value = null }) { Text("Cancel") } },
        )
    }
    if (confirmTranslation.value) {
        AlertDialog(
            onDismissRequest = { confirmTranslation.value = false },
            title = { Text("Download on mobile data?") },
            text = {
                Text(
                    "The translation pack covers all 10 languages." +
                        (translation.modelSizeBytes.takeIf { it > 0 }?.let { " Download size: %.0f MB.".format(it / 1e6) } ?: "") +
                        " Wi-Fi is recommended.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmTranslation.value = false; onDownloadTranslation() }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirmTranslation.value = false }) { Text("Cancel") } },
        )
    }

    val builtInIds = remember(packs.builtIn) { packs.builtIn.map { it.id }.toSet() }
    val onPhone = remember(packs.builtIn, packs.installed) { (packs.builtIn + packs.installed).associateBy { it.id } }
    val catalogById = remember(packs.catalog) { packs.catalog.toMap() }
    // Output languages remain selectable even before their voice or recognition packs are installed.
    val languages = remember(packs.catalog, onPhone) {
        (Language.entries.map { it.iso to it.code } + packs.catalog.map { it.second.lang to it.second.packetCode } +
            onPhone.values.map { it.lang to it.packetCode }).distinct().sortedBy { it.second }.map { it.first }
    }
    val downloading = packs.downloads.any { it.value.error == null }
    val requestDownload = remember<(String) -> Unit>(catalogById, context, onDownload) {
        { id ->
            val entry = catalogById[id]
            if (entry != null) {
                val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
                if (metered) confirm.value = id to entry else onDownload(id)
            }
        }
    }
    // The last message stays drawn while it fades out.
    val lastMessage = rememberLastNonNull(packs.message?.let { it to packs.messageIsError })

    Column(Modifier.fillMaxSize()) {
        BrandBar()
        Column(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Languages", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Choose the language you speak and the language you want to hear. Conversations work offline after installation.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                when {
                    loadingLanguage != null -> "Preparing ${englishName(loadingLanguage)}…"
                    sessionActive -> "End the conversation to change languages."
                    else -> "Speak ${englishName(selectedLanguage)} · Hear ${englishName(listenLanguage)}"
                },
                style = MaterialTheme.typography.labelLarge, color = Palette.Accent,
                modifier = Modifier.padding(top = 8.dp),
            )
            // Busy bar and message ease in and out (padding lives inside, so nothing is reserved while they're hidden).
            Column {
                AnimatedVisibility(packs.busy, enter = fadeIn(Motion.enter()) + expandVertically(Motion.gentle()), exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.gentle())) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                AnimatedVisibility(packs.message != null, enter = fadeIn(Motion.enter()) + expandVertically(Motion.gentle()), exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.gentle())) {
                    lastMessage?.let { (text, isError) ->
                        Text(
                            text,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "translation-pack") {
                    TranslationPackCard(
                        translation = translation,
                        canInstall = !sessionActive && !packs.busy && !downloading,
                        onDownload = {
                            val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
                            if (metered) confirmTranslation.value = true else onDownloadTranslation()
                        },
                        onImport = { translationPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        onCancel = onCancelTranslationDownload,
                    )
                }
                items(languages, key = { it }) { lang ->
                    val speak = packRow(lang, PackManifest.KIND_SPEAK, onPhone, builtInIds, catalogById, packs.downloads)
                    val listen = packRow(lang, PackManifest.KIND_LISTEN, onPhone, builtInIds, catalogById, packs.downloads)
                    LanguageCard(
                        lang, speak, listen, packs.busy, downloading,
                        selectedSpeak = lang == selectedLanguage,
                        selectedListen = lang == listenLanguage,
                        canSelect = !sessionActive && loadingLanguage == null && !packs.busy,
                        onSelectSpeak = { onSelectLanguage(lang) },
                        onSelectListen = { onSelectListenLanguage(lang) },
                        onDelete = onDelete,
                        onDownload = requestDownload,
                        onCancel = onCancelDownload,
                        modifier = Modifier.animateItem(fadeInSpec = Motion.enter(), placementSpec = Motion.gentle(), fadeOutSpec = Motion.exit()),
                    )
                }
                item(key = "without-internet") {
                    Column(
                        Modifier
                            .padding(vertical = 8.dp)
                            .animateItem(fadeInSpec = Motion.enter(), placementSpec = Motion.gentle(), fadeOutSpec = Motion.exit()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Install from a file", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SecondaryButton(
                                onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                                enabled = !packs.busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("Import pack…") }
                            SecondaryButton(onClick = onRescan, enabled = !packs.busy, modifier = Modifier.weight(1f)) {
                                Text("Check transferred packs")
                            }
                        }
                        Text(
                            "Use Import pack to choose a language pack shared from another device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PackAction("Check for new packs (internet)", filled = false, enabled = !packs.busy && !downloading, onClick = onRefreshCatalog)
                    }
                }
            }
        }
    }
}

/** One shared translation pack, installed once for every supported language pair. */
@Composable
private fun TranslationPackCard(
    translation: MainViewModel.TranslationUi,
    canInstall: Boolean,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).animateContentSize(Motion.gentle()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Offline translation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    translation.busy -> translation.message ?: "Preparing translation…"
                    !translation.deviceSupported -> "This phone cannot load the translation model"
                    translation.ready -> "Ready · all 10 languages"
                    else -> "Install one translation pack for all 10 languages."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (translation.ready) Palette.Accent else Palette.TextMuted,
            )
            if (translation.busy) {
                DownloadBar(translation.progress?.coerceIn(0f, 1f) ?: 0f, translation.progress == null)
                PackAction("Cancel", filled = false, enabled = true, onClick = onCancel)
            } else if (!translation.ready) {
                translation.modelSizeBytes.takeIf { it > 0 }?.let {
                    Text("%.0f MB download".format(it / 1e6), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PackAction(if (translation.error != null) "Retry download" else "Download", filled = true, enabled = canInstall && translation.deviceSupported, onClick = onDownload)
                    PackAction("Import file…", filled = false, enabled = canInstall && translation.deviceSupported, onClick = onImport)
                }
                Text(
                    "Download once with internet, or import a pack shared from another device. Translation runs on this phone without internet.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted,
                )
                Text("Uses about 1.4 GB storage. Requires a 64-bit phone with at least 4 GB RAM; 6 GB is recommended.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
            if (!translation.busy && translation.message != null && translation.message != translation.error) {
                Text(translation.message, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
            translation.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun packRow(
    lang: String,
    kind: String,
    onPhone: Map<String, PackManifest>,
    builtInIds: Set<String>,
    catalogById: Map<String, CatalogEntry>,
    downloads: Map<String, MainViewModel.DownloadUi>,
): PackRow {
    val id = "$lang-$kind"
    return PackRow(kind, onPhone[id], id in builtInIds, catalogById[id], downloads[id])
}

/** Compared by value, so a card whose pack hasn't changed is skipped when another card's progress ticks. */
@Immutable
private data class PackRow(
    val kind: String,
    val installed: PackManifest?,
    val builtIn: Boolean,
    val available: CatalogEntry?,
    val download: MainViewModel.DownloadUi?,
)

private enum class Phase { BuiltIn, Installed, Downloading, Available, Unavailable }

/** What one pack line shows. [phase] is the cross-fade key; the rest may change without a cross-fade (progress ticks). */
@Immutable
private data class Line(
    val phase: Phase,
    val status: String,
    val progress: Float,
    val indeterminate: Boolean,
    val error: String?,
    val retry: Boolean,
    val busy: Boolean,
    val anyDownloading: Boolean,
)

private fun PackRow.toLine(busy: Boolean, anyDownloading: Boolean): Line {
    val d = download
    val downloading = d != null && d.error == null
    val phase = when {
        installed != null -> if (builtIn) Phase.BuiltIn else Phase.Installed
        downloading -> Phase.Downloading
        available != null -> Phase.Available
        else -> Phase.Unavailable
    }
    val status = when {
        installed != null -> "%.1f MB · %s".format(installed.size / 1e6, if (builtIn) "built in" else "installed")
        d != null && downloading && d.total > 0 -> "Downloading %.0f of %.0f MB".format(d.downloaded / 1e6, d.total / 1e6)
        downloading -> "Starting download…"
        available != null -> "Not installed · %.0f MB download".format(available.zipSize / 1e6)
        else -> "Not available"
    }
    return Line(
        phase = phase,
        status = status,
        progress = if (d != null && d.total > 0) (d.downloaded.toFloat() / d.total).coerceIn(0f, 1f) else 0f,
        indeterminate = d == null || d.total <= 0,
        error = d?.error,
        retry = d?.error != null,
        busy = busy,
        anyDownloading = anyDownloading,
    )
}

@Composable
private fun LanguageCard(
    lang: String,
    speak: PackRow,
    listen: PackRow,
    busy: Boolean,
    anyDownloading: Boolean,
    selectedSpeak: Boolean,
    selectedListen: Boolean,
    canSelect: Boolean,
    onSelectSpeak: () -> Unit,
    onSelectListen: () -> Unit,
    onDelete: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val any = listOf(speak, listen).firstNotNullOfOrNull {
        it.installed?.let { m -> m.native to m.name } ?: it.available?.let { e -> e.native to e.name }
    }
    Card(modifier = modifier.fillMaxWidth().semantics { this.selected = selectedSpeak || selectedListen }) {
        // A row gaining a progress bar or an error line eases the card taller instead of jumping.
        Column(Modifier.padding(12.dp).animateContentSize(Motion.gentle()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(any?.first ?: nativeName(lang), fontFamily = scriptFont(lang),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(any?.second ?: englishName(lang), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedSpeak) {
                    Text("Speaking", color = Palette.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                } else {
                    PackAction("Speak this", filled = false, enabled = canSelect && speak.installed != null, onClick = onSelectSpeak)
                }
                if (selectedListen) {
                    Text("Listening", color = Palette.Accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                } else {
                    PackAction("Hear this", filled = false, enabled = canSelect, onClick = onSelectListen)
                }
            }
            if (selectedListen && listen.installed == null) {
                Text("Install its voice for spoken playback. Without a voice, you'll see text.", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
            }
            for (row in listOf(speak, listen)) {
                PackLine("$lang-${row.kind}", row, busy, anyDownloading, onDelete, onDownload, onCancel)
            }
        }
    }
}

@Composable
private fun PackLine(
    id: String,
    row: PackRow,
    busy: Boolean,
    anyDownloading: Boolean,
    onDelete: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val line = row.toLine(busy, anyDownloading)
    // Kept while the bar / error fades out, when the live values are already gone.
    val bar = rememberLastNonNull(if (line.phase == Phase.Downloading) line.progress to line.indeterminate else null)
    val error = rememberLastNonNull(line.error)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(if (row.kind == PackManifest.KIND_SPEAK) "Speech recognition" else "Spoken voice", fontWeight = FontWeight.Medium)
                HeldFadeSwap(line.phase, line, label = "pack-status") { _, l ->
                    Text(l.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HeldFadeSwap(line.phase, line, contentAlignment = Alignment.CenterEnd, label = "pack-action") { phase, l ->
                when (phase) {
                    Phase.Installed -> PackAction("Delete", filled = false, enabled = !l.busy) { onDelete(id) }
                    Phase.Downloading -> PackAction("Cancel", filled = false, enabled = true, onClick = onCancel)
                    Phase.Available -> PackAction(if (l.retry) "Retry" else "Download", filled = true, enabled = !l.busy && !l.anyDownloading) { onDownload(id) }
                    Phase.BuiltIn, Phase.Unavailable -> Unit
                }
            }
        }
        AnimatedVisibility(
            line.phase == Phase.Downloading,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.gentle()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.gentle()),
        ) {
            bar?.let { (progress, indeterminate) -> DownloadBar(progress, indeterminate) }
        }
        AnimatedVisibility(
            line.error != null,
            enter = fadeIn(Motion.enter()) + expandVertically(Motion.gentle()),
            exit = fadeOut(Motion.exit()) + shrinkVertically(Motion.gentle()),
        ) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Progress updates arrive a few times a second; the bar glides linearly to each one. The animated value is read
 * inside the progress lambda, so a frame redraws the bar and recomposes nothing.
 */
@Composable
private fun DownloadBar(progress: Float, indeterminate: Boolean) {
    if (indeterminate) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        val shown = animateFloatAsState(progress, tween(300, easing = LinearEasing), label = "download-progress")
        LinearProgressIndicator(progress = { shown.value }, modifier = Modifier.fillMaxWidth())
    }
}

/** Small action inside a card: Delete / Cancel (text) or Download / Retry (teal fill); eases down while pressed. */
@Composable
private fun PackAction(label: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    if (filled) {
        Button(
            onClick = onClick,
            modifier = Modifier.pressScale(interaction),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.Accent,
                contentColor = Palette.NavyDeep,
                disabledContainerColor = Palette.Line,
                disabledContentColor = Palette.TextFaint,
            ),
            elevation = null,
            interactionSource = interaction,
        ) { Text(label) }
    } else {
        TextButton(onClick = onClick, modifier = Modifier.pressScale(interaction), enabled = enabled, interactionSource = interaction) { Text(label) }
    }
}
