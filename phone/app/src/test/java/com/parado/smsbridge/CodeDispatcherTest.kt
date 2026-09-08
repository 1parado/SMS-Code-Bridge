package com.parado.smsbridge

import com.parado.smsbridge.sms.CodeDispatcher
import com.parado.smsbridge.sms.CodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证码分发测试：命中才回调，且只回调验证码本身。 */
class CodeDispatcherTest {

    @Test
    fun dispatches_code_once() {
        val received = mutableListOf<String>()
        val dispatched = CodeDispatcher.handle(
            "【示例平台】验证码 482913，5 分钟内有效",
            "10690000",
        ) { received.add(it) }

        assertTrue(dispatched)
        assertEquals(listOf("482913"), received)
    }

    @Test
    fun ignores_non_verification_sms() {
        val received = mutableListOf<String>()
        val dispatched = CodeDispatcher.handle("您的快递已到达丰巢柜", "10690000") {
            received.add(it)
        }
        assertFalse(dispatched)
        assertTrue(received.isEmpty())
    }

    @Test
    fun respects_whitelist() {
        val options = CodeExtractor.Options(
            whitelist = setOf("10690000"),
            whitelistEnabled = true,
        )
        val received = mutableListOf<String>()
        assertFalse(
            CodeDispatcher.handle("验证码 482913", "10086", options) { received.add(it) },
        )
        assertTrue(
            CodeDispatcher.handle("验证码 482913", "10690000", options) { received.add(it) },
        )
        assertEquals(listOf("482913"), received)
    }

    @Test
    fun never_dispatches_sms_body() {
        val body = "【示例平台】尊敬的 138****0000 用户，验证码 482913"
        val received = mutableListOf<String>()
        CodeDispatcher.handle(body, "10690000") { received.add(it) }
        assertEquals(1, received.size)
        assertFalse(received[0].contains("示例平台"))
        assertFalse(received[0].contains("138"))
    }
}
