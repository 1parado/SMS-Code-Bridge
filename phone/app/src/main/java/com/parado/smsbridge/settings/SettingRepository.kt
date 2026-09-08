package com.parado.smsbridge.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置持久化接口：加载与保存 [SettingStore]。
 *
 * 平台相关能力用接口抽象，便于测试注入假实现（见 [InMemorySettingRepository]）。
 */
interface SettingRepository {
    fun load(): SettingStore
    fun save(store: SettingStore)
}

/** 测试用：内存实现，不依赖 Android，便于 JVM 单测。 */
class InMemorySettingRepository : SettingRepository {
    private var json: String = "{}"
    override fun load(): SettingStore = SettingStore.fromJson(json)
    override fun save(store: SettingStore) {
        json = store.toJson()
    }
}

/** 真实现：SharedPreferences 持久化（仅 Android 运行时使用）。 */
class SharedPreferencesSettingRepository(
    private val prefs: SharedPreferences,
) : SettingRepository {
    override fun load(): SettingStore = SettingStore.fromJson(prefs.getString(KEY, null))

    override fun save(store: SettingStore) {
        prefs.edit().putString(KEY, store.toJson()).apply()
    }

    companion object {
        private const val KEY = "settings"
        private const val PREF_NAME = "smsbridge"

        fun fromContext(context: Context): SharedPreferencesSettingRepository {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return SharedPreferencesSettingRepository(prefs)
        }
    }
}
