package com.deepseek.dshremote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DSH design tokens for the App (requirement 4: "fonts, images, animations must match").
 *
 * Every value below is extracted from DSH's shipped frontend, not guessed.
 * Authoritative source: `docs/11-DSH-visual-tokens.md` plus the packages it cites:
 *
 *  - `dsh-client-ui-theme/lib/client.js`  (`base_css_default` root token block)
 *  - `dsh-client-ui-primitives/lib/DisclosureRow.module.css` (disclosure row metrics)
 *  - `dsh-client-ui-primitives/lib/CodeCard.module.css` (code block metrics)
 *  - `dsh-client-ui-theme/lib/index.js`   (theme + content font size bounds)
 *
 * Where Android cannot reproduce a Web feature, the token still exists and
 * [DshTokens.limitation] records why (currently only superellipse corners).
 */
object DshTokens {

    // ---- 1. Font stacks (docs/11 sec 1.1) ----
    // DSH: --dsw-font-family: -apple-system, BlinkMacSystemFont, "Segoe UI",
    //      "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", ...
    // Android has no -apple-system/Segoe UI, so the platform default IS the
    // faithful equivalent (it resolves to Roboto + Noto Sans CJK). Hard-coding a
    // custom family here would LOSE the CJK fallback chain DSH relies on.
    val fontFamily: FontFamily = FontFamily.Default

    // DSH: --ds-font-family-code: "SF Mono", "JetBrains Mono", "Fira Code",
    //      Consolas, "Liberation Mono", Menlo, Courier, "PingFang SC", "Microsoft YaHei"
    // Android's FontFamily.Monospace resolves to the platform mono (Droid Sans Mono /
    // Roboto Mono), the closest equivalent to that stack. Note that DSH also falls
    // back to the CJK families at the end of the stack, which Android's monospace
    // resolver does not do, so CJK inside code blocks may fall back differently.
    val fontFamilyCode: FontFamily = FontFamily.Monospace

    // ---- 2. Type scale (docs/11 sec 1.2 / 1.3) ----
    // Semantic names mirror DSH's `--dsw-font-*` tokens.
    const val FONT_XXXS_11 = 11
    const val FONT_XXS_12 = 12
    const val FONT_XS_13 = 13
    const val FONT_S_14 = 14
    const val FONT_BASE_16 = 16
    const val FONT_M_18 = 18
    const val FONT_L_20 = 20
    const val FONT_XL_24 = 24

    /** `--dsw-font-xxxs-11` = 11px/14px */
    val xxxs11 = TextStyle(fontFamily = fontFamily, fontSize = 11.sp, lineHeight = 14.sp)
    val xxxsStrong11 = xxxs11.copy(fontWeight = FontWeight.Medium)

    /** `--dsw-font-xxs-12` = 12px/18px */
    val xxs12 = TextStyle(fontFamily = fontFamily, fontSize = 12.sp, lineHeight = 18.sp)
    val xxsStrong12 = xxs12.copy(fontWeight = FontWeight.Medium)

    /** `--dsw-font-xs-13` = 13px/20px -- used by the disclosure (collapse) row title. */
    val xs13 = TextStyle(fontFamily = fontFamily, fontSize = 13.sp, lineHeight = 20.sp)
    val xsStrong13 = xs13.copy(fontWeight = FontWeight.Medium)

    /** `--dsw-font-s-14` = 14px/22px -- secondary body. */
    val s14 = TextStyle(fontFamily = fontFamily, fontSize = 14.sp, lineHeight = 22.sp)
    val sStrong14 = s14.copy(fontWeight = FontWeight.Medium)

    /** `--dsw-font-base-16` = 16px/24px */
    val base16 = TextStyle(fontFamily = fontFamily, fontSize = 16.sp, lineHeight = 24.sp)

    // ---- Markdown scale (docs/11 sec 1.3) ----
    // NOTE: DSH's conversation body is 14px/24px, NOT 16px. The generous 24px
    // leading is the single biggest driver of "does this read like DSH".
    val markdownBase = TextStyle(fontFamily = fontFamily, fontSize = 14.sp, lineHeight = 24.sp)
    val markdownBaseStrong = markdownBase.copy(fontWeight = FontWeight.SemiBold)
    val markdownH1 = TextStyle(fontFamily = fontFamily, fontSize = 21.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    val markdownH2 = TextStyle(fontFamily = fontFamily, fontSize = 19.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val markdownH3 = TextStyle(fontFamily = fontFamily, fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val markdownH4 = TextStyle(fontFamily = fontFamily, fontSize = 14.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val markdownSmall = TextStyle(fontFamily = fontFamily, fontSize = 12.sp, lineHeight = 20.sp)
    val markdownCode = TextStyle(fontFamily = fontFamilyCode, fontSize = 12.sp, lineHeight = 19.sp)
    val markdownCodeBlock = TextStyle(fontFamily = fontFamilyCode, fontSize = 11.sp, lineHeight = 19.sp)
    val markdownTable = TextStyle(fontFamily = fontFamily, fontSize = 13.sp, lineHeight = 22.sp)
    val markdownTableHead = markdownTable.copy(fontWeight = FontWeight.Medium)

    // ---- 3. Corner radius ladder (docs/11 sec 2) ----
    // --dsw-radius-xs 4 / sm 8 / md 12 / lg 16 / xl 20 / panel 28
    val radiusXs = 4.dp
    val radiusSm = 8.dp
    val radiusMd = 12.dp
    val radiusLg = 16.dp
    val radiusXl = 20.dp
    val radiusPanel = 28.dp

    // ---- 4. Motion (docs/11 sec 6 + ui-theme base_css_default) ----
    // --ds-ease-in-out: cubic-bezier(.4,0,.2,1)
    // --ds-transition-duration .2s / -fast .1s / -slow .3s
    const val DURATION_FAST_MS = 100
    const val DURATION_MS = 200
    const val DURATION_SLOW_MS = 300

    // ---- 5. Disclosure row metrics (docs/11 sec 5, DisclosureRow.module.css) ----
    // Source comment verbatim: "[16px leading] gap 6 [title 13/24] at the default size."
    val disclosureRowHeight = 24.dp
    val disclosureIconBox = 16.dp
    /** `.leading { margin-right: 6px }` -- gap between the chevron and the title. */
    val disclosureIconGap = 6.dp
    /** Glyphs render at 14px inside the 16px box. */
    val disclosureGlyph = 14.dp
    val disclosureTitle = xs13

    // ---- 6. Layout constants ----
    val dividerWidth = 1.dp
    val focusRingWidth = 2.dp
    val borderStrokeWidth = 0.5.dp

    /**
     * Features Android cannot reproduce verbatim.
     * Kept in code so the delivery notes never claim full parity by accident.
     */
    val limitation: String =
        "DSH uses --dsw-corner-shape: superellipse(1.5) (continuous corners) and " +
            "backdrop-filter blur(40px). Android has no native equivalents, so this App " +
            "approximates corners with RoundedCornerShape and omits the backdrop blur."
}

/**
 * Light/dark palette built from DSH's alias -> static indirection (docs/11 sec 3).
 *
 * Only the `neutral-bluish-*` and `deepseek-*` statics that the App actually uses
 * are materialised here; every colour keeps its DSH token name in a comment so the
 * mapping stays auditable.
 */
object DshPalette {
    // --dsw-static-neutral-bluish-* (light): 1000 primary / 700 secondary / 600 tertiary
    val bluish1000Light = Color(0xFF0F1115)
    val bluish700Light = Color(0xFF61666B)
    val bluish600Light = Color(0xFF81858C)
    val bluish400Light = Color(0xFFA6AAB0)
    val bluish200Light = Color(0xFFC6CAD0)
    val bluish00Light = Color(0xFFFFFFFF)

    // --dsw-static-deepseek-* (brand)
    val deepseek50Light = Color(0xFFF2F6FE)
    val deepseek100Light = Color(0xFFE4EDFD)
    val deepseek200Light = Color(0xFFD3E2FF)
    val deepseek400Light = Color(0xFF7AAAFF)
    val deepseek450Light = Color(0xFF5686FE)

    // --dsw-static-amber-500 = #f59e0b (warning); blue-500 #3b82f6; blue-600 #2563eb
    val amber500 = Color(0xFFF59E0B)
    val blue500 = Color(0xFF3B82F6)
    val blue600 = Color(0xFF2563EB)

    // Dark statics (the App keeps a dark palette; DSH ships light/dark as well).
    val bluish1000Dark = Color(0xFFEBEEF2)
    val bluish700Dark = Color(0xFFA6AAB0)
    val bluish600Dark = Color(0xFF81858C)
    val bluish400Dark = Color(0xFF61666B)
    val bluish00Dark = Color(0xFF151517)   // DSH dark canvas #151517
    val deepseek50Dark = Color(0xFF1E2430)
    val deepseek200Dark = Color(0xFF2B3352)
    val deepseek450Dark = Color(0xFF7AAAFF)
}

/** Maps a px value from the DSH token table onto sp (Android scales by density). */
fun dshFontSize(px: Int): TextUnit = px.sp