package com.deepseek.dshremote.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette for the App, derived from DSH's design tokens (requirement 4).
 *
 * Every colour maps to a DSH token; see [DshPalette] and `docs/11-DSH-visual-tokens.md`
 * for the authoritative extraction (dsh-client-ui-theme/lib/client.js `base_css_default`
 * plus the `--dsw-alias-*` -> `--dsw-static-*` indirection).
 *
 * Layout rules kept from the earlier spec:
 *  - chat pane is the base surface, sidebar sits one layer above it;
 *  - dividers are hairlines, not stacked shadows;
 *  - nothing hardcodes a colour: composables read `DshTheme.colors`.
 */
@Immutable
data class DshColors(
    // surfaces
    val chatBackground: Color,
    val sidebarBackground: Color,
    val sidebarHeaderBackground: Color,
    val bubbleBackground: Color,
    val bubbleHighlight: Color,
    // lines
    val divider: Color,
    val borderL1: Color,
    val borderL2: Color,
    // brand
    val accent: Color,
    val selectedBackground: Color,
    val onSelectedText: Color,
    // status
    val dotRunning: Color,
    val dotIdle: Color,
    // text (three tiers, matching DSH label-primary/secondary/tertiary)
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    // semantic
    val errorText: Color,
    val warningText: Color,
) {
    companion object {
        /** Light: DSH `--dsw-static-neutral-bluish-*` + `--dsw-static-deepseek-*`. */
        val Light = DshColors(
            chatBackground = DshPalette.bluish00Light,          // --dsw-alias-bg-base
            sidebarBackground = Color(0xFFF5F6F7),              // layered surface above base
            sidebarHeaderBackground = Color(0xFFEBEEF2),        // --dsw-static-neutral-bluish-100
            bubbleBackground = DshPalette.deepseek50Light,      // --dsw-specific-bubble
            bubbleHighlight = DshPalette.deepseek200Light,      // --dsw-specific-bubble-highlight
            divider = Color(0x1A000000),                        // --dsw-alias-border-l2 (#0000001a)
            borderL1 = Color(0x0A000000),                       // --dsw-alias-border-l1
            borderL2 = Color(0x1A000000),                       // --dsw-alias-border-l2
            accent = DshPalette.deepseek450Light,               // --dsw-static-deepseek-450
            selectedBackground = DshPalette.deepseek100Light,   // --dsw-static-deepseek-100
            onSelectedText = DshPalette.blue600,                // --dsw-static-blue-600
            dotRunning = Color(0xFF22C55E),                     // running dot (kept from spec)
            dotIdle = DshPalette.bluish400Light,                // --dsw-static-neutral-bluish-400
            textPrimary = DshPalette.bluish1000Light,           // --dsw-alias-label-primary
            textSecondary = DshPalette.bluish700Light,          // --dsw-alias-label-secondary
            textTertiary = DshPalette.bluish600Light,           // --dsw-alias-label-tertiary
            errorText = Color(0xFFDC2626),                       // error (spec)
            warningText = DshPalette.amber500,                  // --dsw-static-amber-500
        )

        /** Dark: DSH dark canvas is #151517 (dsh-client-ui-theme boot theme). */
        val Dark = DshColors(
            chatBackground = DshPalette.bluish00Dark,
            sidebarBackground = Color(0xFF1B1C1F),
            sidebarHeaderBackground = Color(0xFF232428),
            bubbleBackground = DshPalette.deepseek50Dark,
            bubbleHighlight = DshPalette.deepseek200Dark,
            divider = Color(0x26FFFFFF),
            borderL1 = Color(0x1FFFFFFF),
            borderL2 = Color(0x33FFFFFF),
            accent = DshPalette.deepseek450Dark,
            selectedBackground = DshPalette.deepseek200Dark,
            onSelectedText = DshPalette.deepseek100Light,
            dotRunning = Color(0xFF22C55E),
            dotIdle = DshPalette.bluish400Dark,
            textPrimary = DshPalette.bluish1000Dark,
            textSecondary = DshPalette.bluish700Dark,
            textTertiary = DshPalette.bluish600Dark,
            errorText = Color(0xFFF87171),
            warningText = DshPalette.amber500,
        )
    }
}

/**
 * Corner radii, taken from DSH's radius ladder (docs/11 sec 2):
 * xs 4 / sm 8 / md 12 / lg 16 / xl 20 / panel 28.
 *
 * DSH additionally applies `--dsw-corner-shape: superellipse(1.5)`; Android has no
 * native continuous corners, so [DshTokens.limitation] records the gap.
 */
@Immutable
data class DshShapes(
    val cornerXs: Int = 4,
    val cornerSmall: Int = 8,
    val cornerMedium: Int = 12,
    val cornerLarge: Int = 16,
    val cornerXl: Int = 20,
    val cornerPanel: Int = 28,
    val gutter: Int = 8,
    val dividerWidth: Int = 1,
) {
    companion object {
        /** Verbatim from DSH `--dsw-radius-*`. */
        val FromDsh = DshShapes()
    }
}

val LocalDshColors = staticCompositionLocalOf { DshColors.Light }
val LocalDshShapes = staticCompositionLocalOf { DshShapes.FromDsh }

/**
 * Applies the theme: injects [DshColors] / [DshShapes] and maps DSH's type scale
 * onto Material 3 so `MaterialTheme.typography` is DSH-correct too.
 */
@Composable
fun DshTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DshColors.Dark else DshColors.Light
    val shapes = DshShapes.FromDsh
    androidx.compose.runtime.CompositionLocalProvider(
        LocalDshColors provides colors,
        LocalDshShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = if (dark) {
                darkColorScheme(
                    primary = colors.accent,
                    background = colors.chatBackground,
                    surface = colors.chatBackground,
                    onBackground = colors.textPrimary,
                )
            } else {
                lightColorScheme(
                    primary = colors.accent,
                    background = colors.chatBackground,
                    surface = colors.chatBackground,
                    onBackground = colors.textPrimary,
                )
            },
            // DSH type scale mapped onto the Material roles the App uses.
            typography = Typography(
                bodyLarge = DshTokens.markdownBase,
                bodyMedium = DshTokens.s14,
                bodySmall = DshTokens.xs13,
                labelLarge = DshTokens.xsStrong13,
                labelMedium = DshTokens.xxs12,
                labelSmall = DshTokens.xxxs11,
                titleLarge = DshTokens.markdownH1,
                titleMedium = DshTokens.markdownH3,
                titleSmall = DshTokens.markdownH4,
            ),
            content = content,
        )
    }
}

/** Convenience accessors: `DshTheme.colors` / `DshTheme.shapes`. */
object DshTheme {
    val colors: DshColors
        @Composable get() = LocalDshColors.current
    val shapes: DshShapes
        @Composable get() = LocalDshShapes.current
}