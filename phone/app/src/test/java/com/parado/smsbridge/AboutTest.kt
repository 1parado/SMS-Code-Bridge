package com.parado.smsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 版本信息测试。
 *
 * 构建脚本声明的版本号与运行时 BuildConfig 必须一致，
 * 否则「关于」里显示的版本会与实际构建的 APK 对不上。
 */
class AboutTest {

    private fun declaredVersionName(): String? {
        val gradle = File("build.gradle.kts")
        if (!gradle.exists()) return null
        val match = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(gradle.readText())
        return match?.groupValues?.getOrNull(1)
    }

    @Test
    fun version_name_matches_gradle_declaration() {
        val declared = declaredVersionName()
        assertNotNull("应在 app/build.gradle.kts 中找到 versionName", declared)
        assertEquals(declared, BuildConfig.VERSION_NAME)
    }

    @Test
    fun version_name_is_semantic() {
        val parts = BuildConfig.VERSION_NAME.split(".")
        assertEquals("版本号应为三段式：主.次.修订", 3, parts.size)
        parts.forEach { part -> assertNotNull(part.toIntOrNull()) }
    }
}
