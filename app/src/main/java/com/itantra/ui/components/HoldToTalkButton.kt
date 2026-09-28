package com.itantra.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itantra.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

enum class TalkButtonState { Idle, Listening, Processing, NoLink }

/**
 * The hold-to-talk button (128 dp in a 176 dp touch area). State is shown by shape, line and colour, never glow:
 * Idle = teal disc; Listening = slightly smaller disc inside a moving ring of level marks; Processing = dark disc
 * with a turning arc; NoLink = dashed outline, crossed-out mic.
 *
 * [onPressStart] runs on touch-down and returns whether talking started (false e.g. after asking for the mic
 * permission); only then is [onPressEnd] called when the finger lifts.
 */
@Composable
fun HoldToTalkButton(
    state: TalkButtonState,
    onPressStart: () -> Boolean,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Hold to talk",
) {
    val start by rememberUpdatedState(onPressStart)
    val end by rememberUpdatedState(onPressEnd)
    val pressable = state == TalkButtonState.Idle || state == TalkButtonState.Listening
    val scale by animateFloatAsState(if (state == TalkButtonState.Listening) 0.94f else 1f, label = "talk-scale")

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(176.dp)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .pointerInput(pressable) {
                if (!pressable) return@pointerInput
                detectTapGestures(onPress = {
                    if (start()) {
                        tryAwaitRelease()
                        end()
                    }
                })
            },
    ) {
        when (state) {
            TalkButtonState.Listening -> LevelRing()
            TalkButtonState.Processing -> TurningArc()
            else -> Unit
        }
        val disc = Modifier.size(128.dp).scale(scale).clip(CircleShape)
        when (state) {
            TalkButtonState.Idle, TalkButtonState.Listening ->
                Box(disc.background(Palette.Accent), contentAlignment = Alignment.Center) {
                    if (state == TalkButtonState.Idle) MicIcon(Palette.NavyDeep, 44.dp) else WaveIcon(Palette.NavyDeep)
                }
            TalkButtonState.Processing ->
                Box(disc.background(Palette.TealDark).border(1.5.dp, Palette.Accent, CircleShape), contentAlignment = Alignment.Center) {
                    DotsIcon(Palette.Mint)
                }
            TalkButtonState.NoLink ->
                Box(disc, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            Palette.LineDashed, radius = size.minDimension / 2 - 1.dp.toPx(),
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                        )
                    }
                    MicIcon(Palette.TextFaint, 44.dp, crossed = true)
                }
        }
    }
}

/** 64 radial marks whose lengths ripple while the user is speaking. */
@Composable
private fun LevelRing() {
    val t by rememberInfiniteTransition(label = "ring").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "ring-t",
    )
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r1 = 70.dp.toPx()
        for (i in 0 until 64) {
            val a = i / 64f * 2 * PI.toFloat()
            val amp = (3 + 9 * abs(sin(i * 1.7f + t) * cos(i * 0.45f - t * 0.6f))).dp.toPx()
            drawLine(
                Palette.Accent,
                Offset(c.x + r1 * cos(a), c.y + r1 * sin(a)),
                Offset(c.x + (r1 + amp) * cos(a), c.y + (r1 + amp) * sin(a)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun TurningArc() {
    val angle by rememberInfiniteTransition(label = "arc").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "arc-angle",
    )
    Canvas(Modifier.fillMaxSize()) {
        val r = 76.dp.toPx()
        val topLeft = Offset(center.x - r, center.y - r)
        val arcSize = androidx.compose.ui.geometry.Size(2 * r, 2 * r)
        drawCircle(Palette.Line, radius = r, style = Stroke(1.5.dp.toPx()))
        rotate(angle) {
            drawArc(Palette.Accent, -90f, 90f, false, topLeft, arcSize, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/** Line mic icon (24-unit grid, like the design's inline SVG). */
@Composable
fun MicIcon(color: Color, size: Dp, crossed: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val w = 1.6f * u
        drawRoundRect(
            color, topLeft = Offset(9 * u, 3 * u), size = androidx.compose.ui.geometry.Size(6 * u, 11 * u),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * u), style = Stroke(w),
        )
        drawArc(color, 0f, 180f, false, Offset(5 * u, 4 * u), androidx.compose.ui.geometry.Size(14 * u, 14 * u), style = Stroke(w, cap = StrokeCap.Round))
        drawLine(color, Offset(12 * u, 18 * u), Offset(12 * u, 21 * u), w, StrokeCap.Round)
        if (crossed) drawLine(color, Offset(3 * u, 3 * u), Offset(21 * u, 21 * u), w, StrokeCap.Round)
    }
}

@Composable
private fun WaveIcon(color: Color) {
    Canvas(Modifier.size(48.dp, 28.dp)) {
        val u = size.width / 48f
        val bars = listOf(4f to 3f, 11f to 8f, 18f to 12f, 25f to 6f, 32f to 10f, 39f to 5f, 46f to 2f)
        for ((x, half) in bars) waveBar(color, x * u, half * u, 2.4f * u)
    }
}

private fun DrawScope.waveBar(color: Color, x: Float, half: Float, width: Float) =
    drawLine(color, Offset(x, center.y - half), Offset(x, center.y + half), width, StrokeCap.Round)

@Composable
private fun DotsIcon(color: Color) {
    Canvas(Modifier.size(44.dp, 10.dp)) {
        val r = 4.dp.toPx()
        listOf(1f, 0.7f, 0.4f).forEachIndexed { i, alpha ->
            drawCircle(color.copy(alpha = alpha), r, Offset(r + i * (size.width - 2 * r) / 2, center.y))
        }
    }
}
