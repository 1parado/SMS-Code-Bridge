package com.parado.smsbridge.protocol

import org.json.JSONObject

/**
 * 两端共享的局域网消息协议（v2），须与 computer/src/protocol.rs 保持一致。
 *
 * 安全约束：消息体只携带必要字段，**绝不包含短信原文、手机号或发件人**；
 * 配对码不上网（只传盐值与 PBKDF2 证明），验证码内容以 AES-256-GCM 密文传输。
 * 使用 Android 内置的 org.json，不引入任何外部依赖。
 */
object Protocol {

    const val VERSION = 3

    const val TYPE_PAIR_REQUEST = "pair_request"
    const val TYPE_PAIR_RESPONSE = "pair_response"
    const val TYPE_PAIR_OPEN_REQUEST = "pair_open_request"
    const val TYPE_PAIR_GRANT = "pair_grant"
    const val TYPE_CODE = "code"
    const val TYPE_HEARTBEAT = "heartbeat"
    const val TYPE_UNPAIR = "unpair"
    const val TYPE_DISCOVERY_REQUEST = "discovery_request"
    const val TYPE_DISCOVERY_RESPONSE = "discovery_response"

    sealed class Message {
        abstract val v: Int

        /** 手机 → 电脑：配对请求（不带配对码，只带盐值与知识证明） */
        data class PairRequest(
            override val v: Int,
            val deviceId: String,
            /** 手机生成的随机盐（hex，16 字节），仅本次配对使用。 */
            val saltHex: String,
            /** PBKDF2-HMAC-SHA256(配对码, 盐) 的证明；配对码本身绝不上网传输。 */
            val proofHex: String,
        ) : Message()

        /** 电脑 → 手机：配对结果（sessionId 可用于验证对端确已派生同一密钥） */
        data class PairResponse(
            override val v: Int,
            val ok: Boolean,
            val sessionId: String?,
        ) : Message()

        /** 手机 → 电脑：一键配对请求（可信网络；电脑端用户托盘确认后才下发凭据） */
        data class PairOpenRequest(
            override val v: Int,
            val deviceId: String,
        ) : Message()

        /** 电脑 → 手机：一键配对同意，单播下发会话密钥（明文，见 PROTOCOL.md 威胁模型） */
        data class PairGrant(
            override val v: Int,
            val deviceId: String,
            val secretHex: String,
            /** 会话 ID，供对端做一致性展示。 */
            val sessionId: String,
        ) : Message()

        /** 手机 → 电脑：验证码（密文，只含验证码本身） */
        data class Code(
            override val v: Int,
            val ts: Long,
            /** AES-256-GCM 随机 IV（hex，12 字节），同时用作重放检测的键。 */
            val ivHex: String,
            /** 密文（含认证标签，hex）；AAD 为 "code|{ts}"，明文仅含验证码数字。 */
            val ctHex: String,
        ) : Message()

        /** 手机 → 电脑：心跳保活（仅含版本与设备号） */
        data class Heartbeat(
            override val v: Int,
            val deviceId: String,
        ) : Message()

        /** 手机 → 电脑（广播）：谁在线？ */
        data class DiscoveryRequest(
            override val v: Int,
            val deviceId: String,
        ) : Message()

        /** 电脑 → 手机：我在这里（仅含发现所需字段） */
        data class DiscoveryResponse(
            override val v: Int,
            val deviceId: String,
            val name: String,
            val port: Int,
        ) : Message()

        /** 手机 → 电脑：主动解绑（需会话密钥认证，防伪造） */
        data class Unpair(
            override val v: Int,
            val deviceId: String,
            val ts: Long,
            /** HMAC-SHA256(会话密钥, "unpair|{ts}")，十六进制。 */
            val macHex: String,
        ) : Message()
    }

    /** 版本不匹配的消息不得进入后续处理流程。 */
    fun isSupported(message: Message): Boolean = message.v == VERSION

    fun toJson(message: Message): String = JSONObject().apply {
        when (message) {
            is Message.PairRequest -> {
                put("type", TYPE_PAIR_REQUEST)
                put("v", message.v)
                put("device_id", message.deviceId)
                put("salt_hex", message.saltHex)
                put("proof_hex", message.proofHex)
            }

            is Message.PairResponse -> {
                put("type", TYPE_PAIR_RESPONSE)
                put("v", message.v)
                put("ok", message.ok)
                if (message.sessionId == null) {
                    put("session_id", JSONObject.NULL)
                } else {
                    put("session_id", message.sessionId)
                }
            }

            is Message.PairOpenRequest -> {
                put("type", TYPE_PAIR_OPEN_REQUEST)
                put("v", message.v)
                put("device_id", message.deviceId)
            }

            is Message.PairGrant -> {
                put("type", TYPE_PAIR_GRANT)
                put("v", message.v)
                put("device_id", message.deviceId)
                put("secret_hex", message.secretHex)
                put("session_id", message.sessionId)
            }

            is Message.Code -> {
                put("type", TYPE_CODE)
                put("v", message.v)
                put("ts", message.ts)
                put("iv_hex", message.ivHex)
                put("ct_hex", message.ctHex)
            }

            is Message.Heartbeat -> {
                put("type", TYPE_HEARTBEAT)
                put("v", message.v)
                put("device_id", message.deviceId)
            }

            is Message.DiscoveryRequest -> {
                put("type", TYPE_DISCOVERY_REQUEST)
                put("v", message.v)
                put("device_id", message.deviceId)
            }

            is Message.DiscoveryResponse -> {
                put("type", TYPE_DISCOVERY_RESPONSE)
                put("v", message.v)
                put("device_id", message.deviceId)
                put("name", message.name)
                put("port", message.port)
            }

            is Message.Unpair -> {
                put("type", TYPE_UNPAIR)
                put("v", message.v)
                put("device_id", message.deviceId)
                put("ts", message.ts)
                put("mac_hex", message.macHex)
            }
        }
    }.toString()

    /** 类型未知或结构非法时返回 null，由调用方丢弃。 */
    fun parse(text: String): Message? = try {
        val json = JSONObject(text)
        val v = json.optInt("v", -1)
        when (json.optString("type")) {
            TYPE_PAIR_REQUEST -> Message.PairRequest(
                v,
                json.getString("device_id"),
                json.getString("salt_hex"),
                json.getString("proof_hex"),
            )

            TYPE_PAIR_RESPONSE -> Message.PairResponse(
                v,
                json.optBoolean("ok", false),
                if (json.isNull("session_id")) null else json.optString("session_id"),
            )

            TYPE_PAIR_OPEN_REQUEST -> Message.PairOpenRequest(
                v,
                json.getString("device_id"),
            )

            TYPE_PAIR_GRANT -> Message.PairGrant(
                v,
                json.getString("device_id"),
                json.getString("secret_hex"),
                json.optString("session_id"),
            )

            TYPE_CODE -> Message.Code(
                v,
                json.getLong("ts"),
                json.getString("iv_hex"),
                json.getString("ct_hex"),
            )

            TYPE_HEARTBEAT -> Message.Heartbeat(
                v,
                json.getString("device_id"),
            )

            TYPE_DISCOVERY_REQUEST -> Message.DiscoveryRequest(
                v,
                json.getString("device_id"),
            )

            TYPE_DISCOVERY_RESPONSE -> Message.DiscoveryResponse(
                v,
                json.getString("device_id"),
                json.optString("name"),
                json.optInt("port", 0),
            )

            TYPE_UNPAIR -> Message.Unpair(
                v,
                json.getString("device_id"),
                json.getLong("ts"),
                json.getString("mac_hex"),
            )

            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
