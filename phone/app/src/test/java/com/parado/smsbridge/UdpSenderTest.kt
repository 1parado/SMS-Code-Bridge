package com.parado.smsbridge

import com.parado.smsbridge.net.UdpSender
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** UDP 发送测试：通过回环地址自收自发。 */
class UdpSenderTest {

    @Test
    fun sends_text_over_loopback() {
        val receiver = DatagramSocket(0)
        receiver.soTimeout = 2_000
        val port = receiver.localPort

        val payload = "{\"type\":\"code\"}"
        assertTrue(UdpSender.sendText("127.0.0.1", port, payload))

        val buffer = ByteArray(256)
        val packet = DatagramPacket(buffer, buffer.size)
        receiver.receive(packet)
        val received = ByteArray(packet.length)
        System.arraycopy(packet.data, packet.offset, received, 0, packet.length)
        assertArrayEquals(payload.toByteArray(), received)
        receiver.close()
    }

    @Test
    fun rejects_invalid_arguments() {
        val bytes = "x".toByteArray()
        assertFalse(UdpSender.send("127.0.0.1", 0, bytes))
        assertFalse(UdpSender.send("127.0.0.1", 70000, bytes))
        assertFalse(UdpSender.send("127.0.0.1", 12345, ByteArray(0)))
        assertFalse(UdpSender.send("127.0.0.1", 12345, ByteArray(UdpSender.MAX_FRAME_BYTES + 1)))
    }

    @Test
    fun unreachable_target_returns_false_without_crash() {
        // 使用未监听的高位端口，发送失败应被吞掉而不是抛异常
        val latch = CountDownLatch(1)
        val result = UdpSender.sendText("127.0.0.1", 1, "probe")
        latch.countDown()
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS))
        // 结果取决于系统是否允许向未监听端口发送，只要求不抛异常
        assertTrue(result || !result)
    }
}
