package com.deepseek.dshremote.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-R2-1：IP 字面量绕过私有网段校验的**对抗性**用例。
 *
 * 背景：早先只对"纯数字和点"做 IPv4 严格校验，含 `x` 的 `0x08080808`（= 8.8.8.8，公网）
 * 落进"主机名"分支被放行。明文 HTTP 下这等于把 PC 控制权令牌发到互联网。
 *
 * 本类与 `tests/probe/host-bypass.mjs` 的 MUST_REJECT / MUST_ACCEPT 列表**逐条对齐**。
 */
class HostBypassTest {

    private fun ok(host: String) = PairingParser.parse(qr(host)) is PairingParseResult.Ok

    private fun qr(host: String) =
        """{"v":1,"name":"PC","host":"$host","port":8765,"token":"0123456789abcdef0123456789abcdef","fp":"01234567"}"""

    // ── 必须拒绝（含 PM 点名的全部绕过形态） ────────────────────────
    @Test fun `拒绝 0x7f000001 十六进制 loopback`() { assertFalse(ok("0x7f000001")) }
    @Test fun `拒绝 0x08080808 十六进制公网 8_8_8_8`() { assertFalse(ok("0x08080808")) }
    @Test fun `拒绝 2130706433 十进制整数 127_0_0_1`() { assertFalse(ok("2130706433")) }
    @Test fun `拒绝 017700000001 八进制形态`() { assertFalse(ok("017700000001")) }
    @Test fun `拒绝 0_0_0_0`() { assertFalse(ok("0.0.0.0")) }
    @Test fun `拒绝 192_168_1 段数不足`() { assertFalse(ok("192.168.1")) }
    @Test fun `拒绝 192_168_1_1_1 五段`() { assertFalse(ok("192.168.1.1.1")) }
    @Test fun `拒绝带端口 192_168_1_5_8765`() { assertFalse(ok("192.168.1.5:8765")) }
    @Test fun `拒绝 IPv6 双冒号 loopback`() { assertFalse(ok("::1")) }
    @Test fun `拒绝 IPv6 方括号 loopback`() { assertFalse(ok("[::1]")) }
    @Test fun `拒绝 IPv6 公网 2001_4860`() { assertFalse(ok("2001:4860:4860::8888")) }
    @Test fun `拒绝 IPv4 映射 IPv6 双冒号ffff`() { assertFalse(ok("::ffff:8.8.8.8")) }

    // ── 其余私网/公网边界（对齐探针） ────────────────────────────
    @Test fun `拒绝 172_15_0_1`() { assertFalse(ok("172.15.0.1")) }
    @Test fun `拒绝 172_32_0_1`() { assertFalse(ok("172.32.0.1")) }
    @Test fun `拒绝 192_169_0_1`() { assertFalse(ok("192.169.0.1")) }
    @Test fun `拒绝 11_0_0_1`() { assertFalse(ok("11.0.0.1")) }
    @Test fun `拒绝 8_8_8_8`() { assertFalse(ok("8.8.8.8")) }
    @Test fun `拒绝 1_1_1_1`() { assertFalse(ok("1.1.1.1")) }
    @Test fun `拒绝前导零 192_168_002_1`() { assertFalse(ok("192.168.002.1")) }
    @Test fun `拒绝 1_2_3_999`() { assertFalse(ok("1.2.3.999")) }
    @Test fun `拒绝 288_0_0_1`() { assertFalse(ok("288.0.0.1")) }
    @Test fun `拒绝 0177_0_0_1 八进制点分`() { assertFalse(ok("0177.0.0.1")) }

    // ── 必须放行（保留 mDNS 主机名这一有意取舍） ───────────────────
    @Test fun `放行 10_0_0_1`() { assertTrue(ok("10.0.0.1")) }
    @Test fun `放行 10_255_255_254`() { assertTrue(ok("10.255.255.254")) }
    @Test fun `放行 172_16_0_1`() { assertTrue(ok("172.16.0.1")) }
    @Test fun `放行 172_31_255_254`() { assertTrue(ok("172.31.255.254")) }
    @Test fun `放行 192_168_1_5`() { assertTrue(ok("192.168.1.5")) }
    @Test fun `放行 169_254_1_1`() { assertTrue(ok("169.254.1.1")) }
    @Test fun `放行 127_0_0_1`() { assertTrue(ok("127.0.0.1")) }
    @Test fun `放行 localhost`() { assertTrue(ok("localhost")) }
    @Test fun `放行主机名 my-pc`() { assertTrue(ok("my-pc")) }
    @Test fun `放行主机名 my-pc_local`() { assertTrue(ok("my-pc.local")) }
    @Test fun `放行主机名 dsh-host`() { assertTrue(ok("dsh-host")) }

    // ── 形态识别本身 ───────────────────────────────
    @Test fun `十六进制形态不被当作主机名`() {
        assertFalse(PairingParser.isPrivateHost("0x08080808"))
        assertFalse(PairingParser.isPrivateHost("0X7F000001"))
    }
    @Test fun `十进制整数一律拒绝 无论数值`() {
        // 2130706433 = 127.0.0.1（数值上是 loopback），但十进制整数形态本身不接受
        assertFalse(ok("2130706433"))
        assertFalse(PairingParser.isPrivateHost("2130706433"))
    }
    @Test fun `IPv6 一律拒绝`() {
        assertFalse(PairingParser.isPrivateHost("::1"))
        assertFalse(PairingParser.isPrivateHost("[::1]"))
        assertFalse(PairingParser.isPrivateHost("2001:4860:4860::8888"))
    }
    @Test fun `带端口一律拒绝`() {
        assertFalse(PairingParser.isPrivateHost("192.168.1.5:8765"))
    }
    @Test fun `主机名仍放行`() {
        assertTrue(PairingParser.isPrivateHost("my-pc"))
        assertTrue(PairingParser.isPrivateHost("my-pc.local"))
    }

    // ── 手输路径同样封堵 ───────────────────────────
    private fun manual(host: String) =
        PairingParser.parseManual(host, "0123456789abcdef0123456789abcdef")

    @Test fun `手输拒绝十六进制公网`() {
        assertTrue(manual("0x08080808") is PairingParseResult.Invalid)
    }
    @Test fun `手输拒绝十进制公网`() {
        assertTrue(manual("134744072") is PairingParseResult.Invalid)
    }
    @Test fun `手输放行主机名`() {
        assertTrue(manual("my-pc") is PairingParseResult.Ok)
    }
}
