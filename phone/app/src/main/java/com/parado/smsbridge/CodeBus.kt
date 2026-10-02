package com.parado.smsbridge

/**
 * 验证码中转：静态注册的 [com.parado.smsbridge.sms.SmsReceiver] 无法直接注入回调，
 * 通过这个单例把验证码交给界面或前台服务。
 *
 * **丢码自愈语义**：短信广播会拉起被系统整杀的进程，此时前台服务尚未重新
 * 订阅——emit 找不到订阅者时把验证码缓存下来，下一个订阅者注册时立即重放，
 * 保证冷启动窗口内到达的验证码不丢失。
 *
 * 纯 Kotlin 实现，可在 JVM 上单测。
 */
object CodeBus {

    private val listeners = mutableListOf<(String) -> Unit>()

    /** emit 时无订阅者而暂存的验证码（最多一条：验证码场景只需最新值）。 */
    private var pending: String? = null

    /** 注册监听；返回取消注册的函数。订阅时先重放缓存的验证码。 */
    @Synchronized
    fun subscribe(listener: (String) -> Unit): () -> Unit {
        listeners.add(listener)
        pending?.let { code ->
            pending = null
            runCatching { listener(code) }
        }
        return { removeListener(listener) }
    }

    /** 广播一条验证码；无订阅者时缓存待重放，而不是丢弃。 */
    @Synchronized
    fun emit(code: String) {
        if (code.isEmpty()) return
        if (listeners.isEmpty()) {
            pending = code
            return
        }
        listeners.toList().forEach { listener ->
            runCatching { listener(code) }
        }
    }

    @Synchronized
    private fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun clear() {
        listeners.clear()
        pending = null
    }
}
