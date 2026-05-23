package com.wikillm.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * OpenWebUI-style dark palette. The app is dark-only by design (the user asked
 * for an OpenWebUI look), so we don't follow the system theme or use dynamic
 * color — the palette stays consistent on every device.
 */
private val OpenWebUiDark = darkColorScheme(
    primary            = Color(0xFF8AB4F8), // accent: send button, switches
    onPrimary          = Color(0xFF0A2440),
    primaryContainer   = Color(0xFF2C3A4F), // user message bubble
    onPrimaryContainer = Color(0xFFE6EEFB),
    secondary          = Color(0xFFA8C7FA),
    onSecondary        = Color(0xFF0A2440),
    background         = Color(0xFF0F0F0F),
    onBackground       = Color(0xFFECECEC),
    surface            = Color(0xFF1A1A1A), // top bar, cards
    onSurface          = Color(0xFFECECEC),
    surfaceVariant     = Color(0xFF2A2A2A), // assistant message bubble, inputs
    onSurfaceVariant   = Color(0xFFB4B4B4),
    outline            = Color(0xFF444444),
    outlineVariant     = Color(0xFF333333),
    error              = Color(0xFFF2B8B5),
    onError            = Color(0xFF601410),
    errorContainer     = Color(0xFF402422),
    onErrorContainer   = Color(0xFFF9DEDC),
)

@Composable
fun WikiLLMTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OpenWebUiDark, content = content)
}
