package com.deepseek.dshremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-2：私有网段校验。
 *
 * 明文 HTTP 下 token 会随每个请求离开设备 ⇒ 误填公网 host 等于把控制权令牌发到公网。
 * 这类校验必须**纯逻辑可单测**，不能只靠人工检查。
 */
class PrivateHostTest {

    private fun ok(s: String) = (PairingParser.parse(s) as? PairingParseResult.Ok) != null

    private fun qr(host: String) =
        """{"v":1,"name":"PC","host":"$host","port":8765,"token":"0123456789abcdef0123456789abcdef","fp":"01234567"}"""

    @Test fun `10_8 是私有`() { assertTrue(PairingParser.isPrivateHost("10.0.0.5")) }
    @Test fun `10_255_255_255 是私有`() { assertTrue(PairingParser.isPrivateHost("10.255.255.255")) }
    @Test fun `192_168_16 是私有`() { assertTrue(PairingParser.isPrivateHost("192.168.2.27")) }
    @Test fun `192_168_0_1 是私有`() { assertTrue(PairingParser.isPrivateHost("192.168.0.1")) }
    @Test fun `172_16_0_1 是私有`() { assertTrue(PairingParser.isPrivateHost("172.16.0.1")) }
    @Test fun `172_31_255_255 是私有`() { assertTrue(PairingParser.isPrivateHost("172.31.255.255")) }
    @Test fun `169_254_x_x 是 link-local 私有`() { assertTrue(PairingParser.isPrivateHost("169.254.185.79")) }
    @Test fun `127_0_0_1 是 loopback 私有`() { assertTrue(PairingParser.isPrivateHost("127.0.0.1")) }
    @Test fun `localhost 视为私有`() { assertTrue(PairingParser.isPrivateHost("localhost")) }

    @Test fun `9_255_255_255 是公网`() { assertFalse(PairingParser.isPrivateHost("9.255.255.255")) }
    @Test fun `11_0_0_1 是公网`() { assertFalse(PairingParser.isPrivateHost("11.0.0.1")) }
    @Test fun `172_15_x 是公网`() { assertFalse(PairingParser.isPrivateHost("172.15.0.1")) }
    @Test fun `172_32_x 是公网`() { assertFalse(PairingParser.isPrivateHost("172.32.0.1")) }
    @Test fun `192_169_x 是公网`() { assertFalse(PairingParser.isPrivateHost("192.169.0.1")) }
    @Test fun `8_8_8_8 是公网`() { assertFalse(PairingParser.isPrivateHost("8.8.8.8")) }
    @Test fun `1_1_1_1 是公网`() { assertFalse(PairingParser.isPrivateHost("1.1.1.1")) }

    @Test fun `主机名无法静态判定 放行`() {
        assertTrue(PairingParser.isPrivateHost("my-pc.local"))
        assertTrue(PairingParser.isPrivateHost("desktop-abc"))
    }

    @Test fun `二维码解析拒绝公网 IP`() {
        assertFalse(ok(qr("8.8.8.8")))
    }

    @Test fun `二维码解析接受局域网 IP`() {
        assertTrue(ok(qr("192.168.2.27")))
        assertTrue(ok(qr("10.1.2.3")))
        assertTrue(ok(qr("172.20.0.1")))
    }

    @Test fun `二维码解析接受主机名`() {
        assertTrue(ok(qr("my-pc.local")))
    }

    @Test fun `手动输入同样拒绝公网 IP`() {
        val r = PairingParser.parseManual("8.8.8.8", "0123456789abcdef0123456789abcdef")
        assertTrue(r is PairingParseResult.Invalid)
    }

    @Test fun `手动输入接受局域网 IP`() {
        val r = PairingParser.parseManual("192.168.2.27", "0123456789abcdef0123456789abcdef")
        assertTrue(r is PairingParseResult.Ok)
        assertEquals("192.168.2.27", (r as PairingParseResult.Ok).info.host)
    }

    @Test fun `拒绝公网的提示文案提到局域网`() {
        val r = PairingParser.parseManual("8.8.8.8", "0123456789abcdef0123456789abcdef")
        val msg = (r as PairingParseResult.Invalid).reason
        assertTrue(msg.contains("局域网"))
    }
}
