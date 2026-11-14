package com.tailg.protocol

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 将本地图片或 MP4 视频打包成 ThinkRide 自制壁纸包（外层 zip，含 *_config.ini + *_package.zip）。
 * 结构对齐官方自制壁纸示例：内层含 DragonCross/dashboard/etc/dynamic-load.ini 与媒体资源。
 */
object WallpaperBuilder {

    fun build(
        mediaFile: File,
        isVideo: Boolean,
        wallpaperName: String,
        outZipFile: File,
        workDir: File,
    ): File {
        require(mediaFile.exists() && mediaFile.length() > 0L) { "媒体文件不存在或为空" }
        if (!workDir.exists()) workDir.mkdirs()

        val idPrefix = "${System.currentTimeMillis()}000_0.0.1_wallpaper"
        val mediaEntryName = if (isVideo) "night.mp4" else "bg_1.png"
        val dynamicIni = buildString {
            appendLine("[wallpaper]")
            if (isVideo) {
                appendLine("type=video")
                appendLine("video=$mediaEntryName")
            } else {
                appendLine("type=image")
                appendLine("bg=$mediaEntryName")
            }
        }

        val innerZip = File(workDir, "${idPrefix}_package.zip")
        ZipOutputStream(FileOutputStream(innerZip)).use { zos ->
            // dynamic-load.ini
            zos.putNextEntry(ZipEntry("DragonCross/dashboard/etc/dynamic-load.ini"))
            zos.write(dynamicIni.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 媒体文件放一份在包根目录（与自制壁纸示例一致）
            zos.putNextEntry(ZipEntry(mediaEntryName))
            FileInputStream(mediaFile).use { it.copyTo(zos, 64 * 1024) }
            zos.closeEntry()
        }

        val packMd5 = md5Hex(innerZip)
        val safeName = wallpaperName.ifBlank { "CustomWallpaper" }
        val configContent = buildString {
            appendLine("[wallpaper]")
            appendLine("WallpaperName=$safeName")
            appendLine("WallpaperName_zh_CN=$safeName")
            appendLine("CustomName=$safeName")
            appendLine("Version=0.0.1")
            appendLine("PackMd5=$packMd5")
        }
        val configIni = File(workDir, "${idPrefix}_${packMd5.take(10)}_config.ini")
        configIni.writeText(configContent, Charsets.UTF_8)

        outZipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outZipFile)).use { zos ->
            zos.putNextEntry(ZipEntry(configIni.name))
            FileInputStream(configIni).use { it.copyTo(zos, 16 * 1024) }
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(innerZip.name))
            FileInputStream(innerZip).use { it.copyTo(zos, 64 * 1024) }
            zos.closeEntry()

            zos.putNextEntry(ZipEntry(mediaEntryName))
            FileInputStream(mediaFile).use { it.copyTo(zos, 64 * 1024) }
            zos.closeEntry()
        }
        return outZipFile
    }

    private fun md5Hex(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
