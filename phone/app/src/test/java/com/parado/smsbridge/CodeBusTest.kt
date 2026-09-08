package com.parado.smsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证码中转测试。 */
class CodeBusTest {

    @Test
    fun emits_to_subscriber() {
        CodeBus.clear()
        val received = mutableListOf<String>()
        val unsubscribe = CodeBus.subscribe { received.add(it) }

        CodeBus.emit("482913")
        assertEquals(listOf("482913"), received)
        unsubscribe()
    }

    @Test
    fun unsubscribe_stops_events() {
        CodeBus.clear()
        val received = mutableListOf<String>()
        val unsubscribe = CodeBus.subscribe { received.add(it) }
        CodeBus.emit("111111")
        unsubscribe()
        CodeBus.emit("222222")
        assertEquals(listOf("111111"), received)
    }

    @Test
    fun supports_multiple_subscribers() {
        CodeBus.clear()
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val unsubscribeFirst = CodeBus.subscribe { first.add(it) }
        val unsubscribeSecond = CodeBus.subscribe { second.add(it) }

        CodeBus.emit("482913")
        assertEquals(listOf("482913"), first)
        assertEquals(listOf("482913"), second)
        unsubscribeFirst()
        unsubscribeSecond()
    }

    @Test
    fun ignores_empty_code() {
        CodeBus.clear()
        val received = mutableListOf<String>()
        val unsubscribe = CodeBus.subscribe { received.add(it) }
        CodeBus.emit("")
        assertTrue(received.isEmpty())
        unsubscribe()
    }

    @Test
    fun listener_exception_does_not_break_others() {
        CodeBus.clear()
        val received = mutableListOf<String>()
        val bad = CodeBus.subscribe { error("监听器异常") }
        val good = CodeBus.subscribe { received.add(it) }

        CodeBus.emit("482913")
        assertEquals(listOf("482913"), received)
        bad()
        good()
    }
}
