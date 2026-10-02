package com.parado.smsbridge.pairing

import android.content.Context
import android.content.SharedPreferences

/**
 * 已配对凭据的持久化接口：加载、保存与清除。
 *
 * 平台相关能力用接口抽象，便于测试注入假实现（见 [InMemoryPairingRepository]）。
 */
interface PairingRepository {
    fun load(): PairingStore?
    fun save(store: PairingStore)

    /** 清除凭据（解绑时调用，不得残留旧密钥）。 */
    fun clear()
}

/** 测试用：内存实现，不依赖 Android，便于 JVM 单测。 */
class InMemoryPairingRepository : PairingRepository {
    private var json: String? = null

    override fun load(): PairingStore? = PairingStore.fromJson(json)

    override fun save(store: PairingStore) {
        json = store.toJson()
    }

    override fun clear() {
        json = null
    }
}

/** 真实现：SharedPreferences 持久化（仅 Android 运行时使用）。 */
class SharedPreferencesPairingRepository(
    private val prefs: SharedPreferences,
) : PairingRepository {
    override fun load(): PairingStore? = PairingStore.fromJson(prefs.getString(KEY, null))

    override fun save(store: PairingStore) {
        prefs.edit().putString(KEY, store.toJson()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "pairing"
        private const val PREF_NAME = "smsbridge"

        fun fromContext(context: Context): SharedPreferencesPairingRepository {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return SharedPreferencesPairingRepository(prefs)
        }
    }
}
