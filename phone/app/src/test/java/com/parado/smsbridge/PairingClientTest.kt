package com.parado.smsbridge

import com.parado.smsbridge.pairing.PairingClient
import com.parado.smsbridge.protocol.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对客户端测试（协议 v2）。
 *
 * 跨端固定向量与 Windows 端 pairing.rs / shared/testdata 一致，
 * 两端若算法出现偏差，这里会立刻变红：
 * - RFC 7914：PBKDF2-HMAC-SHA256 已知向量
 * - shared 夹具：code="123456" + 生产迭代次数
 * - 快速向量：code="654321" + 1000 次（低迭代，测试提速）
 */
class PairingClientTest {

    private val fixtureSalt = "30313233343536373839616263646566"
    private val fastProof = "78e979149d45d82751d78cb463aec93679a2af029de714fa630b961c6fe06da7"
    private val fastSecret = "973bc978a87938301cfb12ed362128d322e7d72eefc098a2d826f76192d82ab7"
    private val fastSessionId = "b712290aa3746a5a"

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
    fun pbkdf2_matches_rfc7914_vectors() {
        val salt = "salt".toByteArray()
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            PairingClient.deriveProofHex("password", salt, 1),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            PairingClient.deriveProofHex("password", salt, 2),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            PairingClient.deriveProofHex("password", salt, 4096),
        )
    }

    // 与 shared/testdata/pair_request.json 的 proof_hex 一致（生产迭代次数）。
    @Test
    fun proof_matches_shared_fixture() {
        val salt = Crypto.hexToBytes(fixtureSalt)!!
        assertEquals(
            "887fc04592766b594b2abe0b9ade1a53da0560339eb3c7d2903f38dd2000116e",
            PairingClient.deriveProofHex("123456", salt),
        )
    }

    // 快速跨端固定向量：Windows 端 pairing.rs 的 secret_and_session_id_match_android_end。
    @Test
    fun secret_and_session_id_match_windows_end() {
        val proof = PairingClient.deriveProofHex("654321", "salt-0002".toByteArray(), 1_000)
        assertEquals(fastProof, proof)
        val secret = PairingClient.deriveSecretHex(proof)
        assertEquals(fastSecret, secret)
        assertEquals(fastSessionId, PairingClient.sessionIdOf(secret))
    }

    // 证明与会话密钥必须分离：上网的证明不能直接当密钥用。
    @Test
    fun secret_is_not_the_proof_itself() {
        assertNotEquals(fastProof, PairingClient.deriveSecretHex(fastProof))
    }

    // 与 Windows 端 receiver.rs 的 unpair_mac_matches_shared_fixture 一致。
    @Test
    fun unpair_mac_matches_windows_end() {
        val secret = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        assertEquals(
            "86279a29238265aa8ee995e3909b0ab5773cb3df5d84f1c666605d0483eb19c6",
            PairingClient.unpairMacHex(secret, 1_757_337_600_000L),
        )
    }

    @Test
    fun validate_pair_response_accepts_matching_session_id_only() {
        assertTrue(PairingClient.validatePairResponse(fastSessionId, fastSecret))
        assertFalse(PairingClient.validatePairResponse("0123456789abcdef", fastSecret))
        assertFalse(PairingClient.validatePairResponse(null, fastSecret))
    }

    @Test
    fun salt_is_random_and_valid_hex() {
        val first = PairingClient.createSaltHex()
        val second = PairingClient.createSaltHex()
        assertEquals(32, first.length)
        assertNotEquals(first, second)
        assertEquals(16, Crypto.hexToBytes(first)!!.size)
    }

    @Test
    fun session_to_string_never_leaks_secret() {
        val machine = PairingClient.StateMachine()
        machine.succeed(fastSecret)
        val session = machine.session!!
        assertFalse("toString 不得泄露会话密钥", session.toString().contains(fastSecret))
        assertTrue(session.toString().contains("<redacted>"))
    }

    @Test
    fun state_machine_transitions() {
        val machine = PairingClient.StateMachine()
        assertEquals(PairingClient.State.Idle, machine.state)
        assertNull(machine.session)

        assertTrue(machine.start())
        assertEquals(PairingClient.State.Pairing, machine.state)
        assertFalse("配对中不应重复发起", machine.start())

        machine.succeed(fastSecret)
        assertEquals(PairingClient.State.Paired, machine.state)
        assertEquals(fastSessionId, machine.session?.sessionId)
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
        machine.succeed(fastSecret)
        machine.unbind()
        assertEquals(PairingClient.State.Idle, machine.state)
        assertNull(machine.session)
    }
}
