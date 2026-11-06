package com.epicwalk.voiceanalyzer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF205C45), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8EBDD), onPrimaryContainer = Color(0xFF123B2A),
    secondary = Color(0xFF506458), secondaryContainer = Color(0xFFE5EDE5),
    tertiary = Color(0xFF626147),
    background = Color(0xFFF8FAF7), surface = Color(0xFFF8FAF7),
    onSurface = Color(0xFF1C2821), onBackground = Color(0xFF1C2821),
    surfaceContainer = Color(0xFFEEF2EC), surfaceContainerLow = Color(0xFFF1F5EF),
    surfaceContainerHighest = Color(0xFFE2E8E0), outlineVariant = Color(0xFFD7DFD5)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CD5B1), onPrimary = Color(0xFF0B3823),
    primaryContainer = Color(0xFF244E37), onPrimaryContainer = Color(0xFFCCEBD4),
    secondary = Color(0xFFB2CBB8), secondaryContainer = Color(0xFF283D30),
    background = Color(0xFF111A15), surface = Color(0xFF111A15),
    onSurface = Color(0xFFE0EAE0), onBackground = Color(0xFFE0EAE0),
    surfaceContainer = Color(0xFF1C2921), surfaceContainerLow = Color(0xFF17231B),
    surfaceContainerHighest = Color(0xFF2D3B31), outlineVariant = Color(0xFF3D4D40)
)
@Composable
fun VoiceAnalyzerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}
