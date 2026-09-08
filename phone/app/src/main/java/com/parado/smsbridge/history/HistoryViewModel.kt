package com.parado.smsbridge.history

/**
 * 历史界面逻辑载体（纯 Kotlin，不依赖 AndroidX，便于 JVM 单测）。
 *
 * 持有 [HistoryRepository]，提供加载、追加、清空三类操作。
 */
class HistoryViewModel(
    private val repository: HistoryRepository,
    private val limit: Int = HistoryStore.DEFAULT_LIMIT,
) {
    /** 当前历史（每次从存储重新加载，保证与磁盘一致）。 */
    fun current(): HistoryStore = repository.load()

    /** 追加一条验证码记录并落盘。 */
    fun add(code: String, ts: Long) {
        val store = repository.load()
        store.add(code, ts)
        repository.save(store)
    }

    /** 清空历史并落盘（磁盘写入空数组，确保不留验证码残留）。 */
    fun clear() {
        repository.save(HistoryStore.empty(limit))
    }
}
