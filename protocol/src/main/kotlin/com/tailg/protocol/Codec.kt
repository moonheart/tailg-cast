package com.tailg.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets

/**
 * 台铃 17818 帧编解码：6 字节头 + 负载。
 *
 * 普通帧：type(1) + cmd(1) + len(4, 大端) + payload。
 * JSON 帧：type=EE cmd=FD，len 是 JSON 字节数，负载末尾还会多一个 FF。
 */
object Codec {

    @JvmField
    val UTF8: java.nio.charset.Charset = StandardCharsets.UTF_8

    /** 单帧上限，防止坏长度把内存吃光。 */
    const val MAX_FRAME_BYTES: Int = 32 * 1024 * 1024

    fun frame(type: Int, cmd: Int, payload: ByteArray?): ByteArray {
        val body = payload ?: ByteArray(0)
        val out = ByteArray(6 + body.size)
        out[0] = type.toByte()
        out[1] = cmd.toByte()
        writeBe32(out, 2, body.size)
        body.copyInto(out, 6)
        return out
    }

    fun jsonFrame(json: String): ByteArray {
        val body = json.toByteArray(UTF8)
        val out = ByteArray(6 + body.size + 1)
        out[0] = 0xEE.toByte()
        out[1] = 0xFD.toByte()
        writeBe32(out, 2, body.size)
        body.copyInto(out, 6)
        out[out.lastIndex] = 0xFF.toByte()
        return out
    }

    fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    fun be32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16).toByte(),
        (v ushr 8).toByte(),
        v.toByte(),
    )

    private fun writeBe32(out: ByteArray, off: Int, v: Int) {
        out[off] = (v ushr 24).toByte()
        out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte()
        out[off + 3] = v.toByte()
    }

    /** 读满 n 字节；连接断开返回 null。 */
    fun readFully(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) {
                return if (off == 0) null else buf.copyOf(off)
            }
            off += r
        }
        return buf
    }

    /**
     * 从流里读一个完整帧（含 6 字节头）。
     * JSON 帧的长度字段是 JSON 字节数，实际还多一个 FF 结尾，所以多读 1 字节；
     * 不是 FF 就推回流里，避免错位。
     */
    fun readFrame(input: InputStream): RawFrame? {
        val head = readFully(input, 6) ?: return null
        if (head.size < 6) return null

        val type = head[0].toInt() and 0xFF
        val cmd = head[1].toInt() and 0xFF
        val len = be32(head, 2)

        if (len < 0 || len > MAX_FRAME_BYTES) {
            return RawFrame(type, cmd, len, ByteArray(0), badLength = true)
        }

        var payload = if (len == 0) ByteArray(0) else (readFully(input, len) ?: return null)

        if (type == 0xEE && cmd == 0xFD) {
            val tail = input.read()
            if (tail == 0xFF) {
                payload += 0xFF.toByte()
            } else if (tail >= 0 && input is PushbackInputStream) {
                input.unread(tail)
            }
        }

        return RawFrame(type, cmd, len, payload)
    }

    fun isJson(frame: RawFrame): Boolean = frame.type == 0xEE && frame.cmd == 0xFD

    /** JSON 帧负载去掉结尾 FF 后的文本。 */
    fun jsonText(frame: RawFrame): String {
        var n = frame.payload.size
        while (n > 0 && frame.payload[n - 1] == 0xFF.toByte()) n--
        return String(frame.payload, 0, n, UTF8)
    }

    fun jsonTailOk(frame: RawFrame): Boolean =
        frame.payload.isNotEmpty() && frame.payload.last() == 0xFF.toByte()

    fun fixedString(s: String?, size: Int): ByteArray {
        val out = ByteArray(size)
        val b = s?.toByteArray(UTF8) ?: ByteArray(0)
        b.copyInto(out, 0, 0, minOf(b.size, size))
        return out
    }

    /** 把 128 字节的版本块拼起来（对应官方 kc.d 的解析方式）。 */
    fun versionBlocks(payload: ByteArray): String {
        val sb = StringBuilder()
        var off = 0
        while (off + 128 <= payload.size) {
            val s = String(payload, off, 128, UTF8)
                .trim()
                .replace("\u0000", "")
            if (s.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append(off / 128).append(':').append(s)
            }
            off += 128
        }
        return sb.toString()
    }

    fun concat(a: ByteArray?, b: ByteArray?): ByteArray? {
        if (a == null) return b
        if (b == null) return a
        return a + b
    }

    fun drain(bos: ByteArrayOutputStream): ByteArray {
        val out = bos.toByteArray()
        bos.reset()
        return out
    }
}

data class RawFrame(
    val type: Int,
    val cmd: Int,
    val len: Int,
    val payload: ByteArray,
    val badLength: Boolean = false,
) {
    fun head(): String = "type=%02X cmd=%02X len=%d".format(type, cmd, len)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawFrame) return false
        return type == other.type &&
            cmd == other.cmd &&
            len == other.len &&
            badLength == other.badLength &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + cmd
        result = 31 * result + len
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + badLength.hashCode()
        return result
    }
}
