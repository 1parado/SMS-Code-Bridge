package com.parado.smsbridge.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingViewModelTest {

    /** 默认设置即规格默认值。 */
    @Test
    fun viewModelLoadsDefaults() {
        val vm = SettingViewModel(InMemorySettingRepository())
        assertTrue(vm.current().autoForward())
        assertTrue(vm.current().notify())
    }

    /** toggle_persists：关闭自动转发后落盘，重载仍为关闭。 */
    @Test
    fun setAutoForwardPersists() {
        val vm = SettingViewModel(InMemorySettingRepository())
        vm.setAutoForward(false)
        assertFalse(vm.current().autoForward())
    }

    @Test
    fun setNotifyPersists() {
        val vm = SettingViewModel(InMemorySettingRepository())
        vm.setNotify(false)
        assertFalse(vm.current().notify())
    }

    /** 序列化只含两个布尔开关，无敏感字段。 */
    @Test
    fun maskedJsonOnlyBooleans() {
        val vm = SettingViewModel(InMemorySettingRepository())
        vm.setAutoForward(false)
        val json = vm.current().toJson()
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("token"))
        assertFalse(json.contains("phone"))
        assertTrue(json.contains("auto_forward"))
        assertTrue(json.contains("notify"))
    }
}
