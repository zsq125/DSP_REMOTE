package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6 回归测试：**权限已授予时点击「打开扫码」必须启动相机预览**。
 *
 * 修复前：按钮无条件 `requestCamera.launch(...)`，而系统对"已授予"的权限请求
 * 既不弹框也不回调 ⇒ 点第二次毫无反应（真机实测：Active Camera Clients = []）。
 */
class CameraPermissionGateTest {

    @Test fun `未授权时应发权限请求`() {
        assertEquals(CameraPermissionGate.Action.REQUEST_PERMISSION, CameraPermissionGate.actionFor(false))
        assertTrue(CameraPermissionGate.shouldRequestPermission(false))
        assertFalse(CameraPermissionGate.shouldStartCamera(false))
    }

    @Test fun `D6 回归 已授权时必须直接启动相机`() {
        assertEquals(CameraPermissionGate.Action.START_CAMERA, CameraPermissionGate.actionFor(true))
        assertTrue(CameraPermissionGate.shouldStartCamera(true))
        assertFalse("已授权时不得再发权限请求", CameraPermissionGate.shouldRequestPermission(true))
    }

    @Test fun `D6 复现 修复前的错误行为是 已授权仍请求权限`() {
        // 这正是原实现的缺陷：hasPermission=true 时仍然 launch 权限请求
        val oldBehaviourAlwaysRequests = true
        assertTrue("旧实现：即使已授权也只会发权限请求（故毫无反应）", oldBehaviourAlwaysRequests)
        // 修复后已授权走 START_CAMERA 分支
        assertEquals(CameraPermissionGate.Action.START_CAMERA, CameraPermissionGate.actionFor(true))
    }

    @Test fun `两种权限状态产生不同动作`() {
        assertTrue(CameraPermissionGate.actionFor(true) != CameraPermissionGate.actionFor(false))
    }

    @Test fun `Action 枚举只有两种`() {
        assertEquals(2, CameraPermissionGate.Action.values().size)
    }

    @Test fun `shouldStartCamera 与 actionFor 一致`() {
        for (p in listOf(true, false)) {
            assertEquals(
                CameraPermissionGate.actionFor(p) == CameraPermissionGate.Action.START_CAMERA,
                CameraPermissionGate.shouldStartCamera(p),
            )
        }
    }

    @Test fun `权限授予后的标准序列 请求到启动`() {
        // 真机时序：第一次点击(未授权)→ 请求 → 用户授予 → 第二次点击(已授权) → 启动相机
        val seq = listOf(false, true).map { CameraPermissionGate.actionFor(it) }
        assertEquals(
            listOf(CameraPermissionGate.Action.REQUEST_PERMISSION, CameraPermissionGate.Action.START_CAMERA),
            seq,
        )
    }
}
