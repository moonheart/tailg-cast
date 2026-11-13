package com.tailg.cast

import android.content.pm.PackageManager
import com.tailg.probe.CastEngine
import com.tailg.probe.LogSink
import com.tailg.probe.RootShell
import rikka.shizuku.Shizuku

/**
 * Shizuku 后端：
 * - 用 shell/root 身份启动现有的 VdHelperMain（和 root 路径共用同一套 Binder 协议）；
 * - 把 RootShell 的 su 调用换成 Shizuku 的 shell 进程。
 *
 * 真正建屏的仍是 DisplayManager 虚拟屏，不是 MediaProjection。
 */
object ShizukuBridge : CastEngine.HelperLauncher, RootShell.Executor {

    const val REQUEST_CODE = 21320

    fun isReady(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun uid(): Int = try {
        Shizuku.getUid()
    } catch (_: Throwable) {
        -1
    }

    fun status(): String {
        if (!isReady()) return "未连接（先启动 Shizuku）"
        val id = uid()
        if (!isGranted()) return "UID $id，未授权"
        return "UID $id，已授权" + if (id == 0) "（root 启动）" else "（shell/ADB）"
    }

    fun requestPermission() {
        if (!isReady()) return
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (t: Throwable) {
            LogSink.line("SHIZUKU", "requestPermission 失败: $t")
        }
    }

    override fun available(): Boolean = isReady() && isGranted()

    override fun start(apkPath: String, authority: String): Process {
        val safe = apkPath.replace("'", "'\\''")
        val cmd = "CLASSPATH='$safe' exec app_process /system/bin " +
            "--nice-name=tailg-vd-shizuku com.tailg.probe.VdHelperMain $authority"
        LogSink.line("SHIZUKU", "启动 helper uid=${uid()} apk=$apkPath")
        return ShizukuProcess.start(arrayOf("sh", "-c", cmd), null)
    }

    override fun run(cmd: String, shown: String): RootShell.Result {
        if (!available()) {
            return RootShell.Result(false, "Shizuku 未连接/未授权")
        }
        var p: Process? = null
        return try {
            p = ShizukuProcess.start(arrayOf("sh", "-c", cmd), null)
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread {
                try {
                    p!!.inputStream.bufferedReader().forEachLine { out.appendLine(it) }
                } catch (_: Throwable) {
                }
            }
            val t2 = Thread {
                try {
                    p!!.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
                } catch (_: Throwable) {
                }
            }
            t1.start()
            t2.start()
            // ShizukuRemoteProcess.waitFor(timeout) 走 Process 默认实现会调 exitValue()
            // 并抛 "process hasn't exited"；直接阻塞 waitFor() 才是可靠的。
            val code = p!!.waitFor()
            t1.join(1000)
            t2.join(1000)
            val text = (out.toString() + err.toString()).trim()
            LogSink.line("SHIZUKU", "sh -c \"$shown\" -> code=$code $text")
            RootShell.Result(code == 0, text)
        } catch (t: Throwable) {
            LogSink.line("SHIZUKU", "执行失败: $shown -> $t")
            RootShell.Result(false, t.toString())
        } finally {
            try {
                p?.destroy()
            } catch (_: Throwable) {
            }
        }
    }
}
