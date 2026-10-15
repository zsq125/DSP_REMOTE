package com.deepseek.dshremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 协议层 JSON 编解码（JVM 单测，无需 Robolectric）。 */
class JsonTest {

    private fun obj(s: String) = Json.parseObject(s)

    @Test fun `解析空对象`() {
        val o = obj("{}")
        assertEquals(0, o.fields.size)
    }

    @Test fun `解析字符串字段`() {
        val r = JsonObjectReader(obj("""{"a":"hello"}"""))
        assertEquals("hello", r.optString("a"))
    }

    @Test fun `解析数字字段为 long 与 int`() {
        val r = JsonObjectReader(obj("""{"ts":1791651392296,"n":42}"""))
        assertEquals(1791651392296L, r.optLong("ts"))
        assertEquals(42, r.optInt("n"))
    }

    @Test fun `解析布尔字段`() {
        val r = JsonObjectReader(obj("""{"ok":true,"no":false}"""))
        assertTrue(r.optBoolean("ok"))
        assertEquals(false, r.optBoolean("no"))
    }

    @Test fun `缺失字段走 fallback`() {
        val r = JsonObjectReader(obj("""{"a":1}"""))
        assertEquals("d", r.optString("x", "d"))
        assertEquals(7L, r.optLong("x", 7L))
        assertEquals(true, r.optBoolean("x", true))
        assertNull(r.optObject("x"))
        assertEquals(0, r.optArray("x").size)
    }

    @Test fun `has 对 null 返回 false`() {
        val r = JsonObjectReader(obj("""{"a":null,"b":1}"""))
        assertEquals(false, r.has("a"))
        assertEquals(true, r.has("b"))
    }

    @Test fun `解析嵌套对象`() {
        val r = JsonObjectReader(obj("""{"outer":{"inner":"v"}}"""))
        assertEquals("v", r.optObject("outer")?.optString("inner"))
    }

    @Test fun `解析数组中的对象`() {
        val r = JsonObjectReader(obj("""{"items":[{"id":"a"},{"id":"b"},{"id":"c"}]}"""))
        assertEquals(listOf("a", "b", "c"), r.optArray("items").map { it.optString("id") })
    }

    @Test fun `解析空数组`() {
        val r = JsonObjectReader(obj("""{"items":[]}"""))
        assertEquals(0, r.optArray("items").size)
    }

    @Test fun `解析嵌套数组`() {
        val r = JsonObjectReader(obj("""{"a":[[1,2],[3]]}"""))
        val arr = Json.parseObject("""{"a":[[1,2],[3]]}""").fields["a"]
        assertTrue(arr is JsonValue.Arr)
    }

    @Test fun `解析转义引号与反斜杠`() {
        val r = JsonObjectReader(obj("""{"t":"he said \"hi\" and \\"}"""))
        assertEquals("""he said "hi" and \""", r.optString("t"))
    }

    @Test fun `解析 unicode 转义`() {
        val r = JsonObjectReader(obj("""{"t":"中"}"""))
        assertEquals("中", r.optString("t"))
    }

    @Test fun `解析负数与小数`() {
        val r = JsonObjectReader(obj("""{"a":-5,"b":1.5}"""))
        assertEquals(-5L, r.optLong("a"))
        assertEquals(1.5, (Json.parseObject("""{"b":1.5}""").fields["b"] as JsonValue.Num).value, 1e-9)
    }

    @Test fun `非法 JSON 抛异常`() {
        try {
            Json.parse("{not json}")
            fail("应抛 JsonParseException")
        } catch (e: JsonParseException) {
            assertNotNull(e.message)
        }
    }

    @Test fun `未闭合字符串抛异常`() {
        try { Json.parse("""{"a":"x"""); fail("应抛异常") } catch (e: JsonParseException) { /* 预期 */ }
    }

    @Test fun `缺少冒号抛异常`() {
        try { Json.parse("""{"a" 1}"""); fail("应抛异常") } catch (e: JsonParseException) { /* 预期 */ }
    }

    @Test fun `尾部多余内容抛异常`() {
        try { Json.parse("""{"a":1} extra"""); fail("应抛异常") } catch (e: JsonParseException) { /* 预期 */ }
    }

    @Test fun `quote 转义双引号`() {
        assertEquals(""" "a\"b" """.trim(), Json.quote("a\"b"))
    }

    @Test fun `quote 转义控制字符`() {
        assertEquals(""" "a\nb" """.trim(), Json.quote("a\nb"))
    }

    @Test fun `quote 不转义普通中文`() {
        assertEquals("\"中文\"", Json.quote("中文"))
    }

    @Test fun `ClientFrame quote 保留 emoji`() {
        assertEquals("\"ok 🎉\"", Json.quote("ok 🎉"))
    }
}
