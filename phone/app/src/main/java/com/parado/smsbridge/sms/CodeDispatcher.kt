package com.parado.smsbridge.sms

/**
 * 验证码分发：把「提取」与「后续动作」解耦，方便在 JVM 上单测。
 */
object CodeDispatcher {

    /**
     * 从短信中提取验证码并回调；不是验证码短信时不回调。
     *
     * @return 是否成功提取到验证码
     */
    fun handle(
        body: String?,
        sender: String?,
        options: CodeExtractor.Options = CodeExtractor.Options(),
        onCode: (String) -> Unit,
    ): Boolean {
        val code = CodeExtractor.extract(body, sender, options) ?: return false
        onCode(code)
        return true
    }
}
