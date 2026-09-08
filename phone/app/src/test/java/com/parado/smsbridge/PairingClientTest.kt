package com.parado.smsbridge

import com.parado.smsbridge.pairing.PairingClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对客户端测试。
 *
 * 其中 derive_secret_matches_windows_end 使用跨端固定向量：
 * HMAC-SHA256(key = "123456", message = "salt-0001")，
 * Windows 端 pairing.rs 的 derive_secret_fixed_vector 使用同一向量，
 * 两端若算法不一致，这里会立刻变红。
 */
class PairingClientTest {

    private val fixedVector = "070f816d239ee4cd85583a75e7bd45a8749dea7c72b9777b3a905a1eb520983c"

    @Test
    fun pairing_code_accepts_six_digits_only() {
        assertTrue(PairingClient.isValidCode("123456"))
        assertTrue(PairingClient.isValidCode("000000"))
        assertFalse(PairingClient.isValidCode("12345"))
        assertFalse(PairingClient.isValidCode("1234567"))
        assertFalse(PairingClient.isValidCode("12345a"))
        assertFalse(PairingClient.isValidCode(""))
        assertFalse(PairingClient.isValidCode(null))
    }

    @Test
    fun derive_secret_matches_windows_end() {
        val secret = PairingClient.deriveSecretHex("123456", "salt-0001".toByteArray())
        assertEquals(fixedVector, secret)
    }

    @Test
    fun derive_secret_is_salt_dependent() {
        val a = PairingClient.deriveSecretHex("123456", "salt-0001".toByteArray())
        val b = PairingClient.deriveSecretHex("123456", "salt-0002".toByteArray())
        val c = PairingClient.deriveSecretHex("654321", "salt-0001".toByteArray())
        assertFalse(a == b)
        assertFalse(a == c)
        assertEquals(64, a.length)
    }

    @Test
    fun session_id_is_first_eight_bytes_hex() {
        assertEquals(fixedVector.take(16), PairingClient.sessionIdOf(fixedVector))
    }

    @Test
    fun state_machine_transitions() {
        val machine = PairingClient.StateMachine()
        assertEquals(PairingClient.State.Idle, machine.state)
        assertNull(machine.session)

        assertTrue(machine.start())
        assertEquals(PairingClient.State.Pairing, machine.state)
        assertFalse("配对中不应重复发起", machine.start())

        machine.succeed(fixedVector)
        assertEquals(PairingClient.State.Paired, machine.state)
        assertEquals(fixedVector.take(16), machine.session?.sessionId)
    }

    @Test
    fun failed_pairing_clears_partial_state() {
        val machine = PairingClient.StateMachine()
        machine.start()
        machine.fail()
        assertEquals(PairingClient.State.Failed, machine.state)
        assertNull("失败后不得残留会话", machine.session)
    }

    @Test
    fun unbind_returns_to_idle() {
        val machine = PairingClient.StateMachine()
        machine.start()
        machine.succeed(fixedVector)
        machine.unbind()
        assertEquals(PairingClient.State.Idle, machine.state)
        assertNull(machine.session)
    }
}
