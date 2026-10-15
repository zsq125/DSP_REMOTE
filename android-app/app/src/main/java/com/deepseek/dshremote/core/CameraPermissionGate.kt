package com.deepseek.dshremote.core

/**
 * 相机权限 → 是否应启动预览的门控（**纯逻辑、可 JVM 单测**）。
 *
 * ## D6 根因（真机 HONOR ADT-AN00 实测；本类即复现与回归该缺陷的核心）
 *
 * 原实现有两个致命点：
 *  1. **扫码页根本不存在**：`Screen` 枚举只有 PAIRING / SESSIONS / DETAIL / SETTINGS，
 *     没有 SCANNER；「打开扫码」按钮在**任何情况下**都只调用 `requestCamera.launch(...)`。
 *     ⇒ 权限已授予时，`ActivityResultContracts.RequestPermission` 对"已授予"的请求
 *     **不会再弹框、结果回调也不触发**（系统直接视为已同意），于是「点按钮 → 毫无反应」，
 *     与真机现象完全吻合：`dumpsys media.camera` 的 Active Camera Clients 为 `[]`、
 *     logcat 无任何 CameraX/Camera2 日志。
 *  2. **权限结果存在 Activity 字段**（`scannerPermission`），不参与 Compose 重组，
 *     即使回调置位 UI 也不会刷新。
 *
 * 本类把「点��扫码」该做什么变成可测的纯函数：
 *  - 未授权 ⇒ 发权限请求（现状本就正确：真机第一次点击确实弹了框 ✓）；
 *  - 已授权 ⇒ **必须直接启动相机预览**，绝不能再发权限请求。
 *
 * 不含 Android 依赖，因此 D6 可在 JVM 上确定性复现与回归。
 */
object CameraPermissionGate {

    /** 点击「打开扫码」后应执行的动作。 */
    enum class Action {
        /** 尚未授权：发权限请求。 */
        REQUEST_PERMISSION,

        /** 已授权：直接启动相机预览（**不再请求权限**）。 */
        START_CAMERA,
    }

    /** @param hasPermission 系统当前是否已授予 CAMERA */
    fun actionFor(hasPermission: Boolean): Action =
        if (hasPermission) Action.START_CAMERA else Action.REQUEST_PERMISSION

    /** 是否应跳过权限请求直接启动相机。 */
    fun shouldStartCamera(hasPermission: Boolean): Boolean = hasPermission

    /** 是否需要发权限请求。 */
    fun shouldRequestPermission(hasPermission: Boolean): Boolean = !hasPermission
}
