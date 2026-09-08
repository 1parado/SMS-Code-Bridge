package com.parado.smsbridge.connection

/**
 * 心跳存活跟踪（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 仅记录最近一次收到对端心跳的时间，不做任何网络操作。
 */
class Heartbeat(private val timeoutMs: Long) {
    private var lastSeenMs: Long = -1

    fun mark(nowMs: Long) {
        lastSeenMs = nowMs
    }

    /** 是否仍存活：从未收到心跳视为未连接；最近一次在超时窗口内则存活。 */
    fun isAlive(nowMs: Long): Boolean = lastSeenMs >= 0 && (nowMs - lastSeenMs) <= timeoutMs
}
