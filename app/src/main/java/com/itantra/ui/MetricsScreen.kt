package com.itantra.ui

import android.os.Debug
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.itantra.MainViewModel
import com.itantra.packs.PackManifest
import com.itantra.session.Direction
import com.itantra.session.Message
import com.itantra.ui.components.BrandBar
import com.itantra.ui.components.Rule
import com.itantra.ui.theme.DataText
import com.itantra.ui.theme.Palette
import com.itantra.ui.theme.scriptFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Metrics as an editorial table: latency percentiles from this session's messages, link sizes, this phone's memory
 * and model sizes, and the per-language accuracy measured in CI. A value the app doesn't have yet shows "–".
 */
@Composable
fun MetricsScreen(ui: MainViewModel.UiState) {
    val messages = ui.session.messages
    val outgoing = messages.filter { it.direction == Direction.OUTGOING }
    // This app's memory (PSS), refreshed every few seconds.
    val pssMb by produceState<Long?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { Debug.getPss() / 1024 }
            delay(5_000)
        }
    }
    val onPhone = ui.packs.builtIn + ui.packs.installed
    val speak = onPhone.firstOrNull { it.lang == ui.myLanguage && it.kind == PackManifest.KIND_SPEAK }
    val listen = onPhone.firstOrNull { it.lang == ui.myLanguage && it.kind == PackManifest.KIND_LISTEN }

    Column(Modifier.fillMaxSize()) {
        BrandBar()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Text("Metrics", style = MaterialTheme.typography.headlineLarge)
            Text(
                if (messages.isEmpty()) "No messages yet this session" else "This session · ${messages.size} messages",
                style = DataText, color = Palette.TextMuted, modifier = Modifier.padding(top = 8.dp),
            )

            Section("Latency", "p50", "p90")
            PercentileRow("Round trip (RTT)", outgoing.mapNotNull { it.rttMs } + listOfNotNull(ui.session.rttMs))
            PercentileRow("Release → heard", outgoing.mapNotNull { it.endToEndMs })
            PercentileRow("Speech → text", messages.filter { it.direction != Direction.INCOMING }.mapNotNull { it.sttMs })
            PercentileRow("Text → voice", messages.filter { it.direction != Direction.OUTGOING }.mapNotNull { it.ttsMs })
            Note("From this session's messages on this phone. Talk a few times to fill these in.")

            Section("Link")
            val sizes = messages.mapNotNull { it.wireBytes }
            ValueRow("Text sent per sentence", sizes.takeIf { it.isNotEmpty() }?.let { "${it.min()}–${it.max()} B" })
            ValueRow("Same sentences as audio", audioRange(messages))
            ValueRow("Packet overhead", "17 B + CRC32")
            ValueRow("Link", if (ui.mode == null || ui.mode == MainViewModel.Mode.SOLO) null else ui.linkTypeLabel())

            Section("Phone")
            ValueRow("RAM used by iTantra", pssMb?.let { "$it MB" })
            ValueRow("Speech model, ${englishName(ui.myLanguage)}", speak?.let { "%.1f MB".format(it.size / 1e6) })
            ValueRow("Voice, ${englishName(ui.myLanguage)}", listen?.let { "%.1f MB".format(it.size / 1e6) })

            Section("Accuracy", "CER", "WER")
            ACCURACY.forEach { (iso, cer, wer) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(nativeName(iso), fontFamily = scriptFont(iso), style = MaterialTheme.typography.bodyLarge)
                        Text("  " + englishName(iso), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    }
                    Cell(cer, Palette.OffWhite)
                    Cell(wer, Palette.TextMuted)
                }
                Rule()
            }
            Note(
                "Character / word error rate on 3 FLEURS test clips per language, measured when the packs were built; " +
                    "lower is better. *Tamil: one clip had numbers spoken as words; 19.8% CER over 10 clips.",
            )
        }
    }
}

@Composable
private fun Section(title: String, col1: String? = null, col2: String? = null) {
    Column(Modifier.padding(top = 30.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.Mint, modifier = Modifier.weight(1f))
            col1?.let { Cell(it, Palette.Mint) }
            col2?.let { Cell(it, Palette.Mint) }
        }
        Rule(color = Palette.Accent)
    }
}

@Composable
private fun Cell(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = DataText.copy(fontSize = DataText.fontSize * 1.1f), color = color, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
}

@Composable
private fun PercentileRow(label: String, values: List<Long>) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Cell(percentile(values, 50)?.let { "$it ms" } ?: "–", if (values.isEmpty()) Palette.TextMuted else Palette.OffWhite)
        Cell(percentile(values, 90)?.let { "$it ms" } ?: "–", Palette.TextMuted)
    }
    Rule()
}

@Composable
private fun ValueRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value ?: "–", style = DataText.copy(fontSize = DataText.fontSize * 1.1f), color = if (value == null) Palette.TextMuted else Palette.OffWhite)
    }
    Rule()
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, modifier = Modifier.padding(top = 10.dp))
}

/** Nearest-rank percentile; null when there is nothing to measure. */
private fun percentile(values: List<Long>, p: Int): Long? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return sorted[((p / 100.0) * sorted.size).toInt().coerceIn(0, sorted.lastIndex)]
}

private fun audioRange(messages: List<Message>): String? {
    val kb = messages.mapNotNull { it.recordedSec?.let { s -> (s * 16_000 * 2 / 1024).toLong() } }
    return kb.takeIf { it.isNotEmpty() }?.let { "${it.min()}–${it.max()} KB" }
}

/** FLEURS results from docs/MODELS.md (3 clips per language). */
private val ACCURACY = listOf(
    Triple("hi", "3.9%", "9.3%"), Triple("en", "14.4%", "18.6%"), Triple("mr", "6.3%", "20.6%"),
    Triple("gu", "11.0%", "28.4%"), Triple("bn", "5.7%", "17.5%"), Triple("ta", "42.7%*", "57.7%*"),
    Triple("te", "1.1%", "8.6%"), Triple("kn", "5.4%", "6.7%"), Triple("ml", "14.2%", "36.8%"),
    Triple("or", "15.3%", "34.3%"),
)
