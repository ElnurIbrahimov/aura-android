package com.aura.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * AuraTheme — wraps the Material 3 color scheme with the Aura design
 * tokens defined in [AuraTokens]. Dark theme is the default because
 * Aura runs dark-first, matching the Aura Web UI.
 *
 * Dynamic color is OFF by default. The Aura brand uses a fixed
 * violet/blue palette (matching the web `--accent-purple` #8B5CF6
 * and `--accent-blue` #3B82F6) and dynamic color pulls in
 * wallpaper-derived hues that clash with the brand identity. The
 * user can opt-in via Settings.
 */
@Composable
fun AuraTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = resolvesDarkTheme(themeMode, systemDark)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && themeMode == "system" -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val semanticColors = if (darkTheme) DarkAuraSemanticColors else LightAuraSemanticColors
    CompositionLocalProvider(LocalAuraSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AuraTypography,
            shapes = AuraShapes,
            content = content,
        )
    }
}

internal fun resolvesDarkTheme(themeMode: String, systemDark: Boolean): Boolean = when (themeMode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

// ── Dark scheme — Aura brand (vivid violet on near-black) ─────────────────
// Derived from DarkAuraSemanticColors so the Material color scheme and the
// Aura component tokens (AuraThemeTokens.colors) are a SINGLE source of
// truth. Previously this used the separate AuraTokens object, so stock
// Material widgets (Button, FAB, Switch) rendered a different purple than
// Aura widgets. `error`/`onError` stay saturated for legibility as button
// backgrounds; the softer semantic error is mapped to errorContainer.
private val DarkColors = darkColorScheme(
    primary = DarkAuraSemanticColors.actionPrimary,
    onPrimary = DarkAuraSemanticColors.onActionPrimary,
    primaryContainer = DarkAuraSemanticColors.surface3,
    onPrimaryContainer = DarkAuraSemanticColors.actionPrimary,
    secondary = DarkAuraSemanticColors.info,
    onSecondary = Color.White,
    // FilterChip's *selected* state and FilledTonalButton both read
    // secondaryContainer / onSecondaryContainer. Mapping them to `info`
    // (a blue) meant every selected chip across the app — Hands, Creative,
    // History, Evolution, Global search, the daemon settings — marked
    // itself in a colour the theme uses nowhere else. Tonal accent instead,
    // matching the chips that set their colours explicitly.
    secondaryContainer = DarkAuraSemanticColors.actionPrimary.copy(alpha = 0.18f),
    onSecondaryContainer = DarkAuraSemanticColors.assistantAccent,
    tertiary = DarkAuraSemanticColors.success,
    onTertiary = Color.White,
    tertiaryContainer = DarkAuraSemanticColors.surface2,
    onTertiaryContainer = DarkAuraSemanticColors.success,
    background = DarkAuraSemanticColors.background,
    onBackground = DarkAuraSemanticColors.textPrimary,
    surface = DarkAuraSemanticColors.surface1,
    onSurface = DarkAuraSemanticColors.textPrimary,
    surfaceVariant = DarkAuraSemanticColors.surface2,
    onSurfaceVariant = DarkAuraSemanticColors.textSecondary,
    surfaceTint = DarkAuraSemanticColors.actionPrimary,
    inverseSurface = Color.White,
    inverseOnSurface = DarkAuraSemanticColors.surface0,
    inversePrimary = DarkAuraSemanticColors.actionPrimary,
    outline = DarkAuraSemanticColors.borderDefault,
    outlineVariant = DarkAuraSemanticColors.borderSubtle,
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = DarkAuraSemanticColors.error,
    onErrorContainer = Color(0xFFFCA5A5),
    scrim = DarkAuraSemanticColors.scrim,
)

// ── Light scheme (low priority — Aura runs dark-first) ───────────────────
private val LightColors = lightColorScheme(
    primary = LightAuraSemanticColors.actionPrimary,
    onPrimary = LightAuraSemanticColors.onActionPrimary,
    primaryContainer = LightAuraSemanticColors.surface1,
    onPrimaryContainer = LightAuraSemanticColors.actionPrimary,
    secondary = LightAuraSemanticColors.info,
    onSecondary = Color.White,
    // Same reasoning as the dark scheme above.
    secondaryContainer = LightAuraSemanticColors.actionPrimary.copy(alpha = 0.14f),
    onSecondaryContainer = LightAuraSemanticColors.actionPrimary,
    tertiary = LightAuraSemanticColors.success,
    onTertiary = Color.White,
    background = LightAuraSemanticColors.background,
    onBackground = LightAuraSemanticColors.textPrimary,
    surface = LightAuraSemanticColors.surface0,
    onSurface = LightAuraSemanticColors.textPrimary,
    surfaceVariant = LightAuraSemanticColors.surface1,
    onSurfaceVariant = LightAuraSemanticColors.textSecondary,
    outline = LightAuraSemanticColors.borderDefault,
    outlineVariant = LightAuraSemanticColors.borderSubtle,
    error = Color(0xFFDC2626),
    onError = Color.White,
    scrim = LightAuraSemanticColors.scrim,
)
