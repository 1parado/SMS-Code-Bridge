package com.parado.smsbridge

import com.parado.smsbridge.pairing.InMemoryPairingRepository
import com.parado.smsbridge.pairing.PairingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对凭据持久化测试。
 *
 * 会话密钥使用公开的测试向量值（与 shared/PROTOCOL.md 夹具一致），非真实凭据。
 */
class PairingStoreTest {

    private val testSecret = "973bc978a87938301cfb12ed362128d322e7d72eefc098a2d826f76192d82ab7"

    @Test
    fun create_accepts_valid_credentials() {
        val store = PairingStore.create("192.0.2.10", 45876, testSecret)
        assertNotNull(store)
        assertEquals("192.0.2.10", store!!.host)
        assertEquals(45876, store.port)
        assertEquals(testSecret, store.secretHex)
    }

    @Test
    fun create_trims_host() {
        assertEquals("192.0.2.10", PairingStore.create(" 192.0.2.10 ", 45876, testSecret)!!.host)
    }

    @Test
    fun create_rejects_invalid_fields() {
        assertNull(PairingStore.create("", 45876, testSecret))
        assertNull(PairingStore.create("   ", 45876, testSecret))
        assertNull(PairingStore.create("192.0.2.10", 0, testSecret))
        assertNull(PairingStore.create("192.0.2.10", 70000, testSecret))
        assertNull(PairingStore.create("192.0.2.10", 45876, "aabb"))
        assertNull(PairingStore.create("192.0.2.10", 45876, "zz"))
    }

    @Test
    fun json_roundtrip() {
        val store = PairingStore.create("192.0.2.10", 45876, testSecret)!!
        val restored = PairingStore.fromJson(store.toJson())
        assertNotNull(restored)
        assertEquals(store.host, restored!!.host)
        assertEquals(store.port, restored.port)
        assertEquals(store.secretHex, restored.secretHex)
    }

    @Test
    fun from_json_rejects_garbage_or_incomplete() {
        assertNull(PairingStore.fromJson(null))
        assertNull(PairingStore.fromJson(""))
        assertNull(PairingStore.fromJson("{ broken"))
        assertNull(PairingStore.fromJson("{}"))
        assertNull(PairingStore.fromJson("{\"host\":\"192.0.2.10\"}"))
    }

    @Test
    fun repository_save_load_clear_roundtrip() {
        val repo = InMemoryPairingRepository()
        assertNull("初始状态应无凭据", repo.load())

        val store = PairingStore.create("192.0.2.10", 45876, testSecret)!!
        repo.save(store)
        assertEquals(store.host, repo.load()!!.host)
        assertEquals(store.secretHex, repo.load()!!.secretHex)

        repo.clear()
        assertNull("清除后不得残留凭据", repo.load())
    }

    @Test
    fun invalid_fields_are_rejected_at_creation() {
        assertTrue(PairingStore.create("192.0.2.10", 45876, testSecret)!!.isValid())
        // 空字段在构造时即被拒绝返回 null，而不是给出一个 isValid()==false 的实例
        assertNull(PairingStore.fromJson("{\"host\":\"\",\"port\":0,\"secret_hex\":\"\"}"))
    }
}
