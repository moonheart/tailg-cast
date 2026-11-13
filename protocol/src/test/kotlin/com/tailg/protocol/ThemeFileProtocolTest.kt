package com.tailg.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThemeFileProtocolTest {

    @Test
    fun `file header layout`() {
        val header = ThemeFileProtocol.fileHeader("config.ini", 205_001)

        assertEquals(152, header.size)
        assertContentEquals(byteArrayOf(0x24, 0x46, 0x49, 0x4C, 0x45), header.copyOfRange(0, 5))
        assertEquals(1, header[5].toInt())
        assertEquals(205_001, be32(header, 6))
        assertEquals("config.ini", String(header, 10, 10, Charsets.UTF_8))
        assertEquals(0, be32(header, 138))
        assertEquals(3, be32(header, 142))
        assertEquals(ThemeFileProtocol.CHUNK_SIZE, be32(header, 146))
        assertContentEquals(
            ThemeFileProtocol.checksum(header, 6, 144),
            header.copyOfRange(150, 152),
        )
    }

    @Test
    fun `chunk layout and checksum`() {
        val data = ByteArray(100) { (it and 0xFF).toByte() }
        val chunk = ThemeFileProtocol.chunk(2, data)

        assertEquals(14 + 100 + 2, chunk.size)
        assertEquals(2, chunk[5].toInt())
        assertEquals(2, be32(chunk, 6))
        assertEquals(100, be32(chunk, 10))
        assertContentEquals(data, chunk.copyOfRange(14, 114))
        assertContentEquals(
            ThemeFileProtocol.checksum(chunk, 14, 100),
            chunk.copyOfRange(114, 116),
        )
    }

    @Test
    fun `end marker`() {
        assertContentEquals(
            byteArrayOf(0x24, 0x46, 0x49, 0x4C, 0x45, 0x03),
            ThemeFileProtocol.end(),
        )
    }

    @Test
    fun `checksum nibbles`() {
        val sum3 = ThemeFileProtocol.checksum(byteArrayOf(1, 2), 0, 2)
        assertEquals(0x80, sum3[0].toInt() and 0xFF)
        assertEquals(0x83, sum3[1].toInt() and 0xFF)

        val sum0 = ThemeFileProtocol.checksum(byteArrayOf(0), 0, 1)
        assertEquals(0x80, sum0[0].toInt() and 0xFF)
        assertEquals(0x80, sum0[1].toInt() and 0xFF)
    }

    @Test
    fun `chunk count`() {
        assertEquals(0, ThemeFileProtocol.chunkCount(0))
        assertEquals(1, ThemeFileProtocol.chunkCount(1))
        assertEquals(1, ThemeFileProtocol.chunkCount(102_400))
        assertEquals(2, ThemeFileProtocol.chunkCount(102_401))
    }

    @Test
    fun `long file name is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ThemeFileProtocol.fileHeader("x".repeat(129), 1)
        }
    }

    @Test
    fun `empty file still has a header and end`() {
        val header = ThemeFileProtocol.fileHeader("empty.bin", 0)
        assertEquals(0, be32(header, 6))
        assertEquals(0, be32(header, 142))
        assertTrue(ThemeFileProtocol.end().isNotEmpty())
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
