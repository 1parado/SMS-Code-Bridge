package com.parado.smsbridge.net

/**
 * 局域网发现：广播地址计算与候选地址筛选。
 *
 * 与 Windows 端 computer/src/discovery.rs 使用相同算法，纯函数便于 JVM 单测。
 */
object Discovery {

    /** 发现服务固定端口（与数据端口区分）。 */
    const val DISCOVERY_PORT = 45877

    /** IPv4 字符串转 32 位整数；非法输入返回 null。 */
    fun toInt(address: String): Int? {
        val parts = address.split(".")
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    /** 32 位整数转 IPv4 字符串。 */
    fun toAddress(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

    /** 由本机 IP 与子网掩码计算广播地址。 */
    fun broadcastAddress(ip: String, mask: String): String? {
        val address = toInt(ip) ?: return null
        val maskValue = toInt(mask) ?: return null
        return toAddress(address or maskValue.inv())
    }

    /** 判断两个地址是否位于同一子网。 */
    fun sameSubnet(a: String, b: String, mask: String): Boolean {
        val first = toInt(a) ?: return false
        val second = toInt(b) ?: return false
        val maskValue = toInt(mask) ?: return false
        return (first and maskValue) == (second and maskValue)
    }

    /** 是否为局域网地址（私有网段或链路本地）。 */
    fun isPrivate(address: String): Boolean =
        address.startsWith("10.") ||
            address.startsWith("192.168.") ||
            address.startsWith("169.254.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(address)

    /** 从候选地址中挑出一个可用的局域网地址（排除回环与未指定地址）。 */
    fun pickLanAddress(candidates: List<String>): String? =
        candidates.firstOrNull {
            it != "127.0.0.1" && it != "0.0.0.0" && isPrivate(it)
        }
}
