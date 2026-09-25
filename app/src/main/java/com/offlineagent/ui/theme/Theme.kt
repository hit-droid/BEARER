package com.offlineagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF5BC0BE),
    secondary = Color(0xFF9BC1BC),
    tertiary = Color(0xFFFFD166),
    background = Color(0xFF0B132B),
    surface = Color(0xFF1C2541),
    onPrimary = Color(0xFF06231F),
    onBackground = Color(0xFFEAEAEA),
    onSurface = Color(0xFFEAEAEA),
)

private val LightPalette = lightColorScheme(
    primary = Color(0xFF0B7A78),
    secondary = Color(0xFF4F7C79),
    tertiary = Color(0xFFB5840A),
)

@Composable
fun OfflineAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkPalette else LightPalette,
        content = content,
    )
}
