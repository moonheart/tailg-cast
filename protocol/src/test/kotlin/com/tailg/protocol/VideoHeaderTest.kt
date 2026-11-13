package com.tailg.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VideoHeaderTest {

    @Test
    fun `header is 69 bytes with android magic and big endian size`() {
        val h = VideoHeader.build(1024, 600)
        assertEquals(69, h.size)
        assertEquals(0, h[0].toInt())
        assertEquals("android", VideoHeader.magic(h))
        assertEquals(1024, VideoHeader.width(h))
        assertEquals(600, VideoHeader.height(h))
        assertEquals(0x04, h[65].toInt() and 0xFF)
        assertEquals(0x00, h[66].toInt() and 0xFF)
        assertEquals(0x02, h[67].toInt() and 0xFF)
        assertEquals(0x58, h[68].toInt() and 0xFF)
    }

    @Test
    fun `size max values fit in two bytes`() {
        val h = VideoHeader.build(65535, 65535)
        assertEquals(65535, VideoHeader.width(h))
        assertEquals(65535, VideoHeader.height(h))
    }

    @Test
    fun `rejects out of range size`() {
        assertFailsWith<IllegalArgumentException> { VideoHeader.build(0, 600) }
        assertFailsWith<IllegalArgumentException> { VideoHeader.build(1024, 65536) }
    }
}
