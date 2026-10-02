package com.parado.smsbridge.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.parado.smsbridge.CodeBus
import com.parado.smsbridge.forward.ForwardService

/**
 * 短信广播接收器。
 *
 * 只做一件事：把短信交给 [CodeDispatcher]，命中验证码时通过 [CodeBus] 广播出去。
 * 不读取短信数据库、不存储原文、不影响系统短信的正常投递。
 *
 * **自愈**：进程可能刚被系统整杀后由本广播拉起（此时前台服务尚未恢复）——
 * 收到短信时先拉起 [ForwardService]（SMS 广播属于前台服务后台启动豁免场景），
 * 即使服务订阅晚于本广播，[CodeBus] 的暂存重放也会补发这条验证码。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

        context?.let { receiverContext ->
            runCatching { ForwardService.start(receiverContext) }
        }

        for (sms in messages) {
            CodeDispatcher.handle(sms.messageBody, sms.originatingAddress) { code ->
                CodeBus.emit(code)
            }
        }
    }
}
