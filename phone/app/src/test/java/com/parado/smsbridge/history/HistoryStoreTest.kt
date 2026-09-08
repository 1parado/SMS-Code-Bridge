package com.parado.smsbridge.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStoreTest {

    @Test
    fun appendAndReadBack() {
        val store = HistoryStore(50)
        store.add("482913", 1_000)
        store.add("774321", 2_000)
        assertEquals(2, store.size())
        assertEquals("482913", store.entries()[0].code)
        assertEquals("774321", store.entries()[1].code)
    }

    @Test
    fun jsonRoundTrip() {
        val store = HistoryStore(50)
        store.add("482913", 1_000)
        val reloaded = HistoryStore.fromJson(store.toJson(), 50)
        assertEquals(store.size(), reloaded.size())
        assertEquals("482913", reloaded.entries()[0].code)
    }

    @Test
    fun capacityTrimmedToLimit() {
        val store = HistoryStore(3)
        for (i in 0 until 10) {
            store.add(String.format("%06d", i), (i * 1000).toLong())
        }
        assertEquals(3, store.size())
        // 只保留最近 3 条：000007 / 000008 / 000009
        assertEquals("000007", store.entries()[0].code)
        assertEquals("000009", store.entries()[2].code)
    }

    @Test
    fun clearRemovesAll() {
        val store = HistoryStore(50)
        store.add("482913", 1_000)
        store.clear()
        assertTrue(store.isEmpty())
    }

    @Test
    fun corruptedJsonFallsBackToEmpty() {
        val store = HistoryStore.fromJson("not a json array{", 50)
        assertTrue(store.isEmpty())
    }

    @Test
    fun historyNeverStoresSmsBody() {
        val store = HistoryStore(50)
        store.add("482913", 1_000)
        val json = store.toJson()
        assertFalse(json.contains("body"))
        assertFalse(json.contains("sender"))
        assertFalse(json.contains("+86"))
        assertFalse(json.contains("短信"))
        assertTrue(json.contains("\"ts\""))
        assertTrue(json.contains("\"code\""))
    }
}
