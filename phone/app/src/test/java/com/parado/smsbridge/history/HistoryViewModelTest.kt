package com.parado.smsbridge.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryViewModelTest {

    /** view_model_loads_history：加载的历史与存储一致。 */
    @Test
    fun viewModelLoadsHistory() {
        val repo = InMemoryHistoryRepository(50)
        val vm = HistoryViewModel(repo)
        vm.add("482913", 1_000)
        vm.add("774321", 2_000)
        val loaded = vm.current()
        assertEquals(2, loaded.size())
        assertEquals("482913", loaded.entries()[0].code)
    }

    /** clear_empties_list_and_storage：清空后列表与存储都为空。 */
    @Test
    fun clearEmptiesListAndStorage() {
        val repo = InMemoryHistoryRepository(50)
        val vm = HistoryViewModel(repo)
        vm.add("482913", 1_000)
        vm.clear()
        assertTrue(vm.current().isEmpty())
        assertTrue(repo.load().isEmpty())
    }

    /** list_respects_capacity：超过上限只保留最近 N 条。 */
    @Test
    fun listRespectsCapacity() {
        val repo = InMemoryHistoryRepository(3)
        val vm = HistoryViewModel(repo)
        for (i in 0 until 10) {
            vm.add(String.format("%06d", i), (i * 1000).toLong())
        }
        assertEquals(3, vm.current().size())
        assertEquals("000009", vm.current().entries()[2].code)
    }

    /** masked_display_only：序列化结果只含 ts/code，绝不出现短信原文等敏感字段。 */
    @Test
    fun maskedDisplayOnly() {
        val repo = InMemoryHistoryRepository(50)
        val vm = HistoryViewModel(repo)
        vm.add("482913", 1_000)
        val json = vm.current().toJson()
        assertFalse(json.contains("body"))
        assertFalse(json.contains("sender"))
        assertFalse(json.contains("+86"))
        assertTrue(json.contains("\"ts\""))
        assertTrue(json.contains("\"code\""))
    }
}
