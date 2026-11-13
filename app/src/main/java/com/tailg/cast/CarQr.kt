package com.tailg.cast

import java.net.URI
import java.net.URLDecoder

/**
 * 车机屏幕二维码的解析结果。
 *
 * 车机 Wi-Fi 页的二维码实测是：
 * `http://www.tailgdd.com:3001/appdownload/?TL_c37282ba633&91778425&ap=1`
 * 前两段分别是热点名和密码，`ap=1` 是固定后缀。
 *
 * 蓝牙名和 Wi-Fi 名相同，所以扫一次就能同时填 Wi-Fi 和 BLE。
 */
data class CarQrInfo(val ssid: String, val password: String, val kind: String)

object CarQr {

    fun parse(raw: String?): CarQrInfo? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.startsWith("WIFI:", ignoreCase = true)) return parseWifi(text)
        if (text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            return parseUrl(text)
        }
        return parseTwoLines(text)
    }

    /**
     * 支持两种 URL：
     * - 车机实测格式 `?SSID&PASSWORD&ap=1`
     * - 通用格式 `?ssid=...&password=...`
     * 单纯 `.../appdownload/` 只是下载 App 的二维码，返回 null。
     */
    private fun parseUrl(text: String): CarQrInfo? {
        val query = try {
            URI(text).rawQuery
        } catch (t: Throwable) {
            null
        } ?: return null
        if (query.isBlank()) return null
        val parts = query.split('&').filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        var ssid = ""
        var pass = ""
        for (p in parts) {
            val i = p.indexOf('=')
            if (i <= 0) continue
            val key = p.substring(0, i).lowercase()
            val value = decode(p.substring(i + 1))
            when (key) {
                "ssid", "s", "name" -> ssid = value
                "password", "pass", "pwd", "p" -> pass = value
            }
        }
        if (ssid.isBlank() || pass.isBlank()) {
            ssid = decode(parts[0])
            pass = decode(parts[1])
        }
        if (ssid.isBlank() || pass.isBlank()) return null
        return CarQrInfo(ssid, pass, "url")
    }

    /** 标准 Wi-Fi 二维码：`WIFI:T:WPA;S:ssid;P:password;;`（支持 `\;` 转义）。 */
    private fun parseWifi(text: String): CarQrInfo? {
        val body = text.substringAfter(':', "")
        var ssid = ""
        var pass = ""
        for (field in splitEscaped(body, ';')) {
            when {
                field.startsWith("S:") -> ssid = field.substring(2)
                field.startsWith("P:") -> pass = field.substring(2)
            }
        }
        if (ssid.isBlank() || pass.isBlank()) return null
        return CarQrInfo(ssid, pass, "wifi")
    }

    /** 兜底：两行纯文本，第一行 SSID，第二行密码。 */
    private fun parseTwoLines(text: String): CarQrInfo? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val ssid = lines[0]
        val pass = lines[1]
        if (ssid.length > 32 || pass.length < 8) return null
        return CarQrInfo(ssid, pass, "lines")
    }

    private fun splitEscaped(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var escaped = false
        for (c in s) {
            if (escaped) {
                sb.append(c)
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == sep) {
                out.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    /**
     * URL 里的 `+` 可能是密码字符而不是空格，所以先把 `+` 转成 `%2B` 再解码；
     * 空格按标准写成 `%20` 仍能正常还原。
     */
    private fun decode(s: String): String {
        return try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (t: Throwable) {
            s
        }
    }
}
