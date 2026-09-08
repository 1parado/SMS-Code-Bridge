package com.parado.smsbridge

import com.parado.smsbridge.net.DiscoveryClient
import com.parado.smsbridge.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 发现响应解析测试（纯函数，不涉及网络）。 */
class DiscoveryClientTest {

    private fun fixture(name: String) = File("../../shared/testdata/$name").readText()

    @Test
    fun parses_discovery_response() {
        val device = DiscoveryClient.parseResponse(
            fixture("discovery_response.json"),
            "192.168.1.50",
        )
        assertEquals("192.168.1.50", device?.host)
        assertEquals("pc-0001", device?.deviceId)
        assertEquals("PC-0001", device?.name)
        assertEquals(45876, device?.port)
    }

    @Test
    fun ignores_non_discovery_message() {
        assertNull(DiscoveryClient.parseResponse(fixture("pair_response.json"), "192.168.1.50"))
        assertNull(DiscoveryClient.parseResponse(fixture("code_message.json"), "192.168.1.50"))
    }

    @Test
    fun ignores_malformed_json() {
        assertNull(DiscoveryClient.parseResponse("{ not json", "192.168.1.50"))
        assertNull(DiscoveryClient.parseResponse("", "192.168.1.50"))
    }

    @Test
    fun rejects_invalid_port() {
        val json = Protocol.toJson(
            Protocol.Message.DiscoveryResponse(Protocol.VERSION, "pc-0001", "PC-0001", 0),
        )
        assertNull(DiscoveryClient.parseResponse(json, "192.168.1.50"))
    }

    @Test
    fun rejects_unsupported_version() {
        val json = Protocol.toJson(
            Protocol.Message.DiscoveryResponse(99, "pc-0001", "PC-0001", 45876),
        )
        assertNull(DiscoveryClient.parseResponse(json, "192.168.1.50"))
    }
}
