package com.parado.smsbridge

import com.parado.smsbridge.history.HistoryViewModel
import com.parado.smsbridge.history.InMemoryHistoryRepository
import com.parado.smsbridge.settings.InMemorySettingRepository
import com.parado.smsbridge.settings.SettingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHandlerTest {

    /**
     * disabled_flags_skip_corresponding_action：
     * 自动转发关闭时，验证码仍存历史但不转发；提示关闭时绝不触发提示。
     */
    @Test
    fun disabledFlagsSkipCorrespondingAction() {
        val history = HistoryViewModel(InMemoryHistoryRepository(50))
        val settings = SettingViewModel(InMemorySettingRepository())
        settings.setAutoForward(false)
        settings.setNotify(false)

        var forwarded = false
        var notified = false
        val handler = CodeHandler(history, settings)
        handler.handle(
            "482913",
            1_000,
            onForward = { forwarded = true },
            onNotify = { notified = true },
        )

        // 历史始终记录
        assertEquals(1, history.current().size())
        // 两个开关关闭 → 对应动作被跳过
        assertFalse(forwarded)
        assertFalse(notified)
    }

    /** 开关开启（默认）时，转发与提示均触发。 */
    @Test
    fun enabledFlagsTriggerActions() {
        val history = HistoryViewModel(InMemoryHistoryRepository(50))
        val settings = SettingViewModel(InMemorySettingRepository())

        var forwarded = false
        var notified = false
        val handler = CodeHandler(history, settings)
        handler.handle(
            "482913",
            1_000,
            onForward = { forwarded = true },
            onNotify = { notified = true },
        )
        assertTrue(forwarded)
        assertTrue(notified)
    }
}
