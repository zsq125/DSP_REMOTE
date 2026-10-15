package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Json
import com.deepseek.dshremote.protocol.JsonObjectReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 二维码内容解析（规范 §3.4）+ 手动输入兜底。 */
class PairingParserTest {

    private val valid = """{"v":1,"name":"DESKTOP-X","host":"192.168.2.27","port":8765,"token":"0123456789abcdef0123456789abcdef","fp":"01234567"}"""

    private fun ok(r: PairingParseResult) = (r as PairingParseResult.Ok).info

    @Test fun `解析合法二维码`() {
        val i = ok(PairingParser.parse(valid))
        assertEquals("DESKTOP-X", i.name)
        assertEquals("192.168.2.27", i.host)
        assertEquals(8765, i.port)
        assertEquals("01234567", i.fp)
    }

    @Test fun `解析出 baseUrl`() {
        assertEquals("http://192.168.2.27:8765", ok(PairingParser.parse(valid)).baseUrl)
    }

    @Test fun `解析出带 token 的 streamUrl`() {
        val u = ok(PairingParser.parse(valid)).streamUrl()
        assertTrue(u.startsWith("ws://192.168.2.27:8765/rc/v1/stream?token="))
    }

    @Test fun `忽略首尾空白`() {
        assertEquals("192.168.2.27", ok(PairingParser.parse("  \n$valid \t ")).host)
    }

    @Test fun `拒绝空内容`() {
        assertTrue(PairingParser.parse("") is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 null`() {
        assertTrue(PairingParser.parse(null) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝非 JSON 文本`() {
        assertTrue(PairingParser.parse("hello world") is PairingParseResult.Invalid)
    }

    @Test fun `拒绝损坏的 JSON`() {
        assertTrue(PairingParser.parse("""{"v":1,"host":}""") is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 v 不为 1`() {
        val bad = valid.replace(""""v":1""", """"v":2""")
        assertTrue(PairingParser.parse(bad) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝缺少 host`() {
        val bad = """{"v":1,"name":"n","port":8765,"token":"0123456789abcdef0123456789abcdef","fp":"01234567"}"""
        assertTrue(PairingParser.parse(bad) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝非法 host 字符`() {
        val bad = valid.replace("192.168.2.27", "bad host!")
        assertTrue(PairingParser.parse(bad) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 port 为 0`() {
        assertTrue(PairingParser.parse(valid.replace("\"port\":8765", "\"port\":0")) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 port 越界 65536`() {
        assertTrue(PairingParser.parse(valid.replace("\"port\":8765", "\"port\":65536")) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 port 缺失`() {
        assertTrue(PairingParser.parse(valid.replace("\"port\":8765,", "")) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 token 不是 32 位十六进制`() {
        val bad = valid.replace("0123456789abcdef0123456789abcdef", "short")
        assertTrue(PairingParser.parse(bad) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 token 含非十六进制字符`() {
        val bad = valid.replace("0123456789abcdef0123456789abcdef", "zzzz456789abcdef0123456789abcdef")
        assertTrue(PairingParser.parse(bad) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 fp 不是 8 位`() {
        val bad = valid.replace(""""fp":"01234567"""", """"fp":"0123"""")
        assertTrue(PairingParser.parse(bad) is PairingParseResult.Invalid)
    }

    @Test fun `拒绝 fp 与 token 前缀不一致`() {
        val bad = valid.replace(""""fp":"01234567"""", """"fp":"89abcdef"""")
        assertTrue(PairingParser.parse(bad) is PairingParseResult.Invalid)
    }

    @Test fun `接受 127_0_0_1 作为 host`() {
        val s = valid.replace("192.168.2.27", "127.0.0.1")
        assertEquals("127.0.0.1", ok(PairingParser.parse(s)).host)
    }

    @Test fun `接受主机名作为 host`() {
        val s = valid.replace("192.168.2.27", "my-pc.local")
        assertEquals("my-pc.local", ok(PairingParser.parse(s)).host)
    }

    @Test fun `拒绝 ipv4 段大于 255`() {
        val s = valid.replace("192.168.2.27", "192.168.2.999")
        assertTrue(PairingParser.parse(s) is PairingParseResult.Invalid)
    }

    @Test fun `手动输入 host 与 token 构造 PairingInfo`() {
        val i = ok(PairingParser.parseManual("192.168.2.27", "0123456789abcdef0123456789abcdef"))
        assertEquals(8765, i.port)
        assertEquals("01234567", i.fp)
    }

    @Test fun `手动输入拒绝非法主机`() {
        assertTrue(PairingParser.parseManual("bad host", "0123456789abcdef0123456789abcdef") is PairingParseResult.Invalid)
    }

    @Test fun `手动输入拒绝非法 token`() {
        assertTrue(PairingParser.parseManual("192.168.2.27", "nope") is PairingParseResult.Invalid)
    }

    @Test fun `手输路径可覆盖名字`() {
        val i = ok(PairingParser.parseManual("192.168.2.27", "0123456789abcdef0123456789abcdef", name = "我的电脑"))
        assertEquals("我的电脑", i.name)
    }

    @Test fun `拒绝前导零的 ipv4 歧义写法`() {
        assertTrue(PairingParser.parseManual("192.168.002.27", "0123456789abcdef0123456789abcdef") is PairingParseResult.Invalid)
    }
}
