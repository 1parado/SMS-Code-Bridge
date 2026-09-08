package com.parado.smsbridge

import com.parado.smsbridge.history.HistoryViewModel
import com.parado.smsbridge.settings.SettingViewModel

/**
 * 验证码到达后的统一处理（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 规则：
 * - 无论开关如何，验证码都先写入本地历史（便于事后查看，且只含时间+码）
 * - 仅当「自动转发」开启时调用 [onForward]
 * - 仅当「提示」开启时调用 [onNotify]
 */
class CodeHandler(
    private val history: HistoryViewModel,
    private val settings: SettingViewModel,
) {
    fun handle(
        code: String,
        ts: Long,
        onForward: (String) -> Unit,
        onNotify: (String) -> Unit,
    ) {
        history.add(code, ts)
        if (settings.current().autoForward()) onForward(code)
        if (settings.current().notify()) onNotify(code)
    }
}
