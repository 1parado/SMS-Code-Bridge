package com.parado.smsbridge.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {

    /** reconnect_backoff_sequence：1s→2s→4s→8s→16s，封顶 30s，重连后归位。 */
    @Test
    fun reconnectBackoffSequence() {
        val backoff = Backoff(1_000, 2, 30_000)
        assertEquals(1_000, backoff.nextDelayMs())
        assertEquals(2_000, backoff.nextDelayMs())
        assertEquals(4_000, backoff.nextDelayMs())
        assertEquals(8_000, backoff.nextDelayMs())
        assertEquals(16_000, backoff.nextDelayMs())
        assertEquals(30_000, backoff.nextDelayMs())
        assertEquals(30_000, backoff.nextDelayMs())
        backoff.reset()
        assertEquals(1_000, backoff.nextDelayMs())
    }
}
