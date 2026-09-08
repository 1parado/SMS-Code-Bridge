package com.parado.smsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 权限清单守卫。
 *
 * 直接解析工程内的 AndroidManifest.xml，确保权限集合与 Agent.md 完全一致：
 * 既不能少（功能不可用），也不能多（越权）。后续迭代若偷偷加入新权限，这里会红。
 */
class PermissionsManifestTest {

    private val allowed = setOf(
        "android.permission.RECEIVE_SMS",
        "android.permission.READ_SMS",
        "android.permission.INTERNET",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.ACCESS_NETWORK_STATE",
    )

    private val forbidden = setOf(
        "android.permission.READ_CONTACTS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_CALL_LOG",
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    )

    private fun declaredPermissions(): Set<String> {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("找不到 AndroidManifest.xml（工作目录应为 app 模块）", manifest.exists())

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(manifest)
        val nodes = document.getElementsByTagName("uses-permission")

        val declared = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val name = nodes.item(i).attributes.getNamedItem("android:name")
            if (name != null) {
                declared.add(name.nodeValue)
            }
        }
        return declared
    }

    @Test
    fun manifest_declares_only_allowed_permissions() {
        assertEquals(allowed, declaredPermissions())
    }

    @Test
    fun no_dangerous_permissions_declared() {
        assertEquals(emptySet<String>(), declaredPermissions().intersect(forbidden))
    }
}
