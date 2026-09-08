package com.parado.smsbridge.protocol

import org.json.JSONObject

/**
 * 两端共享的局域网消息协议，须与 computer/src/protocol.rs 保持一致。
 *
 * 安全约束：消息体只携带必要字段，**绝不包含短信原文、手机号或发件人**。
 * 使用 Android 内置的 org.json，不引入任何外部依赖。
 */
object Protocol {

    const val VERSION = 1

    const val TYPE_PAIR_REQUEST = "pair_request"
    const val TYPE_PAIR_RESPONSE = "pair_response"
    const val TYPE_CODE = "code"
    const val TYPE_HEARTBEAT = "heartbeat"
    const val TYPE_UNPAIR = "unpair"
    const val TYPE_DISCOVERY_REQUEST = "discovery_request"
    const val TYPE_DISCOVERY_RESPONSE = "discovery_response"

    sealed class Message {
        abstract val v: Int

        /** 手机 → 电脑：携带配对码的配对请求 */
        data class PairRequest(
            override val v: Int,
            val deviceId: String,
            val code: String,
        ) : Message()

        /** 电脑 → 手机：配对结果 */
        data class PairResponse(
            override val v: Int,
            val ok: Boolean,
            val sessionId: String?,
        ) : Message()

        /** 手机 → 电脑：验证码（只含验证码本身） */
        data class Code(
            override val v: Int,
            val code: String,
            val ts: Long,
            val nonce: String,
            val mac: String,
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

        /** 手机 → 电脑：主动解绑（清密钥 + 清配对） */
        data class Unpair(
            override val v: Int,
            val deviceId: String,
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
                put("code", message.code)
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

            is Message.Code -> {
                put("type", TYPE_CODE)
                put("v", message.v)
                put("code", message.code)
                put("ts", message.ts)
                put("nonce", message.nonce)
                put("mac", message.mac)
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
                json.getString("code"),
            )

            TYPE_PAIR_RESPONSE -> Message.PairResponse(
                v,
                json.optBoolean("ok", false),
                if (json.isNull("session_id")) null else json.optString("session_id"),
            )

            TYPE_CODE -> Message.Code(
                v,
                json.getString("code"),
                json.getLong("ts"),
                json.getString("nonce"),
                json.getString("mac"),
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
            )

            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
