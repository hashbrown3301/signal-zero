package com.itantra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itantra.ui.theme.Palette

/**
 * The iTantra mark: a ring (the network), two nodes (the two phones: this one filled, the other hollow), a voice
 * pulse between them and, from 48 dp up, two faint yantra triangles. Drawn on a 48-unit grid like the design's SVG.
 */
@Composable
fun ITantraMark(size: Dp, modifier: Modifier = Modifier, detailed: Boolean = size >= 48.dp) {
    Canvas(modifier.size(size).semantics { contentDescription = "iTantra" }) {
        val u = this.size.width / 48f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        if (detailed) {
            val stroke = Stroke(1.2f * u, join = StrokeJoin.Round)
            drawPath(Path().apply { moveTo(24 * u, 6 * u); lineTo(39.6f * u, 33 * u); lineTo(8.4f * u, 33 * u); close() }, Palette.Teal, style = stroke)
            drawPath(Path().apply { moveTo(24 * u, 42 * u); lineTo(8.4f * u, 15 * u); lineTo(39.6f * u, 15 * u); close() }, Palette.Teal, style = stroke)
            drawCircle(Palette.Accent, 22 * u, p(24f, 24f), style = Stroke(1.5f * u))
            for ((x, half) in listOf(17f to 3f, 20.5f to 7f, 24f to 12f, 27.5f to 7f, 31f to 3f)) {
                drawLine(Palette.Mint, p(x, 24 - half), p(x, 24 + half), 2.2f * u, StrokeCap.Round)
            }
            drawCircle(Palette.Accent, 2.6f * u, p(9f, 24f))
            drawCircle(Palette.NavyDeep, 2.6f * u, p(39f, 24f))
            drawCircle(Palette.Accent, 2.6f * u, p(39f, 24f), style = Stroke(1.5f * u))
        } else {
            drawCircle(Palette.Accent, 21.5f * u, p(24f, 24f), style = Stroke(2.6f * u))
            for ((x, half) in listOf(16.5f to 3f, 20.25f to 8f, 24f to 13f, 27.75f to 8f, 31.5f to 3f)) {
                drawLine(Palette.Mint, p(x, 24 - half), p(x, 24 + half), 3.2f * u, StrokeCap.Round)
            }
            drawCircle(Palette.Accent, 3.2f * u, p(9f, 24f))
            drawCircle(Palette.Accent, 3.2f * u, p(39f, 24f))
        }
    }
}

/** "iTantra" with a teal i. */
@Composable
fun Wordmark(style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Palette.Accent)) { append("i") }
            append("Tantra")
        },
        style = style,
        color = Palette.OffWhite,
        modifier = modifier,
    )
}

/** Slim top bar on every screen but Home: small mark, wordmark, and an action on the right. */
@Composable
fun BrandBar(modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ITantraMark(24.dp)
            Wordmark(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), Modifier.weight(1f))
            trailing()
        }
        Rule(color = Palette.LineFaint)
    }
}

/** Home screen header: mark, wordmark and tagline. */
@Composable
fun BrandHeader(modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ITantraMark(52.dp)
        Column(Modifier.weight(1f)) {
            Wordmark(MaterialTheme.typography.headlineMedium)
            Text("Offline voice link", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        trailing()
    }
}
