package com.itantra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itantra.ui.theme.LinkColors
import com.itantra.ui.theme.Palette

/** What the link between the two phones is doing. Solo has no link and is shown in neutral colours. */
enum class LinkState { Connected, Connecting, Lost, Solo }

fun LinkState.color(): Color = when (this) {
    LinkState.Connected -> LinkColors.Connected
    LinkState.Connecting -> LinkColors.Connecting
    LinkState.Lost -> LinkColors.Lost
    LinkState.Solo -> Palette.Mint
}

fun LinkState.label(): String = when (this) {
    LinkState.Connected -> "Connected"
    LinkState.Connecting -> "Connecting"
    LinkState.Lost -> "Link lost"
    LinkState.Solo -> "Solo"
}

/**
 * Two phones as nodes: a solid line when connected, fading dots while connecting, a broken line when lost,
 * a faint dashed line in Solo.
 */
@Composable
fun LinkMotif(state: LinkState, modifier: Modifier = Modifier, width: Dp = 40.dp) {
    Canvas(modifier.size(width, 12.dp)) {
        val y = center.y
        val r = 4.dp.toPx()
        val sw = 1.5.dp.toPx()
        val left = Offset(r + sw, y)
        val right = Offset(size.width - r - sw, y)
        val c = if (state == LinkState.Solo) Palette.Line else state.color()
        drawCircle(if (state == LinkState.Solo) Palette.Mint else c, r, left)
        when (state) {
            LinkState.Connected -> drawLine(c, left.copy(x = left.x + r), right.copy(x = right.x - r), sw)
            LinkState.Connecting -> {
                val span = right.x - left.x
                listOf(1f, 0.6f, 0.3f).forEachIndexed { i, a ->
                    drawCircle(c.copy(alpha = a), 1.5.dp.toPx(), Offset(left.x + span * (0.3f + 0.2f * i), y))
                }
            }
            LinkState.Lost -> {
                val mid = (left.x + right.x) / 2
                val gap = 3.dp.toPx()
                drawLine(c, left.copy(x = left.x + r), Offset(mid - 2 * gap, y), sw)
                drawLine(c, Offset(mid + 2 * gap, y), right.copy(x = right.x - r), sw)
                drawLine(c, Offset(mid - gap, y - gap), Offset(mid + gap, y + gap), sw)
                drawLine(c, Offset(mid + gap, y - gap), Offset(mid - gap, y + gap), sw)
            }
            LinkState.Solo -> drawLine(
                c, left.copy(x = left.x + r), right.copy(x = right.x - r), sw,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
            )
        }
        val dashed = state == LinkState.Connecting
        drawCircle(
            c, r, right,
            style = Stroke(sw, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2.5f.dp.toPx(), 2.5f.dp.toPx())) else null),
        )
    }
}

/** A coloured dot plus the state's word, e.g. "● Connected". */
@Composable
fun LinkStatusLabel(state: LinkState, modifier: Modifier = Modifier, text: String = state.label()) {
    val color = state.color()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}
