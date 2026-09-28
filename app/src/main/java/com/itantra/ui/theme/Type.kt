package com.itantra.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.itantra.R

/*
 * Fonts are bundled (the app is offline): Manrope for Latin, the matching Noto Sans family for each Indian script,
 * JetBrains Mono for numbers. All SIL Open Font License; the licences ship in assets/licenses/OFL-*.txt.
 * Only regular + semi-bold are bundled (about 3 MB); Bold falls back to the semi-bold file.
 */

@OptIn(ExperimentalTextApi::class)  // variationSettings: picks the weight inside the variable font
private fun manrope(weight: Int) = Font(
    R.font.manrope, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope is one variable font file; these are the weights the design uses. */
val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700))

val Mono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

private fun noto(regular: Int, semiBold: Int) =
    FontFamily(Font(regular, FontWeight.Normal), Font(semiBold, FontWeight.SemiBold), Font(semiBold, FontWeight.Bold))

private val Devanagari = noto(R.font.noto_devanagari_regular, R.font.noto_devanagari_semibold)
private val Bengali = noto(R.font.noto_bengali_regular, R.font.noto_bengali_semibold)
private val Gujarati = noto(R.font.noto_gujarati_regular, R.font.noto_gujarati_semibold)
private val Tamil = noto(R.font.noto_tamil_regular, R.font.noto_tamil_semibold)
private val Telugu = noto(R.font.noto_telugu_regular, R.font.noto_telugu_semibold)
private val Kannada = noto(R.font.noto_kannada_regular, R.font.noto_kannada_semibold)
private val Malayalam = noto(R.font.noto_malayalam_regular, R.font.noto_malayalam_semibold)
private val Oriya = noto(R.font.noto_oriya_regular, R.font.noto_oriya_bold)  // Noto Sans Oriya has no SemiBold file

/**
 * The font for text in language [iso] (a transcript line, a language's own name). Compose picks one family per
 * text, so Indian-script text must ask for its script's family; anything else stays in Manrope.
 */
fun scriptFont(iso: String?): FontFamily = when (iso) {
    "hi", "mr" -> Devanagari
    "bn" -> Bengali
    "gu" -> Gujarati
    "ta" -> Tamil
    "te" -> Telugu
    "kn" -> Kannada
    "ml" -> Malayalam
    "or" -> Oriya
    else -> Manrope
}

private fun style(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = spacing.em,
)

internal val ITantraTypography = Typography(
    displaySmall = style(38, 54, FontWeight.Bold),              // alert message
    headlineLarge = style(32, 38, FontWeight.SemiBold, -0.01),  // screen titles
    headlineMedium = style(28, 32, FontWeight.Bold, -0.02),     // wordmark
    headlineSmall = style(24, 30, FontWeight.SemiBold, -0.01),  // section titles ("Connect a phone")
    titleLarge = style(21, 32, FontWeight.Normal),              // transcript lines (1.5× for Indian scripts)
    titleMedium = style(17, 22, FontWeight.SemiBold),           // row titles, peer name
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(15, 22, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(13, 19, FontWeight.Normal),
    labelLarge = style(15, 20, FontWeight.SemiBold),            // buttons
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.SemiBold, 0.1),       // SECTION OVERLINES
)

/** Numbers and technical data (latency, bytes, IP addresses): small, muted, monospace. */
val DataText = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)

/** A large IP address on the Host card. */
val DataLarge = TextStyle(fontFamily = Mono, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em)
