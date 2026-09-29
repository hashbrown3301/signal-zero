package com.itantra.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.itantra.R
import com.itantra.ui.theme.Motion
import com.itantra.ui.theme.Palette
import com.itantra.ui.theme.pressScale

/** "SECTION TITLE" in small spaced capitals. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = Palette.TextMuted) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

/** A 1 dp horizontal rule. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = Palette.Line) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Thin rule with a word in the middle ("or"). */
@Composable
fun RuleWithText(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Rule(Modifier.weight(1f))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        Rule(Modifier.weight(1f))
    }
}

/** Small outlined tag, e.g. "Hotspot". */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Palette.Accent,
        modifier = modifier
            .border(1.dp, Palette.Teal, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

data class TabItem(val label: String, @DrawableRes val icon: Int)

/** Equal-width tabs on a thin rule; the selected one gets a teal underline that grows in. */
@Composable
fun UnderlineTabs(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Box(modifier.fillMaxWidth()) {
        Rule(Modifier.align(Alignment.BottomStart))
        Row(Modifier.fillMaxWidth()) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                val interaction = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (on) Palette.Accent else Palette.TextMuted, Motion.enter(), label = "tab-tint")
                val text by animateColorAsState(if (on) Palette.OffWhite else Palette.TextMuted, Motion.enter(), label = "tab-text")
                val line by animateDpAsState(if (on) 2.dp else 0.dp, Motion.snappy(), label = "tab-line")
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .semantics { role = Role.Tab; this.selected = on }
                        .clickable(interaction, indication = null) {
                            if (!on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier.pressScale(interaction, 0.94f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(painterResource(item.icon), null, Modifier.size(20.dp), tint = tint)
                        Text(
                            item.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = text,
                        )
                    }
                    Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).fillMaxWidth().height(line).background(Palette.Accent))
                }
            }
        }
    }
}

enum class Tab(val label: String, @DrawableRes val icon: Int) {
    Home("Home", R.drawable.ic_home),
    Talk("Talk", R.drawable.ic_mic),
    Languages("Languages", R.drawable.ic_languages),
    Metrics("Metrics", R.drawable.ic_metrics),
}

/**
 * Bottom navigation: line icons with short labels, a thin rule above, teal for the current tab. The indicator
 * springs open, colours cross-fade, a press eases the item down, and switching tabs gives a light haptic tick.
 */
@Composable
fun BottomNav(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth().background(Palette.NavyDeep)) {
        Rule()
        Row(Modifier.fillMaxWidth().height(72.dp)) {
            Tab.entries.forEach { tab ->
                val on = tab == current
                val interaction = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (on) Palette.Accent else Palette.TextMuted, Motion.enter(), label = "nav-tint")
                val indicator by animateDpAsState(if (on) 28.dp else 0.dp, Motion.snappy(), label = "nav-indicator")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .height(72.dp)
                        .semantics { role = Role.Tab; selected = on }
                        .clickable(interaction, indication = null) {
                            if (!on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(tab)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.align(Alignment.TopCenter).width(indicator).height(2.dp).background(Palette.Accent, RoundedCornerShape(1.dp)))
                    Column(
                        Modifier.pressScale(interaction, 0.92f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = tint)
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The screen background: a soft top-down wash from navy into the deep navy, then the faint brand-gradient disc
 * and outline circle in the top-right corner. Static drawing only (no animation), so it costs nothing per frame.
 */
fun Modifier.brandBackdrop(): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(0f to Palette.Navy, 0.45f to Palette.NavyDeep, 1f to Palette.NavyDeep))
    val big = 300.dp.toPx()
    val c = Offset(size.width + 300.dp.toPx() - big * 0.9f, -80.dp.toPx())
    drawCircle(
        Brush.linearGradient(listOf(Palette.Navy, Palette.TealDark, Palette.Accent), Offset(c.x - big, c.y - big), Offset(c.x + big, c.y + big)),
        radius = big, center = c, alpha = 0.14f,
    )
    drawCircle(Palette.Line, radius = 190.dp.toPx(), center = Offset(c.x + 20.dp.toPx(), c.y - 10.dp.toPx()), alpha = 0.6f, style = Stroke(1.dp.toPx()))
}
