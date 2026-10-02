package com.parado.smsbridge.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 极简 UDP 发送：把一帧数据发往局域网内的电脑端。
 *
 * 只做最小封装，失败返回 false 由上层决定是否重试。
 */
object UdpSender {

    const val MAX_FRAME_BYTES = 4096

    fun send(host: String, port: Int, payload: ByteArray): Boolean {
        if (payload.isEmpty() || payload.size > MAX_FRAME_BYTES) return false
        if (port <= 0 || port > 65535) return false

        return try {
            DatagramSocket().use { socket ->
                val packet = DatagramPacket(payload, payload.size, InetAddress.getByName(host), port)
                socket.send(packet)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 发送一帧并等待单个应答，返回应答字节；超时或异常返回 null。
     *
     * 用于配对这类「请求-应答」场景：应答来源不校验，
     * 真实性由上层的会话 ID 校验（密码学验证）承担。
     */
    fun sendAndWait(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray? {
        if (payload.isEmpty() || payload.size > MAX_FRAME_BYTES) return null
        if (port <= 0 || port > 65535) return null

        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.send(
                    DatagramPacket(payload, payload.size, InetAddress.getByName(host), port),
                )
                val buffer = ByteArray(MAX_FRAME_BYTES)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                buffer.copyOf(response.length)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun sendText(host: String, port: Int, text: String): Boolean =
        send(host, port, text.toByteArray(Charsets.UTF_8))
}
