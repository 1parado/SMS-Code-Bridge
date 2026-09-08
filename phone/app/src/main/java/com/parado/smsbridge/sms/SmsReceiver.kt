package com.parado.smsbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.parado.smsbridge.CodeBus

/**
 * 短信广播接收器。
 *
 * 只做一件事：把短信交给 [CodeDispatcher]，命中验证码时通过 [CodeBus] 广播出去。
 * 不读取短信数据库、不存储原文、不影响系统短信的正常投递。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        for (sms in messages) {
            CodeDispatcher.handle(sms.messageBody, sms.originatingAddress) { code ->
                CodeBus.emit(code)
            }
        }
    }
}
