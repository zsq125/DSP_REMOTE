package com.deepseek.dshremote.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配对 token 的加密存储（裁定 R-1）。
 *
 * **为什么不用 `androidx.security:security-crypto`**：它会引入一整条依赖链，
 * 而规范 §4 明令禁止 kapt/KSP 等重型注解处理器。这里用**纯 JCE + AndroidKeyStore**：
 * 密钥在系统 Keystore 内生成、**永不出 TEE**（App 只拿句柄），用 AES-GCM 加密后才写 DataStore。
 *
 * **失败即需重新配对**（不崩）：用户清除凭据 / Keystore 重置 ⇒ `KeyPermanentlyInvalidatedException`
 * 等，捕获后删掉失效密钥与密文，调用方据此把状态标记为"未配对"。
 */
object TokenCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dsh_remote_pair_token_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 存储格式：`v1:<base64(iv)>:<base64(ciphertext)>` */
    private const val PREFIX = "v1"

    class DecryptFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 密钥提供者。
     *
     * 抽出接口的原因：**Robolectric 不提供 AndroidKeyStore**（实测
     * `KeyStoreException: AndroidKeyStore not found`），而 AES-GCM 的信封格式、
     * "密文不含明文"、IV 每次随机、密钥失效降级等**逻辑**必须在 JVM 上可测。
     * Android 运行时用 [AndroidKeyStoreProvider]；单测用软件密钥的 [SoftwareProvider]。
     */
    interface KeyProvider {
        /** 取密钥；没有则生成。失败抛异常（由调用方降级为"需重新配对"）。 */
        fun secretKey(): SecretKey
        fun deleteKey(): Boolean
        fun hasKey(): Boolean
    }

    /** 生产实现：密钥在系统 Keystore 内，App 永远拿不到明文密钥。 */
    object AndroidKeyStoreProvider : KeyProvider {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dsh_remote_pair_token_v1"

        private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        override fun secretKey(): SecretKey {
            val ks = keyStore()
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // 不要求用户解锁（后台保活需要），也不在首次使用时阻塞认证
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            return gen.generateKey()
        }

        override fun deleteKey(): Boolean = runCatching { keyStore().deleteEntry(KEY_ALIAS); true }.getOrDefault(false)

        override fun hasKey(): Boolean = runCatching { keyStore().containsAlias(KEY_ALIAS) }.getOrDefault(false)
    }

    /** 当前生效的提供者；测试可替换（Robolectric 无 AndroidKeyStore）。 */
    @Volatile
    var provider: KeyProvider = AndroidKeyStoreProvider

    private fun secretKey(): SecretKey = provider.secretKey()

    /** 加密；返回可直接落盘的字符串。 */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size == IV_LEN) { "unexpected GCM iv length ${iv.size}" }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "$PREFIX:${b64(iv)}:${b64(ct)}"
    }

    /** 解密；失败抛 [DecryptFailed]。 */
    fun decrypt(stored: String): String {
        val parts = stored.split(":")
        if (parts.size != 3 || parts[0] != PREFIX) throw DecryptFailed("malformed ciphertext envelope")
        val iv = unb64(parts[1]); val ct = unb64(parts[2])
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥失效 / 密文被篡改 / 换了设备 —— 一律降级为"需重新配对"
            throw DecryptFailed("token cannot be decrypted on this device", e)
        }
    }

    /** 删除密钥（用户主动"清除配对"时调用）。 */
    fun deleteKey(): Boolean = runCatching { provider.deleteKey() }.getOrDefault(false)

    /** 当前是否持有可用密钥。 */
    fun hasKey(): Boolean = runCatching { provider.hasKey() }.getOrDefault(false)

    /** 是否是本格式的密文（用于判断存量明文数据）。 */
    fun isCiphertext(s: String?): Boolean = s?.startsWith("$PREFIX:") == true

    // ---- test seams -------------------------------------------------
    // Robolectric provides no AndroidKeyStore, so pure-JVM tests swap in a
    // software key. They also need a base64 codec, because `android.util.Base64`
    // is a throwing stub off-device. Both seams leave the production path intact.

    fun isCiphertextForTest(s: String?): Boolean = isCiphertext(s)

    fun encryptForTest(plain: String): String = encrypt(plain)

    /** Base64 codec override; defaults to the platform one. */
    @Volatile
    var codec: Base64Codec = PlatformBase64

    interface Base64Codec {
        fun encode(b: ByteArray): String
        fun decode(s: String): ByteArray
    }

    /** Platform codec (used on device). */
    object PlatformBase64 : Base64Codec {
        override fun encode(b: ByteArray): String =
            android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        override fun decode(s: String): ByteArray = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
    }

    /** JVM codec for pure unit tests (android.util.Base64 is a stub there). */
    object JvmBase64 : Base64Codec {
        private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        override fun encode(b: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 2 < b.size) {
                val n = ((b[i].toInt() and 0xff) shl 16) or ((b[i + 1].toInt() and 0xff) shl 8) or (b[i + 2].toInt() and 0xff)
                sb.append(A[(n shr 18) and 63]).append(A[(n shr 12) and 63])
                sb.append(A[(n shr 6) and 63]).append(A[n and 63])
                i += 3
            }
            when (b.size - i) {
                1 -> {
                    val n = (b[i].toInt() and 0xff) shl 16
                    sb.append(A[(n shr 18) and 63]).append(A[(n shr 12) and 63]).append("==")
                }
                2 -> {
                    val n = ((b[i].toInt() and 0xff) shl 16) or ((b[i + 1].toInt() and 0xff) shl 8)
                    sb.append(A[(n shr 18) and 63]).append(A[(n shr 12) and 63]).append(A[(n shr 6) and 63]).append('=')
                }
            }
            return sb.toString()
        }

        override fun decode(s: String): ByteArray {
            val clean = s.trimEnd('=')
            val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4 + 3)
            var buf = 0
            var bits = 0
            for (c in clean) {
                if (c == '\n' || c == '\r') continue
                val v = A.indexOf(c)
                if (v < 0) continue
                buf = (buf shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buf shr bits) and 0xff)
                }
            }
            return out.toByteArray()
        }
    }

    private fun b64(b: ByteArray): String = codec.encode(b)
    private fun unb64(s: String): ByteArray = codec.decode(s)
}
