package com.parado.smsbridge.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionMonitorTest {

    @Test
    fun heartbeatKeepsConnectedThenDisconnects() {
        val monitor = ConnectionMonitor(15_000, 1_000, 2, 30_000)
        monitor.onHeartbeat(1_000)
        assertTrue(monitor.isConnected(1_000 + 10_000))
        // 超时未打点 → 断连
        assertFalse(monitor.isConnected(1_000 + 20_000))
    }

    @Test
    fun reconnectDelayUsesBackoffAndResetsOnHeartbeat() {
        val monitor = ConnectionMonitor(15_000, 1_000, 2, 30_000)
        assertEquals(1_000, monitor.nextReconnectDelayMs())
        assertEquals(2_000, monitor.nextReconnectDelayMs())
        // 收到心跳 → 重置退避
        monitor.onHeartbeat(5_000)
        assertEquals(1_000, monitor.nextReconnectDelayMs())
    }
}
