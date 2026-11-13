package com.tailg.protocol

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class ThemePackageTest {

    private val root = createTempDirectory("theme-package-test").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `extracts config and package and parses fields`() {
        val packageBytes = ByteArray(2048) { (it * 31).toByte() }
        val md5 = md5(packageBytes)
        val config = """
            ThemeName="台铃K075"
            ThemeType="S19"
            StartVersion="1.1.4"
            CustomName="测试主题"
            CustomDesc="desc"
            CustomAuthor="tester"
            CustomVersion="1.0.0"
            Md5="$md5"
        """.trimIndent()
        val zip = File(root, "theme.zip")
        zip(zip) {
            entry("config.ini", config.toByteArray())
            entry("package.zip", packageBytes)
            entry("bg_1.png", byteArrayOf(1, 2, 3))
        }

        val info = ThemePackage.extract(zip, File(root, "out"))

        assertEquals("台铃K075", info.themeName)
        assertEquals("测试主题", info.customName)
        assertEquals("S19", info.themeType)
        assertEquals("1.1.4", info.startVersion)
        assertEquals("tester", info.customAuthor)
        assertEquals("package.zip", info.packageName)
        assertEquals(packageBytes.size.toLong(), info.packageSize)
        assertTrue(info.md5Matched)
        assertTrue(info.configFile.isFile)
        assertTrue(info.packageFile.isFile)
    }

    @Test
    fun `md5 mismatch is reported but extraction still works`() {
        val zip = File(root, "bad-md5.zip")
        zip(zip) {
            entry(
                "config.ini",
                "CustomName=\"x\"\nMd5=\"0000\"\n".toByteArray(),
            )
            entry("package.zip", byteArrayOf(1, 2, 3))
        }

        val info = ThemePackage.extract(zip, File(root, "out2"))
        assertEquals(false, info.md5Matched)
    }

    @Test
    fun `missing config is rejected`() {
        val zip = File(root, "no-config.zip")
        zip(zip) {
            entry("package.zip", byteArrayOf(1, 2, 3))
        }

        assertFailsWith<IllegalArgumentException> {
            ThemePackage.extract(zip, File(root, "out3"))
        }
    }

    private fun zip(file: File, block: ZipOutputStream.() -> Unit) {
        ZipOutputStream(FileOutputStream(file)).use { out ->
            out.block()
        }
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
