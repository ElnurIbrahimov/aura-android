package com.aura.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class AuraSemanticColors(
    val background: Color,
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val borderFocus: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val actionPrimary: Color,
    val onActionPrimary: Color,
    val actionDisabled: Color,
    val onActionDisabled: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val riskReadOnly: Color,
    val riskWriteLocal: Color,
    val riskWriteRemote: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val assistantAccent: Color,
    val aiThinking: Color,
    val aiToolCall: Color,
    val aiToolResult: Color,
    val selection: Color,
    val streaming: Color,
    val scrim: Color,
)

/**
 * Warm editorial dark. The neutrals were cold (Zinc: blue-grey) and
 * pinned to the extremes — #030303 under #F4F4F5 is the harshest pairing
 * available, which reads clinical rather than calm. These are warm greys
 * (Stone family) pulled back off pure black and pure white, so the screen
 * reads as unlit paper rather than a switched-off panel. Teal stays as the
 * single accent; against a warm ground it now reads as a jewel tone
 * instead of the only saturated thing in a grey room.
 */
val DarkAuraSemanticColors = AuraSemanticColors(
    background = Color(0xFF0B0A09),
    surface0 = Color(0xFF12100F),
    surface1 = Color(0xFF1A1715),
    surface2 = Color(0xFF241F1C),
    surface3 = Color(0xFF2E2825),
    // Borders tinted with the warm text colour, not pure white, so a
    // hairline never reads as a cold seam against the warm ground.
    borderSubtle = Color(0x14EDE9E3),
    borderDefault = Color(0x26EDE9E3),
    borderStrong = Color(0x47EDE9E3),
    borderFocus = Color(0xFF2DD4BF),
    textPrimary = Color(0xFFEDE9E3),
    textSecondary = Color(0xFFB0A79D),
    textTertiary = Color(0xFF8A8078),
    actionPrimary = Color(0xFF14807A),
    onActionPrimary = Color.White,
    actionDisabled = Color(0xFF29292E),
    onActionDisabled = Color(0xFF85858E),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    error = Color(0xFFF87171),
    info = Color(0xFF60A5FA),
    riskReadOnly = Color(0xFF60A5FA),
    riskWriteLocal = Color(0xFFFBBF24),
    riskWriteRemote = Color(0xFFF87171),
    // Your own message is context, not content — you already know what you
    // wrote. A full-saturation teal slab made it the loudest object on the
    // screen while Aura's actual answer sat in plain text beside it. A warm
    // raised surface still reads unmistakably as "mine" (right-aligned,
    // bubbled) without shouting, and it gives the accent back to things
    // that are genuinely accents.
    userBubble = Color(0xFF2E2825),
    onUserBubble = Color(0xFFEDE9E3),
    assistantAccent = Color(0xFF2DD4BF),
    aiThinking = Color(0x402DD4BF),
    aiToolCall = Color(0x403B82F6),
    aiToolResult = Color(0x4034D399),
    selection = Color(0x662DD4BF),
    streaming = Color(0xFF34D399),
    scrim = Color(0xB3000000),
)

/** Warm editorial light — cream paper, warm ink. Mirror of the dark set. */
val LightAuraSemanticColors = AuraSemanticColors(
    background = Color(0xFFFAF7F2),
    surface0 = Color(0xFFFFFDFA),
    surface1 = Color(0xFFF2EDE5),
    surface2 = Color(0xFFE8E1D6),
    surface3 = Color(0xFFDBD2C4),
    borderSubtle = Color(0x141C1917),
    borderDefault = Color(0x261C1917),
    borderStrong = Color(0x471C1917),
    borderFocus = Color(0xFF0D9488),
    textPrimary = Color(0xFF1C1917),
    textSecondary = Color(0xFF57534E),
    // Stone-500 (#78716C) sits at 4.49:1 on this cream ground — just under
    // the 4.5 AA floor AuraSemanticColorsTest enforces. Nudged one step
    // darker to 4.99:1; visually indistinguishable, and it passes.
    textTertiary = Color(0xFF706A64),
    actionPrimary = Color(0xFF0F766E),
    onActionPrimary = Color.White,
    actionDisabled = Color(0xFFE0E0E5),
    onActionDisabled = Color(0xFF686871),
    success = Color(0xFF047857),
    warning = Color(0xFF92400E),
    error = Color(0xFFB91C1C),
    info = Color(0xFF1D4ED8),
    riskReadOnly = Color(0xFF1D4ED8),
    riskWriteLocal = Color(0xFF92400E),
    riskWriteRemote = Color(0xFFB91C1C),
    // Warm raised surface, matching the dark theme's reasoning above.
    userBubble = Color(0xFFE8E1D6),
    onUserBubble = Color(0xFF1C1917),
    assistantAccent = Color(0xFF0D9488),
    aiThinking = Color(0x260D9488),
    aiToolCall = Color(0x261D4ED8),
    aiToolResult = Color(0x26047857),
    selection = Color(0x330D9488),
    streaming = Color(0xFF047857),
    scrim = Color(0x66000000),
)

val LocalAuraSemanticColors = staticCompositionLocalOf { DarkAuraSemanticColors }

object AuraThemeTokens {
    val colors: AuraSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAuraSemanticColors.current
}
