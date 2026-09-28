package com.itantra.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The iTantra palette (design canvas "iTantra UI", System board). Dark theme only. */
object Palette {
    val Navy = Color(0xFF09203F)
    val NavyDeep = Color(0xFF071A32)
    val TealDark = Color(0xFF0B4C55)
    val Teal = Color(0xFF0F6F70)
    val Accent = Color(0xFF1EAE98)
    val Mint = Color(0xFFDDF4EC)
    val OffWhite = Color(0xFFF4F8F6)
    val TextMuted = Color(0xFFA7C4BF)   // secondary text, about 9:1 on NavyDeep
    val TextFaint = Color(0xFF7FA3A0)   // disabled / de-emphasised, still AA
    val Line = Color(0xFF28505A)
    val LineFaint = Color(0xFF1B3A4A)
    val LineDashed = Color(0xFF4E7D82)
}

/** Link state colours: dot, label and the two-phone line all use the same one. */
object LinkColors {
    val Connected = Color(0xFF3CCB7F)
    val Connecting = Color(0xFFE8C547)
    val Lost = Color(0xFFE5484D)
}

/** Emergency alerts only. Never used anywhere else, so an alert is unmistakable. */
val AlertAmber = Color(0xFFF0A43A)

/** The brand gradient, for large background shapes only (never buttons, text or small parts). */
val BrandGradient = Brush.linearGradient(listOf(Palette.Navy, Palette.TealDark, Palette.Accent))

internal val ITantraColors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.NavyDeep,
    primaryContainer = Palette.Teal,
    onPrimaryContainer = Palette.OffWhite,
    secondary = Palette.Mint,
    onSecondary = Palette.NavyDeep,
    secondaryContainer = Palette.TealDark,
    onSecondaryContainer = Palette.OffWhite,
    tertiary = Palette.Mint,
    onTertiary = Palette.NavyDeep,
    background = Palette.NavyDeep,
    onBackground = Palette.OffWhite,
    surface = Palette.NavyDeep,
    onSurface = Palette.OffWhite,
    surfaceVariant = Palette.Navy,
    onSurfaceVariant = Palette.TextMuted,
    surfaceContainerLowest = Palette.NavyDeep,
    surfaceContainerLow = Palette.Navy,
    surfaceContainer = Palette.Navy,
    surfaceContainerHigh = Palette.Navy,
    surfaceContainerHighest = Palette.TealDark,
    outline = Palette.Line,
    outlineVariant = Palette.LineFaint,
    // Failures are shown in mint text, not red or amber: red means "link lost", amber means "alert".
    error = Palette.Mint,
    onError = Palette.NavyDeep,
    errorContainer = Palette.TealDark,
    onErrorContainer = Palette.OffWhite,
    inverseSurface = Palette.Mint,
    inverseOnSurface = Palette.NavyDeep,
    inversePrimary = Palette.Teal,
    scrim = Color.Black,
)
