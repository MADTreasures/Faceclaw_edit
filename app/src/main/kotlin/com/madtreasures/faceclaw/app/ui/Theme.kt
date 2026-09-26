package com.madtreasures.faceclaw.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The glasses' green, used as the phone app's accent. */
val GlassGreen = Color(0xFF3CFF5A)

private val scheme = darkColorScheme(
    primary = GlassGreen,
    onPrimary = Color(0xFF002106),
    primaryContainer = Color(0xFF0F3A17),
    onPrimaryContainer = Color(0xFFB7F5BE),
    secondary = Color(0xFF9CD6A3),
    onSecondary = Color(0xFF002106),
    background = Color(0xFF050806),
    onBackground = Color(0xFFE2E7E1),
    surface = Color(0xFF0B100C),
    onSurface = Color(0xFFE2E7E1),
    surfaceVariant = Color(0xFF151D17),
    onSurfaceVariant = Color(0xFFB9C4B8),
    surfaceContainer = Color(0xFF111713),
    surfaceContainerHigh = Color(0xFF172019),
    outline = Color(0xFF3E4A3F),
    error = Color(0xFFFFB4AB),
)

@Composable
fun FaceclawTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
