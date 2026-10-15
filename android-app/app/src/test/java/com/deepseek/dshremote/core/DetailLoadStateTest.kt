package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.toArgb

/**
 * 详情页加载状态机（用户问题 1）。
 *
 * 缺陷：点会话后**不自动加载**消息，右栏只显示一个需要手动点的「上拉加载更早的消息」，
 * 用户看到的是空白。
 */
class DetailLoadStateTest {

    @Test fun `初始为空闲`() {
        val s = DetailLoadState()
        assertEquals(DetailLoadState.Phase.Idle, s.phase)
        assertFalse(s.showLoading)
        assertFalse(s.hasContent)
    }

    @Test fun `选中会话立即进入首屏加载`() {
        val s = DetailLoadState()
        assertTrue("选中会话必须触发首屏加载", s.onSessionSelected())
        assertEquals(DetailLoadState.Phase.LoadingFirstPage, s.phase)
        assertTrue(s.showLoading)
    }

    @Test fun `首屏成功后进入已加载`() {
        val s = DetailLoadState()
        s.onSessionSelected()
        s.onFirstPageLoaded(hasRows = true)
        assertEquals(DetailLoadState.Phase.Loaded, s.phase)
        assertTrue("验收：右栏应直接有内容", s.hasContent)
        assertFalse(s.showLoading)
    }

    @Test fun `首屏失败进入失败态且有错误文案`() {
        val s = DetailLoadState()
        s.onSessionSelected()
        s.onFirstPageFailed("网络错误")
        assertEquals(DetailLoadState.Phase.Failed, s.phase)
        assertTrue("失败必须可见，不能静默空白", s.showError)
        assertEquals("网络错误", s.error)
    }

    @Test fun `失败文案为空时用兜底`() {
        val s = DetailLoadState()
        s.onFirstPageFailed("")
        assertTrue(s.showError)
        assertTrue((s.error ?: "").isNotBlank())
    }

    @Test fun `重新选中会清掉旧错误`() {
        val s = DetailLoadState()
        s.onFirstPageFailed("旧错误")
        s.onSessionSelected()
        assertEquals(null, s.error)
        assertEquals(DetailLoadState.Phase.LoadingFirstPage, s.phase)
    }

    @Test fun `滚到顶且有更多时触发加载更早`() {
        val s = DetailLoadState()
        s.onFirstPageLoaded(true)
        assertTrue(s.onScrolledToTop(hasMore = true))
    }

    @Test fun `滚到顶但没有更多时不触发`() {
        val s = DetailLoadState()
        s.onFirstPageLoaded(true)
        assertFalse(s.onScrolledToTop(hasMore = false))
    }

    @Test fun `未加载完成时滚到顶不触发`() {
        val s = DetailLoadState()
        assertFalse("加载中不该并发加载更早", s.onScrolledToTop(hasMore = true))
    }

    @Test fun `失败态滚到顶不触发`() {
        val s = DetailLoadState()
        s.onFirstPageFailed("x")
        assertFalse(s.onScrolledToTop(hasMore = true))
    }

    @Test fun `重试回到加载中`() {
        val s = DetailLoadState()
        s.onFirstPageFailed("x")
        assertTrue(s.retry())
        assertEquals(DetailLoadState.Phase.LoadingFirstPage, s.phase)
        assertEquals(null, s.error)
    }

    @Test fun `空会话也算加载完成`() {
        val s = DetailLoadState()
        s.onSessionSelected()
        s.onFirstPageLoaded(hasRows = false)
        assertEquals(DetailLoadState.Phase.Loaded, s.phase)
    }

    @Test fun `切到别的会话重新自动加载`() {
        val s = DetailLoadState()
        s.onSessionSelected()
        s.onFirstPageLoaded(true)
        assertTrue("已加载时切走不重复请求", s.onSwitchedAway() == false)
        s.onFirstPageFailed("x")     // 制造一个需要重新加载的状态
        assertTrue(s.onSwitchedAway())
    }
}

/**
 * 配色常量（需求 4：颜色现已对齐 DSH 设计 token，出处见
 * `docs/11-DSH视觉token提取.md` / `ui/DshTokens.kt`）。
 *
 * 这里锁定关键色值，防止后续调色时被无意改坏。
 */
class ThemeColorTest {

    @Test fun `右栏纯白 对应 DSH bg-base`() {
        assertEquals(0xFFFFFFFF.toInt(), com.deepseek.dshremote.ui.DshColors.Light.chatBackground.toArgb())
    }

    @Test fun `左栏浅灰且比右栏重`() {
        val chat = com.deepseek.dshremote.ui.DshColors.Light.chatBackground
        val side = com.deepseek.dshremote.ui.DshColors.Light.sidebarBackground
        assertEquals(0xFFF5F6F7.toInt(), side.toArgb())
        assertTrue("左栏必须比右栏『重』（稍暗）", side.red < chat.red)
    }

    @Test fun `分隔线为透明黑 hairline 对应 DSH border-l2`() {
        // DSH: --dsw-alias-border-l2 = #0000001a
        assertEquals(0x1A000000L, com.deepseek.dshremote.ui.DshColors.Light.divider.toArgb().toLong() and 0xFFFFFFFFL)
    }

    @Test fun `主色为 DSH deepseek-450`() {
        assertEquals(0xFF5686FEL, com.deepseek.dshremote.ui.DshColors.Light.accent.toArgb().toLong() and 0xFFFFFFFFL)
    }

    @Test fun `选中态为 deepseek-100 且与主色区分`() {
        val c = com.deepseek.dshremote.ui.DshColors.Light
        assertEquals(0xFFE4EDFDL, c.selectedBackground.toArgb().toLong() and 0xFFFFFFFFL)
        assertTrue("选中底色应浅于主色", c.selectedBackground.red > c.accent.red)
    }

    @Test fun `状态圆点绿灰`() {
        val c = com.deepseek.dshremote.ui.DshColors.Light
        assertEquals(0xFF22C55E.toInt(), c.dotRunning.toArgb())
        assertEquals(0xFFA6AAB0.toInt(), c.dotIdle.toArgb())
    }

    @Test fun `文字主次三级色取自 DSH neutral-bluish`() {
        val c = com.deepseek.dshremote.ui.DshColors.Light
        assertEquals(0xFF0F1115L, c.textPrimary.toArgb().toLong() and 0xFFFFFFFFL)
        assertEquals(0xFF61666BL, c.textSecondary.toArgb().toLong() and 0xFFFFFFFFL)
        assertEquals(0xFF81858CL, c.textTertiary.toArgb().toLong() and 0xFFFFFFFFL)
    }

    @Test fun `深色模式存在且背景不是纯白`() {
        val d = com.deepseek.dshremote.ui.DshColors.Dark
        assertTrue("深色模式背景不应是纯白", d.chatBackground.toArgb() != 0xFFFFFFFF.toInt())
        assertTrue("深色模式文字应为浅色", d.textPrimary.red > d.chatBackground.red)
    }

    @Test fun `深浅两套都提供了全部关键色`() {
        val l = com.deepseek.dshremote.ui.DshColors.Light
        val d = com.deepseek.dshremote.ui.DshColors.Dark
        // 抽查关键字段，确保深色模式没有漏配
        assertTrue(d.chatBackground.toArgb() != l.chatBackground.toArgb())
        assertTrue(d.sidebarBackground.toArgb() != l.sidebarBackground.toArgb())
        assertEquals(d.dotRunning.toArgb(), l.dotRunning.toArgb())   // 状态色两套一致
        assertTrue(d.selectedBackground.toArgb() != l.selectedBackground.toArgb())
    }

    @Test fun `圆角阶梯取自 DSH radius ladder`() {
        val s = com.deepseek.dshremote.ui.DshShapes.FromDsh
        assertEquals(4, s.cornerXs)
        assertEquals(8, s.cornerSmall)
        assertEquals(12, s.cornerMedium)
        assertEquals(16, s.cornerLarge)
        assertEquals(20, s.cornerXl)
        assertEquals(28, s.cornerPanel)
        assertEquals(1, s.dividerWidth)
    }
}
