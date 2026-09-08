package com.parado.smsbridge

/**
 * 验证码中转：静态注册的 [com.parado.smsbridge.sms.SmsReceiver] 无法直接注入回调，
 * 通过这个单例把验证码交给界面或前台服务。
 *
 * 纯 Kotlin 实现，可在 JVM 上单测。
 */
object CodeBus {

    private val listeners = mutableListOf<(String) -> Unit>()

    /** 注册监听；返回取消注册的函数。 */
    fun subscribe(listener: (String) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** 广播一条验证码。 */
    fun emit(code: String) {
        if (code.isEmpty()) return
        listeners.toList().forEach { listener ->
            runCatching { listener(code) }
        }
    }

    fun clear() {
        listeners.clear()
    }
}
