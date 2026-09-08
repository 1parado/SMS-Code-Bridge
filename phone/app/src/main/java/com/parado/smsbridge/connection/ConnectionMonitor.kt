package com.parado.smsbridge.connection

/**
 * 连接状态监控（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * - 收到对端心跳时打点并重置重连退避
 * - 心跳超时未打点 → 判定断连
 * - 断连后用 [nextReconnectDelayMs] 取得指数退避的下一跳延迟
 */
class ConnectionMonitor(
    timeoutMs: Long,
    baseMs: Long,
    factor: Long,
    maxMs: Long,
) {
    private val heartbeat = Heartbeat(timeoutMs)
    private val backoff = Backoff(baseMs, factor, maxMs)

    /** 收到一次心跳：续命并重置退避（视为已重连）。 */
    fun onHeartbeat(nowMs: Long) {
        heartbeat.mark(nowMs)
        backoff.reset()
    }

    fun isConnected(nowMs: Long): Boolean = heartbeat.isAlive(nowMs)

    fun nextReconnectDelayMs(): Long = backoff.nextDelayMs()
}
