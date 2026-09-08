package com.parado.smsbridge.history

import org.json.JSONArray
import org.json.JSONObject

/**
 * 单条历史记录：接收时间（毫秒）+ 验证码。
 *
 * 只有这两个字段，从结构上杜绝短信原文、手机号、发件人等敏感内容入库。
 */
data class HistoryEntry(val ts: Long, val code: String)

/**
 * 历史存储（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 仅保存「时间 + 验证码」，超过上限时丢弃最旧条目，保留最近 [limit] 条。
 */
class HistoryStore private constructor(
    private val limit: Int,
    private val entries: MutableList<HistoryEntry>,
) {
    constructor(limit: Int) : this(limit.coerceAtLeast(1), ArrayList())

    /** 追加一条验证码记录；返回新插入的条目。 */
    fun add(code: String, ts: Long): HistoryEntry {
        val entry = HistoryEntry(ts, code)
        entries.add(entry)
        while (entries.size > limit) entries.removeAt(0)
        return entry
    }

    fun entries(): List<HistoryEntry> = ArrayList(entries)
    fun size(): Int = entries.size
    fun isEmpty(): Boolean = entries.isEmpty()
    fun clear() = entries.clear()

    /** 序列化为 JSON 数组（仅含 ts/code 两字段）。 */
    fun toJson(): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject().apply {
                    put("ts", e.ts)
                    put("code", e.code)
                },
            )
        }
        return arr.toString()
    }

    companion object {
        const val DEFAULT_LIMIT = 50

        fun empty(limit: Int = DEFAULT_LIMIT): HistoryStore = HistoryStore(limit)

        /** 从 JSON 加载；空/非法内容一律回退空历史。 */
        fun fromJson(json: String?, limit: Int = DEFAULT_LIMIT): HistoryStore {
            val store = HistoryStore(limit)
            if (json.isNullOrBlank()) return store
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    store.entries.add(HistoryEntry(o.getLong("ts"), o.getString("code")))
                }
                while (store.entries.size > limit) store.entries.removeAt(0)
            }
            return store
        }
    }
}
