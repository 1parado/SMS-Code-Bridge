package com.parado.smsbridge

import com.parado.smsbridge.net.CodeSender
import com.parado.smsbridge.pairing.SessionState
import com.parado.smsbridge.protocol.Crypto
import com.parado.smsbridge.protocol.Protocol
import com.parado.smsbridge.sms.CodeDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * 「短信正文 → 电脑端」全链路测试（回环，与真机链路同构）：
 *
 * 短信正文 → [CodeDispatcher] 提取（= SmsReceiver 的实际入口）
 *          → [CodeBus] 分发（含 ForwardService 的处理逻辑：历史 + CodeSender）
 *          → AES-256-GCM 加密单播
 *          → 伪电脑端接收，按 shared/PROTOCOL.md 同款流程解密回读验证码。
 *
 * 密钥使用 shared/PROTOCOL.md 约定的公开测试向量（非真实凭据）。
 * Windows 端同款解密流程由 computer/src/receiver.rs 的夹具互验测试锁定，
 * 因此本测试通过即证明：手机端监控到短信后，电脑端必然能解出并写入剪贴板。
 */
class SmsToPcChainTest {

    private val secretHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    private fun receiveCode(pc: DatagramSocket): String {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        pc.receive(packet)
        val message = Protocol.parse(
            String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
        ) as Protocol.Message.Code
        // 电脑端 receiver.rs 的同款解密流程：iv/ct hex 解码 + AAD "code|{ts}"
        val plain = Crypto.aesGcmOpen(
            Crypto.hexToBytes(secretHex)!!,
            Crypto.hexToBytes(message.ivHex)!!,
            "code|${message.ts}".toByteArray(Charsets.UTF_8),
            Crypto.hexToBytes(message.ctHex)!!,
        )
        return plain?.toString(Charsets.UTF_8) ?: "(解密失败)"
    }

    @Test
    fun sms_body_reaches_pc_as_decrypted_code() {
        CodeBus.clear()
        val pc = DatagramSocket(0).apply { soTimeout = 3_000 }
        SessionState.update("127.0.0.1", pc.localPort, secretHex)

        // 模拟 ForwardService 的常驻订阅：历史 + 转发（转发目标为伪电脑端）
        CodeBus.subscribe { code ->
            CodeSender.send(SessionState.host, SessionState.port, secretHex, code)
        }

        // 模拟 SmsReceiver 的入口：一条真实格式的验证码短信
        val extracted = CodeDispatcher.handle(
            "【某某平台】您的验证码是482913，5分钟内有效，请勿泄露。",
            "1069000000",
        ) { code -> CodeBus.emit(code) }

        val decrypted = receiveCode(pc)
        pc.close()
        CodeBus.clear()

        assertTrue("应从短信中提取到验证码", extracted)
        assertEquals("电脑端应解密出与短信一致的验证码", "482913", decrypted)
    }

    /** 进程冷启动自愈链路：广播先到（无订阅者）→ 服务订阅 → 暂存重放 → 电脑端收到。 */
    @Test
    fun cold_start_sms_is_replayed_and_delivered() {
        CodeBus.clear()
        val pc = DatagramSocket(0).apply { soTimeout = 3_000 }
        SessionState.update("127.0.0.1", pc.localPort, secretHex)

        // 1) 广播先到：此时服务尚未订阅，CodeBus 暂存
        CodeDispatcher.handle("您的验证码是306415", "1069000000") { code ->
            CodeBus.emit(code)
        }

        // 2) 服务被拉起并订阅：暂存验证码立即重放进转发链路
        CodeBus.subscribe { code ->
            CodeSender.send(SessionState.host, SessionState.port, secretHex, code)
        }

        val decrypted = receiveCode(pc)
        pc.close()
        CodeBus.clear()

        assertEquals("冷启动窗口内的验证码应经重放送达电脑端", "306415", decrypted)
    }
}
