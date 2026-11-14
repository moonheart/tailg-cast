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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 官方协议区分两类资源：
 * - THEME：仪表主题（ThemeEventManager）
 * - WALLPAPER：仪表壁纸（WallpaperEventManager）
 */
enum class ThemeUploadMode(val func: String, val label: String) {
    THEME("THEME", "仪表主题"),
    WALLPAPER("WALLPAPER", "仪表壁纸"),
}

/**
 * 主题/壁纸上传服务：17818 控制 + 19000 文件（$FILE）。
 *
 * 按官方 ThinkRide（ThemeEventManager / WallpaperEventManager + OtaWriter + OtaReader）状态机实现：
 * 1) 17818: act=1 查询空间与残留任务 -> act=2 下发 task -> act=4 type=0 车辆条件检查
 * 2) 19000: 逐个文件发送 $FILE(0x01) 文件头 -> 等 item=12 crc=0 -> 发分片 $FILE(0x02) -> $FILE(0x03)
 *           -> 处理 item=13 loss 补包 -> 等 act=3 result=2 (切换下一文件) / result=0 (全部收齐)
 * 3) 17818: act=4 type=1 触发车机校验安装 -> 轮询 act=1 等待 upresult=0 -> act=6 完成清理
 */
class ThemeServer(
    private val ctx: Context,
    private val pkg: ThemePackageInfo,
    private val mode: ThemeUploadMode,
    private val listener: Listener,
) {

    interface Listener {
        fun onState(text: String)
        fun onProgress(label: String, sent: Long, total: Long)
        fun onLog(line: String)
        fun onError(message: String)
        fun onDone()
    }

    private data class IncomingJson(val port: Int, val json: JSONObject)

    private enum class FileResult { NEXT_FILE, DONE_ALL }

    private val config: Config = Config.load(ctx)
    private val totalBytes: Long = pkg.configFile.length() + pkg.packageFile.length()
    private val sent = AtomicLong(0)

    @Volatile
    private var running = true

    @Volatile
    private var controlSocket: Socket? = null

    @Volatile
    private var fileSocket: Socket? = null

    @Volatile
    private var controlOut: OutputStream? = null

    @Volatile
    private var fileOut: OutputStream? = null

    private val controlOutLock = Any()
    private val fileOutLock = Any()

    private var controlServer: ServerSocket? = null
    private var fileServer: ServerSocket? = null

    private val controlConnected = CountDownLatch(1)
    private val fileConnected = CountDownLatch(1)
    private val events = LinkedBlockingQueue<IncomingJson>()
    private var watchdog: Thread? = null

    fun start() {
        Thread({ run() }, "theme-upload").start()
        watchdog = Thread({
            try {
                Thread.sleep(240_000)
                if (running) {
                    fail("等待车机超时（4 分钟）：确认车机已连上手机热点")
                }
            } catch (ignored: InterruptedException) {
            }
        }, "theme-watchdog").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        controlConnected.countDown()
        fileConnected.countDown()
        closeQuietly(controlSocket)
        closeQuietly(fileSocket)
        closeQuietly(controlServer)
        closeQuietly(fileServer)
        watchdog?.interrupt()
    }

    private fun run() {
        try {
            bind()
            listener.onState("等待车机连接 17818 / 19000（${mode.label}）")
            listener.onLog("监听 0.0.0.0:17818 / 19000 · 协议 func=${mode.func}")

            Thread({ acceptControl() }, "theme-control").apply { isDaemon = true; start() }
            Thread({ acceptFiles() }, "theme-files").apply { isDaemon = true; start() }

            if (!controlConnected.await(120, TimeUnit.SECONDS)) {
                fail("等待车机连接 17818 超时")
                return
            }
            if (!fileConnected.await(60, TimeUnit.SECONDS)) {
                fail("等待车机连接 19000 超时")
                return
            }
            if (!running) return

            runStateMachine()
        } catch (t: Throwable) {
            if (running) fail("上传失败：${t.message}")
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
            s.soTimeout = 1000
            controlOut = s.getOutputStream()
            listener.onLog("车机已连接 17818")
            controlConnected.countDown()
            readLoop(17818, PushbackInputStream(s.getInputStream(), 8), controlOut!!, controlOutLock)
        } catch (t: Throwable) {
            if (running) fail("17818 连接异常：${t.message}")
        }
    }

    private fun acceptFiles() {
        try {
            val s = fileServer?.accept() ?: return
            fileSocket = s
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 1000
            fileOut = s.getOutputStream()
            listener.onLog("车机已连接 19000")
            fileConnected.countDown()
            readLoop(19000, PushbackInputStream(s.getInputStream(), 8), fileOut!!, fileOutLock)
        } catch (t: Throwable) {
            if (running) fail("19000 连接异常：${t.message}")
        }
    }

    private fun readLoop(
        port: Int,
        input: PushbackInputStream,
        output: OutputStream,
        outLock: Any,
    ) {
        while (running) {
            val first = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running) listener.onLog("$port 读断开：${t.message}")
                return
            }
            if (first < 0) {
                if (running) listener.onLog("$port 对端关闭连接")
                return
            }
            input.unread(first)

            // 兼容极少数固件在 19000 直接发裸 JSON 的情况；正常情况都是 EE FD 帧或二进制帧
            if (first == '{'.code) {
                val raw = readBraceJson(input) ?: continue
                listener.onLog("$port<< $raw")
                parseAndEnqueue(port, raw, output, outLock)
                continue
            }

            val frame: RawFrame = try {
                Codec.readFrame(input) ?: return
            } catch (_: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running) listener.onLog("$port 读帧失败：${t.message}")
                return
            }

            com.tailg.probe.LogSink.rawFrame("THEME_$port", "RX", frame.type, frame.cmd, frame.payload)
            if (Codec.isJson(frame)) {
                val text = Codec.jsonText(frame)
                listener.onLog("$port<< ${text.take(300)}")
                parseAndEnqueue(port, text, output, outLock)
            } else {
                handleBinary(port, frame, output, outLock)
            }
        }
    }

    private fun parseAndEnqueue(port: Int, text: String, output: OutputStream, outLock: Any) {
        val json = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return
        }
        if (json.optString("func") == "TUC" && json.optString("act") == "GET") {
            sendJson(
                port,
                output,
                outLock,
                """{"msg_id":27,"func":"TUC","act":"SEND","tuc":"${config.tuc}","tucs":${config.tucs}}""",
            )
            sendJson(port, output, outLock, """{"msg_id":27,"func":"TUC","act":"STATUS","status":1}""")
            return
        }
        events.offer(IncomingJson(port, json))
    }

    private fun readBraceJson(input: PushbackInputStream): String? {
        val bos = ByteArrayOutputStream()
        var depth = 0
        val deadline = System.currentTimeMillis() + 3000
        while (running && System.currentTimeMillis() < deadline) {
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                return null
            }
            if (b < 0) return null
            bos.write(b)
            if (b == '{'.code) depth++
            if (b == '}'.code) {
                depth--
                if (depth <= 0) return bos.toString("UTF-8")
            }
            if (bos.size() > 16384) return bos.toString("UTF-8")
        }
        return null
    }

    private fun handleBinary(port: Int, frame: RawFrame, output: OutputStream, outLock: Any) {
        if (frame.type == 1 && frame.cmd == 1) {
            val blocks = ByteArray(4 * 128)
            copyBlock(config.version, blocks, 0)
            copyBlock(config.mcuVersion, blocks, 128)
            copyBlock(config.ucVersion, blocks, 256)
            copyBlock(config.tucVersion, blocks, 384)
            synchronized(outLock) {
                output.write(Codec.frame(1, 2, blocks))
                output.flush()
            }
            listener.onLog("$port>> 版本块")
        } else if (frame.type == 1 && frame.cmd == 0x11) {
            synchronized(outLock) {
                output.write(Codec.frame(1, 0x12, Codec.fixedString(config.mac, 32)))
                output.flush()
            }
            listener.onLog("$port>> MAC")
        } else if (frame.type == 2 && frame.cmd == 1) {
            // 心跳帧静默忽略，避免刷屏
        } else {
            listener.onLog("$port<< 二进制 ${frame.head()}")
        }
    }

    private fun runStateMachine() {
        val taskId = pkg.md5.take(20).ifBlank { "10000c21f4237b145a98" }

        // 1. act=1 查询车机存储空间与残留任务
        step(1, "${mode.func} act=1 查询空间与状态")
        var act1 = queryStatusAndClearStale(taskId) ?: return
        val diskSpace = act1.optLong("diskspace", 0)
        val spst = act1.optInt("spst", 0)
        if (diskSpace == -1L || spst == 1) {
            fail("车机存储空间不足或磁盘异常（diskspace=$diskSpace, spst=$spst）")
            return
        }

        // 2. act=2 下发任务号
        step(2, "${mode.func} act=2 下发任务")
        sendControlJson("""{"msg_id":27,"func":"${mode.func}","act":2,"task":"$taskId"}""")
        val act2 = awaitJson(10_000) {
            it.json.optString("func") == mode.func && it.json.optInt("act") == 2
        } ?: run {
            fail("等待车机确认任务 (act=2) 超时")
            return
        }
        val act2Result = act2.json.optInt("result", 0)
        if (act2Result != 0) {
            fail("车机拒绝任务下发 (act=2 result=$act2Result)")
            return
        }

        // 3. act=4 type=0 车辆安全状态检查
        step(3, "${mode.func} act=4 type=0 车辆条件检查")
        sendControlJson("""{"msg_id":27,"func":"${mode.func}","act":4,"type":0}""")
        val act4Pre = awaitJson(10_000) {
            it.json.optString("func") == mode.func && it.json.optInt("act") == 4
        } ?: run {
            fail("等待车机状态检查 (act=4 type=0) 超时")
            return
        }
        val gears = act4Pre.json.optInt("gears", 0)
        val speed = act4Pre.json.optInt("speed", 0)
        val power = act4Pre.json.optInt("power", -1)
        listener.onLog("车机状态：power=$power, gears=$gears, speed=$speed")
        if (speed > 0) {
            fail("请先停车（当前车速 $speed）后再上传")
            return
        }
        if (gears > 0) {
            fail("请先挂入驻车/空档（gears=$gears）后再上传")
            return
        }

        // 4. 19000 逐个传输 config.ini 与 package.zip
        step(4, "19000 传输配置文件 ${pkg.configFile.name}")
        val r1 = transferSingleFile(pkg.configFile, pkg.configFile.name, isLast = false) ?: return
        if (r1 == FileResult.NEXT_FILE) {
            step(5, "19000 传输资源包 ${pkg.packageName}")
            transferSingleFile(pkg.packageFile, pkg.packageName, isLast = true) ?: return
        }

        // 5. act=4 type=1 触发校验与安装
        step(6, "${mode.func} act=4 type=1 开始校验与安装")
        sendControlJson("""{"msg_id":27,"func":"${mode.func}","act":4,"type":1}""")
        val act4Install = awaitJson(15_000) {
            it.json.optString("func") == mode.func && it.json.optInt("act") == 4
        } ?: run {
            fail("等待车机校验安装 (act=4 type=1) 超时")
            return
        }
        val installRet = act4Install.json.optInt("result", 0)
        if (installRet != 0) {
            fail("车机校验安装包失败 (act=4 type=1 result=$installRet)")
            return
        }

        // 6. 等待车机合成安装完成（官方约 21~25 秒后通过 act=1 轮询 upresult=0）
        step(7, "车机校验完成，正在安装${mode.label}…")
        if (!waitInstallComplete()) return

        sendControlJson("""{"msg_id":27,"func":"${mode.func}","act":6}""")
        listener.onProgress("安装完成", totalBytes, totalBytes)
        listener.onState("${mode.label}安装成功")
        listener.onDone()
        stop()
    }

    private fun queryStatusAndClearStale(taskId: String): JSONObject? {
        repeat(2) { attempt ->
            sendControlJson("""{"msg_id":27,"func":"${mode.func}","act":1}""")
            val resp = awaitJson(10_000) {
                it.json.optString("func") == mode.func && it.json.optInt("act") == 1
            }?.json ?: run {
                fail("等待车机响应 ${mode.func} act=1 超时（车机未应答或不支持 ${mode.func}）")
                return null
            }
            val upresult = resp.optInt("upresult", -1)
            val oldTask = resp.optString("task", "")
            val hasStale = (upresult == 2) ||
                (oldTask.isNotBlank() && oldTask != "NULL" && oldTask != taskId)
            if (attempt == 0 && hasStale) {
                listener.onLog("车机有未完成任务 ($oldTask, upresult=$upresult)，发送 act=5 取消")
                sendControlJson("""{"msg_id":27,"func":"${mode.func}","act":5}""")
                awaitJson(8_000) {
                    it.json.optString("func") == mode.func && it.json.optInt("act") == 5
                }
                Thread.sleep(300)
                return@repeat
            }
            return resp
        }
        fail("无法清理车机旧任务状态")
        return null
    }

    private fun transferSingleFile(file: File, name: String, isLast: Boolean): FileResult? {
        if (!file.isFile || file.length() <= 0) {
            fail("$name 不存在或为空")
            return null
        }
        val chunkCount = ThemeFileProtocol.chunkCount(file.length())
        val baseSent = sent.get()

        repeat(2) { attempt ->
            listener.onLog("发送文件头 $name (${file.length()} 字节, $chunkCount 片)")
            writeFileBytes(ThemeFileProtocol.fileHeader(name, file.length()))

            val ack = awaitJson(10_000) {
                it.json.optInt("msg_id") == 10 && it.json.optInt("item") == 12
            } ?: run {
                fail("等待车机确认文件头 ($name, item=12) 超时")
                return null
            }
            val crc = ack.json.optInt("crc", -1)
            if (crc != 0) {
                fail("车机文件头校验未通过 ($name, crc=$crc)")
                return null
            }

            sendChunks(file, name, baseSent, null)
            Thread.sleep(100)
            writeFileBytes(ThemeFileProtocol.end())

            // 等待 item=13 (loss 补包) 或 act=3 (单文件/全部完成)
            val deadline = System.currentTimeMillis() + 60_000
            while (running && System.currentTimeMillis() < deadline) {
                val msg = events.poll(1000, TimeUnit.MILLISECONDS) ?: continue
                val j = msg.json
                val msgId = j.optInt("msg_id")
                val func = j.optString("func")
                val act = j.optInt("act", -1)

                if (msgId == 10 && j.optInt("item") == 13) {
                    val loss = j.optJSONArray("loss") ?: JSONArray()
                    if (loss.length() == 1 && loss.optInt(0) == -1) {
                        listener.onLog("$name 车机要求重传整个文件 (loss=[-1])")
                        sent.set(baseSent)
                        break // 跳出内层，外层 repeat 重发文件头
                    }
                    if (loss.length() > 0) {
                        val wanted = HashSet<Int>()
                        for (i in 0 until loss.length()) wanted.add(loss.optInt(i))
                        listener.onLog("$name 补发丢包分片：$wanted")
                        sendChunks(file, name, baseSent, wanted)
                        Thread.sleep(100)
                        writeFileBytes(ThemeFileProtocol.end())
                    }
                    continue
                }

                if (func == mode.func && act == 3) {
                    val res = j.optInt("result", -1)
                    return when (res) {
                        0 -> FileResult.DONE_ALL
                        2 -> if (isLast) FileResult.DONE_ALL else FileResult.NEXT_FILE
                        else -> {
                            fail("车机文件校验失败 ($name, ${mode.func} act=3 result=$res)")
                            null
                        }
                    }
                }

                // 兼容 19000 上返回的 msg_id=27 act=3 (OtaJsonMsgManager.java)
                if (msg.port == 19000 && msgId == 27 && act == 3) {
                    val res = j.optInt("result", -1)
                    if (res == 1) {
                        return if (isLast) FileResult.DONE_ALL else FileResult.NEXT_FILE
                    }
                    fail("19000 校验失败 ($name, result=$res)")
                    return null
                }

                if (func == mode.func && act == 1 && j.optInt("upresult", -1) == 1) {
                    fail("车机返回上传失败 (upresult=1)")
                    return null
                }
            }

            if (attempt == 1) {
                fail("等待 $name 传输确认超时")
                return null
            }
        }
        return null
    }

    private fun sendChunks(
        file: File,
        name: String,
        baseSent: Long,
        onlyIndices: Set<Int>?,
    ) {
        FileInputStream(file).use { fis ->
            val buf = ByteArray(ThemeFileProtocol.CHUNK_SIZE)
            var index = 0
            var fileSent = 0L
            while (running) {
                val n = fis.read(buf)
                if (n <= 0) break
                if (onlyIndices == null || onlyIndices.contains(index)) {
                    writeFileBytes(ThemeFileProtocol.chunk(index, buf, 0, n))
                }
                if (onlyIndices == null) {
                    fileSent += n.toLong()
                    val now = baseSent + fileSent
                    sent.set(now)
                    listener.onProgress(name, now, totalBytes)
                }
                index++
            }
        }
    }

    private fun waitInstallComplete(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000
        var lastPoll = System.currentTimeMillis()
        while (running && System.currentTimeMillis() < deadline) {
            val msg = events.poll(1000, TimeUnit.MILLISECONDS)
            if (msg != null) {
                val j = msg.json
                if (j.optString("func") == mode.func && j.optInt("act") == 1) {
                    val upresult = j.optInt("upresult", -1)
                    if (upresult == 0) return true
                    if (upresult == 1) {
                        sendControlJson("""{"msg_id":27,"func":"${mode.func}","act":5}""")
                        fail("车机安装${mode.label}失败 (upresult=1)")
                        return false
                    }
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastPoll >= 5000) {
                lastPoll = now
                sendControlJson("""{"msg_id":27,"func":"${mode.func}","act":1}""")
            }
        }
        if (running) {
            fail("等待车机安装结果超时（90 秒），请查看车机仪表状态")
        }
        return false
    }

    private fun awaitJson(timeoutMs: Long, predicate: (IncomingJson) -> Boolean): IncomingJson? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (running && System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(50)
            val msg = events.poll(minOf(remaining, 500), TimeUnit.MILLISECONDS) ?: continue
            if (predicate(msg)) return msg
        }
        return null
    }

    private fun step(n: Int, text: String) {
        listener.onState("步骤 $n/7：$text")
        listener.onLog("步骤 $n/7：$text")
    }

    private fun sendControlJson(json: String) {
        val out = controlOut ?: throw IllegalStateException("17818 未连接")
        sendJson(17818, out, controlOutLock, json)
    }

    private fun sendJson(port: Int, output: OutputStream, lock: Any, json: String) {
        listener.onLog("$port>> $json")
        val bytes = Codec.jsonFrame(json)
        com.tailg.probe.LogSink.raw("THEME_$port", "TX", bytes)
        synchronized(lock) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun writeFileBytes(bytes: ByteArray) {
        val out = fileOut ?: throw IllegalStateException("19000 未连接")
        synchronized(fileOutLock) {
            out.write(bytes)
            out.flush()
        }
    }

    private fun copyBlock(text: String, out: ByteArray, offset: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, 128)
        bytes.copyInto(out, offset, 0, n)
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
        controlConnected.countDown()
        fileConnected.countDown()
    }

    private fun closeQuietly(c: AutoCloseable?) {
        try {
            c?.close()
        } catch (ignored: Throwable) {
        }
    }
}
