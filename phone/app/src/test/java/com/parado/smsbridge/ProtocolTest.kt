package com.parado.smsbridge

import com.parado.smsbridge.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 协议解析测试。
 *
 * 夹具与 Windows 端共用（shared/testdata），确保两端对同一份 JSON 的解析结果一致。
 */
class ProtocolTest {

    private fun fixture(name: String): String {
        val file = File("../../shared/testdata/$name")
        assertTrue("找不到夹具 $name（工作目录应为 app 模块）", file.exists())
        return file.readText()
    }

    @Test
    fun pairRequestRoundtrip() {
        val message = Protocol.parse(fixture("pair_request.json"))
        assertNotNull(message)
        message as Protocol.Message.PairRequest
        assertEquals("device-0001", message.deviceId)
        assertEquals("30313233343536373839616263646566", message.saltHex)
        assertEquals(
            "887fc04592766b594b2abe0b9ade1a53da0560339eb3c7d2903f38dd2000116e",
            message.proofHex,
        )
        assertTrue(Protocol.isSupported(message))

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }

    @Test
    fun pairResponseRoundtrip() {
        val message = Protocol.parse(fixture("pair_response.json"))
        assertNotNull(message)
        message as Protocol.Message.PairResponse
        assertTrue(message.ok)
        assertEquals("0f5ae74e37fae0e8", message.sessionId)

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }

    @Test
    fun codeMessageRoundtrip() {
        val message = Protocol.parse(fixture("code_message.json"))
        assertNotNull(message)
        message as Protocol.Message.Code
        assertEquals(1_757_337_600_000L, message.ts)
        assertEquals("000102030405060708090a0b", message.ivHex)
        assertEquals("733ae422f4d65d9adc9f1e88ba1812b0a0ebc425b984", message.ctHex)

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }

    @Test
    fun unknownFieldIgnored() {
        val withUnknown = Protocol.parse(fixture("code_message_unknown_field.json"))
        val baseline = Protocol.parse(fixture("code_message.json"))
        assertEquals(baseline, withUnknown)
    }

    @Test
    fun versionMismatchRejected() {
        val message = Protocol.parse(fixture("code_message_version_mismatch.json"))
        assertNotNull("结构合法，应能解析", message)
        assertFalse(Protocol.isSupported(message!!))
    }

    @Test
    fun unknownTypeReturnsNull() {
        assertNull(Protocol.parse("{\"type\":\"whatever\",\"v\":2}"))
    }

    @Test
    fun malformedJsonReturnsNull() {
        assertNull(Protocol.parse("{ not json"))
        assertNull(Protocol.parse(""))
    }

    @Test
    fun codeMessageNeverCarriesSensitiveFields() {
        val message = Protocol.parse(fixture("code_message.json")) as Protocol.Message.Code
        val json = Protocol.toJson(message)
        // 密文形态的消息不应出现明文验证码字段或短信相关字段
        listOf("body", "sender", "phone", "address", "content", "\"nonce\"").forEach { forbidden ->
            assertFalse("消息体不应出现字段 $forbidden: $json", json.contains(forbidden))
        }
    }

    @Test
    fun heartbeatRoundtrip() {
        val message = Protocol.parse(fixture("heartbeat.json"))
        assertNotNull(message)
        message as Protocol.Message.Heartbeat
        assertEquals(Protocol.VERSION, message.v)
        assertEquals("device-0001", message.deviceId)

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }

    @Test
    fun unpairRoundtrip() {
        val message = Protocol.parse(fixture("unpair.json"))
        assertNotNull(message)
        message as Protocol.Message.Unpair
        assertEquals(Protocol.VERSION, message.v)
        assertEquals("device-0001", message.deviceId)
        assertEquals(1_757_337_600_000L, message.ts)
        assertEquals(
            "86279a29238265aa8ee995e3909b0ab5773cb3df5d84f1c666605d0483eb19c6",
            message.macHex,
        )

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }

    @Test
    fun heartbeatAndDiscoveryNeverCarrySensitiveFields() {
        val heartbeat = Protocol.toJson(Protocol.Message.Heartbeat(Protocol.VERSION, "device-0001"))
        val discovery = Protocol.toJson(
            Protocol.Message.DiscoveryResponse(Protocol.VERSION, "pc-0001", "PC-0001", 45876),
        )
        listOf("body", "sender", "phone", "address", "content", "secret", "code").forEach { forbidden ->
            assertFalse("heartbeat 不应出现字段 $forbidden: $heartbeat", heartbeat.contains(forbidden))
            assertFalse("discovery 不应出现字段 $forbidden: $discovery", discovery.contains(forbidden))
        }
    }
}
