package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * WebSocket 连接建立失败的复现与回归测试（对应 D1–D4）。
 *
 * 背景（Android 14 模拟器 + 真实网关实测）：WS 长连接**从未建立**，UI 恒显「未连接」。
 * 根因是三个缺陷叠加：
 *  - D1 竞态：配对 `savePairing()` 是 suspend，尚未落盘就启动了服务 ⇒ 服务读到 null；
 *  - D2 一次性标志：`if (!started)` 使首次早退后再也不重试 ⇒ manager 永不创建；
 *  - D3 重启不恢复：服务只在配对回调里启动，App 重启后无人恢复连接；
 *  - D4 状态未订阅：只有详情页订阅状态，列表页恒显示 DISCONNECTED。
 *
 * 这些类刻意做成**纯 Kotlin、无 Android 依赖**，因此能在 JVM 上确定性复现。
 */
class ConnectionBootstrapperTest {

    private class FakePairingSource {
        @Volatile var pairing: PairingInfo? = null
        @Volatile var loadCount = 0
        suspend fun load(): PairingInfo? { loadCount++; return pairing }
    }

    private fun info() = PairingInfo("PC", "10.0.2.2", 8765, "0123456789abcdef0123456789abcdef", "01234567")

    private fun bootstrap(src: FakePairingSource, maxRetries: Int = 3) =
        ConnectionBootstrapper(
            loadPairing = { src.load() },
            onConnect = { },
            maxUnpairedRetries = maxRetries,
        )

    // ── D2 复现：未配对早退后不再重试 ────────────────────────────
    @Test fun `D2 复现 未配对时首次尝试不建连`() = runBlocking {
        val src = FakePairingSource()               // 尚未配对
        val b = bootstrap(src)
        assertFalse("未配对时不应建连", b.ensureConnected())
        assertEquals(ConnectionBootstrapper.Phase.WAITING_PAIRING, b.phase)
    }

    @Test fun `D2 回归 早退后配对变为可用 必须能再建连`() = runBlocking {
        val src = FakePairingSource()
        val b = bootstrap(src)
        assertFalse(b.ensureConnected())             // 第一次：未配对，早退
        src.pairing = info()                        // 随后配对完成（D1 竞态的落盘）
        assertTrue("早退不得成为一次性终局，必须能重试", b.ensureConnected())
        assertEquals(ConnectionBootstrapper.Phase.CONNECTED, b.phase)
    }

    @Test fun `D2 回归 连续多次早退后仍可建连`() = runBlocking {
        val src = FakePairingSource()
        val b = bootstrap(src, maxRetries = 5)
        repeat(3) { assertFalse(b.ensureConnected()) }
        src.pairing = info()
        assertTrue(b.ensureConnected())
    }

    @Test fun `D2 回归 未配对重试预算用尽后标记 UNPAIRED`() = runBlocking {
        val src = FakePairingSource()
        val b = bootstrap(src, maxRetries = 3)
        assertEquals(ConnectionBootstrapper.Phase.IDLE, b.phase)
        repeat(10) { b.ensureConnected() }
        assertTrue("用尽预算后必须标记 UNPAIRED，UI 据此提示先配对", b.retriesExhausted)
        assertEquals(ConnectionBootstrapper.Phase.UNPAIRED, b.phase)
    }

    @Test fun `D2 回归 预算用尽后 配对完成仍能建连`() = runBlocking {
        val src = FakePairingSource()
        val b = bootstrap(src, maxRetries = 2)
        repeat(5) { b.ensureConnected() }
        assertTrue(b.retriesExhausted)
        src.pairing = info()                                  // 用户随后完成配对
        assertTrue("预算用尽不是终局，配对落盘后仍须能建连", b.ensureConnected())
        assertEquals(ConnectionBootstrapper.Phase.CONNECTED, b.phase)
    }

    @Test fun `reset 允许重新尝试`() = runBlocking {
        val src = FakePairingSource()
        val b = bootstrap(src, maxRetries = 1)
        b.ensureConnected()
        assertTrue(b.retriesExhausted)
        b.reset()
        assertEquals(ConnectionBootstrapper.Phase.IDLE, b.phase)
        assertEquals(0L, b.attemptCount)
    }

    // ── D3 复现：重启后应自动恢复 ────────────────────────────────
    @Test fun `D3 复现 全新实例代表进程重启 已有配对应立即建连`() = runBlocking {
        val src = FakePairingSource().apply { pairing = info() }   // 上次运行已配对
        val b = bootstrap(src)                                      // 进程重启 ⇒ 全新 bootstrapper
        assertTrue("已配对时重启必须自动建连", b.ensureConnected())
        assertEquals(ConnectionBootstrapper.Phase.CONNECTED, b.phase)
    }

    @Test fun `D3 回归 建连成功后重复调用是幂等的`() = runBlocking {
        val src = FakePairingSource().apply { pairing = info() }
        var connects = 0
        val b = ConnectionBootstrapper({ src.load() }, { connects++ })
        assertTrue(b.ensureConnected())
        assertFalse("已建连时不应重复建连", b.ensureConnected())
        assertFalse(b.ensureConnected())
        assertEquals(1, connects)
    }

    @Test fun `D3 回归 重启时配对已损坏则不建连且不崩`() = runBlocking {
        val src = FakePairingSource()               // 密钥失效 ⇒ loadPairing 返回 null
        val b = bootstrap(src)
        assertFalse(b.ensureConnected())
        assertEquals(ConnectionBootstrapper.Phase.WAITING_PAIRING, b.phase)
    }

    // ── D1：配对完成后显式触发 ────────────────────────────────
    @Test fun `D1 回归 配对保存后显式触发即可建连`() = runBlocking {
        val src = FakePairingSource()
        val b = bootstrap(src)
        src.pairing = info()                        // 模拟 savePairing 已落盘
        b.onPairingSaved()                          // UI 在落盘完成后调用
        assertEquals(ConnectionBootstrapper.Phase.CONNECTED, b.phase)
    }

    @Test fun `D1 回归 onPairingSaved 在未落盘时不误建连`() = runBlocking {
        val src = FakePairingSource()
        val b = bootstrap(src)
        b.onPairingSaved()
        assertEquals(ConnectionBootstrapper.Phase.WAITING_PAIRING, b.phase)
    }

    @Test fun `onPairingSaved 幂等`() = runBlocking {
        val src = FakePairingSource().apply { pairing = info() }
        var connects = 0
        val b = ConnectionBootstrapper({ src.load() }, { connects++ })
        b.onPairingSaved(); b.onPairingSaved(); b.onPairingSaved()
        assertEquals(1, connects)
    }

    @Test fun `服务被再次 start 时会重新驱动建连`() = runBlocking {
        val src = FakePairingSource()
        val b = bootstrap(src)
        b.ensureConnected()                          // 第一次：早退
        src.pairing = info()
        b.onStartCommandLike()                       // START_STICKY 被系统再次拉起
        assertEquals(ConnectionBootstrapper.Phase.CONNECTED, b.phase)
    }

    @Test fun `managerCreated 标志可查询`() = runBlocking {
        val src = FakePairingSource().apply { pairing = info() }
        val b = bootstrap(src)
        assertFalse(b.managerCreated)
        b.ensureConnected()
        assertTrue(b.managerCreated)
    }
}

/**
 * D4：连接状态订阅。UI 必须在**任意页面**都能拿到真实状态，
 * 而不是只有详情页订阅、列表页恒显 DISCONNECTED。
 */
class ConnectionStateBusTest {

    @Test fun `D4 回归 多订阅者都能收到状态`() = runBlocking {
        val bus = ConnectionStateBus()
        val got = mutableListOf<ConnectionSnapshot>()
        bus.subscribe { got += it }            // 立即回放当前快照
        val a = ConnectionSnapshot(ConnectionState.CONNECTED, 0, 0, null, 0)
        val b = ConnectionSnapshot(ConnectionState.DRAINING_QUEUE, 0, 0, null, 2)
        bus.publish(a); bus.publish(b)
        // [0] = subscribe 的立即回放，之后是两次 publish
        assertEquals(3, got.size)
        assertEquals(ConnectionState.CONNECTED, got[1].state)
        assertEquals(ConnectionState.DRAINING_QUEUE, got[2].state)
    }

    @Test fun `D4 回归 多个订阅者全部收到`() = runBlocking {
        val bus = ConnectionStateBus()
        val a = mutableListOf<ConnectionState>()
        val b = mutableListOf<ConnectionState>()
        bus.subscribe { a += it.state }        // 订阅即回放当前快照
        bus.subscribe { b += it.state }
        bus.publish(ConnectionSnapshot(ConnectionState.CONNECTING, 0, 0, null, 0))
        assertEquals("a: 回放 + 1 次 publish", 2, a.size)
        assertEquals("b: 回放 + 1 次 publish", 2, b.size)
        assertEquals(ConnectionState.CONNECTING, a.last())
        assertEquals(ConnectionState.CONNECTING, b.last())
    }

    @Test fun `D4 回归 退订后不再收到`() = runBlocking {
        val bus = ConnectionStateBus()
        val got = mutableListOf<ConnectionSnapshot>()
        val token = bus.subscribe { got += it }        // ① 立即回放
        bus.publish(ConnectionSnapshot(ConnectionState.CONNECTED, 0, 0, null, 0))   // ②
        bus.unsubscribe(token)
        bus.publish(ConnectionSnapshot(ConnectionState.DISCONNECTED, 0, 0, null, 0)) // ③ 不应收到
        assertEquals("回放 1 次 + publish 1 次 = 2；退订后的 publish 不再送达", 2, got.size)
    }

    @Test fun `状态总线暴露当前快照`() = runBlocking {
        val bus = ConnectionStateBus()
        assertEquals(ConnectionState.DISCONNECTED, bus.current().state)
        bus.publish(ConnectionSnapshot(ConnectionState.BACKING_OFF, 2, 500, "boom", 3))
        assertEquals(ConnectionState.BACKING_OFF, bus.current().state)
        assertEquals(2, bus.current().attempt)
        assertEquals("boom", bus.current().lastError)
    }

    @Test fun `六个状态都能被发布`() = runBlocking {
        val bus = ConnectionStateBus()
        val seen = mutableListOf<ConnectionState>()
        bus.subscribe { seen += it.state }          // 立即回放 DISCONNECTED
        ConnectionState.values().forEach { bus.publish(ConnectionSnapshot(it, 0, 0, null, 0)) }
        // [0] 是 subscribe 的回放，其余六个按枚举顺序
        assertEquals(ConnectionState.DISCONNECTED, seen[0])
        assertEquals(ConnectionState.values().toList(), seen.drop(1))
    }
}
