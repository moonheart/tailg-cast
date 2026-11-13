package com.tailg.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PushbackInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {

    @Test
    fun `frame writes big endian length`() {
        val f = Codec.frame(0x01, 0x0C, byteArrayOf(1, 2, 3))
        assertEquals(9, f.size)
        assertEquals(0x01, f[0].toInt())
        assertEquals(0x0C, f[1].toInt())
        assertContentEquals(byteArrayOf(0, 0, 0, 3), f.copyOfRange(2, 6))
        assertContentEquals(byteArrayOf(1, 2, 3), f.copyOfRange(6, 9))
    }

    @Test
    fun `frame with null payload has zero length`() {
        val f = Codec.frame(0x02, 0x01, null)
        assertEquals(6, f.size)
        assertContentEquals(byteArrayOf(0, 0, 0, 0), f.copyOfRange(2, 6))
    }

    @Test
    fun `json frame has ee fd header and ff tail`() {
        val json = """{"msg_id":27,"func":"OTA","act":1,"type":0}"""
        val body = json.toByteArray(Codec.UTF8)
        val f = Codec.jsonFrame(json)

        assertEquals(0xEE, f[0].toInt() and 0xFF)
        assertEquals(0xFD, f[1].toInt() and 0xFF)
        assertEquals(body.size, Codec.be32(f, 2))
        assertEquals(0xFF, f.last().toInt() and 0xFF)
        assertEquals(json, String(f, 6, body.size, Codec.UTF8))
    }

    @Test
    fun `be32 round trips`() {
        for (v in intArrayOf(0, 1, 255, 256, 65535, 1 shl 24, -1)) {
            assertEquals(v, Codec.be32(Codec.be32(v), 0))
        }
    }

    @Test
    fun `read frame consumes exactly one frame`() {
        val a = Codec.frame(0x01, 0x02, byteArrayOf(7, 8))
        val b = Codec.jsonFrame("""{"ok":true}""")
        val input = ByteArrayInputStream(a + b)

        val fa = Codec.readFrame(input)!!
        assertEquals(0x01, fa.type)
        assertEquals(0x02, fa.cmd)
        assertContentEquals(byteArrayOf(7, 8), fa.payload)
        assertFalse(fa.badLength)

        val fb = Codec.readFrame(input)!!
        assertTrue(Codec.isJson(fb))
        assertTrue(Codec.jsonTailOk(fb))
        assertEquals("""{"ok":true}""", Codec.jsonText(fb))

        assertNull(Codec.readFrame(input))
    }

    @Test
    fun `json tail is pushed back when it is not ff`() {
        val body = "abc".toByteArray(Codec.UTF8)
        val head = byteArrayOf(0xEE.toByte(), 0xFD.toByte()) + Codec.be32(body.size) + body
        val input = PushbackInputStream(ByteArrayInputStream(head + byteArrayOf(0x42)), 4)

        val f = Codec.readFrame(input)!!
        assertEquals("abc", Codec.jsonText(f))
        assertFalse(Codec.jsonTailOk(f))
        assertEquals(0x42, input.read())
    }

    @Test
    fun `bad length does not allocate payload`() {
        val head = byteArrayOf(0x01, 0x02) + Codec.be32(Int.MAX_VALUE)
        val f = Codec.readFrame(ByteArrayInputStream(head))!!
        assertTrue(f.badLength)
        assertEquals(0, f.payload.size)
    }

    @Test
    fun `fixed string pads and truncates`() {
        assertContentEquals(byteArrayOf(65, 66, 0, 0), Codec.fixedString("AB", 4))
        assertContentEquals(byteArrayOf(65, 66), Codec.fixedString("ABC", 2))
        assertContentEquals(byteArrayOf(0, 0), Codec.fixedString(null, 2))
    }

    @Test
    fun `version blocks split every 128 bytes`() {
        val payload = ByteArray(256)
        "V1.2.5-PROBE".toByteArray(Codec.UTF8).copyInto(payload, 0)
        "MCU-PROBE".toByteArray(Codec.UTF8).copyInto(payload, 128)
        assertEquals("0:V1.2.5-PROBE | 1:MCU-PROBE", Codec.versionBlocks(payload))
    }

    @Test
    fun `drain returns and clears buffer`() {
        val bos = ByteArrayOutputStream()
        bos.write(byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), Codec.drain(bos))
        assertEquals(0, bos.size())
    }
}
