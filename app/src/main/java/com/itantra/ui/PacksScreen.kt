package com.itantra.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itantra.MainViewModel
import com.itantra.packs.PackManifest

/** Lists built-in and installed language packs; installs from a file (picker) or from adb-sideloaded zips. */
@Composable
fun PacksScreen(
    packs: MainViewModel.PacksUi,
    onBack: () -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onRescan: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val builtInIds = packs.builtIn.map { it.id }.toSet()
    val byLang = (packs.builtIn + packs.installed).groupBy { it.lang }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Language packs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(
            "Speak pack = understand your speech in that language. Listen pack = hear that language spoken.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                enabled = !packs.busy,
                modifier = Modifier.weight(1f),
            ) { Text("Import pack…") }
            OutlinedButton(onClick = onRescan, enabled = !packs.busy, modifier = Modifier.weight(1f)) {
                Text("Check sideloaded")
            }
        }
        if (packs.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        packs.message?.let {
            Text(
                it,
                color = if (packs.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(byLang.entries.sortedBy { (lang, list) -> list.first().packetCode }.toList(), key = { it.key }) { (_, list) ->
                LanguageCard(list, builtInIds, packs.busy, onDelete)
            }
            item {
                Text(
                    "Sideload with a PC: adb push <pack>.zip ${packs.incomingPath.ifEmpty { "/sdcard/Android/data/com.itantra/files/incoming" }}/ " +
                        "then tap \"Check sideloaded\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LanguageCard(packs: List<PackManifest>, builtInIds: Set<String>, busy: Boolean, onDelete: (String) -> Unit) {
    val first = packs.first()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${first.native} · ${first.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            for (kind in listOf(PackManifest.KIND_SPEAK, PackManifest.KIND_LISTEN)) {
                val pack = packs.firstOrNull { it.kind == kind }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(if (kind == PackManifest.KIND_SPEAK) "Speak" else "Listen", fontWeight = FontWeight.Medium)
                        Text(
                            pack?.let { "%.1f MB · %s%s".format(it.size / 1e6, it.engine.type, if (it.id in builtInIds) " · built in" else "") }
                                ?: "not installed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pack != null && pack.id !in builtInIds) {
                        TextButton(onClick = { onDelete(pack.id) }, enabled = !busy) { Text("Delete") }
                    }
                }
            }
        }
    }
}
