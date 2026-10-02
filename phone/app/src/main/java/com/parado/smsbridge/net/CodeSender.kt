package com.parado.smsbridge.net

import com.parado.smsbridge.protocol.Crypto
import com.parado.smsbridge.protocol.Protocol

/**
 * 验证码加密发送：界面与前台服务共用同一条链路。
 *
 * AES-256-GCM(会话密钥)，IV 随机 12 字节，AAD 绑定时间戳——与 Windows 端
 * receiver.rs 的解密逻辑互为镜像。
 */
object CodeSender {

    /** 加密并单播一条验证码；参数非法或发送失败返回 false。 */
    fun send(host: String, port: Int, secretHex: String, code: String): Boolean {
        if (host.isBlank() || port !in 1..65535 || code.isEmpty()) return false
        val secret = Crypto.hexToBytes(secretHex) ?: return false
        if (secret.size != 32) return false
        val ts = System.currentTimeMillis()
        val iv = Crypto.randomBytes(Crypto.IV_LEN)
        val aad = "code|$ts".toByteArray(Charsets.UTF_8)
        val ciphertext = Crypto.aesGcmSeal(secret, iv, aad, code.toByteArray(Charsets.UTF_8))
        val message = Protocol.Message.Code(
            Protocol.VERSION,
            ts,
            Crypto.toHex(iv),
            Crypto.toHex(ciphertext),
        )
        return UdpSender.sendText(host, port, Protocol.toJson(message))
    }
}
