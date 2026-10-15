package com.deepseek.dshremote.core

/**
 * 提示词投递模式（对齐 DSH 契约 `lib/types/session.d.ts:137-139`）。
 *
 * DSH 原文：
 *   `'queue' appends a turn; 'steer' interrupts it`
 *   且 `Agent.steer`（`dsh-agent/lib/types/runtime-types.d.ts:194`）注明
 *   **"An idle driver starts a turn"**
 *
 * ⇒ 关键差异：
 *  - `steer`：会话 running 时**打断当前回合**；会话 idle 时**直接开新回合（唤醒）**；
 *  - `queue`：只在"已有回合"时被消费；会话 idle 时消息躺在 inbox 里**永远不会自己醒**
 *    （这正是之前"发消息没反应、response 永远为空"的根因）。
 *
 * 用户要求「用户发送的消息优先级最高」⇒ App 默认 [STEER]。
 */
object PromptMode {
    const val STEER = "steer"
    const val QUEUE = "queue"

    /** 契约默认值（与网关 `DEFAULT_PROMPT_MODE` 保持一致）。 */
    const val DEFAULT_MODE = STEER

    /**
     * 校验网关接受的取值。
     *
     * ⚠ F-4（PM 裁决 2026-10-12）：网关对**显式 `null`** 返回 400，与 `""`/`1`/`["steer"]`
     * 一致；本函数只做**大小写敏感**的取值判定，`isValid(null)` 为 false。
     */
    fun isValid(mode: String?): Boolean = mode == STEER || mode == QUEUE

    /** 归一：null / 非法值 ⇒ 默认 steer（本地防御；网关才是权威校验方）。 */
    fun normalize(mode: String?): String = if (isValid(mode)) mode!! else STEER

    /** 该模式是否会唤醒一个 idle 会话。 */
    fun wakesIdle(mode: String?): Boolean = normalize(mode) == STEER

    /** 该模式是否会打断一个正在运行的回合。 */
    fun interruptsRunning(mode: String?): Boolean = normalize(mode) == STEER
}
