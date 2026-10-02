package com.parado.smsbridge

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.parado.smsbridge.forward.ForwardService
import com.parado.smsbridge.history.HistoryViewModel
import com.parado.smsbridge.history.SharedPreferencesHistoryRepository
import com.parado.smsbridge.net.Discovery
import com.parado.smsbridge.net.DiscoveryClient
import com.parado.smsbridge.net.UdpSender
import com.parado.smsbridge.pairing.PairingClient
import com.parado.smsbridge.pairing.PairingRepository
import com.parado.smsbridge.pairing.PairingStore
import com.parado.smsbridge.pairing.SessionState
import com.parado.smsbridge.pairing.SharedPreferencesPairingRepository
import com.parado.smsbridge.protocol.Crypto
import com.parado.smsbridge.protocol.Protocol
import com.parado.smsbridge.settings.SettingViewModel
import com.parado.smsbridge.settings.SharedPreferencesSettingRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：发现电脑 → 一键配对或配对码配对；转发由前台服务负责。
 *
 * 职责边界：本界面只做展示、配对发起与设置；验证码的接收、转发、心跳
 * 全部在 [ForwardService]，因此界面退到后台不影响转发链路。
 */
class MainActivity : Activity() {

    private val permissionRequestCode = 1001

    private lateinit var statusText: TextView
    private lateinit var codeInput: EditText
    private lateinit var quickPairButton: Button
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

    private lateinit var history: HistoryViewModel
    private lateinit var settings: SettingViewModel
    private lateinit var pairingRepo: PairingRepository
    private var unsubscribe: (() -> Unit)? = null

    /** 配对响应与一键配对授权的等待时长（毫秒）。 */
    private val pairResponseTimeoutMs = 3_000
    private val pairGrantTimeoutMs = 30_000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        codeInput = findViewById(R.id.codeInput)
        quickPairButton = findViewById(R.id.quickPairButton)
        pairButton = findViewById(R.id.pairButton)
        unbindButton = findViewById(R.id.unbindButton)
        historyList = findViewById(R.id.historyList)
        clearHistoryButton = findViewById(R.id.clearHistoryButton)
        autoForwardCheckbox = findViewById(R.id.autoForwardCheckbox)
        notifyCheckbox = findViewById(R.id.notifyCheckbox)
        discoverButton = findViewById(R.id.discoverButton)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        findViewById<TextView>(R.id.aboutText).text =
            "SMS Code Bridge v${BuildConfig.VERSION_NAME} · 数据仅在本机与局域网"

        history = HistoryViewModel(SharedPreferencesHistoryRepository.fromContext(this))
        settings = SettingViewModel(SharedPreferencesSettingRepository.fromContext(this))
        pairingRepo = SharedPreferencesPairingRepository.fromContext(this)

        autoForwardCheckbox.isChecked = settings.current().autoForward()
        notifyCheckbox.isChecked = settings.current().notify()
        autoForwardCheckbox.setOnCheckedChangeListener { _, checked -> settings.setAutoForward(checked) }
        notifyCheckbox.setOnCheckedChangeListener { _, checked -> settings.setNotify(checked) }

        discoverButton.setOnClickListener {
            show("正在寻找电脑…")
            Thread { discoverPc() }.start()
        }

        quickPairButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 0
            if (host.isEmpty() || port <= 0) {
                show("请先自动寻找电脑，或填写电脑端地址与端口")
                return@setOnClickListener
            }
            sendQuickPair(host, port)
        }

        pairButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 0
            val code = codeInput.text.toString().trim()
            if (!PairingClient.isValidCode(code)) {
                show("配对码需为 6 位数字")
                return@setOnClickListener
            }
            if (host.isEmpty() || port <= 0) {
                show("请先自动寻找电脑，或填写电脑端地址与端口")
                return@setOnClickListener
            }
            sendPairRequest(host, port, code)
        }

        unbindButton.setOnClickListener {
            // 先用会话密钥计算解绑认证，再清理本地状态与持久化凭据
            SessionState.secretHex?.let { secretHex ->
                sendUnpair(SessionState.host, SessionState.port, secretHex)
            }
            stateMachine.unbind()
            SessionState.clear()
            pairingRepo.clear()
            ForwardService.stop(this)
            show("已解绑")
            refresh()
        }

        clearHistoryButton.setOnClickListener {
            history.clear()
            refreshHistory()
            show("历史已清空")
        }

        requestPermissionsIfNeeded()
        restorePairedSession()
        refresh()
        refreshHistory()
    }

    override fun onResume() {
        super.onResume()
        // 转发由前台服务负责；界面订阅只用于实时刷新历史列表
        unsubscribe = CodeBus.subscribe {
            runOnUiThread { refreshHistory() }
        }
        refreshStatus()
    }

    override fun onPause() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onPause()
    }

    /** 启动恢复：已保存的配对凭据直接回到已配对状态，并拉起前台转发服务。 */
    private fun restorePairedSession() {
        pairingRepo.load()?.let { saved ->
            stateMachine.succeed(saved.secretHex)
            SessionState.update(saved.host, saved.port, saved.secretHex)
            hostInput.setText(saved.host)
            portInput.setText(saved.port.toString())
            ForwardService.start(this)
        }
    }

    /** 在后台线程广播「谁在线」，回到主线程回填地址与端口。 */
    private fun discoverPc() {
        val broadcast = guessBroadcastAddress()
        val devices = if (broadcast == null) {
            emptyList()
        } else {
            DiscoveryClient.discover(broadcast, deviceId = ForwardService.DEVICE_ID)
        }
        runOnUiThread {
            if (devices.isEmpty()) {
                show("未找到电脑端：请确认同一局域网且电脑端已启动")
            } else {
                val device = devices.first()
                hostInput.setText(device.host)
                portInput.setText(device.port.toString())
                show("已找到 ${device.name}（${device.host}:${device.port}），可直接一键配对")
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
     * 一键配对：请电脑端用户在托盘点「同意配对」，凭据单播下发。
     * 明文凭据仅适用于可信网络（威胁模型见 shared/PROTOCOL.md）。
     */
    private fun sendQuickPair(host: String, port: Int) {
        if (!stateMachine.start()) return
        show("已通知电脑端，请在电脑端托盘点「同意配对」…")
        Thread {
            val request = Protocol.toJson(
                Protocol.Message.PairOpenRequest(Protocol.VERSION, ForwardService.DEVICE_ID),
            )
            val grant = UdpSender
                .sendAndWait(host, port, request.toByteArray(Charsets.UTF_8), pairGrantTimeoutMs)
                ?.let { response ->
                    if (response.isFrom(host)) {
                        Protocol.parse(String(response.bytes, Charsets.UTF_8))
                            as? Protocol.Message.PairGrant
                    } else {
                        null
                    }
                }
            val valid = grant != null &&
                grant.deviceId == ForwardService.DEVICE_ID &&
                PairingStore.create(host, port, grant.secretHex) != null
            runOnUiThread {
                if (valid) {
                    finishPairing(host, port, grant!!.secretHex, "已配对（一键）")
                } else {
                    stateMachine.fail()
                    show("电脑端未确认：请在其托盘点「同意配对」后重试（发起后 60 秒内有效）")
                }
                refresh()
            }
        }.start()
    }

    /** 配对码配对：PBKDF2 证明验码，配对码不上网，适合任何网络。 */
    private fun sendPairRequest(host: String, port: Int, code: String) {
        if (!stateMachine.start()) return
        show("配对中…")
        Thread {
            val saltHex = PairingClient.createSaltHex()
            val salt = Crypto.hexToBytes(saltHex) ?: ByteArray(0)
            val proofHex = PairingClient.deriveProofHex(code, salt)
            val request = Protocol.toJson(
                Protocol.Message.PairRequest(Protocol.VERSION, ForwardService.DEVICE_ID, saltHex, proofHex),
            )
            val response = UdpSender
                .sendAndWait(host, port, request.toByteArray(Charsets.UTF_8), pairResponseTimeoutMs)
                ?.let { r ->
                    if (r.isFrom(host)) {
                        Protocol.parse(String(r.bytes, Charsets.UTF_8)) as? Protocol.Message.PairResponse
                    } else {
                        null
                    }
                }
            val secretHex = PairingClient.deriveSecretHex(proofHex)
            val paired = response != null &&
                response.ok &&
                PairingClient.validatePairResponse(response.sessionId, secretHex)
            runOnUiThread {
                if (paired) {
                    finishPairing(host, port, secretHex, "已配对（在线）")
                } else {
                    stateMachine.fail()
                    show(
                        if (response == null) {
                            "配对超时：请在电脑端托盘点「显示配对码」获取新码"
                        } else {
                            "配对失败：请检查配对码"
                        },
                    )
                }
                refresh()
            }
        }.start()
    }

    /** 配对成功的统一收尾：更新状态、持久化凭据、拉起前台转发服务。 */
    private fun finishPairing(host: String, port: Int, secretHex: String, message: String) {
        stateMachine.succeed(secretHex)
        SessionState.update(host, port, secretHex)
        PairingStore.create(host, port, secretHex)?.let(pairingRepo::save)
        ForwardService.start(this)
        statusText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 解绑时通知电脑端清除密钥与配对（需会话密钥认证；尽力发送，失败不影响本地解绑）。 */
    private fun sendUnpair(host: String, port: Int, secretHex: String) {
        if (host.isEmpty() || port <= 0) return
        val ts = System.currentTimeMillis()
        val payload = Protocol.toJson(
            Protocol.Message.Unpair(
                Protocol.VERSION,
                ForwardService.DEVICE_ID,
                ts,
                PairingClient.unpairMacHex(secretHex, ts),
            ),
        )
        Thread { UdpSender.sendText(host, port, payload) }.start()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    /** 状态行：结合连接在线情况展示。 */
    private fun refreshStatus() {
        statusText.text = when {
            !SessionState.isPaired() -> "未配对"
            SessionState.online -> "已配对（在线）"
            else -> "已配对（连接中…）"
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
}
