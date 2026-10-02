package com.parado.smsbridge

import com.parado.smsbridge.protocol.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 协议密码学原语测试。
 *
 * 固定向量与 Windows 端（RustCrypto）及 shared/testdata 夹具一致，
 * 保证两端密文与派生结果字节级相同。
 */
class CryptoTest {

    // shared/PROTOCOL.md 约定的夹具测试密钥：000102...1f。
    private val fixtureSecret = ByteArray(32) { it.toByte() }
    private val fixtureIv = "000102030405060708090a0b"
    private val fixtureTs = 1_757_337_600_000L

    @Test
    fun pbkdf2_matches_rfc7914_vector() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            Crypto.toHex(Crypto.pbkdf2HmacSha256("password".toByteArray(), "salt".toByteArray(), 1)),
        )
    }

    @Test
    fun hmac_matches_known_vector() {
        // RFC 4231 风格的经典向量（HMAC-SHA256, key="key"）
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            Crypto.toHex(Crypto.hmacSha256("key".toByteArray(), "The quick brown fox jumps over the lazy dog".toByteArray())),
        )
    }

    @Test
    fun gcm_seal_matches_shared_fixture() {
        // shared/testdata/code_message.json 的 ct_hex（明文 "482913"，AAD "code|{ts}"）
        val iv = Crypto.hexToBytes(fixtureIv)!!
        val aad = "code|$fixtureTs".toByteArray()
        val sealed = Crypto.aesGcmSeal(fixtureSecret, iv, aad, "482913".toByteArray())
        assertEquals("733ae422f4d65d9adc9f1e88ba1812b0a0ebc425b984", Crypto.toHex(sealed))
    }

    @Test
    fun gcm_roundtrip() {
        val iv = Crypto.randomBytes(Crypto.IV_LEN)
        val aad = "code|123".toByteArray()
        val plain = "864209".toByteArray()
        val sealed = Crypto.aesGcmSeal(fixtureSecret, iv, aad, plain)
        assertArrayEquals(plain, Crypto.aesGcmOpen(fixtureSecret, iv, aad, sealed))
    }

    @Test
    fun gcm_rejects_tampered_ciphertext() {
        val iv = Crypto.hexToBytes(fixtureIv)!!
        val sealed = Crypto.aesGcmSeal(fixtureSecret, iv, "code|1".toByteArray(), "482913".toByteArray())
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(Crypto.aesGcmOpen(fixtureSecret, iv, "code|1".toByteArray(), tampered))
    }

    @Test
    fun gcm_rejects_wrong_aad() {
        val iv = Crypto.hexToBytes(fixtureIv)!!
        val sealed = Crypto.aesGcmSeal(fixtureSecret, iv, "code|1".toByteArray(), "482913".toByteArray())
        assertNull(Crypto.aesGcmOpen(fixtureSecret, iv, "code|2".toByteArray(), sealed))
    }

    @Test
    fun gcm_rejects_wrong_key() {
        val iv = Crypto.hexToBytes(fixtureIv)!!
        val sealed = Crypto.aesGcmSeal(fixtureSecret, iv, "code|1".toByteArray(), "482913".toByteArray())
        val otherKey = ByteArray(32) { 9 }
        assertNull(Crypto.aesGcmOpen(otherKey, iv, "code|1".toByteArray(), sealed))
    }

    @Test
    fun random_bytes_have_requested_length_and_vary() {
        assertEquals(16, Crypto.randomBytes(16).size)
        assertNotEquals(Crypto.toHex(Crypto.randomBytes(16)), Crypto.toHex(Crypto.randomBytes(16)))
    }

    @Test
    fun hex_helpers_roundtrip_and_reject_garbage() {
        val bytes = byteArrayOf(0x00, 0x0f, (0xff).toByte())
        assertEquals("000fff", Crypto.toHex(bytes))
        assertArrayEquals(bytes, Crypto.hexToBytes("000fff"))
        assertNull(Crypto.hexToBytes("0"))
        assertNull(Crypto.hexToBytes("zz"))
    }
}
