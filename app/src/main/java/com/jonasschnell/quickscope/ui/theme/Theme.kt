package com.jonasschnell.quickscope.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Black = Color(0xFF000000)
/** Gemeinsamer Satz Flaechen: Seite (Black), Sheet/Dialog (Sheet), Karte auf Schwarz (Card), erhoehte Flaeche mit Rand (SurfaceRaised). */
val Card = Color(0xFF1E1E1E)
val CardHigh = Color(0xFF2B2B2B)
val Sheet = Color(0xFF141414)
val SurfaceRaised = CardHigh
/** Duenner Rand um erhoehte Flaechen (Zeilen, Karten, Felder, Chips, Sekundaerknoepfe). */
val Outline = Color(0xFF454545)
/** Gedrueckter Zustand einer erhoehten Flaeche. */
val Pressed = Color(0xFF3A3A3A)
/** Rand der Danger-Zeilen. */
val DangerOutline = Color(0xFF8A3B3B)
val Accent = Color(0xFFD4FF3A)
val OnAccent = Color(0xFF000000)
val Muted = Color(0xFF9E9EA4)
val Danger = Color(0xFFFF6B6B)
val Warn = Color(0xFFFFC857)
/** Warmes Orange fuer "nicht ganz": sanft statt hartem Rot. */
val Soft = Color(0xFFFF9F5A)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    secondary = Accent,
    onSecondary = OnAccent,
    background = Black,
    onBackground = Color.White,
    surface = Black,
    onSurface = Color.White,
    surfaceVariant = CardHigh,
    onSurfaceVariant = Muted,
    surfaceContainer = Card,
    surfaceContainerHigh = CardHigh,
    surfaceContainerHighest = CardHigh,
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF1F1F1F),
    error = Danger,
    onError = Color.Black,
    primaryContainer = CardHigh,
    onPrimaryContainer = Color.White,
    secondaryContainer = CardHigh,
    onSecondaryContainer = Color.White,
    tertiary = Accent,
    onTertiary = OnAccent,
    tertiaryContainer = CardHigh,
    onTertiaryContainer = Color.White,
    errorContainer = CardHigh,
    onErrorContainer = Danger,
    surfaceDim = Black,
    surfaceBright = CardHigh,
    surfaceContainerLowest = Black,
    surfaceContainerLow = Sheet,
    surfaceTint = Accent,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    inversePrimary = Accent,
    scrim = Color.Black,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun QuickscopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes) {
        // Surface setzt LocalContentColor = onBackground (weiss), sonst waere Text ausserhalb von Surfaces schwarz auf schwarz.
        Surface(modifier = Modifier.fillMaxSize(), color = Black, contentColor = Color.White, content = content)
    }
}
