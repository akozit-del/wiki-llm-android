package com.wikillm.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** OpenWebUI-style dark palette. */
private val OpenWebUiDark = darkColorScheme(
    primary            = Color(0xFF8AB4F8),
    onPrimary          = Color(0xFF0A2440),
    primaryContainer   = Color(0xFF2C3A4F), // user message bubble
    onPrimaryContainer = Color(0xFFE6EEFB),
    secondary          = Color(0xFFA8C7FA),
    onSecondary        = Color(0xFF0A2440),
    background         = Color(0xFF0F0F0F),
    onBackground       = Color(0xFFECECEC),
    surface            = Color(0xFF1A1A1A),
    onSurface          = Color(0xFFECECEC),
    surfaceVariant     = Color(0xFF2A2A2A), // assistant bubble, inputs
    onSurfaceVariant   = Color(0xFFB4B4B4),
    outline            = Color(0xFF444444),
    outlineVariant     = Color(0xFF333333),
    error              = Color(0xFFF2B8B5),
    onError            = Color(0xFF601410),
    errorContainer     = Color(0xFF402422),
    onErrorContainer   = Color(0xFFF9DEDC),
)

/** Light counterpart for users who prefer (or whose phone is set to) light mode. */
private val OpenWebUiLight = lightColorScheme(
    primary            = Color(0xFF1A73E8),
    onPrimary          = Color(0xFFFFFFFF),
    primaryContainer   = Color(0xFFD6E3FF), // user message bubble
    onPrimaryContainer = Color(0xFF0A1F3F),
    secondary          = Color(0xFF4A6592),
    onSecondary        = Color(0xFFFFFFFF),
    background         = Color(0xFFFAFAFA),
    onBackground       = Color(0xFF1A1A1A),
    surface            = Color(0xFFFFFFFF),
    onSurface          = Color(0xFF1A1A1A),
    surfaceVariant     = Color(0xFFEDEFF3), // assistant bubble, inputs
    onSurfaceVariant   = Color(0xFF44474E),
    outline            = Color(0xFFC4C7CF),
    outlineVariant     = Color(0xFFE0E2E8),
    error              = Color(0xFFBA1A1A),
    onError            = Color(0xFFFFFFFF),
    errorContainer     = Color(0xFFFFDAD6),
    onErrorContainer   = Color(0xFF410002),
)

/** True when the given mode resolves to a dark color scheme. */
@Composable
fun ThemePrefs.Mode.isDark(): Boolean = when (this) {
    ThemePrefs.Mode.SYSTEM -> isSystemInDarkTheme()
    ThemePrefs.Mode.LIGHT -> false
    ThemePrefs.Mode.DARK -> true
}

@Composable
fun WikiLLMTheme(mode: ThemePrefs.Mode = ThemePrefs.Mode.SYSTEM, content: @Composable () -> Unit) {
    val colorScheme = if (mode.isDark()) OpenWebUiDark else OpenWebUiLight
    MaterialTheme(colorScheme = colorScheme, content = content)
}
