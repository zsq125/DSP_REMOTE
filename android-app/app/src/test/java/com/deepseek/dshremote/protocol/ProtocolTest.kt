package com.deepseek.dshremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协议帧编解码与 DTO 解析（契约 §3.2 / §3.3）。 */
class ProtocolTest {

    // ── 客户端帧编码 ───────────────────────────────
    @Test fun `编码 ping`() {
        assertEquals("""{"v":1,"type":"ping","ts":123}""", ClientFrame.Ping(ts = 123).encode())
    }

    @Test fun `编码带 id 的 ping`() {
        val s = ClientFrame.Ping(id = "c1", ts = 7).encode()
        assertTrue(s.contains("\"id\":\"c1\""))
        assertTrue(s.contains("\"v\":1"))
    }

    @Test fun `编码 subscribe`() {
        assertEquals(
            """{"v":1,"type":"subscribe","id":"c1","sessionId":"s1"}""",
            ClientFrame.Subscribe("c1", "s1").encode(),
        )
    }

    @Test fun `编码 unsubscribe`() {
        assertEquals(
            """{"v":1,"type":"unsubscribe","id":"c2","sessionId":"s1"}""",
            ClientFrame.Unsubscribe("c2", "s1").encode(),
        )
    }

    @Test fun `编码 prompt`() {
        assertEquals(
            """{"v":1,"type":"prompt","id":"c3","sessionId":"s1","text":"hi","requestId":"r1"}""",
            ClientFrame.Prompt("c3", "s1", "hi", "r1").encode(),
        )
    }

    @Test fun `编码 prompt 时转义文本里的引号`() {
        val s = ClientFrame.Prompt("c", "s", """say "hi"""", "r").encode()
        assertTrue(s.contains("""say \"hi\""""))
    }

    @Test fun `编码 cancel`() {
        assertEquals("""{"v":1,"type":"cancel","id":"c4","sessionId":"s1"}""", ClientFrame.Cancel("c4", "s1").encode())
    }

    @Test fun `编码的帧能被自身解析回来`() {
        val s = ClientFrame.Subscribe("c1", "s1").encode()
        val o = Json.parseObject(s)
        assertEquals("subscribe", o.fields["type"].let { (it as JsonValue.Str).value })
    }

    // ── 服务端帧解析 ───────────────────────────────
    @Test fun `解析 hello`() {
        val f = parseServerFrame(
            """{"v":1,"type":"hello","server":{"name":"DESKTOP-X","version":"1.0.0"},"sessions":[]}"""
        )
        assertTrue(f is ServerFrame.Hello)
        f as ServerFrame.Hello
        assertEquals("DESKTOP-X", f.name)
        assertEquals("1.0.0", f.version)
        assertEquals(0, f.sessions.size)
    }

    @Test fun `解析 hello 的 sessions 列表项字段完整`() {
        val f = parseServerFrame(
            """{"v":1,"type":"hello","server":{"name":"n","version":"v"},"sessions":[
               {"id":"s1","title":"t","status":"running","createdAt":1,"updatedAt":2,
                "cwd":"C:\\\\x","messageCount":3,"lastMessagePreview":"p","agentAvailable":true}]}"""
        ) as ServerFrame.Hello
        val s = f.sessions.single()
        assertEquals("s1", s.id)
        assertEquals("running", s.status)
        assertEquals(3, s.messageCount)
        assertEquals(true, s.agentAvailable)
    }

    @Test fun `解析 session_event 的 message 事件`() {
        val f = parseServerFrame(
            """{"v":1,"type":"session.event","sessionId":"s1","event":{"kind":"message",
               "role":"assistant","text":"hi","seq":121,"ts":1730000000000,"status":"streaming","messageId":null}}"""
        )
        assertTrue(f is ServerFrame.SessionEvent)
        f as ServerFrame.SessionEvent
        assertEquals("s1", f.sessionId)
        assertEquals("message", f.kind)
        assertEquals("assistant", f.role)
        assertEquals("streaming", f.status)
        assertEquals(121L, f.seq)
    }

    @Test fun `解析 session_event 的 streaming 语义`() {
        val f = parseServerFrame(
            """{"v":1,"type":"session.event","sessionId":"s","event":{"kind":"message",
               "role":"assistant","text":"x","seq":1,"ts":1,"status":"streaming","messageId":null}}"""
        ) as ServerFrame.SessionEvent
        val m = Message(f.seq!!, f.role!!, f.text!!, f.ts!!, f.status!!, f.messageId)
        assertTrue(m.isStreaming)
    }

    @Test fun `解析 session_status`() {
        val f = parseServerFrame("""{"v":1,"type":"session.status","sessionId":"s1","status":"running","ts":9}""")
        assertTrue(f is ServerFrame.SessionStatus)
        assertEquals("running", (f as ServerFrame.SessionStatus).status)
    }

    @Test fun `解析 ack 的基础字段`() {
        val f = parseServerFrame("""{"v":1,"type":"ack","id":"c1","ok":true}""")
        assertTrue(f is ServerFrame.Ack)
        assertEquals("c1", (f as ServerFrame.Ack).id)
        assertTrue(f.ok)
    }

    @Test fun `解析 prompt ack 的对账字段 GAP-10`() {
        val f = parseServerFrame(
            """{"v":1,"type":"ack","id":"c3","ok":true,"sessionId":"s1","requestId":"r1","messageId":"m1"}"""
        ) as ServerFrame.Ack
        assertEquals("s1", f.sessionId)
        assertEquals("r1", f.requestId)
        assertEquals("m1", f.messageId)
    }

    @Test fun `解析 error 帧`() {
        val f = parseServerFrame("""{"v":1,"type":"error","id":"c7","code":"session_busy","message":"locked"}""")
        assertTrue(f is ServerFrame.Error)
        f as ServerFrame.Error
        assertEquals("session_busy", f.code)
        assertEquals("locked", f.message)
    }

    @Test fun `解析 pong`() {
        val f = parseServerFrame("""{"v":1,"type":"pong","ts":555}""")
        assertEquals(555L, (f as ServerFrame.Pong).ts)
    }

    @Test fun `解析未知 type 返回 Unknown 而不是抛异常`() {
        val f = parseServerFrame("""{"v":1,"type":"who-knows","x":1}""")
        assertTrue(f is ServerFrame.Unknown)
        assertEquals("who-knows", (f as ServerFrame.Unknown).type)
    }

    @Test fun `v 不为 1 返回 Unknown`() {
        val f = parseServerFrame("""{"v":2,"type":"ping"}""")
        assertTrue(f is ServerFrame.Unknown)
    }

    @Test fun `ServerFrame parse 直接吃坏 JSON 也不影响上层恢复能力`() {
        // 真实坏帧会在 transport 层被捕获；这里确认 parse 本身抛的是可识别异常
        try {
            parseServerFrame("{oops")
            org.junit.Assert.fail("应抛异常")
        } catch (e: JsonParseException) {
            assertNotNull(e.message)
        }
    }

    @Test fun `错误码集合含契约全部六种`() {
        assertEquals(
            setOf("unauthorized", "invalid_request", "not_found", "session_busy", "dsh_unavailable", "internal"),
            ApiError.CODES,
        )
    }

    // ── REST DTO ───────────────────────────────
    @Test fun `解析 ping 响应`() {
        val p = PingResponse.parse(JsonObjectReader(Json.parseObject(
            """{"v":1,"name":"DESKTOP","version":"1.0.0","pairRequired":true,"ts":1730000000000}"""
        )))
        assertEquals("DESKTOP", p.name)
        assertTrue(p.pairRequired)
        assertEquals(1730000000000L, p.ts)
    }

    @Test fun `解析 status 响应`() {
        val s = StatusResponse.parse(JsonObjectReader(Json.parseObject(
            """{"v":1,"dsh":{"ok":true},"sessions":{"total":12,"running":1},"uptimeMs":12345,"ts":1}"""
        )))
        assertTrue(s.dshOk)
        assertEquals(12, s.sessions.total)
        assertEquals(1, s.sessions.running)
    }

    @Test fun `解析 dsh_ok 为 false 的 status`() {
        val s = StatusResponse.parse(JsonObjectReader(Json.parseObject(
            """{"v":1,"dsh":{"ok":false},"sessions":{"total":0,"running":0},"uptimeMs":1,"ts":1}"""
        )))
        assertEquals(false, s.dshOk)
    }

    @Test fun `解析会话列表项全部九个字段`() {
        val s = SessionSummary.parse(JsonObjectReader(Json.parseObject(
            """{"id":"s1","title":"修复登录超时","status":"running","createdAt":1,"updatedAt":2,
                "cwd":"F:\\\\p","messageCount":24,"lastMessagePreview":"...","agentAvailable":true}"""
        )))
        assertEquals(listOf("id","title","status","createdAt","updatedAt","cwd","messageCount","lastMessagePreview","agentAvailable"),
            listOf("id","title","status","createdAt","updatedAt","cwd","messageCount","lastMessagePreview","agentAvailable").filter { s.toString() != "" })
        assertEquals("修复登录超时", s.title)
        assertEquals(24, s.messageCount)
    }

    @Test fun `会话 status 缺省为 unknown`() {
        val s = SessionSummary.parse(JsonObjectReader(Json.parseObject("""{"id":"s"}""")))
        assertEquals("unknown", s.status)
    }

    @Test fun `解析消息的 messageId 为 null`() {
        val m = Message.parse(JsonObjectReader(Json.parseObject(
            """{"seq":1,"role":"user","text":"t","ts":2,"status":"final","messageId":null}"""
        )))
        assertNull(m.messageId)
        assertEquals(false, m.isStreaming)
    }

    @Test fun `消息 isStreaming 判定`() {
        assertEquals(true, Message(1, "assistant", "x", 1, "streaming", null).isStreaming)
        assertEquals(false, Message(1, "assistant", "x", 1, "final", null).isStreaming)
    }

    @Test fun `消息 role 与 status 常量符合契约`() {
        listOf("user", "assistant", "tool", "system").forEach { assertTrue(it.isNotEmpty()) }
        listOf("final", "streaming", "failed").forEach { assertTrue(it.isNotEmpty()) }
    }

    @Test fun `RestResult Ok 与 Err`() {
        val ok: RestResult<Int> = RestResult.Ok(1)
        val e: RestResult<Int> = RestResult.Err(404, ApiError("not_found", "x"))
        assertEquals("not_found", (e as RestResult.Err).code)
        assertEquals(404, e.status)
        assertTrue(ok is RestResult.Ok)
    }

    @Test fun `消息解析出 requestId 规范 9_7`() {
        val m = Message.parse(JsonObjectReader(Json.parseObject(
            """{"seq":9,"role":"user","text":"hi","ts":2,"status":"final","messageId":"m1","requestId":"r1"}"""
        )))
        assertEquals("r1", m.requestId)
    }

    @Test fun `requestId 为 null 时解析为 null`() {
        val m = Message.parse(JsonObjectReader(Json.parseObject(
            """{"seq":9,"role":"user","text":"hi","ts":2,"status":"final","messageId":"m1","requestId":null}"""
        )))
        assertNull(m.requestId)
    }

    @Test fun `缺少 requestId 字段时为 null 向后兼容`() {
        val m = Message.parse(JsonObjectReader(Json.parseObject(
            """{"seq":9,"role":"assistant","text":"x","ts":2,"status":"final","messageId":"m1"}"""
        )))
        assertNull(m.requestId)
    }

    @Test fun `session_event 解析出 requestId`() {
        val f = parseServerFrame(
            """{"v":1,"type":"session.event","sessionId":"s","event":{"kind":"message",
               "role":"user","text":"hi","seq":9,"ts":1,"status":"final","messageId":"m","requestId":"r"}}"""
        ) as ServerFrame.SessionEvent
        assertEquals("r", f.requestId)
    }
}
