package com.parado.smsbridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.parado.smsbridge.connection.ConnectionMonitor
import com.parado.smsbridge.history.HistoryViewModel
import com.parado.smsbridge.history.SharedPreferencesHistoryRepository
import com.parado.smsbridge.net.UdpSender
import com.parado.smsbridge.pairing.PairingClient
import com.parado.smsbridge.protocol.Protocol
import com.parado.smsbridge.settings.SettingViewModel
import com.parado.smsbridge.settings.SharedPreferencesSettingRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简主界面：输入电脑端显示的配对码，完成配对后由广播接收器自动转发验证码。
 *
 * 不做多页面、不做设置堆砌；首次启动只申请真正需要的短信权限。
 */
class MainActivity : Activity() {

    private val permissionRequestCode = 1001

    private lateinit var statusText: TextView
    private lateinit var codeInput: EditText
    private lateinit var pairButton: Button
    private lateinit var unbindButton: Button
    private lateinit var historyList: ListView
    private lateinit var clearHistoryButton: Button
    private lateinit var autoForwardCheckbox: CheckBox
    private lateinit var notifyCheckbox: CheckBox

    private val stateMachine = PairingClient.StateMachine()

    /** 电脑端地址与端口：v0.1 由用户填入，后续版本改为自动发现。 */
    private var host: String = ""
    private var port: Int = 0

    private lateinit var history: HistoryViewModel
    private lateinit var settings: SettingViewModel
    private lateinit var handler: CodeHandler
    private lateinit var monitor: ConnectionMonitor
    private var unsubscribe: (() -> Unit)? = null

    /** 本机设备号（与电脑端配对时登记的 device_id 保持一致）。 */
    private val deviceId = "android"

    /** 心跳发送间隔与超时（毫秒）。 */
    private val heartbeatIntervalMs = 5_000L
    private val heartbeatTimeoutMs = 15_000L

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = Runnable { sendHeartbeat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        codeInput = findViewById(R.id.codeInput)
        pairButton = findViewById(R.id.pairButton)
        unbindButton = findViewById(R.id.unbindButton)
        historyList = findViewById(R.id.historyList)
        clearHistoryButton = findViewById(R.id.clearHistoryButton)
        autoForwardCheckbox = findViewById(R.id.autoForwardCheckbox)
        notifyCheckbox = findViewById(R.id.notifyCheckbox)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val portInput = findViewById<EditText>(R.id.portInput)

        history = HistoryViewModel(SharedPreferencesHistoryRepository.fromContext(this))
        settings = SettingViewModel(SharedPreferencesSettingRepository.fromContext(this))
        handler = CodeHandler(history, settings)
        monitor = ConnectionMonitor(heartbeatTimeoutMs, 1_000, 2, 30_000)

        autoForwardCheckbox.isChecked = settings.current().autoForward()
        notifyCheckbox.isChecked = settings.current().notify()
        autoForwardCheckbox.setOnCheckedChangeListener { _, checked -> settings.setAutoForward(checked) }
        notifyCheckbox.setOnCheckedChangeListener { _, checked -> settings.setNotify(checked) }

        pairButton.setOnClickListener {
            host = hostInput.text.toString().trim()
            port = portInput.text.toString().trim().toIntOrNull() ?: 0
            val code = codeInput.text.toString().trim()
            if (!PairingClient.isValidCode(code)) {
                show("配对码需为 6 位数字")
                return@setOnClickListener
            }
            if (host.isEmpty() || port <= 0) {
                show("请填写电脑端地址与端口")
                return@setOnClickListener
            }
            sendPairRequest(code)
        }

        unbindButton.setOnClickListener {
            stateMachine.unbind()
            stopHeartbeat()
            sendUnpair()
            monitor = ConnectionMonitor(heartbeatTimeoutMs, 1_000, 2, 30_000)
            refresh()
        }

        clearHistoryButton.setOnClickListener {
            history.clear()
            refreshHistory()
            show("历史已清空")
        }

        requestSmsPermission()
        refresh()
        refreshHistory()
    }

    override fun onResume() {
        super.onResume()
        unsubscribe = CodeBus.subscribe { code ->
            handler.handle(
                code,
                System.currentTimeMillis(),
                onForward = { sendCode(it) },
                onNotify = { toast("已发送验证码到电脑") },
            )
            refreshHistory()
        }
        if (stateMachine.state == PairingClient.State.Paired) startHeartbeat()
    }

    override fun onPause() {
        unsubscribe?.invoke()
        unsubscribe = null
        stopHeartbeat()
        super.onPause()
    }

    private fun sendPairRequest(code: String) {
        stateMachine.start()
        val payload = Protocol.toJson(
            Protocol.Message.PairRequest(Protocol.VERSION, deviceId, code),
        )
        val ok = UdpSender.sendText(host, port, payload)
        if (ok) {
            // v0.1：电脑端响应由后台服务处理，此处先置为配对中并本地建立会话
            stateMachine.succeed(PairingClient.deriveSecretHex(code, ByteArray(0)))
            statusText.text = "已配对（在线）"
            startHeartbeat()
        } else {
            stateMachine.fail()
            show("发送失败，请检查地址与网络")
        }
        refresh()
    }

    /** 周期性向电脑端发送心跳；失败则进入指数退避重连。 */
    private fun sendHeartbeat() {
        if (stateMachine.session == null) {
            stopHeartbeat()
            return
        }
        val payload = Protocol.toJson(Protocol.Message.Heartbeat(Protocol.VERSION, deviceId))
        val ok = UdpSender.sendText(host, port, payload)
        val delay = if (ok) {
            monitor.onHeartbeat(System.currentTimeMillis())
            heartbeatIntervalMs
        } else {
            statusText.text = "已配对（重连中…）"
            monitor.nextReconnectDelayMs()
        }
        heartbeatHandler.postDelayed(heartbeatRunnable, delay)
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, heartbeatIntervalMs)
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    /** 解绑时通知电脑端清除密钥与配对（尽力发送，失败不影响本地解绑）。 */
    private fun sendUnpair() {
        if (host.isEmpty() || port <= 0) return
        val payload = Protocol.toJson(Protocol.Message.Unpair(Protocol.VERSION, deviceId))
        UdpSender.sendText(host, port, payload)
    }

    private fun sendCode(code: String) {
        val session = stateMachine.session ?: return
        val message = Protocol.Message.Code(
            Protocol.VERSION,
            code,
            System.currentTimeMillis(),
            System.nanoTime().toString(),
            session.secretHex,
        )
        UdpSender.sendText(host, port, Protocol.toJson(message))
    }

    private fun requestSmsPermission() {
        val needed = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), permissionRequestCode)
        }
    }

    private fun refresh() {
        unbindButton.visibility = if (stateMachine.state == PairingClient.State.Paired) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun refreshHistory() {
        val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val items = history.current().entries().map { e ->
            "${timeFmt.format(Date(e.ts))}   ${e.code}"
        }
        historyList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
    }

    private fun show(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        statusText.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
