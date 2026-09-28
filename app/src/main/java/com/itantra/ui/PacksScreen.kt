package com.itantra.ui

import android.net.ConnectivityManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itantra.MainViewModel
import com.itantra.packs.CatalogEntry
import com.itantra.packs.PackManifest

/**
 * Every language iTantra supports, with a speak and a listen pack each: built in, installed (Delete),
 * downloading (progress + Cancel) or available (Download). Sideloading and file import stay available offline.
 */
@Composable
fun PacksScreen(
    packs: MainViewModel.PacksUi,
    onBack: () -> Unit,
    onImport: (Uri) -> Unit,
    onRescan: () -> Unit,
    onDelete: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onRefreshCatalog: () -> Unit,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    // Ask before a big download on mobile data; on Wi-Fi just start.
    var confirm by remember { mutableStateOf<Pair<String, CatalogEntry>?>(null) }
    fun requestDownload(id: String, entry: CatalogEntry) {
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        if (metered) confirm = id to entry else onDownload(id)
    }
    confirm?.let { (id, entry) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Download on mobile data?") },
            text = { Text("${entry.name} ${entry.kind} is %.0f MB. Wi-Fi is recommended.".format(entry.zipSize / 1e6)) },
            confirmButton = { TextButton(onClick = { confirm = null; onDownload(id) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    val builtInIds = packs.builtIn.map { it.id }.toSet()
    val onPhone = (packs.builtIn + packs.installed).associateBy { it.id }
    val catalogById = packs.catalog.toMap()
    // Languages: everything in the catalogue plus anything installed that the catalogue doesn't know.
    val languages = (packs.catalog.map { it.second.lang to it.second.packetCode } +
        onPhone.values.map { it.lang to it.packetCode }).distinct().sortedBy { it.second }.map { it.first }
    val downloading = packs.downloads.any { it.value.error == null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Language packs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(
            "Speak = understand your speech in that language. Listen = hear that language spoken. " +
                "Downloaded once, then they work offline.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (packs.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        packs.message?.let {
            Text(
                it,
                color = if (packs.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(languages, key = { it }) { lang ->
                val rows = listOf(PackManifest.KIND_SPEAK, PackManifest.KIND_LISTEN).map { kind ->
                    val id = "$lang-$kind"
                    PackRow(kind, onPhone[id], id in builtInIds, catalogById[id], packs.downloads[id])
                }
                LanguageCard(lang, rows, packs.busy, downloading,
                    onDelete = onDelete,
                    onDownload = { id -> catalogById[id]?.let { requestDownload(id, it) } },
                    onCancel = onCancelDownload)
            }
            item {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Without internet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                            enabled = !packs.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Import pack…") }
                        OutlinedButton(onClick = onRescan, enabled = !packs.busy, modifier = Modifier.weight(1f)) {
                            Text("Check sideloaded")
                        }
                    }
                    Text(
                        "From a PC: adb push <pack>.zip ${packs.incomingPath.ifEmpty { "/sdcard/Android/data/com.itantra/files/incoming" }}/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRefreshCatalog, enabled = !packs.busy && !downloading) {
                        Text("Check for new packs (internet)")
                    }
                }
            }
        }
    }
}

private data class PackRow(
    val kind: String,
    val installed: PackManifest?,
    val builtIn: Boolean,
    val available: CatalogEntry?,
    val download: MainViewModel.DownloadUi?,
)

@Composable
private fun LanguageCard(
    lang: String,
    rows: List<PackRow>,
    busy: Boolean,
    anyDownloading: Boolean,
    onDelete: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val any = rows.firstNotNullOfOrNull { it.installed?.let { m -> m.native to m.name } ?: it.available?.let { e -> e.native to e.name } }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(any?.let { "${it.first} · ${it.second}" } ?: lang, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            for (row in rows) {
                val id = "$lang-${row.kind}"
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(if (row.kind == PackManifest.KIND_SPEAK) "Speak" else "Listen", fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    row.installed != null -> "%.1f MB · %s".format(row.installed.size / 1e6,
                                        if (row.builtIn) "built in" else "installed")
                                    row.download != null && row.download.error == null && row.download.total > 0 ->
                                        "Downloading %.0f of %.0f MB".format(row.download.downloaded / 1e6, row.download.total / 1e6)
                                    row.download != null && row.download.error == null -> "Starting download…"
                                    row.available != null -> "Not installed · %.0f MB download".format(row.available.zipSize / 1e6)
                                    else -> "Not available"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when {
                            row.installed != null && !row.builtIn ->
                                TextButton(onClick = { onDelete(id) }, enabled = !busy) { Text("Delete") }
                            row.download != null && row.download.error == null ->
                                TextButton(onClick = onCancel) { Text("Cancel") }
                            row.installed == null && row.available != null ->
                                Button(onClick = { onDownload(id) }, enabled = !busy && !anyDownloading) {
                                    Text(if (row.download?.error != null) "Retry" else "Download")
                                }
                        }
                    }
                    val d = row.download
                    if (d != null && d.error == null) {
                        if (d.total > 0) LinearProgressIndicator(progress = { d.downloaded.toFloat() / d.total }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    d?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
