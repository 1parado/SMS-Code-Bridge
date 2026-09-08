package com.parado.smsbridge.settings

/**
 * 设置界面逻辑载体（纯 Kotlin，不依赖 AndroidX，便于 JVM 单测）。
 *
 * 持有 [SettingRepository]，提供加载与两个开关的改动保存。
 */
class SettingViewModel(private val repository: SettingRepository) {
    /** 当前设置（每次从存储重新加载，保证与磁盘一致）。 */
    fun current(): SettingStore = repository.load()

    /** 改动自动转发开关并落盘。 */
    fun setAutoForward(value: Boolean) {
        val store = repository.load()
        store.setAutoForward(value)
        repository.save(store)
    }

    /** 改动提示开关并落盘。 */
    fun setNotify(value: Boolean) {
        val store = repository.load()
        store.setNotify(value)
        repository.save(store)
    }
}
