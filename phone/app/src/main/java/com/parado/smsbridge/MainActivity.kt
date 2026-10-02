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
import com.parado.smsbridge.net.Discovery
import com.parado.smsbridge.net.DiscoveryClient
import com.parado.smsbridge.net.UdpSender
import com.parado.smsbridge.pairing.PairingClient
import com.parado.smsbridge.pairing.PairingRepository
import com.parado.smsbridge.pairing.PairingStore
import com.parado.smsbridge.pairing.SharedPreferencesPairingRepository
import com.parado.smsbridge.protocol.Crypto
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
    private lateinit var discoverButton: Button
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText

    private val stateMachine = PairingClient.StateMachine()

    /** 电脑端地址与端口：v0.1 由用户填入，后续版本改为自动发现。 */
    private var host: String = ""
    private var port: Int = 0

    private lateinit var history: HistoryViewModel
    private lateinit var settings: SettingViewModel
    private lateinit var handler: CodeHandler
    private lateinit var monitor: ConnectionMonitor
    private lateinit var pairingRepo: PairingRepository
    private var unsubscribe: (() -> Unit)? = null

    /** 本机设备号（与电脑端配对时登记的 device_id 保持一致）。 */
    private val deviceId = "android"

    /** 心跳发送间隔与超时（毫秒）。 */
    private val heartbeatIntervalMs = 5_000L
    private val heartbeatTimeoutMs = 15_000L

    /** 配对响应等待时长（毫秒）。 */
    private val pairResponseTimeoutMs = 3_000

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
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        discoverButton = findViewById(R.id.discoverButton)
        findViewById<TextView>(R.id.aboutText).text =
            "SMS Code Bridge v${BuildConfig.VERSION_NAME} · 数据仅在本机与局域网"

        discoverButton.setOnClickListener {
            statusText.text = "正在寻找电脑…"
            Thread { discoverPc() }.start()
        }

        history = HistoryViewModel(SharedPreferencesHistoryRepository.fromContext(this))
        settings = SettingViewModel(SharedPreferencesSettingRepository.fromContext(this))
        handler = CodeHandler(history, settings)
        monitor = ConnectionMonitor(heartbeatTimeoutMs, 1_000, 2, 30_000)

        // 恢复已保存的配对凭据：重启后无需重新配对，地址回填输入框
        pairingRepo = SharedPreferencesPairingRepository.fromContext(this)
        pairingRepo.load()?.let { saved ->
            stateMachine.succeed(saved.secretHex)
            host = saved.host
            port = saved.port
            hostInput.setText(saved.host)
            portInput.setText(saved.port.toString())
            statusText.text = "已配对（恢复）"
        }

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
            // 先用会话密钥计算解绑认证，再清理本地状态与持久化凭据
            stateMachine.session?.let { session -> sendUnpair(session.secretHex) }
            stateMachine.unbind()
            pairingRepo.clear()
            stopHeartbeat()
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

    /** 在后台线程广播「谁在线」，回到主线程回填地址与端口。 */
    private fun discoverPc() {
        val broadcast = guessBroadcastAddress()
        val devices = if (broadcast == null) {
            emptyList()
        } else {
            DiscoveryClient.discover(broadcast, deviceId = deviceId)
        }
        runOnUiThread {
            if (devices.isEmpty()) {
                show("未找到电脑端：请确认在同一局域网且电脑端已启动")
            } else {
                val device = devices.first()
                host = device.host
                port = device.port
                hostInput.setText(device.host)
                portInput.setText(device.port.toString())
                show("已找到 ${device.name}：${device.host}:${device.port}")
            }
        }
    }

    /** 猜测广播地址：取本机局域网地址 + 常见 24 位掩码。 */
    private fun guessBroadcastAddress(): String? {
        val addresses = mutableListOf<String>()
        runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is java.net.Inet4Address) {
                        addr.hostAddress?.let { addresses.add(it) }
                    }
                }
            }
        }
        val local = Discovery.pickLanAddress(addresses) ?: return null
        return Discovery.broadcastAddress(local, "255.255.255.0")
    }

    /**
     * 发起配对：生成盐值与证明（配对码不上网）发送 PairRequest，
     * 等待 PairResponse 并用会话 ID 验证电脑端确已派生同一密钥。
     * 网络操作全部在后台线程，避免主线程网络异常。
     */
    private fun sendPairRequest(code: String) {
        if (!stateMachine.start()) return
        show("配对中…")
        // 捕获请求时的地址：保存凭据必须与实际配对所用地址一致
        val requestHost = host
        val requestPort = port
        Thread {
            val saltHex = PairingClient.createSaltHex()
            val salt = Crypto.hexToBytes(saltHex) ?: ByteArray(0)
            val proofHex = PairingClient.deriveProofHex(code, salt)
            val request = Protocol.toJson(
                Protocol.Message.PairRequest(Protocol.VERSION, deviceId, saltHex, proofHex),
            )
            val response = UdpSender
                .sendAndWait(requestHost, requestPort, request.toByteArray(Charsets.UTF_8), pairResponseTimeoutMs)
                ?.let { bytes -> Protocol.parse(String(bytes, Charsets.UTF_8)) }
                as? Protocol.Message.PairResponse
            val secretHex = PairingClient.deriveSecretHex(proofHex)
            val paired = response != null &&
                response.ok &&
                PairingClient.validatePairResponse(response.sessionId, secretHex)
            runOnUiThread {
                if (paired) {
                    stateMachine.succeed(secretHex)
                    // 凭据仅存本机，重启后自动恢复配对状态
                    PairingStore.create(requestHost, requestPort, secretHex)
                        ?.let(pairingRepo::save)
                    statusText.text = "已配对（在线）"
                    startHeartbeat()
                } else {
                    stateMachine.fail()
                    show(
                        if (response == null) {
                            "配对超时：请确认电脑端配对码未过期"
                        } else {
                            "配对失败：请检查配对码"
                        },
                    )
                }
                refresh()
            }
        }.start()
    }

    /** 周期性向电脑端发送心跳；失败则进入指数退避重连。网络操作在后台线程。 */
    private fun sendHeartbeat() {
        if (stateMachine.session == null) {
            stopHeartbeat()
            return
        }
        val payload = Protocol.toJson(Protocol.Message.Heartbeat(Protocol.VERSION, deviceId))
        Thread {
            val ok = UdpSender.sendText(host, port, payload)
            runOnUiThread {
                val delay = if (ok) {
                    monitor.onHeartbeat(System.currentTimeMillis())
                    heartbeatIntervalMs
                } else {
                    statusText.text = "已配对（重连中…）"
                    monitor.nextReconnectDelayMs()
                }
                heartbeatHandler.postDelayed(heartbeatRunnable, delay)
            }
        }.start()
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, heartbeatIntervalMs)
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    /** 解绑时通知电脑端清除密钥与配对（需会话密钥认证；尽力发送，失败不影响本地解绑）。 */
    private fun sendUnpair(secretHex: String) {
        if (host.isEmpty() || port <= 0) return
        val ts = System.currentTimeMillis()
        val payload = Protocol.toJson(
            Protocol.Message.Unpair(Protocol.VERSION, deviceId, ts, PairingClient.unpairMacHex(secretHex, ts)),
        )
        Thread { UdpSender.sendText(host, port, payload) }.start()
    }

    /** 加密发送验证码：AES-256-GCM（密钥为配对派生的会话密钥），网络操作在后台线程。 */
    private fun sendCode(code: String) {
        val session = stateMachine.session ?: return
        val secret = Crypto.hexToBytes(session.secretHex) ?: return
        Thread {
            val ts = System.currentTimeMillis()
            val iv = Crypto.randomBytes(Crypto.IV_LEN)
            val aad = "code|$ts".toByteArray(Charsets.UTF_8)
            val ciphertext = Crypto.aesGcmSeal(secret, iv, aad, code.toByteArray(Charsets.UTF_8))
            val message = Protocol.Message.Code(
                Protocol.VERSION,
                ts,
                Crypto.toHex(iv),
                Crypto.toHex(ciphertext),
            )
            UdpSender.sendText(host, port, Protocol.toJson(message))
        }.start()
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
