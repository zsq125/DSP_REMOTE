package com.deepseek.dshremote.core

import com.deepseek.dshremote.protocol.Json
import com.deepseek.dshremote.protocol.JsonObjectReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cold-start pairing read (2026-10-12).
 *
 * The App once showed a blank screen with ZERO network calls. This pins down which
 * of the three outcomes [DshRemoteStore.loadPairingDetailed] can produce, and --
 * crucially -- that a **valid stored pairing produces a usable host/port/token**,
 * because the load path is gated on exactly that.
 */
class PairingReadLogicTest {

    init {
        // Robolectric has no AndroidKeyStore and `android.util.Base64` is a
        // throwing stub off-device, so use the software key + JVM codec.
        com.deepseek.dshremote.data.TokenCipher.provider =
            com.deepseek.dshremote.data.SoftwareKeyProvider()
        com.deepseek.dshremote.data.TokenCipher.codec =
            com.deepseek.dshremote.data.TokenCipher.JvmBase64
    }

    // Mirrors the datastore contents observed on the device.
    private val storedHost = "100.64.1.5"
    private val storedPort = "8765"
    private val storedFp = "01234567"
    private val storedToken = "0123456789abcdef0123456789abcdef"
    private val storedName = "*"

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Rebuilds exactly the JSON that loadPairingDetailed() hands to the parser.
     *
     * NOTE: it receives the **decrypted** token, mirroring the real flow:
     * `loadPairingDetailed` decrypts first, then interpolates the plaintext.
     */
    private fun rebuild(plainToken: String): String =
        """{"v":1,"name":${quote(storedName)},"host":"$storedHost","port":$storedPort,"token":"$plainToken","fp":"${storedFp}"}"""

    @Test fun `明文 token 被判为未配对（不迁移）`() {
        // Non-envelope data (legacy plaintext) must NOT be treated as recoverable.
        assertTrue(
            "明文 token 不应通过 isCiphertext",
            !com.deepseek.dshremote.data.TokenCipher.isCiphertextForTest(storedToken),
        )
    }

    @Test fun `真实配对数据能通过解析并给出非空 host`() {
        val sealed = com.deepseek.dshremote.data.TokenCipher.encryptForTest(storedToken)
        assertTrue(com.deepseek.dshremote.data.TokenCipher.isCiphertextForTest(sealed))
        // The real flow: decrypt -> rebuild JSON with the PLAINTEXT token -> parse.
        val parsed = PairingParser.parse(rebuild(com.deepseek.dshremote.data.TokenCipher.decrypt(sealed)))
        assertTrue("有效配对必须解析成功，实际=$parsed", parsed is PairingParseResult.Ok)
        val info = (parsed as PairingParseResult.Ok).info
        assertEquals(storedHost, info.host)
        assertEquals(8765, info.port)
        assertEquals(storedToken, info.token)
        assertEquals("http://100.64.1.5:8765", info.baseUrl)
        assertTrue("baseUrl 必须非空，否则 IO 被门控挡住（本次空白的直接原因）", info.baseUrl.isNotEmpty())
    }

    @Test fun `WS 地址与 REST 地址同源`() {
        val info = PairingInfo("pc", storedHost, 8765, storedToken, storedFp)
        assertEquals("http://100.64.1.5:8765", info.baseUrl)
        assertTrue(info.streamUrl().startsWith("ws://100.64.1.5:8765/rc/v1/stream?token="))
    }

    @Test fun `fp 与 token 前 8 位一致（设备实测值）`() {
        // The stored fp is 01234567 and the stored token starts with the same
        // 8 chars, which is exactly what loadPairingDetailed() re-validates.
        val parsed = PairingParser.parse(rebuild(storedToken))
        assertTrue("fp/token 一致时应解析成功，实际=$parsed", parsed is PairingParseResult.Ok)
        val info = (parsed as PairingParseResult.Ok).info
        assertEquals(storedToken.take(8), info.fp)
        assertEquals(storedHost, info.host)
    }

    @Test fun `Tailscale 网关地址被接受`() {
        // 100.64.1.5 is inside CGNAT 100.64/10 -- this exact host is in use.
        assertTrue(PairingParser.isPrivateHost("100.64.1.5"))
        assertTrue(PairingParser.parse(rebuild(storedToken)) is PairingParseResult.Ok)
    }

    @Test fun `端口与 token 格式非法时报 Invalid 而非崩溃`() {
        val bad = PairingParser.parse(
            """{"v":1,"name":"pc","host":"100.64.1.5","port":0,"token":"$storedToken","fp":"$storedFp"}"""
        )
        assertTrue(bad is PairingParseResult.Invalid)
    }

    @Test fun `公共地址被拒绝`() {
        assertTrue(!PairingParser.isPrivateHost("8.8.8.8"))
    }
}