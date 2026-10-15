package com.deepseek.dshremote.protocol

/**
 * 极简 JSON 解析器（纯 Kotlin，**零 Android 依赖**）。
 *
 * 为什么不用 org.json：JVM 单元测试里 `org.json` 只有抛异常的 stub（除非引入 Robolectric），
 * 协议层是验收 §5.1 要求覆盖的重点，用自实现可以让协议编解码**在纯 JVM 下直接测**。
 *
 * 只实现协议需要的子集：对象 / 数组 / 字符串（含 \u 转义）/ 数字 / true / false / null。
 */
sealed class JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()

    fun asObj(): Obj? = this as? Obj
    fun asArr(): Arr? = this as? Arr
}

class JsonParseException(message: String) : RuntimeException(message)

object Json {

    /** 解析一个 JSON 文本；失败抛 [JsonParseException]（调用方负责转成协议级错误）。 */
    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.eof()) throw JsonParseException("trailing content at ${p.pos}")
        return v
    }

    /** 解析并要求是对象；非对象视为协议错误。 */
    fun parseObject(text: String): JsonValue.Obj =
        parse(text).asObj() ?: throw JsonParseException("expected a JSON object")

    /** 转义为合法 JSON 字符串字面量（含首尾引号）。 */
    fun quote(s: String): String = ClientFrame.q(s)

    private class Parser(val s: String) {
        var pos = 0
        fun eof() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): JsonValue {
            skipWs()
            if (eof()) throw JsonParseException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonValue.Str(str())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> if (c == '-' || c.isDigit()) num() else throw JsonParseException("unexpected char '$c' at $pos")
            }
        }

        fun expect(lit: String) {
            if (!s.startsWith(lit, pos)) throw JsonParseException("expected $lit at $pos")
            pos += lit.length
        }

        fun obj(): JsonValue.Obj {
            pos++ // {
            val out = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return JsonValue.Obj(out) }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw JsonParseException("expected key at $pos")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw JsonParseException("expected ':' at $pos")
                pos++
                out[k] = value()
                skipWs()
                if (pos >= s.length) throw JsonParseException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonValue.Obj(out) }
                    else -> throw JsonParseException("expected ',' or '}' at $pos")
                }
            }
        }

        fun arr(): JsonValue.Arr {
            pos++ // [
            val out = ArrayList<JsonValue>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return JsonValue.Arr(out) }
            while (true) {
                out.add(value())
                skipWs()
                if (pos >= s.length) throw JsonParseException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonValue.Arr(out) }
                    else -> throw JsonParseException("expected ',' or ']' at $pos")
                }
            }
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonParseException("unterminated string")
                when (val c = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (pos >= s.length) throw JsonParseException("dangling escape")
                        when (val e = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= s.length) throw JsonParseException("bad \\u escape")
                                val hex = s.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonParseException("bad escape '\\$e'")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        fun num(): JsonValue.Num {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val raw = s.substring(start, pos)
            return JsonValue.Num(raw.toDoubleOrNull() ?: throw JsonParseException("bad number '$raw'"))
        }
    }
}

/** [JsonValue] → 协议层的 [JsonReader] 视图。 */
class JsonObjectReader(private val obj: JsonValue.Obj) : JsonReader {
    override fun optString(key: String, fallback: String?): String? = when (val v = obj.fields[key]) {
        is JsonValue.Str -> v.value
        is JsonValue.Num -> v.value.toString()
        is JsonValue.Bool -> v.value.toString()
        else -> fallback
    }

    override fun optLong(key: String, fallback: Long): Long = when (val v = obj.fields[key]) {
        is JsonValue.Num -> v.value.toLong()
        is JsonValue.Str -> v.value.toLongOrNull() ?: fallback
        else -> fallback
    }

    override fun optInt(key: String, fallback: Int): Int = optLong(key, fallback.toLong()).toInt()

    override fun optBoolean(key: String, fallback: Boolean): Boolean = when (val v = obj.fields[key]) {
        is JsonValue.Bool -> v.value
        is JsonValue.Str -> v.value.equals("true", true)
        is JsonValue.Num -> v.value != 0.0
        else -> fallback
    }

    override fun optObject(key: String): JsonReader? = (obj.fields[key] as? JsonValue.Obj)?.let { JsonObjectReader(it) }

    override fun optArray(key: String): List<JsonReader> =
        (obj.fields[key] as? JsonValue.Arr)?.items?.mapNotNull { (it as? JsonValue.Obj)?.let { o -> JsonObjectReader(o) } } ?: emptyList()

    override fun has(key: String): Boolean = obj.fields.containsKey(key) && obj.fields[key] !is JsonValue.Null
}

/** 解析服务端/网关的一行 JSON。 */
fun parseServerFrame(text: String): ServerFrame = ServerFrame.parse(JsonObjectReader(Json.parseObject(text)))
