package com.parado.smsbridge.history

import android.content.Context
import android.content.SharedPreferences

/**
 * 历史持久化接口：加载与保存 [HistoryStore]。
 *
 * 平台相关能力用接口抽象，便于测试注入假实现（见 [InMemoryHistoryRepository]）。
 */
interface HistoryRepository {
    fun load(): HistoryStore
    fun save(store: HistoryStore)
}

/** 测试用：内存实现，不依赖 Android，便于 JVM 单测。 */
class InMemoryHistoryRepository(private val limit: Int = HistoryStore.DEFAULT_LIMIT) : HistoryRepository {
    private var json: String = "[]"
    override fun load(): HistoryStore = HistoryStore.fromJson(json, limit)
    override fun save(store: HistoryStore) {
        json = store.toJson()
    }
}

/** 真实现：SharedPreferences 持久化（仅 Android 运行时使用）。 */
class SharedPreferencesHistoryRepository(
    private val prefs: SharedPreferences,
    private val limit: Int = HistoryStore.DEFAULT_LIMIT,
) : HistoryRepository {
    override fun load(): HistoryStore = HistoryStore.fromJson(prefs.getString(KEY, null), limit)

    override fun save(store: HistoryStore) {
        prefs.edit().putString(KEY, store.toJson()).apply()
    }

    companion object {
        private const val KEY = "history"
        private const val PREF_NAME = "smsbridge"

        fun fromContext(context: Context, limit: Int = HistoryStore.DEFAULT_LIMIT): SharedPreferencesHistoryRepository {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return SharedPreferencesHistoryRepository(prefs, limit)
        }
    }
}
