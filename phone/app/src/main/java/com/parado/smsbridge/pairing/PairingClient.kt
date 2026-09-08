package com.parado.smsbridge.pairing

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 配对客户端：配对码校验、会话密钥派生与配对状态机。
 *
 * 会话密钥由 HMAC-SHA256(配对码, 盐值) 派生，**不在网络中传输**，
 * 与 Windows 端 pairing.rs 使用完全相同的算法与固定向量（见测试）。
 */
object PairingClient {

    const val CODE_LENGTH = 6

    enum class State { Idle, Pairing, Paired, Failed }

    data class Session(val sessionId: String, val secretHex: String)

    /** 配对码必须是 6 位数字。 */
    fun isValidCode(input: String?): Boolean =
        !input.isNullOrBlank() && input.length == CODE_LENGTH && input.all { it.isDigit() }

    /** HMAC-SHA256(key = 配对码, message = 盐值)，返回小写十六进制。 */
    fun deriveSecretHex(code: String, salt: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(salt)
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    /** 会话 ID 取密钥前 8 字节的十六进制，与 Windows 端保持一致。 */
    fun sessionIdOf(secretHex: String): String = secretHex.take(16)

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
