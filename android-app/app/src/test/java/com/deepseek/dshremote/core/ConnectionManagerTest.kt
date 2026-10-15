package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.ServerFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 连接管理器：6 态迁移、心跳（25s/10s）、退避重连、队列排空。
 * 全部用虚拟时钟 + 假传输，**不 sleep、不联网**。
 */
class ConnectionManagerTest {

    private lateinit var clock: FakeClock
    private lateinit var sched: FakeScheduler
    private lateinit var store: InMemoryQueueStore
    private lateinit var queue: PendingQueue
    private lateinit var transport: FakeTransport
    private lateinit var states: MutableList<ConnectionState>
    private lateinit var mgr: ConnectionManager

    private val url = "ws://192.168.2.27:8765/rc/v1/stream?token=t"

    @Before fun setUp() {
        clock = FakeClock()
        sched = FakeScheduler()
        store = InMemoryQueueStore()
        queue = PendingQueue(store)
        transport = FakeTransport()
        states = mutableListOf()
        mgr = ConnectionManager(
            clock = clock,
            scheduler = sched,
            random = FakeRandom(listOf(0.0)),
            transportFactory = { transport },
            queue = queue,
            onSnapshot = { states += it.state },
        )
    }

    private fun connected() {
        mgr.connect(url)
        transport.open()
    }

    // ── 基本状态迁移 ───────────────────────────────
    @Test fun `初始状态是 DISCONNECTED`() {
        assertEquals(ConnectionState.DISCONNECTED, mgr.state)
    }

    @Test fun `connect 后进入 CONNECTING`() {
        mgr.connect(url)
        assertEquals(ConnectionState.CONNECTING, mgr.state)
    }

    @Test fun `transport open 后进入 CONNECTED`() {
        connected()
        assertEquals(ConnectionState.CONNECTED, mgr.state)
    }

    @Test fun `disconnect 后回到 DISCONNECTED 且不再自动重连`() {
        connected()
        mgr.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, mgr.state)
        val calls = transport.connectCalls
        sched.advance(120_000)
        assertEquals(calls, transport.connectCalls)
    }

    @Test fun `迁移序列 CONNECTING 到 CONNECTED 被记录`() {
        connected()
        // 快照在状态不变时也会重复推送（UI 需要队列长度等字段刷新），故断言去重后的状态序列
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED), states.distinct())
    }

    @Test fun `六个状态都出现在枚举中`() {
        assertEquals(6, ConnectionState.values().size)
    }

    // ── 心跳（GAP-14：只由 pong 复位） ───────────────
    @Test fun `连通后安排了 25s 的 ping`() {
        connected()
        assertTrue(sched.scheduledDelays.contains(25_000L))
    }

    @Test fun `25s 后发出 ping`() {
        connected()
        sched.advance(25_000)
        assertTrue(transport.sent.any { it.contains("\"type\":\"ping\"") })
    }

    @Test fun `收到 pong 后不判定超时`() {
        connected()
        sched.advance(25_000)                      // 发出 ping
        transport.text("""{"v":1,"type":"pong","ts":1}""")
        sched.advance(9_000)                       // 未到 10s
        assertTrue(transport.closed.not())
        assertEquals(ConnectionState.CONNECTED, mgr.state)
    }

    @Test fun `10s 内没有 pong 判定连接失效`() {
        connected()
        sched.advance(25_000)                      // ping
        sched.advance(10_000)                      // pong 超时
        assertTrue(transport.closed)
        assertTrue(mgr.state != ConnectionState.CONNECTED)
    }

    @Test fun `其它入站帧不复位 pong 计时 GAP-14`() {
        connected()
        sched.advance(25_000)                      // ping 已发
        // 收到一堆非 pong 帧
        transport.text("""{"v":1,"type":"session.status","sessionId":"s","status":"running","ts":1}""")
        transport.text("""{"v":1,"type":"ack","id":"x","ok":true}""")
        transport.text("""{"v":1,"type":"session.event","sessionId":"s","event":{"kind":"message","role":"user","text":"x","seq":1,"ts":1,"status":"final","messageId":null}}""")
        sched.advance(10_000)
        assertTrue("非 pong 帧不得复位 pong 计时", transport.closed)
    }

    @Test fun `服务端断开触发重连退避`() {
        connected()
        transport.closedByServer(1006, "gone")
        assertTrue(mgr.state == ConnectionState.BACKING_OFF || mgr.state == ConnectionState.WAITING_FOR_RETRY)
    }

    @Test fun `transport 报错同样触发退避`() {
        connected()
        transport.fail("network down")
        assertTrue(mgr.state != ConnectionState.CONNECTED)
    }

    // ── 退避重连 ───────────────────────────────
    @Test fun `断线后进入 BACKING_OFF`() {
        connected()
        transport.closedByServer()
        assertEquals(ConnectionState.BACKING_OFF, mgr.state)
    }

    @Test fun `第一次重连延时 500ms`() {
        connected()
        transport.closedByServer()
        assertEquals(500L, sched.scheduledDelays.last())
    }

    @Test fun `重连次数递增 延时指数增长`() {
        connected()
        transport.closedByServer()
        sched.advance(500); transport.closedByServer()
        sched.advance(1_000); transport.closedByServer()
        sched.advance(2_000)
        assertEquals(listOf(500L, 1_000L, 2_000L), sched.scheduledDelays.filter { it in listOf(500L, 1_000L, 2_000L) }.take(3))
    }

    @Test fun `退避上限 30s`() {
        connected()
        repeat(10) {
            transport.closedByServer()
            sched.advance(30_000)
        }
        assertTrue("退避不得超过 30s", sched.scheduledDelays.all { it <= 30_000L })
    }

    @Test fun `退避期间 transport 被关闭`() {
        connected()
        transport.closedByServer()
        assertTrue(transport.closed)
    }

    @Test fun `重连成功后 attempt 归零`() {
        connected()
        transport.closedByServer()
        sched.advance(500)
        transport.open()
        assertEquals(ConnectionState.CONNECTED, mgr.state)
        assertEquals(0, mgr.currentSnapshot().attempt)
    }

    @Test fun `网络恢复立即重连且不等待剩余退避`() {
        connected()
        transport.closedByServer()                  // 进入退避
        val calls = transport.connectCalls
        mgr.onNetworkAvailable()
        assertTrue(transport.connectCalls > calls)
    }

    @Test fun `网络恢复在已连接时是空操作`() {
        connected()
        val calls = transport.connectCalls
        mgr.onNetworkAvailable()
        assertEquals(calls, transport.connectCalls)
    }

    // ── 队列排空（DRAINING_QUEUE） ───────────────────
    @Test fun `空队列时直接 CONNECTED 不进 DRAINING`() {
        connected()
        assertFalse(states.contains(ConnectionState.DRAINING_QUEUE))
    }

    @Test fun `有队列时进入 DRAINING_QUEUE`() {
        queue.enqueue("s1", "hello", 1L)
        connected()
        assertTrue(states.contains(ConnectionState.DRAINING_QUEUE))
    }

    @Test fun `只发队首一条 等 ack 再发下一条`() {
        queue.enqueue("s1", "a", 1L)
        queue.enqueue("s1", "b", 2L)
        connected()
        assertEquals(1, transport.sentPromptRequestIds().size)
        assertEquals("a", queue.snapshot().first().text)
    }

    @Test fun `收到 ack 后发出下一条`() {
        val a = queue.enqueue("s1", "a", 1L)
        val b = queue.enqueue("s1", "b", 2L)
        connected()
        transport.text("""{"v":1,"type":"ack","id":"q-${a.requestId}","ok":true,"sessionId":"s1","requestId":"${a.requestId}","messageId":"m1"}""")
        assertEquals(2, transport.sentPromptRequestIds().size)
        assertEquals(b.requestId, queue.headRequestId())
    }

    @Test fun `全部 ack 后回到 CONNECTED`() {
        val a = queue.enqueue("s1", "a", 1L)
        val b = queue.enqueue("s1", "b", 2L)
        connected()
        transport.text("""{"v":1,"type":"ack","id":"x","ok":true,"sessionId":"s1","requestId":"${a.requestId}","messageId":"m"}""")
        transport.text("""{"v":1,"type":"ack","id":"y","ok":true,"sessionId":"s1","requestId":"${b.requestId}","messageId":"m"}""")
        assertEquals(ConnectionState.CONNECTED, mgr.state)
        assertTrue(queue.isEmpty)
    }

    @Test fun `ack 带错 requestId 时忽略`() {
        val a = queue.enqueue("s1", "a", 1L)
        connected()
        transport.text("""{"v":1,"type":"ack","id":"x","ok":true,"requestId":"wrong","messageId":"m"}""")
        assertEquals(1, queue.size)
        assertEquals(a.requestId, queue.headRequestId())
    }

    @Test fun `ack 超时视为断线并重连`() {
        queue.enqueue("s1", "a", 1L)
        connected()
        sched.advance(15_000)                      // ACK_TIMEOUT
        assertTrue(transport.closed)
    }

    @Test fun `session_busy 错误后重发同一条`() {
        val a = queue.enqueue("s1", "a", 1L)
        connected()
        transport.text("""{"v":1,"type":"error","id":"q-${a.requestId}","code":"session_busy","message":"locked"}""")
        assertEquals(2, transport.sentPromptRequestIds().size)
        assertEquals(a.requestId, queue.headRequestId())
    }

    @Test fun `busy 重试用尽后从队列移除`() {
        val a = queue.enqueue("s1", "a", 1L)
        connected()
        repeat(4) {
            transport.text("""{"v":1,"type":"error","id":"q-${a.requestId}","code":"session_busy","message":"locked"}""")
        }
        assertTrue(queue.isEmpty)
    }

    @Test fun `不可重试错误直接移除并继续下一条`() {
        val a = queue.enqueue("s1", "a", 1L)
        val b = queue.enqueue("s1", "b", 2L)
        connected()
        transport.text("""{"v":1,"type":"error","id":"q-${a.requestId}","code":"invalid_request","message":"bad"}""")
        assertEquals(b.requestId, queue.headRequestId())
    }

    @Test fun `error 不带 id 时按队首处理`() {
        val a = queue.enqueue("s1", "a", 1L)
        connected()
        transport.text("""{"v":1,"type":"error","id":null,"code":"session_busy","message":"x"}""")
        assertEquals(a.requestId, queue.headRequestId())
    }

    @Test fun `排空期间心跳仍然安排`() {
        queue.enqueue("s1", "a", 1L)
        connected()
        assertTrue(sched.scheduledDelays.contains(25_000L))
    }

    @Test fun `断线期间队列不丢 重连后继续排空`() {
        val a = queue.enqueue("s1", "a", 1L)
        connected()
        transport.closedByServer()
        sched.advance(500)
        transport.open()
        assertEquals(a.requestId, queue.headRequestId())
        transport.text("""{"v":1,"type":"ack","id":"z","ok":true,"requestId":"${a.requestId}","messageId":"m"}""")
        assertTrue(queue.isEmpty)
    }

    @Test fun `连续断网 3 次 消息不丢`() {
        val a = queue.enqueue("s1", "a", 1L)
        val b = queue.enqueue("s1", "b", 2L)
        connected()
        repeat(3) {
            transport.closedByServer()
            sched.advance(30_000)
            transport.open()
        }
        assertEquals(2, queue.size)
        transport.text("""{"v":1,"type":"ack","id":"1","ok":true,"requestId":"${a.requestId}","messageId":"m"}""")
        transport.text("""{"v":1,"type":"ack","id":"2","ok":true,"requestId":"${b.requestId}","messageId":"m"}""")
        assertTrue(queue.isEmpty)
    }

    @Test fun `快照包含状态 次数 队列长度`() {
        queue.enqueue("s1", "a", 1L)
        connected()
        val snap = mgr.currentSnapshot()
        assertEquals(ConnectionState.DRAINING_QUEUE, snap.state)
        assertEquals(1, snap.queued)
    }

    @Test fun `未知帧被安全忽略 不影响连接`() {
        connected()
        transport.text("""{"v":1,"type":"mystery"}""")
        assertEquals(ConnectionState.CONNECTED, mgr.state)
    }

    @Test fun `onFrame 回调能拿到解析后的帧`() {
        val got = mutableListOf<ServerFrame>()
        val m2 = ConnectionManager(clock, sched, FakeRandom(listOf(0.0)), { transport }, queue,
            onFrame = { got += it })
        m2.connect(url); transport.open()
        transport.text("""{"v":1,"type":"pong","ts":1}""")
        assertEquals(1, got.size)
        assertTrue(got[0] is ServerFrame.Pong)
    }

    @Test fun `心跳常量符合规范 25s 与 10s`() {
        assertEquals(25_000L, ConnectionManager.PING_INTERVAL_MS)
        assertEquals(10_000L, ConnectionManager.PONG_TIMEOUT_MS)
    }
}
