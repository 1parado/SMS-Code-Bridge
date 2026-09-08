package com.parado.smsbridge.settings

import org.json.JSONObject

/**
 * 应用设置：自动转发、提示开关。
 *
 * 只含两个布尔开关，无账号、无密钥、无设备信息——结构上杜绝敏感数据入库。
 */
class SettingStore private constructor(
    private var autoForward: Boolean,
    private var notify: Boolean,
) {
    constructor() : this(DEFAULT_AUTO_FORWARD, DEFAULT_NOTIFY)

    fun autoForward(): Boolean = autoForward
    fun notify(): Boolean = notify
    fun setAutoForward(value: Boolean) { autoForward = value }
    fun setNotify(value: Boolean) { notify = value }

    /** 序列化为 JSON（仅 auto_forward / notify 两个布尔字段）。 */
    fun toJson(): String = JSONObject().apply {
        put(KEY_AUTO_FORWARD, autoForward)
        put(KEY_NOTIFY, notify)
    }.toString()

    companion object {
        const val DEFAULT_AUTO_FORWARD = true
        const val DEFAULT_NOTIFY = true
        private const val KEY_AUTO_FORWARD = "auto_forward"
        private const val KEY_NOTIFY = "notify"

        /** 默认设置：自动转发开、提示开。 */
        fun empty(): SettingStore = SettingStore()

        /** 从 JSON 加载；空或非法内容一律回退默认（绝不因配置损坏崩溃）。 */
        fun fromJson(json: String?): SettingStore {
            if (json.isNullOrBlank()) return SettingStore()
            return runCatching {
                val o = JSONObject(json)
                SettingStore(
                    o.optBoolean(KEY_AUTO_FORWARD, DEFAULT_AUTO_FORWARD),
                    o.optBoolean(KEY_NOTIFY, DEFAULT_NOTIFY),
                )
            }.getOrDefault(SettingStore())
        }
    }
}
