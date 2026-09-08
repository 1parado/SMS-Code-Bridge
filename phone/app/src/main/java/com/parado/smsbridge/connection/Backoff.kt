package com.parado.smsbridge.connection

/**
 * 指数退避重连调度（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * delay = base * factor^attempt，封顶 max；连接恢复后 [reset] 退避计数。
 */
class Backoff(private val baseMs: Long, private val factor: Long, private val maxMs: Long) {
    private var attempt: Int = 0

    fun nextDelayMs(): Long {
        var delay = baseMs
        for (i in 0 until attempt) {
            delay = if (delay >= maxMs) maxMs else (delay * factor).coerceAtMost(maxMs)
        }
        attempt++
        return delay
    }

    fun reset() {
        attempt = 0
    }
}
