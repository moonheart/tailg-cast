package com.tailg.protocol

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ThemePackageInfo(
    val themeName: String,
    val customName: String,
    val customDesc: String,
    val customAuthor: String,
    val customVersion: String,
    val themeType: String,
    val startVersion: String,
    val md5: String,
    val md5Matched: Boolean,
    val configFile: File,
    val packageFile: File,
    val packageName: String,
    val packageSize: Long,
)

/**
 * 解析「仪表助手」那种外层主题 zip：
 *   config.ini + package.zip + bg_1.png + night.mp4
 *
 * 只解出 config.ini 和 package.zip，文件名固定，避免 zip 路径穿越。
 */
object ThemePackage {

    fun extract(outerZip: File, outDir: File): ThemePackageInfo {
        require(outerZip.isFile) { "主题 zip 不存在: ${outerZip.absolutePath}" }
        outDir.mkdirs()

        ZipFile(outerZip).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }

            val iniEntry = entries.firstOrNull {
                it.name.substringAfterLast('/').equals("config.ini", ignoreCase = true)
            } ?: entries.firstOrNull {
                it.name.endsWith(".ini", ignoreCase = true)
            } ?: throw IllegalArgumentException("外层 zip 里没有 config.ini")

            val pkgEntry = entries.firstOrNull {
                it.name.substringAfterLast('/').equals("package.zip", ignoreCase = true)
            } ?: entries.firstOrNull {
                it.name.endsWith(".zip", ignoreCase = true)
            } ?: throw IllegalArgumentException("外层 zip 里没有 package.zip")

            val configFile = File(outDir, iniEntry.name.substringAfterLast('/'))
            val packageFile = File(outDir, pkgEntry.name.substringAfterLast('/'))
            copyEntry(zip, iniEntry.name, configFile)
            copyEntry(zip, pkgEntry.name, packageFile)

            val ini = parseIni(configFile)
            val customName = ini["customname"].orEmpty().ifBlank {
                ini["themename"].orEmpty().ifBlank {
                    ini["wallpapername_zh_cn"].orEmpty().ifBlank {
                        ini["wallpapername"].orEmpty().ifBlank {
                            throw IllegalArgumentException("config.ini 里没有 CustomName/ThemeName/WallpaperName")
                        }
                    }
                }
            }
            val expectedMd5 = ini["md5"].orEmpty().ifBlank { ini["packmd5"].orEmpty() }
            val actualMd5 = md5(packageFile)
            val matched = expectedMd5.isBlank() || actualMd5.equals(expectedMd5, ignoreCase = true)

            return ThemePackageInfo(
                themeName = ini["themename"].orEmpty().ifBlank { ini["wallpapername"].orEmpty() },
                customName = customName,
                customDesc = ini["customdesc"].orEmpty(),
                customAuthor = ini["customauthor"].orEmpty(),
                customVersion = ini["customversion"].orEmpty().ifBlank { ini["version"].orEmpty() },
                themeType = ini["themetype"].orEmpty(),
                startVersion = ini["startversion"].orEmpty(),
                md5 = expectedMd5,
                md5Matched = matched,
                configFile = configFile,
                packageFile = packageFile,
                packageName = packageFile.name,
                packageSize = packageFile.length(),
            )
        }
    }

    private fun copyEntry(zip: ZipFile, name: String, out: File) {
        val entry = zip.getEntry(name) ?: throw IllegalArgumentException("zip 缺少 $name")
        zip.getInputStream(entry).use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    private fun parseIni(file: File): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim().removePrefix("﻿")
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) return@forEach
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim().lowercase()
                var value = line.substring(idx + 1).trim()
                if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length - 1)
                }
                map[key] = value
            }
        }
        return map
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
