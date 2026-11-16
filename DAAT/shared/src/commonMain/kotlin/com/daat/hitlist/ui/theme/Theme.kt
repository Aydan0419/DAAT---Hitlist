package com.daat.hitlist.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Fall palette: bright, warm surfaces with green, gold, orange and yellow, a hint of
// red, and near-black for text and the navigation bar.
val Cream = Color(0xFFFFF8E7)
val Ink = Color(0xFF17130D)
val Leaf = Color(0xFF1B7F3B)
val Pumpkin = Color(0xFFF57C00)
val Sun = Color(0xFFFFC928)
val Maple = Color(0xFFC62828)

/** Accent colours that are used as text on light cards, so they are the darker tones. */
val Green = Leaf
val Gold = Color(0xFFB45F00)

val Panel = Color.White
val PanelHigh = Color(0xFFFFE7B0)
val Mist = Color(0xFF5E5646)

/** Green into gold into orange into red: used sparingly, for the logo and highlights. */
val AutumnBrush = Brush.horizontalGradient(listOf(Leaf, Color(0xFFE0A800), Pumpkin, Maple))

private val Colors = lightColorScheme(
    primary = Leaf,
    onPrimary = Color.White,
    primaryContainer = Ink,
    onPrimaryContainer = Sun,
    secondary = Pumpkin,
    onSecondary = Ink,
    secondaryContainer = Sun,
    onSecondaryContainer = Ink,
    tertiary = Sun,
    onTertiary = Ink,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF6EBCF),
    onSurfaceVariant = Mist,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Panel,
    surfaceContainerHigh = Color(0xFFFFF1CF),
    surfaceContainerHighest = PanelHigh,
    inverseSurface = Ink,
    inverseOnSurface = Cream,
    inversePrimary = Sun,
    outline = Color(0xFFBFAF88),
    outlineVariant = Color(0xFFE8DCBD),
    error = Maple,
    onError = Color.White,
)

private val Type = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = 3.sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    )
}

/** Hitlist uses one bright, warm theme on every phone, with a dark navigation bar. */
@Composable
fun HitlistTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
}
