package com.parado.smsbridge.pairing

import com.parado.smsbridge.protocol.Crypto
import org.json.JSONObject

/**
 * 已配对凭据：电脑端地址、端口与会话密钥（十六进制）。
 *
 * 会话密钥仅存本机（与电脑端 devices.json 同级，依赖系统文件权限），
 * 绝不上网传输；结构上只存恢复配对所必需的字段，不含短信原文等任何敏感内容。
 */
class PairingStore private constructor(
    val host: String,
    val port: Int,
    val secretHex: String,
) {
    /** 结构完整且可用的凭据：地址非空、端口合法、密钥为 32 字节十六进制。 */
    fun isValid(): Boolean =
        host.isNotBlank() &&
            port in 1..65535 &&
            Crypto.hexToBytes(secretHex)?.size == 32

    /** 序列化为 JSON（仅 host / port / secret_hex 三个字段）。 */
    fun toJson(): String = JSONObject().apply {
        put(KEY_HOST, host)
        put(KEY_PORT, port)
        put(KEY_SECRET, secretHex)
    }.toString()

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SECRET = "secret_hex"

        /** 构造凭据；字段非法时返回 null（调用方按未配对处理）。 */
        fun create(host: String, port: Int, secretHex: String): PairingStore? {
            val store = PairingStore(host.trim(), port, secretHex)
            return store.takeIf { it.isValid() }
        }

        /** 从 JSON 加载；空、非法或结构不完整一律返回 null（绝不因数据损坏崩溃）。 */
        fun fromJson(json: String?): PairingStore? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                create(o.optString(KEY_HOST), o.optInt(KEY_PORT), o.optString(KEY_SECRET))
            }.getOrNull()
        }
    }
}
