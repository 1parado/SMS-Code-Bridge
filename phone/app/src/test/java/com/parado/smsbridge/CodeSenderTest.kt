package com.parado.smsbridge

import com.parado.smsbridge.net.CodeSender
import com.parado.smsbridge.protocol.Crypto
import com.parado.smsbridge.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * 加密发码链路端到端测试（回环）：
 * CodeSender 加密发送 → 本地伪电脑端接收 → 按 shared/PROTOCOL.md 同款
 * 解密流程还原验证码，证明「手机加密 → 电脑可解」这条链路两端一致。
 */
class CodeSenderTest {

    // shared/PROTOCOL.md 约定的夹具测试密钥（公开测试向量，非真实凭据）
    private val secretHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    @Test
    fun sends_encrypted_code_that_a_computer_can_decrypt() {
        val receiver = DatagramSocket(0).apply { soTimeout = 2_000 }
        val port = receiver.localPort

        assertTrue(CodeSender.send("127.0.0.1", port, secretHex, "482913"))

        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        receiver.receive(packet)
        receiver.close()

        val message = Protocol.parse(
            String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
        ) as Protocol.Message.Code

        val secret = Crypto.hexToBytes(secretHex)!!
        val iv = Crypto.hexToBytes(message.ivHex)!!
        val aad = "code|${message.ts}".toByteArray(Charsets.UTF_8)
        val plain = Crypto.aesGcmOpen(
            secret,
            iv,
            aad,
            Crypto.hexToBytes(message.ctHex)!!,
        )
        assertEquals("482913", plain?.toString(Charsets.UTF_8))
    }

    @Test
    fun rejects_invalid_inputs() {
        assertFalse(CodeSender.send("", 45876, secretHex, "482913"))
        assertFalse(CodeSender.send("127.0.0.1", 0, secretHex, "482913"))
        assertFalse(CodeSender.send("127.0.0.1", 45876, secretHex, ""))
        assertFalse(CodeSender.send("127.0.0.1", 45876, "not-hex", "482913"))
        assertFalse(CodeSender.send("127.0.0.1", 45876, "aabb", "482913"))
    }

    @Test
    fun each_message_uses_a_fresh_iv() {
        // 两条消息的 IV 必须不同（GCM IV 复用会彻底破坏机密性）
        val receiver = DatagramSocket(0).apply { soTimeout = 2_000 }
        val port = receiver.localPort

        assertTrue(CodeSender.send("127.0.0.1", port, secretHex, "111111"))
        val first = receiveCode(receiver)
        assertTrue(CodeSender.send("127.0.0.1", port, secretHex, "222222"))
        val second = receiveCode(receiver)
        receiver.close()

        assertTrue(first.ivHex != second.ivHex)
    }

    private fun receiveCode(receiver: DatagramSocket): Protocol.Message.Code {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        receiver.receive(packet)
        return Protocol.parse(
            String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
        ) as Protocol.Message.Code
    }
}
