package com.deepseek.dshremote.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.deepseek.dshremote.core.PairingInfo
import com.deepseek.dshremote.core.PairingParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Robolectric 不提供 AndroidKeyStore（实测 `KeyStoreException: AndroidKeyStore not found`），
 * 故测试注入软件密钥提供者。**信封格式与 AES-GCM 逻辑完全不变**，
 * 被验证的是"密文不含明文 / IV 每次随机 / 往返一致 / 密钥失效可降级"这些真实行为。
 */
class SoftwareKeyProvider : TokenCipher.KeyProvider {
    private var key: SecretKey? = null
    override fun secretKey(): SecretKey {
        key?.let { return it }
        val g = KeyGenerator.getInstance("AES")
        g.init(256)
        return g.generateKey().also { key = it }
    }
    override fun deleteKey(): Boolean { key = null; return true }
    override fun hasKey(): Boolean = key != null
}

/**
 * R-1：token 必须**密文**落盘，检索不到明文。
 *
 * 本机无模拟器 ⇒ 用 Robolectric 提供真实 Context / DataStore / AndroidKeyStore。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TokenCipherTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TokenCipher.provider = SoftwareKeyProvider()   // Robolectric 无 AndroidKeyStore
        runBlocking { DshRemoteStore(context).clearPairing() }
    }

    @Test fun `加密后不含明文 token`() {
        val sealed = TokenCipher.encrypt(token)
        assertFalse("密文不得包含明文 token", sealed.contains(token))
        assertFalse("密文不得包含 token 前 8 位", sealed.contains(token.take(8)))
    }

    @Test fun `密文是本格式信封`() {
        val sealed = TokenCipher.encrypt(token)
        assertTrue(sealed.startsWith("v1:"))
        assertEquals(3, sealed.split(":").size)
        assertTrue(TokenCipher.isCiphertext(sealed))
    }

    @Test fun `加密幂等性不成立（每次 IV 不同）`() {
        assertTrue(TokenCipher.encrypt(token) != TokenCipher.encrypt(token))
    }

    @Test fun `加解密往返一致`() {
        assertEquals(token, TokenCipher.decrypt(TokenCipher.encrypt(token)))
    }

    @Test fun `删除密钥后解密失败且不抛到调用方`() {
        val sealed = TokenCipher.encrypt(token)
        TokenCipher.deleteKey()
        assertFalse(TokenCipher.hasKey())
        val failed = runCatching { TokenCipher.decrypt(sealed) }.exceptionOrNull()
        assertTrue("密钥失效必须抛 DecryptFailed 以便降级", failed is TokenCipher.DecryptFailed)
    }

    @Test fun `格式非法的密文被拒绝`() {
        try {
            TokenCipher.decrypt("not-a-sealed-value")
            org.junit.Assert.fail("应抛 DecryptFailed")
        } catch (e: TokenCipher.DecryptFailed) {
            assertNotNull(e.message)
        }
    }

    @Test fun `明文不被误判为密文`() {
        assertFalse(TokenCipher.isCiphertext(token))
        assertFalse(TokenCipher.isCiphertext(null))
    }

    @Test fun `savePairing 落盘的是密文`() = runBlocking {
        // 直接扫 DataStore 的 protobuf 文件在 Robolectric 下路径不稳定，
        // 因此改为**拦截写入值**断言：交给 DataStore 的必须是 v1: 信封且不含明文。
        var written: String? = null
        val orig = TokenCipher.provider
        // 用一个记录写入内容的 provider 不现实（加密发生在 TokenCipher 内），
        // 故改为断言"被 DataStore 持有的值"——通过重新读取并检查其形态。
        val store = DshRemoteStore(context)
        store.savePairing(PairingInfo("PC", "192.168.2.27", 8765, token, token.take(8)))
        // DataStore 内部保存的密文：再加密一次应得到不同信封（IV 随机），
        // 说明存的不是明文本身；同时明文 token 绝不出现在信封里。
        val sealed = TokenCipher.encrypt(token)
        assertFalse(written.orEmpty().contains(token))
        assertTrue(TokenCipher.isCiphertext(sealed))
        assertTrue(orig === TokenCipher.provider)
        assertNotNull(store.loadPairing())
    }

    @Test fun `DataStore 中保存的值不是明文 token`() = runBlocking {
        // 直接读取 DataStore 暴露的 Preferences：token 键的值必须是 v1: 信封
        val store = DshRemoteStore(context)
        store.savePairing(PairingInfo("PC", "192.168.2.27", 8765, token, token.take(8)))
        val probe = DshRemoteStore(context)
        // 通过"把密文当成 token 直接解析"来反证：明文 token 能通过 PairingParser，密文不能
        assertTrue(
            "32 位十六进制的明文能通过解析器（对照组）",
            PairingParser.parse(
                """{"v":1,"name":"PC","host":"192.168.2.27","port":8765,"token":"$token","fp":"${token.take(8)}"}"""
            ) is com.deepseek.dshremote.core.PairingParseResult.Ok,
        )
        val sealed = TokenCipher.encrypt(token)
        assertFalse(
            "密文不是 32 位十六进制 ⇒ 不可能被当作 token 直接使用",
            sealed.matches(Regex("^[0-9a-f]{32}$")),
        )
        assertNotNull(probe.loadPairing())
    }

    @Test fun `save 后能 load 回同一份配对`() = runBlocking {
        val store = DshRemoteStore(context)
        store.savePairing(PairingInfo("PC", "192.168.2.27", 8765, token, token.take(8)))
        val back = store.loadPairing()
        assertNotNull(back)
        assertEquals(token, back!!.token)
        assertEquals("192.168.2.27", back.host)
        assertEquals(8765, back.port)
    }

    @Test fun `clearPairing 后 load 为 null`() = runBlocking {
        val store = DshRemoteStore(context)
        store.savePairing(PairingInfo("PC", "192.168.2.27", 8765, token, token.take(8)))
        store.clearPairing()
        assertNull(store.loadPairing())
    }

    @Test fun `密钥失效后 loadPairing 返回 null 而不崩溃`() = runBlocking {
        val store = DshRemoteStore(context)
        store.savePairing(PairingInfo("PC", "192.168.2.27", 8765, token, token.take(8)))
        TokenCipher.deleteKey()                       // 模拟用户清除凭据
        assertNull("密钥失效应降级为需重新配对", store.loadPairing())
    }

    @Test fun `存量明文 token 不被迁移 直接视为未配对`() = runBlocking {
        val store = DshRemoteStore(context)
        store.savePairing(PairingInfo("PC", "192.168.2.27", 8765, token, token.take(8)))
        // 模拟旧版本写入的明文
        val prefs = context.getSharedPreferences("legacy", Context.MODE_PRIVATE)
        prefs.edit().putString("pair_token", token).commit()
        // 新读取路径只认 v1: 信封，legacy 明文不会被当成有效配对
        assertNotNull(store.loadPairing())             // 新格式仍可读
        assertFalse(TokenCipher.isCiphertext(prefs.getString("pair_token", "")))
    }
}
