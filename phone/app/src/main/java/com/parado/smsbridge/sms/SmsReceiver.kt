package com.parado.smsbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * 短信广播接收器。
 *
 * 只做一件事：把短信交给 [CodeDispatcher]，命中验证码时回调 [onCodeDetected]。
 * 不读取短信数据库、不存储原文、不影响系统短信的正常投递。
 */
class SmsReceiver : BroadcastReceiver() {

    /** 验证码回调；生产环境由前台服务注入。 */
    @Volatile
    var onCodeDetected: ((String) -> Unit)? = null

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        for (sms in messages) {
            CodeDispatcher.handle(sms.messageBody, sms.originatingAddress) { code ->
                onCodeDetected?.invoke(code)
            }
        }
    }
}
