package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-1 (2026-10-12): a connect that never completes must still be retried.
 *
 * Device evidence: `SocketTimeoutException ... after 8000ms` with the socket left in
 * SYN_SENT. Because the heartbeat only starts in `onOpen`, the previous code had no
 * timer at all during CONNECTING -- the manager could sit there forever, which is
 * exactly the observed "no SYN for 70s" behaviour.
 */
class ConnectTimeoutTest {

    private fun fixture(): Triple<ConnectionManager, FakeScheduler, FakeTransport> {
        val clock = FakeClock()
        val sched = FakeScheduler()
        val transport = FakeTransport()
        val m = ConnectionManager(
            clock = clock,
            scheduler = sched,
            random = FakeRandom(listOf(0.5)),
            transportFactory = { transport },
            queue = PendingQueue(InMemoryQueueStore()),
        )
        return Triple(m, sched, transport)
    }

    @Test fun `连接超时会触发重连而不是永远挂着`() {
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        assertEquals(ConnectionState.CONNECTING, m.currentSnapshot().state)

        // Nothing opened the socket; the timer must fire and drive a retry.
        sched.advance(ConnectionManager.CONNECT_TIMEOUT_MS)
        assertEquals(
            "超时后必须离开 CONNECTING（否则永远挂死）",
            ConnectionState.BACKING_OFF, m.currentSnapshot().state,
        )
        assertTrue("半开 socket 必须被关闭", transport.closed)
    }

    @Test fun `超时后进入退避并安排下一次重试`() {
        val (m, sched, _) = fixture()
        m.connect("ws://host/stream")
        sched.advance(ConnectionManager.CONNECT_TIMEOUT_MS)
        assertEquals(ConnectionState.BACKING_OFF, m.currentSnapshot().state)
        assertTrue("退避期间必须排定下一次重试", m.currentSnapshot().nextRetryInMs > 0)
    }

    @Test fun `超时后重连会再发一次 connect`() {
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        sched.advance(ConnectionManager.CONNECT_TIMEOUT_MS)
        val before = transport.connectCalls
        // Let the scheduled backoff elapse so the retry actually runs.
        sched.advance(Backoff.MAX_MS)
        assertTrue("退避后必须再次尝试（至少 +1 次）", transport.connectCalls > before)
    }

    @Test fun `正常建连后不再触发连接超时`() {
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        transport.open()
        assertEquals(ConnectionState.CONNECTED, m.currentSnapshot().state)
        // Firing the (already cancelled) timer must not disturb the live connection.
        sched.advance(ConnectionManager.CONNECT_TIMEOUT_MS * 2)
        assertEquals(ConnectionState.CONNECTED, m.currentSnapshot().state)
        assertEquals("已连接后不应再因超时重连", 1, transport.connectCalls)
    }

    @Test fun `超时时长落在心跳之前`() {
        // Otherwise a slow-but-alive connect would be killed by the wrong timer.
        assertTrue(ConnectionManager.CONNECT_TIMEOUT_MS < ConnectionManager.PING_INTERVAL_MS)
        assertEquals(10_000L, ConnectionManager.CONNECT_TIMEOUT_MS)
    }

    @Test fun `反复超时不会停止重试（无次数上限）`() {
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        repeat(8) {
            sched.advance(ConnectionManager.CONNECT_TIMEOUT_MS)
            sched.advance(Backoff.MAX_MS)
        }
        assertTrue("重试不得因次数上限而停止（bug2 R-2）", transport.connectCalls >= 8)
    }

    // ---- "connected, then dropped" -- the path the device actually hit ----

    @Test fun `已连接后掉线 服务端关闭 会触发重连`() {
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        transport.open()
        assertEquals(ConnectionState.CONNECTED, m.currentSnapshot().state)
        val before = transport.connectCalls

        transport.closedByServer(1006, "screen off")
        sched.advance(Backoff.MAX_MS)
        assertTrue("掉线后必须重连", transport.connectCalls > before)
    }

    @Test fun `已连接后掉线 传输层报错 会触发重连`() {
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        transport.open()
        val before = transport.connectCalls

        transport.fail("broken pipe")
        sched.advance(Backoff.MAX_MS)
        assertTrue("报错后必须重连", transport.connectCalls > before)
    }

    @Test fun `看门狗发现长期无入站帧时主动重连`() {
        // The Doze case: NO callback ever arrives, so only a watchdog can notice.
        // Measured symptom was "screen off 60s -> 0 reconnects in 30s".
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        transport.open()
        val before = transport.connectCalls

        sched.advance(ConnectionManager.LIVENESS_SILENCE_LIMIT_MS + ConnectionManager.LIVENESS_WATCHDOG_MS)
        assertTrue(
            "长期静默必须由看门狗主动重连（否则永久不同步）",
            transport.connectCalls > before || m.currentSnapshot().state != ConnectionState.CONNECTED,
        )
    }

    @Test fun `有入站流量时看门狗不得误杀`() {
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        transport.open()
        val before = transport.connectCalls
        // Keep the link busy so silence never accumulates.
        repeat(6) {
            transport.text("""{"v":1,"type":"pong","ts":1}""")
            sched.advance(ConnectionManager.LIVENESS_WATCHDOG_MS)
        }
        assertEquals(ConnectionState.CONNECTED, m.currentSnapshot().state)
        assertEquals("有流量时不得重连", before, transport.connectCalls)
    }

    @Test fun `退避到顶后仍会周期性重试`() {
        val (m, sched, transport) = fixture()
        m.connect("ws://host/stream")
        // Many consecutive failures: the delay saturates at MAX_MS but must not stop.
        repeat(12) {
            sched.advance(Backoff.MAX_MS)
            transport.closedByServer(1006, "gone")
        }
        val before = transport.connectCalls
        // Keep giving the scheduler time: the point of R-2 is that retries never
        // STOP, not that they fire instantly.
        var grew = false
        repeat(20) {
            if (grew) return@repeat
            sched.advance(Backoff.MAX_MS)
            if (transport.connectCalls > before) grew = true
        }
        assertTrue("退避到顶后仍会继续尝试（R-2）：必须始终还有下一次尝试", grew)
    }
}