package com.deepseek.dshremote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tailscale / CGNAT 段 `100.64.0.0/10` 的放行（远程互联）。
 *
 * 背景：用户最终需求是**手机用 4G/5G 流量连家里的 PC**（不是局域网）。
 * 选定 Tailscale 方案后，PC 侧地址形如 `100.64.1.5`，落在 CGNAT 段
 * `100.64.0.0 ~ 100.127.255.255`。
 *
 * ⚠ 安全边界**不能因此放松**：只精确放开这一个 /10，其余判定完全不变。
 * 特别要防"看到 100 开头就放行"这种过度放宽 —— 故显式覆盖 /10 的两个边界外地址。
 */
class TailscaleRangeTest {

    private fun ok(host: String) = PairingParser.parse(qr(host)) is PairingParseResult.Ok

    private fun qr(host: String) =
        """{"v":1,"name":"PC","host":"$host","port":8765,"token":"0123456789abcdef0123456789abcdef","fp":"01234567"}"""

    // ── 真实地址与段内边界：必须接受 ────────────────────────────
    @Test fun `接受真实 Tailscale 地址 100_85_173_87`() {
        assertTrue("PC 的真实 Tailscale IP 必须可用", PairingParser.isPrivateHost("100.64.1.5"))
        assertTrue(ok("100.64.1.5"))
    }

    @Test fun `接受段内下边界 100_64_0_0`() {
        assertTrue(PairingParser.isPrivateHost("100.64.0.0"))
        assertTrue(ok("100.64.0.0"))
    }

    @Test fun `接受段内上边界 100_127_255_255`() {
        assertTrue(PairingParser.isPrivateHost("100.127.255.255"))
        assertTrue(ok("100.127.255.255"))
    }

    @Test fun `接受段内其它代表地址`() {
        for (h in listOf("100.64.0.1", "100.100.100.100", "100.127.0.1", "100.73.14.250")) {
            assertTrue("$h 应在 100.64.0.0/10 内", PairingParser.isPrivateHost(h))
        }
    }

    // ── 边界外：必须拒绝（防止"100 开头就放行"） ──────────────────
    @Test fun `拒绝 100_63_255_255 下边界外一格`() {
        assertFalse(PairingParser.isPrivateHost("100.63.255.255"))
        assertFalse(ok("100.63.255.255"))
    }

    @Test fun `拒绝 100_128_0_0 上边界外一格`() {
        assertFalse(PairingParser.isPrivateHost("100.128.0.0"))
        assertFalse(ok("100.128.0.0"))
    }

    @Test fun `拒绝 100_0_0_1 与 100_255_255_255`() {
        assertFalse(PairingParser.isPrivateHost("100.0.0.1"))
        assertFalse(PairingParser.isPrivateHost("100.255.255.255"))
    }

    // ── 公网回归：不得因本次改动被放宽 ────────────────────────────
    @Test fun `继续拒绝公网 8_8_8_8`() {
        assertFalse(PairingParser.isPrivateHost("8.8.8.8"))
        assertFalse(ok("8.8.8.8"))
    }

    @Test fun `继续拒绝公网 111_18_198_76`() {
        assertFalse(PairingParser.isPrivateHost("111.18.198.76"))
        assertFalse(ok("111.18.198.76"))
    }

    @Test fun `继续拒绝 101_x 等紧邻公网段`() {
        assertFalse(PairingParser.isPrivateHost("101.0.0.1"))
        assertFalse(PairingParser.isPrivateHost("99.128.0.1"))
    }

    // ── 既有私网段不得回归 ──────────────────────────────────────
    @Test fun `既有私网段仍然允许`() {
        for (h in listOf("10.0.0.1", "192.168.1.5", "172.16.0.1", "172.31.255.254", "169.254.1.1", "127.0.0.1")) {
            assertTrue("$h 应继续允许", PairingParser.isPrivateHost(h))
        }
    }

    @Test fun `主机名继续放行`() {
        assertTrue(PairingParser.isPrivateHost("my-pc"))
        assertTrue(PairingParser.isPrivateHost("xxx.trycloudflare.com"))
        assertTrue(ok("pc.example.com"))
    }

    // ── 对抗性绕过防护不得回退（D-R2-1） ──────────────────────────
    @Test fun `十六进制绕过仍被拒绝`() {
        assertFalse(PairingParser.isPrivateHost("0x7f000001"))
        assertFalse(PairingParser.isPrivateHost("0x08080808"))
    }

    @Test fun `十进制整数绕过仍被拒绝`() {
        assertFalse(PairingParser.isPrivateHost("2130706433"))
        assertFalse(PairingParser.isPrivateHost("134744072"))
    }

    @Test fun `Tailscale 段的绕过形态同样被拒绝`() {
        // 100.64.0.0 的十进制写法不能被当成合法 Tailscale 地址放行
        assertFalse(PairingParser.isPrivateHost("1687441408"))
    }

    @Test fun `八进制与前导零仍被拒绝`() {
        assertFalse(PairingParser.isPrivateHost("0177.0.0.1"))
        assertFalse(PairingParser.isPrivateHost("192.168.002.1"))
    }

    @Test fun `IPv6 与带端口仍被拒绝`() {
        assertFalse(PairingParser.isPrivateHost("::1"))
        assertFalse(PairingParser.isPrivateHost("[::1]"))
        // 带端口的形态不是合法 IP 字面量 ⇒ 一律拒绝（端口由契约的 port 字段单独承载）
        assertFalse(PairingParser.isPrivateHost("192.168.1.5:8765"))
        assertFalse(PairingParser.isPrivateHost("100.64.1.5:8765"))
    }

    // ── 手输路径同样生效 ────────────────────────────────────────
    @Test fun `手输接受 Tailscale 地址`() {
        val r = PairingParser.parseManual("100.64.1.5", "0123456789abcdef0123456789abcdef")
        assertTrue(r is PairingParseResult.Ok)
        assertEquals2("100.64.1.5", (r as PairingParseResult.Ok).info.host)
    }

    @Test fun `手输接受段内边界`() {
        assertTrue(PairingParser.parseManual("100.64.0.0", "0123456789abcdef0123456789abcdef") is PairingParseResult.Ok)
        assertTrue(PairingParser.parseManual("100.127.255.255", "0123456789abcdef0123456789abcdef") is PairingParseResult.Ok)
    }

    @Test fun `手输拒绝段外地址`() {
        assertTrue(PairingParser.parseManual("100.63.255.255", "0123456789abcdef0123456789abcdef") is PairingParseResult.Invalid)
        assertTrue(PairingParser.parseManual("100.128.0.0", "0123456789abcdef0123456789abcdef") is PairingParseResult.Invalid)
        assertTrue(PairingParser.parseManual("8.8.8.8", "0123456789abcdef0123456789abcdef") is PairingParseResult.Invalid)
    }

    private fun assertEquals2(expected: String, actual: String) =
        org.junit.Assert.assertEquals(expected, actual)
}
