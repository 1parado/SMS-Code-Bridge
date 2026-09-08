package com.parado.smsbridge

import com.parado.smsbridge.net.Discovery
import com.parado.smsbridge.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 局域网发现测试：地址计算（与 Windows 端同算法）与发现消息的跨端夹具。 */
class DiscoveryTest {

    @Test
    fun computes_class_c_broadcast_address() {
        assertEquals("192.168.1.255", Discovery.broadcastAddress("192.168.1.100", "255.255.255.0"))
    }

    @Test
    fun computes_class_b_broadcast_address() {
        assertEquals("10.0.255.255", Discovery.broadcastAddress("10.0.5.7", "255.255.0.0"))
    }

    @Test
    fun broadcast_of_full_mask_is_address_itself() {
        assertEquals("192.168.1.1", Discovery.broadcastAddress("192.168.1.1", "255.255.255.255"))
    }

    @Test
    fun invalid_address_returns_null() {
        assertNull(Discovery.broadcastAddress("192.168.1", "255.255.255.0"))
        assertNull(Discovery.broadcastAddress("192.168.1.300", "255.255.255.0"))
        assertNull(Discovery.broadcastAddress("192.168.1.1", "bad-mask"))
    }

    @Test
    fun detects_same_subnet() {
        assertTrue(Discovery.sameSubnet("192.168.1.10", "192.168.1.200", "255.255.255.0"))
        assertFalse(Discovery.sameSubnet("192.168.1.10", "192.168.2.10", "255.255.255.0"))
    }

    @Test
    fun picks_private_address_and_skips_loopback() {
        val picked = Discovery.pickLanAddress(listOf("127.0.0.1", "192.168.1.23", "10.0.0.5"))
        assertEquals("192.168.1.23", picked)
    }

    @Test
    fun no_lan_address_returns_null() {
        assertNull(Discovery.pickLanAddress(listOf("127.0.0.1", "0.0.0.0", "8.8.8.8")))
    }

    @Test
    fun discovery_request_matches_shared_fixture() {
        val raw = File("../../shared/testdata/discovery_request.json").readText()
        val message = Protocol.parse(raw) as Protocol.Message.DiscoveryRequest
        assertEquals("device-0001", message.deviceId)
        assertTrue(Protocol.isSupported(message))

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }

    @Test
    fun discovery_response_matches_shared_fixture() {
        val raw = File("../../shared/testdata/discovery_response.json").readText()
        val message = Protocol.parse(raw) as Protocol.Message.DiscoveryResponse
        assertEquals("pc-0001", message.deviceId)
        assertEquals("PC-0001", message.name)
        assertEquals(45876, message.port)

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }
}
