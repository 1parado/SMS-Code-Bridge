package com.parado.smsbridge.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {

    /** heartbeat_timeout_marks_disconnected：超时窗口外即判定断连。 */
    @Test
    fun heartbeatTimeoutMarksDisconnected() {
        val hb = Heartbeat(15_000)
        hb.mark(1_000)
        assertTrue(hb.isAlive(1_000 + 15_000))
        assertFalse(hb.isAlive(1_000 + 15_001))
        // 从未收到心跳 → 未连接
        assertFalse(Heartbeat(15_000).isAlive(999_999))
    }
}
