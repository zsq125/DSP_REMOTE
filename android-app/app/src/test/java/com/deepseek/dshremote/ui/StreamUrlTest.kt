package com.deepseek.dshremote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS URL construction (2026-12 regression).
 *
 * The App used to build the socket address from `hostOf(baseUrl)`, which strips
 * the port by design. The result was `ws://100.64.1.5/rc/v1/stream`, i.e.
 * **port 80**, so the handshake always timed out:
 *
 * ```
 * ws connect() issued url=ws://100.64.1.5/rc/v1/stream?token=…
 * ws onFailure SocketTimeoutException failed to connect to /100.64.1.5 (port 80)
 * ```
 *
 * Because `onOpen` never fired, the subscription manager stayed `opened=false`,
 * so `subscribe` was never sent -- which looked like "the App never subscribes".
 */
class StreamUrlTest {

    private val base = "http://100.64.1.5:8765"
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun `ws 地址必须保留端口`() {
        assertEquals(
            "ws://100.64.1.5:8765/rc/v1/stream?token=$token",
            streamUrl(base, token),
        )
    }

    @Test fun `ws 地址不得落到默认端口 80`() {
        assertFalse(
            "不能是 ws://host/...（会被解析成 80 端口）",
            streamUrl(base, token).startsWith("ws://100.64.1.5/"),
        )
    }

    @Test fun `局域网地址同样保留端口`() {
        assertEquals(
            "ws://192.168.2.27:8765/rc/v1/stream?token=$token",
            streamUrl("http://192.168.2.27:8765", token),
        )
    }

    @Test fun `hostOf 只取主机名 故意丢端口`() {
        assertEquals("100.64.1.5", hostOf(base))
        assertEquals("192.168.2.27", hostOf("http://192.168.2.27:8765"))
    }

    @Test fun `hostOf 与 streamUrl 语义不同 不可互相替代`() {
        // The exact bug: hostOf drops ":8765"; streamUrl keeps it.
        assertFalse("hostOf 故意不含端口", hostOf(base).contains(":8765"))
        assertTrue("streamUrl 必须含端口", streamUrl(base, token).contains(":8765/rc"))
        assertFalse(
            "hostOf 的结果不能直接当 URL 主体（这正是原缺陷）",
            streamUrl(base, token).startsWith("ws://" + hostOf(base) + "/rc"),
        )
    }

    @Test fun `ws 方案替换只发生一次`() {
        assertTrue(streamUrl(base, token).startsWith("ws://"))
        assertFalse(streamUrl(base, token).contains("http://"))
    }
}