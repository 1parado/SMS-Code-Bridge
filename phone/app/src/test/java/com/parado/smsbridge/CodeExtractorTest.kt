package com.parado.smsbridge

import com.parado.smsbridge.sms.CodeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证码提取测试。
 *
 * 样本全部脱敏：发件人使用业务短信号段占位，正文不含任何真实手机号。
 */
class CodeExtractorTest {

    private fun extract(body: String?, sender: String = "10690000") = CodeExtractor.extract(body, sender)

    @Test
    fun extracts_code_from_chinese_keyword() {
        assertEquals("482913", extract("【示例平台】您的验证码是 482913，5 分钟内有效。"))
        assertEquals("482913", extract("校验码：482913"))
        assertEquals("482913", extract("动态码 482913，请勿泄露"))
    }

    @Test
    fun extracts_code_from_english_keyword_case_insensitive() {
        assertEquals("482913", extract("Your code is 482913"))
        assertEquals("482913", extract("Your CODE: 482913"))
        assertEquals("482913", extract("verification OTP 482913"))
    }

    @Test
    fun handles_code_with_spaces_or_dashes() {
        assertEquals("123456", extract("验证码 123 456"))
        assertEquals("123456", extract("验证码：123-456"))
    }

    @Test
    fun handles_4_6_8_digit_codes() {
        assertEquals("1234", extract("验证码 1234"))
        assertEquals("123456", extract("验证码 123456"))
        assertEquals("12345678", extract("验证码 12345678"))
    }

    @Test
    fun ignores_code_outside_length_range() {
        assertNull("3 位不应被认定为验证码", extract("验证码 123"))
        assertNull("9 位不应被认定为验证码", extract("验证码 123456789"))
    }

    @Test
    fun picks_code_when_body_has_multiple_numbers() {
        assertEquals("482913", extract("订单 98765 的验证码为 482913，请在 10 分钟内使用"))
    }

    @Test
    fun ignores_sms_without_keyword() {
        assertNull(extract("今天天气不错，记得带伞"))
        assertNull(extract("您的订单已发货，运单号 123456"))
    }

    @Test
    fun ignores_empty_or_blank_body() {
        assertNull(extract(null))
        assertNull(extract(""))
        assertNull(extract("   "))
    }

    @Test
    fun handles_very_long_body() {
        val padding = "这是一条很长的短信内容，".repeat(80)
        assertEquals("482913", extract("${padding}验证码 482913${padding}"))
    }

    @Test
    fun returns_null_when_keyword_present_but_no_valid_code() {
        assertNull(extract("您的验证码已发送，请注意查收"))
    }

    @Test
    fun sender_whitelist_disabled_by_default() {
        val anySender = extract("验证码 482913", sender = "10086")
        assertEquals("482913", anySender)
        assertEquals("482913", extract("验证码 482913", sender = "unknown"))
    }

    @Test
    fun sender_whitelist_enabled_filters_others() {
        val options = CodeExtractor.Options(
            whitelist = setOf("10690000"),
            whitelistEnabled = true,
        )
        assertEquals(
            "482913",
            CodeExtractor.extract("验证码 482913", "10690000", options),
        )
        assertNull(
            CodeExtractor.extract("验证码 482913", "10086", options),
        )
        assertNull(
            CodeExtractor.extract("验证码 482913", null, options),
        )
    }

    @Test
    fun never_emits_full_sms_body() {
        val body = "【示例平台】尊敬的 138****0000 用户，您的验证码是 482913"
        val code = extract(body)
        assertEquals("482913", code)
        assertFalse("输出不应包含短信原文", code!!.contains("示例平台"))
        assertFalse("输出不应包含手机号片段", code.contains("138"))
    }
}
