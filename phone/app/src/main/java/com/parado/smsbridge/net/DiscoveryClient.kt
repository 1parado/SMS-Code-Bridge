package com.parado.smsbridge.net

import com.parado.smsbridge.protocol.Protocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 局域网发现客户端：广播「谁在线」并收集电脑端的应答。
 *
 * [parseResponse] 是纯函数，可在 JVM 上单测；[discover] 涉及网络，由界面在后台线程调用。
 */
object DiscoveryClient {

    /** 发现到的电脑端。 */
    data class FoundDevice(
        val host: String,
        val deviceId: String,
        val name: String,
        val port: Int,
    )

    /** 解析一条发现响应；不是发现响应或结构非法时返回 null。 */
    fun parseResponse(json: String, fromHost: String): FoundDevice? {
        val message = Protocol.parse(json) as? Protocol.Message.DiscoveryResponse ?: return null
        if (!Protocol.isSupported(message)) return null
        if (message.port <= 0) return null
        return FoundDevice(fromHost, message.deviceId, message.name, message.port)
    }

    /**
     * 向广播地址发送发现请求并等待应答。
     *
     * @param timeoutMs 等待时长，超时后返回已收集到的结果
     */
    fun discover(
        broadcastAddress: String,
        timeoutMs: Int = 1_500,
        deviceId: String = "device-0001",
    ): List<FoundDevice> {
        val payload = Protocol.toJson(
            Protocol.Message.DiscoveryRequest(Protocol.VERSION, deviceId),
        ).toByteArray(Charsets.UTF_8)

        val found = mutableListOf<FoundDevice>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs
            }
            socket.send(
                DatagramPacket(
                    payload,
                    payload.size,
                    InetAddress.getByName(broadcastAddress),
                    Discovery.DISCOVERY_PORT,
                ),
            )

            val buffer = ByteArray(UdpSender.MAX_FRAME_BYTES)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { socket.receive(packet) }.getOrElse { break }
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                val host = packet.address?.hostAddress ?: continue
                parseResponse(text, host)?.let { device ->
                    if (found.none { it.host == device.host && it.port == device.port }) {
                        found.add(device)
                    }
                }
            }
        } catch (_: Exception) {
            // 发现失败时返回已收集结果，绝不抛给调用方
        } finally {
            socket?.close()
        }
        return found
    }
}
