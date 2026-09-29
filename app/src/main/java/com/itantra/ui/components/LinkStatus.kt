package com.itantra.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itantra.ui.theme.LinkColors
import com.itantra.ui.theme.Motion
import com.itantra.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.sin

/** How the link between the two phones looks (green / yellow / red). Solo has no link and is shown in neutral colours. */
enum class LinkLook { Connected, Connecting, Lost, Solo }

fun LinkLook.color(): Color = when (this) {
    LinkLook.Connected -> LinkColors.Connected
    LinkLook.Connecting -> LinkColors.Connecting
    LinkLook.Lost -> LinkColors.Lost
    LinkLook.Solo -> Palette.Mint
}

fun LinkLook.label(): String = when (this) {
    LinkLook.Connected -> "Connected"
    LinkLook.Connecting -> "Connecting"
    LinkLook.Lost -> "Link lost"
    LinkLook.Solo -> "Solo"
}

/**
 * Two phones as nodes: a solid line when connected, dots swelling in turn while connecting, a broken line when lost,
 * a faint dashed line in Solo. A change of state cross-fades.
 */
@Composable
fun LinkMotif(state: LinkLook, modifier: Modifier = Modifier, width: Dp = 40.dp) {
    Crossfade(state, modifier, Motion.enter(), label = "link-motif") { look -> LinkMotifCanvas(look, width) }
}

/** One state's drawing. The infinite transition exists only while this is Connecting (and for its 220 ms fade-out). */
@Composable
private fun LinkMotifCanvas(state: LinkLook, width: Dp) {
    // Connecting: the dots swell one after another, left to right, like a loading indicator.
    val dots = if (width >= 80.dp) 5 else 3
    val phase = if (state == LinkLook.Connecting) {
        rememberInfiniteTransition(label = "link-dots").animateFloat(
            0f, dots.toFloat(), infiniteRepeatable(tween(260 * dots, easing = LinearEasing)), label = "link-dots-phase",
        )
    } else null
    // Dash patterns are built once, not per frame.
    val density = LocalDensity.current
    val ringDash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.5.dp.toPx())) } }
    val soloDash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())) } }
    Canvas(Modifier.size(width, 12.dp)) {
        val y = center.y
        val r = 4.dp.toPx()
        val sw = 1.5.dp.toPx()
        val left = Offset(r + sw, y)
        val right = Offset(size.width - r - sw, y)
        val c = if (state == LinkLook.Solo) Palette.Line else state.color()
        drawCircle(if (state == LinkLook.Solo) Palette.Mint else c, r, left)
        when (state) {
            LinkLook.Connected -> drawLine(c, left.copy(x = left.x + r), right.copy(x = right.x - r), sw)
            LinkLook.Connecting -> {
                val p = phase?.value ?: 0f  // read while drawing: a frame redraws this canvas, it never recomposes
                val from = left.x + r + 4.dp.toPx()
                val step = (right.x - r - 4.dp.toPx() - from) / (dots - 1)
                for (i in 0 until dots) {
                    val d = ((p - i) % dots + dots) % dots  // how far the wave is past dot i
                    val swell = if (d < 1f) sin(PI.toFloat() * d) else 0f
                    drawCircle(c.copy(alpha = 0.35f + 0.65f * swell), (1.5f + 1.5f * swell).dp.toPx(), Offset(from + step * i, y))
                }
            }
            LinkLook.Lost -> {
                val mid = (left.x + right.x) / 2
                val gap = 3.dp.toPx()
                drawLine(c, left.copy(x = left.x + r), Offset(mid - 2 * gap, y), sw)
                drawLine(c, Offset(mid + 2 * gap, y), right.copy(x = right.x - r), sw)
                drawLine(c, Offset(mid - gap, y - gap), Offset(mid + gap, y + gap), sw)
                drawLine(c, Offset(mid + gap, y - gap), Offset(mid - gap, y + gap), sw)
            }
            LinkLook.Solo -> drawLine(c, left.copy(x = left.x + r), right.copy(x = right.x - r), sw, pathEffect = soloDash)
        }
        drawCircle(c, r, right, style = Stroke(sw, pathEffect = if (state == LinkLook.Connecting) ringDash else null))
    }
}

/** A coloured dot plus the state's word, e.g. "● Connected". Colour eases and the word cross-fades on change. */
@Composable
fun LinkStatusLabel(state: LinkLook, modifier: Modifier = Modifier, text: String = state.label()) {
    val color by animateColorAsState(state.color(), Motion.enter(), label = "link-label-color")
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).drawBehind { drawCircle(color) })
        Crossfade(text, animationSpec = Motion.enter(), label = "link-label-text") {
            Text(it, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}
