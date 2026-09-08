package com.parado.smsbridge.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingStoreTest {

    /** defaults_as_specified：默认自动转发开、提示开。 */
    @Test
    fun defaultsAsSpecified() {
        val store = SettingStore()
        assertTrue(store.autoForward())
        assertTrue(store.notify())
    }

    @Test
    fun jsonRoundTrip() {
        val store = SettingStore()
        store.setAutoForward(false)
        store.setNotify(false)
        val reloaded = SettingStore.fromJson(store.toJson())
        assertFalse(reloaded.autoForward())
        assertFalse(reloaded.notify())
    }

    @Test
    fun corruptedJsonFallsBackToDefault() {
        val store = SettingStore.fromJson("not a json{")
        assertTrue(store.autoForward())
        assertTrue(store.notify())
    }

    /** toggle_persists（store 层）：关闭后序列化再加载仍保持关闭。 */
    @Test
    fun togglePersists() {
        val store = SettingStore()
        store.setAutoForward(false)
        val reloaded = SettingStore.fromJson(store.toJson())
        assertFalse(reloaded.autoForward())
    }
}
