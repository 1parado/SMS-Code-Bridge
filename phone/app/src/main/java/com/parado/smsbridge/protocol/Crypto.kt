package com.parado.smsbridge.protocol

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 协议 v2 的密码学原语：PBKDF2-HMAC-SHA256、HMAC-SHA256 与 AES-256-GCM。
 *
 * 与 Windows 端（RustCrypto `pbkdf2` / `aes-gcm`）保持字节级一致，
 * 由 shared/testdata 固定向量与两端测试共同锁定。
 * PBKDF2 手工实现：minSdk 24 的 SecretKeyFactory 不保证提供 HmacSHA256 变体。
 */
object Crypto {

    /** AES-GCM IV 长度（字节）。 */
    const val IV_LEN = 12

    /** AES-GCM 认证标签长度（字节）。 */
    const val TAG_LEN = 16

    /** 配对盐值长度（字节）。 */
    const val SALT_LEN = 16

    private val random = SecureRandom()

    /** 生成 n 字节密码学安全随机数。 */
    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /**
     * PBKDF2-HMAC-SHA256（RFC 8018）。
     * 派生长度固定 32 字节（= hLen），只需单个块：U1 = HMAC(p, salt || INT(1))，
     * Ti = U1 ⊕ U2 ⊕ … ⊕ Uc。
     */
    fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations >= 1) { "iterations 必须为正" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 2..iterations) {
            mac.update(u)
            u = mac.doFinal()
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return t
    }

    /** HMAC-SHA256，返回 32 字节。 */
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** AES-256-GCM 加密，返回「密文‖16 字节认证标签」。 */
    fun aesGcmSeal(key: ByteArray, iv: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 密钥必须为 32 字节" }
        require(iv.size == IV_LEN) { "GCM IV 必须为 $IV_LEN 字节" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    /** AES-256-GCM 认证解密；认证标签或 AAD 不符时返回 null。 */
    fun aesGcmOpen(key: ByteArray, iv: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        require(key.size == 32) { "AES-256 密钥必须为 32 字节" }
        require(iv.size == IV_LEN) { "GCM IV 必须为 $IV_LEN 字节" }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed)
        } catch (_: Exception) {
            null
        }
    }

    /** 字节转小写十六进制，与 Windows 端 to_hex 一致。 */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }

    /** 十六进制转字节；长度非偶数或含非法字符时返回 null。 */
    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
