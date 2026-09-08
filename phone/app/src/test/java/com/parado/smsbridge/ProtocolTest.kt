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
        assertEquals("123456", message.code)
        assertEquals("device-0001", message.deviceId)
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
        assertEquals("s-0001", message.sessionId)

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }

    @Test
    fun codeMessageRoundtrip() {
        val message = Protocol.parse(fixture("code_message.json"))
        assertNotNull(message)
        message as Protocol.Message.Code
        assertEquals("482913", message.code)
        assertEquals(1_757_337_600_000L, message.ts)
        assertEquals("n-0001", message.nonce)

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
        assertNull(Protocol.parse("{\"type\":\"whatever\",\"v\":1}"))
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
        listOf("body", "sender", "phone", "address", "content").forEach { forbidden ->
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

        val reparsed = Protocol.parse(Protocol.toJson(message))
        assertEquals(message, reparsed)
    }

    @Test
    fun heartbeatAndUnpairNeverCarrySensitiveFields() {
        val heartbeat = Protocol.toJson(Protocol.Message.Heartbeat(Protocol.VERSION, "device-0001"))
        val unpair = Protocol.toJson(Protocol.Message.Unpair(Protocol.VERSION, "device-0001"))
        listOf("body", "sender", "phone", "address", "content", "secret", "code").forEach { forbidden ->
            assertFalse("heartbeat 不应出现字段 $forbidden: $heartbeat", heartbeat.contains(forbidden))
            assertFalse("unpair 不应出现字段 $forbidden: $unpair", unpair.contains(forbidden))
        }
    }
}
