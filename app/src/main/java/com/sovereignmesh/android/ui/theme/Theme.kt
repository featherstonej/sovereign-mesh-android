package com.sovereignmesh.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CryptoGreen,
    secondary = CryptoTeal,
    tertiary = TacticalAmber,
    background = StealthBackground,
    surface = StealthSurface,
    surfaceVariant = StealthSurfaceVariant,
    error = TacticalRed,
    onPrimary = TextDark,
    onSecondary = TextDark,
    onTertiary = TextDark,
    onBackground = TextLight,
    onSurface = TextLight,
    onError = TextLight
)

@Composable
fun SovereignTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
