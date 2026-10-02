package com.parado.smsbridge.pairing

/**
 * 当前会话的进程级状态：界面与前台服务共用的单一事实源。
 *
 * 配对成功后凭据在此缓存并持久化到 [PairingRepository]；服务存活期间
 * 转发与心跳都从这里读取，不依赖界面是否在前台。
 */
object SessionState {

    var host: String = ""
        private set

    var port: Int = 0
        private set

    /** 会话密钥（十六进制）；null 表示未配对。绝不上网传输（加密消息除外）。 */
    var secretHex: String? = null
        private set

    /** 连接是否在线（心跳成功置真、连续失败置假），仅供状态展示。 */
    var online: Boolean = false

    fun isPaired(): Boolean = !secretHex.isNullOrBlank() && host.isNotBlank() && port in 1..65535

    /** 更新会话凭据（配对成功或重启恢复时调用）。 */
    fun update(host: String, port: Int, secretHex: String) {
        this.host = host
        this.port = port
        this.secretHex = secretHex
    }

    /** 解绑：清除一切会话状态。 */
    fun clear() {
        host = ""
        port = 0
        secretHex = null
        online = false
    }
}
