package com.parado.smsbridge.pairing

import com.parado.smsbridge.protocol.Crypto

/**
 * 配对客户端：配对码校验、会话密钥派生与配对状态机（协议 v2）。
 *
 * 派生链（与 Windows 端 pairing.rs 完全一致，见跨端固定向量测试）：
 * 1. 本地生成随机盐，证明 = PBKDF2-HMAC-SHA256(配对码, 盐) 随请求上网——**配对码不上网**；
 * 2. 会话密钥 = HMAC-SHA256(证明, "session-v2")，**不在网络中传输**；
 * 3. 会话 ID = HMAC(密钥, "session-id") 前 8 字节，电脑端回传后据此验证对端
 *    确已派生同一密钥（防伪造 PairResponse）。
 */
object PairingClient {

    const val CODE_LENGTH = 6

    /** 配对证明的 PBKDF2 迭代次数，与 Windows 端 PROOF_ITERATIONS 一致。 */
    const val PROOF_ITERATIONS = 100_000

    private const val SESSION_LABEL = "sms-code-bridge/session-v2"
    private const val SESSION_ID_LABEL = "session-id"

    enum class State { Idle, Pairing, Paired, Failed }

    data class Session(val sessionId: String, val secretHex: String) {
        /** 会话密钥属敏感凭据，toString 一律脱敏，防止日志泄露。 */
        override fun toString(): String = "Session(sessionId=$sessionId, secret=<redacted>)"
    }

    /** 配对码必须是 6 位数字。 */
    fun isValidCode(input: String?): Boolean =
        !input.isNullOrBlank() && input.length == CODE_LENGTH && input.all { it.isDigit() }

    /** 生成一次性配对盐值（16 字节，十六进制）。 */
    fun createSaltHex(): String = Crypto.toHex(Crypto.randomBytes(Crypto.SALT_LEN))

    /** 配对证明：PBKDF2-HMAC-SHA256(配对码, 盐)，返回小写十六进制。 */
    fun deriveProofHex(code: String, salt: ByteArray, iterations: Int = PROOF_ITERATIONS): String =
        Crypto.toHex(Crypto.pbkdf2HmacSha256(code.toByteArray(Charsets.UTF_8), salt, iterations))

    /** 会话密钥：HMAC-SHA256(key = 证明, message = 会话标签)，返回小写十六进制。 */
    fun deriveSecretHex(proofHex: String): String {
        val proof = requireNotNull(Crypto.hexToBytes(proofHex)) { "证明十六进制非法" }
        return Crypto.toHex(Crypto.hmacSha256(proof, SESSION_LABEL.toByteArray(Charsets.UTF_8)))
    }

    /** 会话 ID 取 HMAC(密钥, "session-id") 前 8 字节的十六进制，与 Windows 端保持一致。 */
    fun sessionIdOf(secretHex: String): String {
        val secret = requireNotNull(Crypto.hexToBytes(secretHex)) { "密钥十六进制非法" }
        val mac = Crypto.hmacSha256(secret, SESSION_ID_LABEL.toByteArray(Charsets.UTF_8))
        return Crypto.toHex(mac.copyOfRange(0, 8))
    }

    /** 解绑认证：HMAC-SHA256(会话密钥, "unpair|{ts}")，十六进制。 */
    fun unpairMacHex(secretHex: String, ts: Long): String {
        val secret = requireNotNull(Crypto.hexToBytes(secretHex)) { "密钥十六进制非法" }
        return Crypto.toHex(Crypto.hmacSha256(secret, "unpair|$ts".toByteArray(Charsets.UTF_8)))
    }

    /**
     * 校验 PairResponse：ok 为真且 session_id 与本地派生值一致——
     * 伪造者没有会话密钥就算不出这个值。
     */
    fun validatePairResponse(sessionId: String?, secretHex: String): Boolean =
        sessionId != null && sessionId == sessionIdOf(secretHex)

    /** 配对状态机：未配对 → 配对中 → 已配对 / 失败。 */
    class StateMachine {
        var state: State = State.Idle
            private set

        var session: Session? = null
            private set

        fun start(): Boolean {
            if (state == State.Pairing) return false
            state = State.Pairing
            session = null
            return true
        }

        fun succeed(secretHex: String) {
            state = State.Paired
            session = Session(sessionIdOf(secretHex), secretHex)
        }

        /** 失败或取消：清除一切中间状态，避免残留半成品凭据。 */
        fun fail() {
            state = State.Failed
            session = null
        }

        fun unbind() {
            state = State.Idle
            session = null
        }
    }
}
