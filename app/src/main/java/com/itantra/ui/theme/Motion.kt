package com.itantra.ui.theme

import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Motion tokens: springs for anything the finger moves (critically damped, no visible bounce), short eased fades
 * for content swaps. Compose scales every duration by the system "animation scale", so "Remove animations" in
 * Android settings turns all of this off.
 */
object Motion {
    /** Press feedback, indicators, small toggles. */
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 700f)

    /** Size and position changes of larger content. */
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 300f)

    /** Content fading in (screens, cards, status lines). */
    fun <T> enter(): FiniteAnimationSpec<T> = tween(220, easing = EaseOutCubic)

    /** Content fading out: quicker than [enter], so the new content leads. */
    fun <T> exit(): FiniteAnimationSpec<T> = tween(140, easing = EaseInCubic)
}

/**
 * Shrinks the element slightly while [interactionSource] is pressed. Drawn in a graphics layer, so it costs no
 * layout or recomposition of the content, which keeps it smooth on low-end phones.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, Motion.snappy(), label = "press-scale")
    graphicsLayer { scaleX = scale; scaleY = scale }
}
