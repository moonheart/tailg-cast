package com.tailg.cast.theme

import android.content.Context
import android.content.Intent
import android.util.Log
import com.tailg.probe.Config
import com.tailg.probe.ProbeService
import com.tailg.probe.RootShell
import com.tailg.protocol.Codec
import com.tailg.protocol.RawFrame
import com.tailg.protocol.ThemeFileProtocol
import com.tailg.protocol.ThemePackageInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 独立主题上传服务：17818 控制 + 19000 文件。
 *
 * 它不依赖 ProbeService，上传前要先停掉投屏服务，否则端口冲突。
 * 协议来自第三方「仪表助手 2.0」的逆向，见协议文档 §29。
 */
class ThemeServer(
    private val ctx: Context,
    private val pkg: ThemePackageInfo,
    private val listener: Listener,
) {

    interface Listener {
        fun onState(text: String)
        fun onProgress(label: String, sent: Long, total: Long)
        fun onLog(line: String)
        fun onError(message: String)
        fun onDone()
    }

    private val config: Config = Config.load(ctx)
    private val totalBytes: Long = pkg.configFile.length() + pkg.packageFile.length()
    private val sent = AtomicLong(0)

    @Volatile
    private var running = true

    @Volatile
    private var controlSocket: Socket? = null

    @Volatile
    private var fileSocket: Socket? = null

    private var controlServer: ServerSocket? = null
    private var fileServer: ServerSocket? = null

    private val filesReady = CountDownLatch(1)
    private val filesDone = CountDownLatch(1)
    private var watchdog: Thread? = null

    fun start() {
        Thread({ run() }, "theme-upload").start()
        watchdog = Thread({
            try {
                Thread.sleep(180_000)
                if (running) {
                    fail("等待车机超时（3 分钟）：确认车机已连上手机热点")
                }
            } catch (ignored: InterruptedException) {
            }
        }, "theme-watchdog").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        filesReady.countDown()
        filesDone.countDown()
        closeQuietly(controlSocket)
        closeQuietly(fileSocket)
        closeQuietly(controlServer)
        closeQuietly(fileServer)
        watchdog?.interrupt()
    }

    private fun run() {
        try {
            bind()
            listener.onState("等待车机连接 17818 / 19000")
            listener.onLog("监听 0.0.0.0:17818 / 19000")

            Thread({ acceptControl() }, "theme-control").apply { isDaemon = true; start() }
            Thread({ acceptFiles() }, "theme-files").apply { isDaemon = true; start() }
        } catch (t: Throwable) {
            fail("启动主题服务失败：${t.message}")
        }
    }

    private fun bind() {
        var last: Throwable? = null
        repeat(2) { attempt ->
            try {
                controlServer = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", 17818))
                }
                fileServer = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", 19000))
                }
                return
            } catch (t: Throwable) {
                last = t
                closeQuietly(controlServer)
                closeQuietly(fileServer)
                controlServer = null
                fileServer = null
                if (attempt == 0) {
                    listener.onLog("端口被占，先停掉本 App 投屏或原厂 App 后重试")
                    try {
                        ctx.stopService(Intent(ctx, ProbeService::class.java))
                    } catch (ignored: Throwable) {
                    }
                    RootShell.run("am force-stop com.tailg.run.intelligence")
                    Thread.sleep(1000)
                }
            }
        }
        throw last ?: IllegalStateException("端口绑定失败")
    }

    private fun acceptControl() {
        try {
            val s = controlServer?.accept() ?: return
            controlSocket = s
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 5000
            listener.onState("车机已连接 17818，开始 WALLPAPER 握手")
            handleControl(s)
        } catch (t: Throwable) {
            if (running) fail("17818 失败：${t.message}")
        }
    }

    private fun acceptFiles() {
        try {
            val s = fileServer?.accept() ?: return
            fileSocket = s
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 5000
            listener.onState("车机已连接 19000，等待开始传输")
            handleFiles(s)
        } catch (t: Throwable) {
            if (running) fail("19000 失败：${t.message}")
        }
    }

    private fun handleControl(socket: Socket) {
        val input = PushbackInputStream(socket.getInputStream(), 8)
        val output = socket.getOutputStream()

        step(1, "WALLPAPER act 1")
        sendJson(output, """{"msg_id":27,"func":"WALLPAPER","act":1}""")
        waitJson(input, output)

        step(2, "WALLPAPER act 2")
        sendJson(
            output,
            """{"msg_id":27,"func":"WALLPAPER","act":2,"task":"10000c21f4237b145a98"}""",
        )
        waitJson(input, output)

        step(3, "WALLPAPER act 4 type 0")
        sendJson(output, """{"msg_id":27,"func":"WALLPAPER","act":4,"type":0}""")
        waitJson(input, output)

        listener.onState("等待 19000 传文件")
        filesReady.countDown()

        if (!filesDone.await(120, TimeUnit.SECONDS)) {
            fail("等待 19000 文件传输超时")
            return
        }
        if (!running) return

        step(4, "WALLPAPER act 4 type 1")
        sendJson(output, """{"msg_id":27,"func":"WALLPAPER","act":4,"type":1}""")
        waitJson(input, output)

        listener.onProgress("完成", totalBytes, totalBytes)
        listener.onState("主题上传完成")
        listener.onDone()
        stop()
    }

    private fun handleFiles(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        if (!filesReady.await(60, TimeUnit.SECONDS)) {
            fail("17818 没有完成握手，19000 放弃")
            return
        }

        sendFile(input, output, pkg.configFile, "config.ini")
        sendFile(input, output, pkg.packageFile, pkg.packageName)

        filesDone.countDown()
    }

    private fun sendFile(input: InputStream, output: OutputStream, file: File, name: String) {
        if (!file.isFile || file.length() <= 0) {
            throw IllegalStateException("$name 不存在或为空")
        }
        listener.onLog("发送 $name (${file.length()} 字节)")
        output.write(ThemeFileProtocol.fileHeader(name, file.length()))
        output.flush()

        // 仪表助手在文件头之后读一次车机 JSON；读不到也继续。
        val resp = readRawJson(input, output)
        if (resp != null) listener.onLog("19000<< ${resp.take(200)}")

        FileInputStream(file).use { fis ->
            val buf = ByteArray(ThemeFileProtocol.CHUNK_SIZE)
            var index = 0
            while (running) {
                val n = fis.read(buf)
                if (n <= 0) break
                output.write(ThemeFileProtocol.chunk(index, buf, 0, n))
                output.flush()
                index++
                val now = sent.addAndGet(n.toLong())
                listener.onProgress(name, now, totalBytes)
            }
        }

        output.write(ThemeFileProtocol.end())
        output.flush()
        listener.onLog("$name 发送完成")
    }

    private fun step(n: Int, text: String) {
        listener.onState("步骤 $n/4：$text")
        listener.onLog("步骤 $n/4：$text")
    }

    private fun sendJson(output: OutputStream, json: String) {
        listener.onLog("17818>> $json")
        output.write(Codec.jsonFrame(json))
        output.flush()
    }

    /** 读一个 17818 JSON 回包；顺手处理 TUC/版本/MAC 握手。 */
    private fun waitJson(input: PushbackInputStream, output: OutputStream): String? {
        val deadline = System.currentTimeMillis() + 5000
        while (running && System.currentTimeMillis() < deadline) {
            val frame: RawFrame = try {
                Codec.readFrame(input) ?: return null
            } catch (timeout: SocketTimeoutException) {
                listener.onLog("17818 回包超时，继续")
                return null
            } catch (t: Throwable) {
                listener.onLog("17818 读包失败：${t.message}")
                return null
            }

            if (Codec.isJson(frame)) {
                val text = Codec.jsonText(frame)
                listener.onLog("17818<< ${text.take(300)}")
                if (handleHandshake(text, output)) continue
                if (text.contains("\"WALLPAPER\"")) return text
                // 其他 JSON 也当回包，避免车机只回一个通用 ack 时卡住
                return text
            }
            handleBinary(frame, output)
        }
        return null
    }

    private fun handleHandshake(text: String, output: OutputStream): Boolean {
        if (text.contains("\"TUC\"") && text.contains("\"GET\"")) {
            sendJson(
                output,
                """{"msg_id":27,"func":"TUC","act":"SEND","tuc":"${config.tuc}","tucs":${config.tucs}}""",
            )
            sendJson(output, """{"msg_id":27,"func":"TUC","act":"STATUS","status":1}""")
            return true
        }
        return false
    }

    private fun handleBinary(frame: RawFrame, output: OutputStream) {
        if (frame.type == 1 && frame.cmd == 1) {
            val blocks = ByteArray(4 * 128)
            copyBlock(config.version, blocks, 0)
            copyBlock(config.mcuVersion, blocks, 128)
            copyBlock(config.ucVersion, blocks, 256)
            copyBlock(config.tucVersion, blocks, 384)
            output.write(Codec.frame(1, 2, blocks))
            output.flush()
            listener.onLog("17818>> 版本块")
        } else if (frame.type == 1 && frame.cmd == 0x11) {
            output.write(Codec.frame(1, 0x12, Codec.fixedString(config.mac, 32)))
            output.flush()
            listener.onLog("17818>> MAC")
        } else {
            listener.onLog("17818<< 二进制 ${frame.head()}")
        }
    }

    private fun copyBlock(text: String, out: ByteArray, offset: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, 128)
        bytes.copyInto(out, offset, 0, n)
    }

    /** 19000 文件头后可能有一个 JSON ack；读到 } 或超时为止。 */
    private fun readRawJson(input: InputStream, output: OutputStream): String? {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(2048)
        val deadline = System.currentTimeMillis() + 5000
        while (running && System.currentTimeMillis() < deadline) {
            val n = try {
                input.read(buf)
            } catch (timeout: SocketTimeoutException) {
                return null
            } catch (t: Throwable) {
                return null
            }
            if (n <= 0) return null
            bos.write(buf, 0, n)
            val text = bos.toString("UTF-8")
            if (text.contains("}")) {
                if (text.contains("\"TUC\"") && text.contains("\"GET\"")) {
                    sendJson(
                        output,
                        """{"msg_id":27,"func":"TUC","act":"SEND","tuc":"${config.tuc}","tucs":${config.tucs}}""",
                    )
                    sendJson(output, """{"msg_id":27,"func":"TUC","act":"STATUS","status":1}""")
                }
                return text
            }
            if (bos.size() > 8192) return bos.toString("UTF-8")
        }
        return null
    }

    private fun fail(message: String) {
        if (!running) return
        running = false
        listener.onError(message)
        listener.onState("失败：$message")
        Log.e("ThemeUpload", message)
        closeQuietly(controlSocket)
        closeQuietly(fileSocket)
        closeQuietly(controlServer)
        closeQuietly(fileServer)
        filesReady.countDown()
        filesDone.countDown()
    }

    private fun closeQuietly(c: AutoCloseable?) {
        try {
            c?.close()
        } catch (ignored: Throwable) {
        }
    }
}
