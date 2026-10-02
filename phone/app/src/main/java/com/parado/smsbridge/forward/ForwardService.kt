package com.parado.smsbridge.forward

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.parado.smsbridge.CodeBus
import com.parado.smsbridge.CodeHandler
import com.parado.smsbridge.connection.ConnectionMonitor
import com.parado.smsbridge.history.HistoryViewModel
import com.parado.smsbridge.history.SharedPreferencesHistoryRepository
import com.parado.smsbridge.net.CodeSender
import com.parado.smsbridge.net.UdpSender
import com.parado.smsbridge.pairing.PairingClient
import com.parado.smsbridge.pairing.SessionState
import com.parado.smsbridge.protocol.Protocol
import com.parado.smsbridge.settings.SettingViewModel
import com.parado.smsbridge.settings.SharedPreferencesSettingRepository

/**
 * 前台转发服务：验证码转发的常驻执行者。
 *
 * 订阅 [CodeBus] 并持有心跳——服务存活期间，无论界面是否在前台，
 * 短信验证码都会被提取并推送到电脑端。这是「收到短信 → 电脑剪贴板」
 * 链路真实可用的关键：Activity 退到后台不再中断转发。
 */
class ForwardService : Service() {

    private lateinit var history: HistoryViewModel
    private lateinit var settings: SettingViewModel
    private lateinit var handler: CodeHandler
    private lateinit var monitor: ConnectionMonitor

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = Runnable { sendHeartbeat() }

    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        history = HistoryViewModel(SharedPreferencesHistoryRepository.fromContext(this))
        settings = SettingViewModel(SharedPreferencesSettingRepository.fromContext(this))
        handler = CodeHandler(history, settings)
        monitor = ConnectionMonitor(HEARTBEAT_TIMEOUT_MS, RECONNECT_BASE_MS, RECONNECT_FACTOR, RECONNECT_MAX_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInPlace()
        subscribe()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
        // START_STICKY：进程被系统回收后自动拉起，转发链路自愈
        return START_STICKY
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        unsubscribe = null
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundInPlace() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "验证码转发", NotificationManager.IMPORTANCE_MIN),
            )
        }
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("SMS Code Bridge")
            .setContentText(if (SessionState.isPaired()) "已配对：验证码将自动转发到电脑" else "未配对：完成配对后开始转发")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    /** 订阅验证码总线：这是转发链路的常驻一环（幂等，不重复订阅）。 */
    private fun subscribe() {
        if (unsubscribe != null) return
        unsubscribe = CodeBus.subscribe { code ->
            handler.handle(
                code,
                System.currentTimeMillis(),
                onForward = { forward(it) },
                onNotify = { toast("已发送验证码到电脑") },
            )
        }
    }

    private fun forward(code: String) {
        val secretHex = SessionState.secretHex ?: return
        val ok = CodeSender.send(SessionState.host, SessionState.port, secretHex, code)
        SessionState.online = ok
        if (!ok) toast("发送失败，请检查电脑端是否在线")
    }

    /** 周期性心跳：服务存活期间持续保活，失败进入指数退避。 */
    private fun sendHeartbeat() {
        if (!SessionState.isPaired()) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable)
            return
        }
        val payload = Protocol.toJson(Protocol.Message.Heartbeat(Protocol.VERSION, DEVICE_ID))
        Thread {
            val ok = UdpSender.sendText(SessionState.host, SessionState.port, payload)
            runOnUiThread {
                val delay = if (ok) {
                    monitor.onHeartbeat(System.currentTimeMillis())
                    SessionState.online = true
                    HEARTBEAT_INTERVAL_MS
                } else {
                    SessionState.online = false
                    monitor.nextReconnectDelayMs()
                }
                heartbeatHandler.postDelayed(heartbeatRunnable, delay)
            }
        }.start()
    }

    private fun runOnUiThread(block: () -> Unit) {
        heartbeatHandler.post(block)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val DEVICE_ID = "android"
        const val CHANNEL_ID = "forward"
        const val NOTIFICATION_ID = 1
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val HEARTBEAT_TIMEOUT_MS = 15_000L
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_FACTOR = 2L
        const val RECONNECT_MAX_MS = 30_000L

        /** 启动前台服务（幂等）。配对成功或重启恢复时调用。 */
        fun start(context: Context) {
            val intent = Intent(context, ForwardService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止服务（解绑时调用，通知随之消失）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, ForwardService::class.java))
        }
    }
}
