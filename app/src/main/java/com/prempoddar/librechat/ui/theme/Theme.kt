package com.prempoddar.librechat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prempoddar.librechat.R
import com.prempoddar.librechat.ui.design.AppTheme
import com.prempoddar.librechat.ui.design.Appearance

/** Colours Material doesn't have names for. */
@Immutable
data class Signal(
    val phosphor: Color,
    val amber: Color,
    val danger: Color,
    val line: Color,
    val faint: Color,
    val mine: Color,
    val theirs: Color,
    val radarGlow: Color,
)

/** LibreChat's own colour: signal green. */
const val LIBRE_BRAND = 0x0E9F55

val LocalSignal = staticCompositionLocalOf {
    Signal(Color.Green, Color.Yellow, Color.Red, Color.Gray, Color.Gray, Color.Green, Color.White, Color.Green)
}

@OptIn(ExperimentalTextApi::class)
private fun grotesk(w: Int) = Font(R.font.space_grotesk, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

@OptIn(ExperimentalTextApi::class)
private fun mono(w: Int) = Font(R.font.jetbrains_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Grotesk = FontFamily(grotesk(400), grotesk(500), grotesk(600), grotesk(700))
/** Ids, safety numbers, hop counts: anything a person might read out or compare. */
val Mono = FontFamily(mono(400), mono(600))

val MonoSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight(400), fontSize = 11.sp, letterSpacing = 0.02.em)
val MonoLabel = TextStyle(fontFamily = Mono, fontWeight = FontWeight(600), fontSize = 11.sp, letterSpacing = 0.12.em)

private val Type = Typography(
    displaySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(700), fontSize = 34.sp, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(700), fontSize = 28.sp, letterSpacing = (-0.01).em),
    headlineSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(700), fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(400), fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(400), fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(400), fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), fontSize = 15.sp),
    labelMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight(600), fontSize = 10.sp, letterSpacing = 0.1.em),
)

@Composable
fun LibreTheme(appearance: Appearance, content: @Composable () -> Unit) {
    AppTheme(appearance, LIBRE_BRAND, Type) {
        val c = MaterialTheme.colorScheme
        // The signal colours, taken from the theme so they always contrast with what they sit on.
        val signal = Signal(
            phosphor = c.primary, amber = c.tertiary, danger = c.error, line = c.outlineVariant,
            faint = c.onSurfaceVariant, mine = c.primaryContainer, theirs = c.surfaceContainerHigh,
            radarGlow = c.primary.copy(alpha = 0.2f),
        )
        CompositionLocalProvider(LocalSignal provides signal, content = content)
    }
}
