package com.tailg.probe

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Local CarLife Head-Unit TCP bridge (127.0.0.1:7240/8240/9340...).
 * Connects to ColorOS Car+ / com.baidu.carlife.oppo and feeds raw H.264 Annex-B frames
 * directly into CastEngine without decoding or re-encoding.
 */
class CarlifeBridge(
    private val reqWidth: Int,
    private val reqHeight: Int,
    private val reqFps: Int,
    private val callback: Callback,
    private val ensureServer: (() -> Unit)? = null,
) {
    interface Callback {
        fun onState(state: String)
        fun onH264Frame(nalu: ByteArray)
    }

    private val touchExec = Executors.newSingleThreadExecutor()

    @Volatile private var running = false
    @Volatile private var handshakeAcked = false
    @Volatile private var videoW = reqWidth
    @Volatile private var videoH = reqHeight

    private var cmdSock: Socket? = null
    private var videoSock: Socket? = null
    private var touchSock: Socket? = null
    private var audioSock: Socket? = null
    private var ttsSock: Socket? = null
    private var vrSock: Socket? = null
    private var dataSock: Socket? = null
    private var cmdOut: OutputStream? = null
    @Volatile private var touchOut: OutputStream? = null

    fun videoWidth(): Int = videoW
    fun videoHeight(): Int = videoH

    fun start() {
        if (running) return
        running = true
        handshakeAcked = false
        Thread({ connectLoop() }, "CarLife-Bridge").start()
    }

    fun stop() {
        running = false
        handshakeAcked = false
        closeSockets()
        touchExec.shutdownNow()
    }

    fun sendTouch(action: Int, x: Int, y: Int) {
        val out = touchOut ?: return
        if (!running) return
        touchExec.execute {
            try {
                sendFrame8(out, MSG_TOUCH_ACTION, encodeTouchAction(action, x, y))
            } catch (_: Exception) {
            }
        }
    }

    private fun connectLoop() {
        var lastLaunchAt = 0L
        while (running) {
            try {
                callback.onState("CarLife 连接 127.0.0.1:7240…")
                LogSink.line("CARLIFE", "尝试连接 127.0.0.1:7240")
                val cmd = try {
                    openTcp(LOCALHOST, PORT_CMD)
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    if (ensureServer != null && now - lastLaunchAt > 10000L) {
                        lastLaunchAt = now
                        LogSink.line("CARLIFE", "7240 未监听，尝试通过 root 拉起 Car+ 连接")
                        callback.onState("正在自动拉起 CarLife 服务…")
                        ensureServer.invoke()
                        Thread.sleep(1200)
                        openTcp(LOCALHOST, PORT_CMD)
                    } else {
                        throw e
                    }
                }
                cmdSock = cmd
                cmdOut = cmd.getOutputStream()

                videoSock = openTcpOptional(LOCALHOST, PORT_VIDEO)
                touchSock = openTcpOptional(LOCALHOST, PORT_TOUCH)?.also { touchOut = it.getOutputStream() }
                audioSock = openTcpOptional(LOCALHOST, PORT_AUDIO)
                ttsSock = openTcpOptional(LOCALHOST, PORT_TTS)
                vrSock = openTcpOptional(LOCALHOST, PORT_VR)
                dataSock = openTcpOptional(LOCALHOST, PORT_DATA)

                startDrainer(audioSock, "CarLife-Audio")
                startDrainer(ttsSock, "CarLife-TTS")
                startDrainer(vrSock, "CarLife-VR")
                startDrainer(dataSock, "CarLife-Data")

                if (videoSock != null) {
                    Thread({ videoLoop() }, "CarLife-Video").start()
                }

                handshakeAcked = false
                Thread({ handshakeRetryLoop() }, "CarLife-Handshake").start()

                cmdLoop(cmd)
            } catch (e: Exception) {
                if (running) {
                    LogSink.line("CARLIFE", "连接中断/等待重试: ${e.message}")
                    callback.onState("等待 CarLife 端口 (127.0.0.1:7240)…")
                }
            } finally {
                closeSockets()
            }
            if (running) {
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun closeSockets() {
        listOf(cmdSock, videoSock, touchSock, audioSock, ttsSock, vrSock, dataSock).forEach(::closeQuietly)
        cmdSock = null
        videoSock = null
        touchSock = null
        audioSock = null
        ttsSock = null
        vrSock = null
        dataSock = null
        cmdOut = null
        touchOut = null
    }

    private fun openTcp(host: String, port: Int): Socket =
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), 2000)
        }

    private fun openTcpOptional(host: String, port: Int): Socket? =
        try {
            openTcp(host, port)
        } catch (_: Exception) {
            null
        }

    private fun startDrainer(sock: Socket?, name: String) {
        if (sock == null) return
        Thread({
            val buf = ByteArray(8192)
            try {
                val input: InputStream = sock.getInputStream()
                while (running && input.read(buf) != -1) {
                    // Drain auxiliary channels
                }
            } catch (_: Exception) {
            }
        }, name).start()
    }

    private fun handshakeRetryLoop() {
        while (running && !handshakeAcked && cmdSock != null) {
            try {
                sendCmd(MSG_HU_PROTOCOL_VERSION, encodeProtocolVersion(4, 0))
                Thread.sleep(1500)
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun heartbeatLoop() {
        while (running && cmdSock != null) {
            try {
                Thread.sleep(1500)
                if (!running || cmdSock == null) break
                sendCmd(MSG_HU_HEARTBEAT, ByteArray(0))
            } catch (_: Exception) {
                break
            }
        }
    }

    @Throws(IOException::class)
    private fun cmdLoop(cmd: Socket) {
        val input = DataInputStream(cmd.getInputStream())
        while (running) {
            val len = input.readUnsignedShort()
            input.readUnsignedShort()
            val serviceType = input.readInt()
            val payload = ByteArray(len)
            if (len > 0) input.readFully(payload)
            handleCmd(serviceType, payload)
        }
    }

    @Throws(IOException::class)
    private fun handleCmd(serviceType: Int, payload: ByteArray) {
        when (serviceType) {
            MSG_MD_PROTOCOL_VERSION_MATCH -> {
                val f = IntArray(4)
                decodeFlatIntFields(payload, f, 3)
                if (f[1] == 1) {
                    handshakeAcked = true
                    LogSink.line("CARLIFE", "版本匹配成功，开始设备握手")
                    sendCmd(MSG_HU_STATISTICS_INFO, encodeStatisticsInfo())
                    Thread({ heartbeatLoop() }, "CarLife-HB").start()
                }
            }
            MSG_MD_INFO -> sendCmd(MSG_HU_INFO, encodeDeviceInfo())
            MSG_MD_AUTHEN_RESULT -> {
                sendCmd(MSG_HU_AUTHEN_RESULT_ACK, ByteArray(0))
                sendCmd(MSG_HU_VIDEO_ENCODER_INIT, encodeVideoEncoderInfo(reqWidth, reqHeight, reqFps))
            }
            MSG_MD_FEATURE_CONFIG_LIST -> sendCmd(MSG_HU_FEATURE_CONFIG_LIST, encodeFeatureConfigList(1))
            MSG_MD_VIDEO_ENCODER_INIT_DONE ->
                sendCmd(MSG_HU_VIDEO_ENCODER_INIT, encodeVideoEncoderInfo(reqWidth, reqHeight, reqFps))
            MSG_MD_VIDEO_ENCODER_INIT -> {
                val f = IntArray(4)
                decodeFlatIntFields(payload, f, 3)
                if (f[1] > 0) videoW = f[1]
                if (f[2] > 0) videoH = f[2]
                LogSink.line("CARLIFE", "视频就绪 ${videoW}x${videoH}，请求推流")
                callback.onState("CarLife 投屏中 (${videoW}x${videoH})")
                sendCmd(MSG_HU_VIDEO_ENCODER_START, ByteArray(0))
            }
        }
    }

    private fun videoLoop() {
        val sock = videoSock ?: return
        var firstLogged = false
        try {
            val input = DataInputStream(sock.getInputStream())
            while (running) {
                val len = input.readInt()
                input.readInt() // timestamp
                val serviceType = input.readInt()
                if (len < 0 || len > 8 * 1024 * 1024) {
                    throw IOException("Invalid video frame len: $len")
                }
                if (len == 0) continue
                val nalu = ByteArray(len)
                input.readFully(nalu)
                if (!firstLogged) {
                    firstLogged = true
                    LogSink.line("CARLIFE", "首帧 serviceType=$serviceType len=$len")
                }
                if (serviceType == MSG_VIDEO_DATA || serviceType == 0x00020001 || len > 4) {
                    callback.onH264Frame(nalu)
                }
            }
        } catch (_: EOFException) {
        } catch (e: Exception) {
            if (running) LogSink.line("CARLIFE", "视频通道结束: ${e.message}")
        }
    }

    @Synchronized
    @Throws(IOException::class)
    private fun sendCmd(serviceType: Int, payload: ByteArray) {
        val out = cmdOut ?: return
        sendFrame8(out, serviceType, payload)
    }

    companion object {
        private const val LOCALHOST = "127.0.0.1"
        private const val PORT_CMD = 7240
        private const val PORT_VIDEO = 8240
        private const val PORT_AUDIO = 9240
        private const val PORT_TTS = 9241
        private const val PORT_VR = 9242
        private const val PORT_TOUCH = 9340
        private const val PORT_DATA = 9440

        private const val MSG_HU_PROTOCOL_VERSION = 98305
        private const val MSG_MD_PROTOCOL_VERSION_MATCH = 65538
        private const val MSG_HU_INFO = 98307
        private const val MSG_MD_INFO = 65540
        private const val MSG_HU_VIDEO_ENCODER_INIT = 98311
        private const val MSG_MD_VIDEO_ENCODER_INIT = 65544
        private const val MSG_MD_VIDEO_ENCODER_INIT_DONE = 65551
        private const val MSG_HU_VIDEO_ENCODER_START = 98313
        private const val MSG_HU_STATISTICS_INFO = 98343
        private const val MSG_MD_AUTHEN_RESULT = 65611
        private const val MSG_HU_AUTHEN_RESULT_ACK = 98380
        private const val MSG_MD_FEATURE_CONFIG_LIST = 65617
        private const val MSG_HU_FEATURE_CONFIG_LIST = 98386
        private const val MSG_HU_HEARTBEAT = 131074
        private const val MSG_VIDEO_DATA = 131073
        private const val MSG_TOUCH_ACTION = 425985

        @Throws(IOException::class)
        private fun sendFrame8(out: OutputStream, serviceType: Int, payload: ByteArray) {
            val len = payload.size
            val pkt = ByteArray(8 + len)
            pkt[0] = ((len ushr 8) and 0xFF).toByte()
            pkt[1] = (len and 0xFF).toByte()
            pkt[2] = 0
            pkt[3] = 0
            pkt[4] = ((serviceType ushr 24) and 0xFF).toByte()
            pkt[5] = ((serviceType ushr 16) and 0xFF).toByte()
            pkt[6] = ((serviceType ushr 8) and 0xFF).toByte()
            pkt[7] = (serviceType and 0xFF).toByte()
            if (len > 0) System.arraycopy(payload, 0, pkt, 8, len)
            out.write(pkt)
            out.flush()
        }

        private fun closeQuietly(s: Socket?) {
            try {
                s?.close()
            } catch (_: Exception) {
            }
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while ((v and 0x7FL.inv()) != 0L) {
                out.write(((v and 0x7FL) or 0x80L).toInt())
                v = v ushr 7
            }
            out.write((v and 0x7FL).toInt())
        }

        private fun writeInt32(out: ByteArrayOutputStream, field: Int, value: Int) {
            writeVarint(out, field.toLong() shl 3)
            writeVarint(out, value.toLong() and 0xFFFFFFFFL)
        }

        private fun writeBool(out: ByteArrayOutputStream, field: Int, value: Boolean) {
            writeVarint(out, field.toLong() shl 3)
            out.write(if (value) 1 else 0)
        }

        private fun writeString(out: ByteArrayOutputStream, field: Int, value: String) {
            writeBytes(out, field, value.toByteArray(StandardCharsets.UTF_8))
        }

        private fun writeBytes(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
            writeVarint(out, (field.toLong() shl 3) or 2L)
            writeVarint(out, bytes.size.toLong())
            out.write(bytes)
        }

        private fun encodeProtocolVersion(major: Int, minor: Int) = ByteArrayOutputStream().apply {
            writeInt32(this, 1, major)
            writeInt32(this, 2, minor)
        }.toByteArray()

        private fun encodeStatisticsInfo() = ByteArrayOutputStream().apply {
            writeString(this, 1, "tailg_carlife_hu_01")
            writeString(this, 2, "2.0.0")
            writeInt32(this, 3, 200)
            writeString(this, 4, "20010100")
            writeInt32(this, 5, 1)
            writeInt32(this, 6, 1)
            writeInt32(this, 7, 1)
        }.toByteArray()

        private fun encodeDeviceInfo() = ByteArrayOutputStream().apply {
            writeString(this, 1, "Android")
            writeString(this, 4, "Tailg_CarLife")
            writeString(this, 13, "Tailg")
            writeString(this, 14, "Tailg_Dashboard")
            writeString(this, 19, "14")
            writeString(this, 20, "34")
            writeInt32(this, 21, 34)
            writeString(this, 22, "2.0.0")
            writeString(this, 23, "00:11:22:33:44:55")
        }.toByteArray()

        private fun encodeVideoEncoderInfo(width: Int, height: Int, fps: Int) = ByteArrayOutputStream().apply {
            writeInt32(this, 1, width)
            writeInt32(this, 2, height)
            writeInt32(this, 3, fps)
        }.toByteArray()

        private fun encodeFeatureConfigList(connectType: Int) = ByteArrayOutputStream().apply {
            writeInt32(this, 1, 2)
            writeBytes(this, 2, encodeFeatureConfigItem("I_FRAME_INTERVAL", 2))
            writeBytes(this, 2, encodeFeatureConfigItem("CONNECT_TYPE", connectType))
            writeBool(this, 3, false)
            writeString(this, 4, "Tailg_CarLife")
            writeString(this, 5, "00:11:22:33:44:55")
        }.toByteArray()

        private fun encodeFeatureConfigItem(key: String, value: Int) = ByteArrayOutputStream().apply {
            writeString(this, 1, key)
            writeInt32(this, 2, value)
        }.toByteArray()

        private fun encodeTouchAction(action: Int, x: Int, y: Int) = ByteArrayOutputStream().apply {
            writeInt32(this, 1, action)
            writeInt32(this, 2, x)
            writeInt32(this, 3, y)
        }.toByteArray()

        private fun decodeFlatIntFields(data: ByteArray, outFields: IntArray, maxField: Int) {
            var i = 0
            while (i < data.size) {
                var tag = 0L
                var shift = 0
                while (i < data.size) {
                    val b = data[i++].toInt() and 0xFF
                    tag = tag or ((b and 0x7F).toLong() shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                val field = (tag ushr 3).toInt()
                when ((tag and 0x7L).toInt()) {
                    0 -> {
                        var value = 0L
                        shift = 0
                        while (i < data.size) {
                            val b = data[i++].toInt() and 0xFF
                            value = value or ((b and 0x7F).toLong() shl shift)
                            if ((b and 0x80) == 0) break
                            shift += 7
                        }
                        if (field in 1..maxField) outFields[field] = value.toInt()
                    }
                    2 -> {
                        var len = 0L
                        shift = 0
                        while (i < data.size) {
                            val b = data[i++].toInt() and 0xFF
                            len = len or ((b and 0x7F).toLong() shl shift)
                            if ((b and 0x80) == 0) break
                            shift += 7
                        }
                        i += len.toInt()
                    }
                    1 -> i += 8
                    5 -> i += 4
                    else -> break
                }
            }
        }
    }
}
