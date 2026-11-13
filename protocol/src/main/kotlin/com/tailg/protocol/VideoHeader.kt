package com.tailg.protocol

import java.nio.charset.StandardCharsets

/**
 * 15456 视频口握手头：69 字节。
 *
 * 00 + "android" (8 字节，后面补 0) + 保留 + 宽(2, 大端) + 高(2, 大端)。
 * 宽高在 65..68，和 SPS 里的尺寸保持一致。
 */
object VideoHeader {

    const val SIZE: Int = 69

    private const val MAGIC = "android"

    fun build(width: Int, height: Int): ByteArray {
        require(width in 1..0xFFFF) { "width out of range: $width" }
        require(height in 1..0xFFFF) { "height out of range: $height" }

        val out = ByteArray(SIZE)
        MAGIC.toByteArray(StandardCharsets.US_ASCII).copyInto(out, 1)
        out[65] = (width ushr 8).toByte()
        out[66] = width.toByte()
        out[67] = (height ushr 8).toByte()
        out[68] = height.toByte()
        return out
    }

    fun width(header: ByteArray): Int {
        require(header.size >= SIZE) { "header too short: ${header.size}" }
        return ((header[65].toInt() and 0xFF) shl 8) or (header[66].toInt() and 0xFF)
    }

    fun height(header: ByteArray): Int {
        require(header.size >= SIZE) { "header too short: ${header.size}" }
        return ((header[67].toInt() and 0xFF) shl 8) or (header[68].toInt() and 0xFF)
    }

    fun magic(header: ByteArray): String {
        require(header.size >= 9) { "header too short: ${header.size}" }
        var end = 1
        while (end < 65 && header[end] != 0.toByte()) end++
        return String(header, 1, end - 1, StandardCharsets.US_ASCII)
    }
}
