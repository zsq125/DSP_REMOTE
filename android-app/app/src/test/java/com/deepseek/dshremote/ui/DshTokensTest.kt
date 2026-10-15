package com.deepseek.dshremote.ui

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement 4 (visual parity with DSH): the tokens below are transcribed from
 * DSH's shipped frontend, not chosen by eye.
 *
 * Authoritative source: `docs/11-DSH-visual-tokens.md`, which extracts from
 *  - `dsh-client-ui-theme/lib/client.js`  (`base_css_default` root tokens)
 *  - `dsh-client-ui-primitives/lib/DisclosureRow.module.css`
 *  - `dsh-client-ui-primitives/lib/CodeCard.module.css`
 *  - `dsh-client-ui-theme/lib/index.js`   (content font size bounds)
 */
class DshTokensTest {

    /**
     * `Color.toArgb()` 返回**有符号** int，而本文档里的色值是 32 位无符号十六进制。
     * 统一用这个 helper 比较，避免 -11106562 这类"看起来不对"的误判。
     */
    private fun argb(c: androidx.compose.ui.graphics.Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL

    // ---- type scale (docs/11 sec 1.2 / 1.3) ----

    @Test fun `正文是 14sp 24sp 而非 16sp`() {
        assertEquals(14f, DshTokens.markdownBase.fontSize.value, 0.001f)
        assertEquals(24f, DshTokens.markdownBase.lineHeight.value, 0.001f)
    }

    @Test fun `三级文字字号与 DSH 一致`() {
        assertEquals(13f, DshTokens.xs13.fontSize.value, 0.001f)
        assertEquals(20f, DshTokens.xs13.lineHeight.value, 0.001f)
        assertEquals(12f, DshTokens.xxs12.fontSize.value, 0.001f)
        assertEquals(18f, DshTokens.xxs12.lineHeight.value, 0.001f)
        assertEquals(11f, DshTokens.xxxs11.fontSize.value, 0.001f)
        assertEquals(14f, DshTokens.xxxs11.lineHeight.value, 0.001f)
    }

    @Test fun `次级正文 14sp 22sp`() {
        assertEquals(14f, DshTokens.s14.fontSize.value, 0.001f)
        assertEquals(22f, DshTokens.s14.lineHeight.value, 0.001f)
    }

    @Test fun `标题阶梯 H1 到 H4`() {
        assertEquals(21f, DshTokens.markdownH1.fontSize.value, 0.001f)
        assertEquals(30f, DshTokens.markdownH1.lineHeight.value, 0.001f)
        assertEquals(19f, DshTokens.markdownH2.fontSize.value, 0.001f)
        assertEquals(18f, DshTokens.markdownH3.fontSize.value, 0.001f)
        assertEquals(14f, DshTokens.markdownH4.fontSize.value, 0.001f)
    }

    @Test fun `代码 12sp 19sp 代码块 11sp 19sp`() {
        assertEquals(12f, DshTokens.markdownCode.fontSize.value, 0.001f)
        assertEquals(19f, DshTokens.markdownCode.lineHeight.value, 0.001f)
        assertEquals(11f, DshTokens.markdownCodeBlock.fontSize.value, 0.001f)
        assertEquals(19f, DshTokens.markdownCodeBlock.lineHeight.value, 0.001f)
    }

    @Test fun `表格 13sp 22sp`() {
        assertEquals(13f, DshTokens.markdownTable.fontSize.value, 0.001f)
        assertEquals(22f, DshTokens.markdownTable.lineHeight.value, 0.001f)
    }

    @Test fun `代码字体是等宽 正文不是`() {
        assertEquals(androidx.compose.ui.text.font.FontFamily.Monospace, DshTokens.fontFamilyCode)
        assertEquals(androidx.compose.ui.text.font.FontFamily.Default, DshTokens.fontFamily)
    }

    @Test fun `字重阶梯正确`() {
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, DshTokens.markdownH1.fontWeight)
        assertEquals(androidx.compose.ui.text.font.FontWeight.SemiBold, DshTokens.markdownBaseStrong.fontWeight)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Medium, DshTokens.xsStrong13.fontWeight)
    }

    // ---- radius ladder (docs/11 sec 2) ----

    @Test fun `圆角阶梯 4 8 12 16 20 28`() {
        assertEquals(4, DshShapes.FromDsh.cornerXs)
        assertEquals(8, DshShapes.FromDsh.cornerSmall)
        assertEquals(12, DshShapes.FromDsh.cornerMedium)
        assertEquals(16, DshShapes.FromDsh.cornerLarge)
        assertEquals(20, DshShapes.FromDsh.cornerXl)
        assertEquals(28, DshShapes.FromDsh.cornerPanel)
    }

    // ---- disclosure row metrics (docs/11 sec 5) ----

    @Test fun `折叠行规格 24 高 16 图标 6 间距 13sp 标题`() {
        assertEquals(24, DshTokens.disclosureRowHeight.value.toInt())
        assertEquals(16, DshTokens.disclosureIconBox.value.toInt())
        assertEquals(6, DshTokens.disclosureIconGap.value.toInt())
        assertEquals(14, DshTokens.disclosureGlyph.value.toInt())
        assertEquals(13f, DshTokens.disclosureTitle.fontSize.value, 0.001f)
        assertEquals(20f, DshTokens.disclosureTitle.lineHeight.value, 0.001f)
    }

    // ---- motion (docs/11 sec 6 + ui-theme base_css_default) ----

    @Test fun `动效时长与 DSH 一致`() {
        assertEquals(100, DshTokens.DURATION_FAST_MS)
        assertEquals(200, DshTokens.DURATION_MS)
        assertEquals(300, DshTokens.DURATION_SLOW_MS)
    }

    @Test fun `微光周期 1500ms 与 TextShimmer 一致`() {
        assertEquals(1500, DSH_SHIMMER_DURATION_MS)
    }

    @Test fun `焦点环 2px 描边 0_5px`() {
        assertEquals(2f, DshTokens.focusRingWidth.value, 0.001f)
        assertEquals(0.5f, DshTokens.borderStrokeWidth.value, 0.001f)
    }

    // ---- palette (docs/11 sec 3) ----

    @Test fun `文字三级颜色取自 neutral-bluish`() {
        assertEquals(0xFF0F1115L, argb(DshColors.Light.textPrimary))
        assertEquals(0xFF61666BL, argb(DshColors.Light.textSecondary))
        assertEquals(0xFF81858CL, argb(DshColors.Light.textTertiary))
    }

    @Test fun `品牌色取自 deepseek-450`() {
        assertEquals(0xFF5686FEL, argb(DshColors.Light.accent))
    }

    @Test fun `气泡取自 deepseek-50 与 200`() {
        assertEquals(0xFFF2F6FEL, argb(DshColors.Light.bubbleBackground))
        assertEquals(0xFFD3E2FFL, argb(DshColors.Light.bubbleHighlight))
    }

    @Test fun `边框四级透明黑 与 DSH 一致`() {
        assertEquals(0x0A000000L, argb(DshColors.Light.borderL1))
        assertEquals(0x1A000000L, argb(DshColors.Light.borderL2))
    }

    @Test fun `警告色 amber-500`() {
        assertEquals(0xFFF59E0BL, argb(DshColors.Light.warningText))
    }

    @Test fun `深色画布是 DSH 的 151517`() {
        assertEquals(0xFF151517L, argb(DshColors.Dark.chatBackground))
    }

    @Test fun `深色文字三级可读`() {
        assertTrue("深色下主文字应接近白", DshColors.Dark.textPrimary.toArgb() > 0xFF888888.toInt())
    }

    @Test fun `记录了 Android 无法复刻的特性`() {
        // superellipse / backdrop-filter 必须在代码里留痕，避免交付时误称完全对齐
        assertTrue(DshTokens.limitation.contains("superellipse"))
    }
}

/**
 * 图片尺寸计算（需求 4 图片部分）。
 *
 * 来源：`dsh-client-ui-primitives/lib/ImagePreview.module.css`
 * —— `.frame { height:180px }`、`.image { object-fit:contain }`。
 */
class ImageSizingTest {

    @Test fun `帧高常量 180 与 DSH 一致`() {
        assertEquals(180, ImageSizing.FRAME_HEIGHT_PX)
        assertEquals(8, ImageSizing.CORNER_PX)
        assertEquals(12, ImageSizing.STATUS_FONT_PX)
        assertEquals(18, ImageSizing.STATUS_LINE_HEIGHT_PX)
    }

    @Test fun `横向图按宽度受限缩放`() {
        val s = ImageSizing.contain(2000, 1000, 400, 180)
        assertEquals(360, s.width)
        assertEquals(180, s.height)
    }

    @Test fun `纵向图按高度受限缩放`() {
        val s = ImageSizing.contain(1000, 2000, 400, 180)
        assertEquals(90, s.width)
        assertEquals(180, s.height)
    }

    @Test fun `contain 不裁剪 始终落在帧内`() {
        val s = ImageSizing.contain(1600, 900, 400, 180)
        assertTrue(s.width <= 400)
        assertTrue(s.height <= 180)
    }

    @Test fun `小图不放大`() {
        // object-fit: contain scales *down* only for rendering; a 50x50 image
        // stays small rather than being blown up to fill the frame.
        val s = ImageSizing.contain(50, 50, 400, 180)
        assertEquals(50, s.width)
        assertEquals(50, s.height)
    }

    @Test fun `非法尺寸返回空`() {
        assertTrue(ImageSizing.contain(0, 100, 400, 180).isEmpty)
        assertTrue(ImageSizing.contain(100, 0, 400, 180).isEmpty)
        assertTrue(ImageSizing.contain(100, 100, 0, 0).isEmpty)
    }

    @Test fun `超大尺寸被夹住 不溢出`() {
        val s = ImageSizing.contain(ImageSizing.MAX_EDGE_PX, ImageSizing.MAX_EDGE_PX, 400, 180)
        assertTrue(s.width <= 400 && s.height <= 180)
    }

    @Test fun `覆盖率在 0 到 1 之间`() {
        val c = ImageSizing.coverage(ImageSizing.contain(2000, 1000, 400, 180), 400, 180)
        assertTrue(c > 0f && c <= 1f)
    }
}