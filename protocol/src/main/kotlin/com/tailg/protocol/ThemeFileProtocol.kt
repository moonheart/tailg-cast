package com.tailg.protocol

import java.nio.charset.StandardCharsets

/**
 * 台铃车机主题/壁纸上传的文件帧（第三方「仪表助手 2.0」逆向）。
 *
 * 文件头 152 字节：
 *   "$FILE" + 01 + 文件长度(4) + 文件名(128) + 0(4)
 *   + 分片数(4) + 102400(4) + 校验(2)
 *
 * 分片： "$FILE" + 02 + 序号(4) + 长度(4) + 数据 + 校验(2)
 * 结束： "$FILE" 03
 *
 * 校验：字节累加取低 8 位，高/低半字节各 |0x80。
 */
object ThemeFileProtocol {

    const val CHUNK_SIZE: Int = 102400
    const val MAX_NAME_BYTES: Int = 128

    const val TYPE_HEADER: Int = 1
    const val TYPE_CHUNK: Int = 2
    const val TYPE_END: Int = 3

    private val MAGIC = byteArrayOf(0x24, 0x46, 0x49, 0x4C, 0x45) // "$FILE"

    fun fileHeader(fileName: String, length: Long): ByteArray {
        require(fileName.isNotBlank()) { "file name is blank" }
        val name = fileName.toByteArray(StandardCharsets.UTF_8)
        require(name.size <= MAX_NAME_BYTES) { "file name too long: ${name.size} bytes" }
        require(length >= 0) { "negative length" }

        val payload = ByteArray(144)
        putBe32(payload, 0, length)
        name.copyInto(payload, 4)
        putBe32(payload, 136, chunkCount(length))
        putBe32(payload, 140, CHUNK_SIZE)

        val out = ByteArray(152)
        MAGIC.copyInto(out, 0)
        out[5] = TYPE_HEADER.toByte()
        payload.copyInto(out, 6)
        checksum(payload, 0, payload.size).copyInto(out, 150)
        return out
    }

    fun chunk(index: Int, data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray {
        require(index >= 0) { "negative chunk index" }
        require(length in 0..CHUNK_SIZE) { "bad chunk length: $length" }
        require(offset >= 0 && offset + length <= data.size) { "chunk out of data range" }

        val out = ByteArray(14 + length + 2)
        MAGIC.copyInto(out, 0)
        out[5] = TYPE_CHUNK.toByte()
        putBe32(out, 6, index)
        putBe32(out, 10, length)
        data.copyInto(out, 14, offset, offset + length)
        checksum(out, 14, length).copyInto(out, 14 + length)
        return out
    }

    fun end(): ByteArray = MAGIC + byteArrayOf(TYPE_END.toByte())

    /** 文件/分片校验：先按字节累加，再拆成高低半字节。 */
    fun checksum(data: ByteArray, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "checksum range" }
        var sum = 0
        for (i in offset until offset + length) {
            sum = (sum + (data[i].toInt() and 0xFF)) and 0xFF
        }
        return byteArrayOf(
            (((sum and 0xF0) ushr 4) or 0x80).toByte(),
            ((sum and 0x0F) or 0x80).toByte(),
        )
    }

    fun chunkCount(length: Long): Int {
        require(length >= 0) { "negative length" }
        return ((length + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
    }

    private fun putBe32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun putBe32(out: ByteArray, offset: Int, value: Int) {
        putBe32(out, offset, value.toLong() and 0xFFFFFFFFL)
    }
}
