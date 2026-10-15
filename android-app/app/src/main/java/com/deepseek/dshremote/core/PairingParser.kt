package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Json
import com.deepseek.dshremote.protocol.JsonObjectReader

/** 配对信息（规范 §3.4 二维码内容）。 */
data class PairingInfo(
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val fp: String,
) {
    /** `http://host:port` 根地址。 */
    val baseUrl: String get() = "http://$host:$port"

    /** WS 地址（含 token 查询参数，契约 §3.1）。 */
    fun streamUrl(): String = "ws://$host:$port/rc/v1/stream?token=$token"
}

sealed class PairingParseResult {
    data class Ok(val info: PairingInfo) : PairingParseResult()
    data class Invalid(val reason: String) : PairingParseResult()
}

/**
 * 解析二维码内容 = 规范 §3.4 的**单行 JSON**：
 * `{"v":1,"name":"...","host":"...","port":8765,"token":"<32hex>","fp":"<8hex>"}`
 *
 * 纯逻辑（不依赖 CameraX/ML Kit），因此可 JVM 单测。ML Kit 只负责把条码文本交到这里。
 */
object PairingParser {

    private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]{0,253}[A-Za-z0-9])?$")
    private val TOKEN_32HEX = Regex("^[0-9a-f]{32}$")
    private val FP_8HEX = Regex("^[0-9a-f]{8}$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** 解析。返回 [PairingParseResult]，**永不抛异常**（扫码内容可能是任意文本）。 */
    fun parse(raw: String?): PairingParseResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return PairingParseResult.Invalid("内容为空")
        if (!text.startsWith("{")) return PairingParseResult.Invalid("不是 JSON 对象（应以 { 开头）")
        val obj = try {
            Json.parseObject(text)
        } catch (e: Exception) {
            return PairingParseResult.Invalid("JSON 解析失败：${e.message}")
        }
        return fromReader(JsonObjectReader(obj))
    }

    /** 从「手动输入的 host:port + token」构造（规范要求的手输兜底路径）。 */
    fun parseManual(hostPort: String, token: String, name: String = ""): PairingParseResult {
        val hp = hostPort.trim()
        val tk = token.trim()
        if (!isValidHost(hp)) return PairingParseResult.Invalid("主机名或 IP 不合法：$hp")
        if (!isPrivateHost(hp)) return PairingParseResult.Invalid(
            "出于安全考虑，只允许局域网或 Tailscale 地址"
                + "（10/8、172.16/12、192.168/16、169.254/16、100.64.0.0/10（Tailscale）或主机名），当前：$hp"
        )
        if (!TOKEN_32HEX.matches(tk)) return PairingParseResult.Invalid("token 必须是 32 位十六进制")
        return PairingParseResult.Ok(
            PairingInfo(
                name = name,
                host = hp,
                port = DEFAULT_PORT,
                token = tk,
                fp = tk.take(8),
            )
        )
    }

    const val DEFAULT_PORT = 8765

    private fun fromReader(j: com.deepseek.dshremote.protocol.JsonReader): PairingParseResult {
        if (j.optInt("v") != 1) return PairingParseResult.Invalid("协议版本 v 必须为 1")
        val host = j.optString("host").orEmpty()
        if (host.isEmpty()) return PairingParseResult.Invalid("缺少 host")
        if (!isValidHost(host)) return PairingParseResult.Invalid("host 不合法：$host")
        if (!isPrivateHost(host)) return PairingParseResult.Invalid(
            "出于安全考虑，只允许局域网或 Tailscale 地址"
                + "（10/8、172.16/12、192.168/16、169.254/16、100.64.0.0/10（Tailscale）或主机名），当前：$host"
        )
        val port = j.optInt("port", 0)
        if (port !in 1..65535) return PairingParseResult.Invalid("port 越界：$port")
        val token = j.optString("token").orEmpty()
        if (!TOKEN_32HEX.matches(token)) return PairingParseResult.Invalid("token 必须是 32 位十六进制")
        val fp = j.optString("fp").orEmpty()
        if (!FP_8HEX.matches(fp)) return PairingParseResult.Invalid("fp 必须是 8 位十六进制")
        if (fp != token.take(8)) return PairingParseResult.Invalid("fp 与 token 前 8 位不一致")
        return PairingParseResult.Ok(
            PairingInfo(name = j.optString("name").orEmpty(), host = host, port = port, token = token, fp = fp)
        )
    }

    /** IPv6 字面量（含 `::`、方括号包裹、zone id）。 */
    private val IPV6_LITERAL = Regex("^[0-9A-Fa-f:.]+$")

    /**
     * 判定一个字符串**是否长得像 IP 字面量**（而不是主机名）。
     *
     * ⚠ 对抗性绕过（PM 2026-10-11 D-R2-1）：早先只对"纯数字和点"做 IPv4 严格校验，
     * 于是 `0x08080808`（= 8.8.8.8，公网）落进"主机名"分支被放行。
     * 这里在**进入主机名分支之前**先识别所有 IP 字面量形态：
     *  - 十六进制（`0x...`）、纯十进制整数（`2130706433`）、前导零八进制（`0177.0.0.1`）
     *  - IPv6（`::1`、`2001:...`、`[::1]`）
     *  - 带端口（`192.168.1.5:8765`）
     * 一律走严格解析；解析不通过 ⇒ **拒绝**。
     */
    private fun looksLikeIpLiteral(h: String): Boolean {
        if (h.isEmpty()) return false
        val lower = h.lowercase()
        // IPv6：含冒号，或方括号包裹
        if (lower.contains(':')) return true
        // 十六进制：只认 **0x 前缀**，不能仅凭"含字母 x"就判定 ——
        // 否则 pc.example.com / xxx.trycloudflare.com 这类正常域名会被误判成 IP 而遭拒。
        if (lower.startsWith("0x")) return true
        // 纯数字（可能是十进制整数 / 八进制 / 点分十进制）
        if (h.all { it.isDigit() || it == '.' }) return true
        return false
    }

    /** 主机名校验：IP 字面量一律严格解析，其余按主机名规则（mDNS 场景保留放行）。 */
    private fun isValidHost(h: String): Boolean {
        if (h.isEmpty()) return false
        if (looksLikeIpLiteral(h)) return parseIpLiteral(h) != null
        return HOSTNAME.matches(h)
    }

    /**
     * 严格解析 IP 字面量：**只接受点分十进制 IPv4**（四段、无前导零、每段 0..255）。
     *
     * 其余形态一律 null（= 拒绝），不回退当主机名：
     *  - 十六进制 `0x08080808`
     *  - 纯十进制整数 `2130706433` / `134744072`
     *  - 八进制 / 前导零 `0177.0.0.1` / `192.168.002.1`
     *  - IPv6 `::1` / `[::1]` / `2001:...` / `::ffff:8.8.8.8`
     *  - 带端口 `192.168.1.5:8765`、段数不足/过多
     *
     * 判定口径与 `tests/probe/host-bypass.mjs` 的 MUST_REJECT 列表逐条对齐。
     */
    private fun parseIpLiteral(h: String): IntArray? {
        val s = h.trim()
        if (s.isEmpty()) return null
        // 只认 IPv4 四段十进制：不含冒号、不以 0x 开头、每段 0..255 且无前导零
        if (s.contains(':') || s.lowercase().startsWith("0x")) return null
        if (!isIpv4(s)) return null
        val parts = s.split(".")
        // isIpv4 已保证四段，这里再显式校验一次段数，避免将来放宽正则时漏出
        if (parts.size != 4) return null
        return parts.map { it.toInt() }.toIntArray()
    }

    /**
     * 允许的地址段判定（R-2 加固 + D-R2-1 补齐绕过 + **Tailscale 远程互联**）。
     *
     * 场景：明文 HTTP 下 token 会随每个请求离开设备。若用户把 host 误填成公网地址，
     * 等于把控制权令牌发到公网。这里在**解析阶段**就拒绝明显的公网地址。
     * 真正的主机名（含 `xxx.local` / 各类域名）无法静态判定，仍放行。
     *
     * 放行段：
     *  - `10/8`、`172.16/12`、`192.168/16`、`169.254/16`、`127/8`：传统局域网；
     *  - **`100.64.0.0/10`（CGNAT 段）**：**Tailscale 等 overlay VPN 使用的地址段**。
     *    这是"手机用 4G/5G 流量远程连家里 PC"这一核心需求所必需的 —— 手机不在同一局域网，
     *    只能走 Tailscale 隧道拿到 100.x 地址。该段外层由 WireGuard 加密，
     *    隧道内仍是明文 HTTP（协议层无需 wss）。
     *
     * ⚠ 边界必须精确：只放行 `100.64.0.0 ~ 100.127.255.255`，
     * **不能"100 开头就放行"**（`100.0.0.1`、`100.255.255.255` 属公网，必须拒绝）。
     */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h == "localhost") return true
        // 不是可识别的 IP 字面量 ⇒ 视为主机名，放行（mDNS / 各类域名）
        if (!looksLikeIpLiteral(h)) return true
        val p = parseIpLiteral(h) ?: return false     // 是 IP 形态但解析不过 ⇒ 拒绝
        return when {
            p[0] == 10 -> true                                   // 10/8
            p[0] == 192 && p[1] == 168 -> true                    // 192.168/16
            p[0] == 172 && p[1] in 16..31 -> true                 // 172.16/12
            p[0] == 169 && p[1] == 254 -> true                    // 169.254/16 link-local
            p[0] == 127 -> true                                   // 127/8 loopback
            // Tailscale / CGNAT 段：手机 4G/5G 远程连接家庭 PC 时使用
            p[0] == 100 && p[1] in 64..127 -> true
            else -> false
        }
    }

    private fun isIpv4(s: String): Boolean {
        val m = IPV4.matchEntire(s) ?: return false
        for (i in 1..4) {
            val part = m.groupValues[i]
            // 拒绝歧义写法：多于 3 位（如 999）、前导零（如 010）；单独的 "0" 合法
            if (part.length > 3) return false
            if (part.length > 1 && part.startsWith("0")) return false
            val v = part.toIntOrNull() ?: return false
            if (v !in 0..255) return false
        }
        return true
    }
}
