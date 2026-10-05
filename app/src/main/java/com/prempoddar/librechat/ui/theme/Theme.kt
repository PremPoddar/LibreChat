package com.prempoddar.librechat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prempoddar.librechat.R
import com.prempoddar.librechat.data.ThemeMode

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

private val DarkSignal = Signal(
    phosphor = Color(0xFF5CFF9D), amber = Color(0xFFFFB547), danger = Color(0xFFFF5C6C), line = Color(0xFF1E2823),
    faint = Color(0xFF55675E), mine = Color(0xFF173726), theirs = Color(0xFF151D19), radarGlow = Color(0x335CFF9D),
)

private val LightSignal = Signal(
    phosphor = Color(0xFF0E9F55), amber = Color(0xFFB76E00), danger = Color(0xFFD12F45), line = Color(0xFFDCE3DE),
    faint = Color(0xFF94A39B), mine = Color(0xFFD9F5E5), theirs = Color(0xFFFFFFFF), radarGlow = Color(0x220E9F55),
)

val LocalSignal = staticCompositionLocalOf { DarkSignal }

private val Dark = darkColorScheme(
    primary = Color(0xFF5CFF9D), onPrimary = Color(0xFF00210E),
    primaryContainer = Color(0xFF173726), onPrimaryContainer = Color(0xFFB9F7D2),
    secondary = Color(0xFFFFB547), onSecondary = Color(0xFF2A1800),
    background = Color(0xFF0B0F0D), onBackground = Color(0xFFDDE7E1),
    surface = Color(0xFF121815), onSurface = Color(0xFFDDE7E1),
    surfaceVariant = Color(0xFF18201C), onSurfaceVariant = Color(0xFF7F948A),
    surfaceContainer = Color(0xFF121815), surfaceContainerHigh = Color(0xFF18201C), surfaceContainerHighest = Color(0xFF1E2823),
    surfaceContainerLow = Color(0xFF0F1411), surfaceContainerLowest = Color(0xFF0B0F0D),
    outline = Color(0xFF2B3832), outlineVariant = Color(0xFF1E2823), error = Color(0xFFFF5C6C),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0E9F55), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F5E5), onPrimaryContainer = Color(0xFF00391B),
    secondary = Color(0xFFB76E00), onSecondary = Color.White,
    background = Color(0xFFF2F4F1), onBackground = Color(0xFF0E1411),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0E1411),
    surfaceVariant = Color(0xFFEAEEEA), onSurfaceVariant = Color(0xFF5D6B64),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF6F8F5), surfaceContainerHighest = Color(0xFFEAEEEA),
    surfaceContainerLow = Color(0xFFF6F8F5), surfaceContainerLowest = Color(0xFFFFFFFF),
    outline = Color(0xFFC6D0CA), outlineVariant = Color(0xFFDCE3DE), error = Color(0xFFD12F45),
)

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

private val ShapeSet = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isDark(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun LibreTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    CompositionLocalProvider(LocalSignal provides if (dark) DarkSignal else LightSignal) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = ShapeSet, content = content)
    }
}
