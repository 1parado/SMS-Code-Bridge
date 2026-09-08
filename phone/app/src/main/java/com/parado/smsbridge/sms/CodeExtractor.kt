package com.parado.smsbridge.sms

/**
 * 验证码提取：从短信正文中提取纯数字验证码。
 *
 * 设计约束：
 * - 纯 Kotlin 实现，不依赖 Android SDK，可在 JVM 上直接单测
 * - 只输出验证码本身，绝不输出短信原文、手机号或发件人
 */
object CodeExtractor {

    /** 验证码长度范围。 */
    const val MIN_CODE_LENGTH = 4
    const val MAX_CODE_LENGTH = 8

    /** 命中这些关键词才认为是验证码短信。 */
    private val KEYWORDS = listOf("验证码", "校验码", "动态码", "code", "otp", "passcode")

    /** 允许数字之间有空格或短横，便于识别 "123 456" / "123-456"。 */
    private val DIGIT_RUN = Regex("""\d[\d\s-]{2,14}\d""")

    /** 关键词之后向后查找的最大窗口。 */
    private const val LOOKAHEAD = 40

    data class Options(
        /** 发件人白名单；仅在 whitelistEnabled 为 true 时生效。 */
        val whitelist: Set<String> = emptySet(),
        val whitelistEnabled: Boolean = false,
    )

    /**
     * 提取验证码；不是验证码短信、被白名单过滤或找不到合规数字时返回 null。
     */
    fun extract(body: String?, sender: String? = null, options: Options = Options()): String? {
        if (body.isNullOrBlank()) return null

        if (options.whitelistEnabled) {
            if (sender.isNullOrBlank() || sender !in options.whitelist) return null
        }

        val lowered = body.lowercase()
        if (KEYWORDS.none { lowered.contains(it) }) return null

        // 优先取关键词之后的数字，最贴近真实短信的表达方式
        for (keyword in KEYWORDS) {
            var index = lowered.indexOf(keyword)
            while (index >= 0) {
                val start = index + keyword.length
                val end = (start + LOOKAHEAD).coerceAtMost(body.length)
                val candidate = normalize(DIGIT_RUN.find(body.substring(start, end))?.value)
                if (candidate != null) return candidate
                index = lowered.indexOf(keyword, index + 1)
            }
        }

        // 退化：取整条短信中最长的合规数字串
        return DIGIT_RUN.findAll(body)
            .mapNotNull { normalize(it.value) }
            .maxByOrNull { it.length }
    }

    private fun normalize(raw: String?): String? {
        if (raw == null) return null
        val digits = raw.filter(Char::isDigit)
        return if (digits.length in MIN_CODE_LENGTH..MAX_CODE_LENGTH) digits else null
    }
}
