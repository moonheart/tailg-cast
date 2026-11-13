package com.tailg.cast

import android.app.Application
import android.os.Build
import com.tailg.probe.CastEngine
import com.tailg.probe.IVdHelper
import com.tailg.probe.LogSink
import com.tailg.probe.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeConfig
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupLogLine
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.adb.PrivilegeAdbConnectionOptions
import priv.kit.core.command.PrivilegeCommand
import priv.kit.core.userservice.PrivilegeUserServiceProcess
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import priv.kit.ui.PrivilegeUi
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiStartupMode
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * priv-kit 统一特权桥接层：
 * - 支持 Root、内置无线调试 ADB（mDNS 配对/连接）、Shizuku 委托启动、手动 ADB 命令启动；
 * - 通过 UserService 直接提供 IVdHelper（虚拟屏 + 触控注入 + am start --display）；
 * - 作为 RootShell.Executor 执行所有特权命令，未连接时兼容旧路径回退。
 */
object PrivKitBridge : CastEngine.DirectHelperProvider, RootShell.Executor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Application

    val uiConfig: PrivilegeUiConfig by lazy {
        PrivilegeUiConfig(
            startupModes = listOf(
                PrivilegeUiStartupMode.ROOT,
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.MANUAL_SHELL,
            ),
            externalStartProviders = listOf(ShizukuExternalStartProvider),
            manualShellScriptPath = "tailg-priv.sh",
        )
    }

    private val vdUserServiceSpec = PrivilegeUserServiceSpec(
        serviceClassName = VdUserService::class.java.name,
        tag = "tailg-vd",
        version = 1,
        process = PrivilegeUserServiceProcess.DEDICATED,
        daemon = true,
    )

    @Volatile
    private var cachedConnection: PrivilegeUserServiceConnection? = null

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val serverState: StateFlow<PrivilegeServerInfo?>
        get() = Privilege.serverState

    private val logListener = PrivilegeStartupLogListener { line: PrivilegeStartupLogLine ->
        appendLog("[${line.source}] ${line.message}")
    }

    fun init(application: Application) {
        if (::app.isInitialized) return
        app = application
        PrivilegeConfig.crashLogDirectories = listOfNotNull(
            File(application.filesDir, "privilege-crashes").apply { mkdirs() },
            application.getExternalFilesDir("privilege-crashes"),
        )
        CastEngine.setDirectHelperProvider(this)
        RootShell.setExecutor(this)
        scope.launch {
            Privilege.serverState.collect { info ->
                if (info == null) {
                    cachedConnection = null
                } else {
                    appendLog("特权服务已连接: uid=${info.uid} pid=${info.pid}")
                    _lastError.value = null
                }
            }
        }
        scope.launch {
            runCatching { PrivilegeUi.prepareExternalShellScript(uiConfig.manualShellScriptPath) }
            runCatching { PrivilegeUi.startSilently(config = uiConfig) }
            if (!isConnected()) {
                tryAutoConnect()
            }
        }
    }

    fun isConnected(): Boolean =
        Privilege.serverState.value != null && Privilege.pingServer()

    fun serverInfo(): PrivilegeServerInfo? =
        if (isConnected()) Privilege.serverState.value else null

    fun statusSummary(): String {
        val info = serverInfo()
        if (info != null) {
            val mode = when (info.uid) {
                0 -> "Root (UID 0)"
                1000 -> "System (UID 1000)"
                2000 -> "Shell / ADB (UID 2000)"
                else -> "UID ${info.uid}"
            }
            return "已连接 · $mode"
        }
        if (ShizukuBridge.available()) {
            return "未连接 · Shizuku 已就绪，可一键授权"
        }
        if (ShizukuBridge.isReady()) {
            return "未连接 · 请先授予 Shizuku 权限或使用无线调试"
        }
        return "未连接 · 支持 Root / 无线调试 / Shizuku"
    }

    private suspend fun tryAutoConnect() {
        runCatching { Privilege.connectReadyServer() }
        if (isConnected()) return

        // 1) 若 Shizuku 已授权且可用，优先安静委托拉起
        if (ShizukuBridge.available()) {
            runCatching { startViaShizukuInternal() }
            if (isConnected()) return
        }
        // 2) 若已有 Root 权限，尝试安静启动
        if (hasSuBinary()) {
            runCatching { Privilege.startRoot(startupLogListener = logListener) }
            if (isConnected()) return
        }
        // 3) 若系统已开启无线调试且已配对过，尝试自动连接 ADB
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val mgr = Privilege.createAdbManager()
                if (mgr.getWirelessDebuggingControlStatus().wirelessDebuggingEnabled) {
                    Privilege.startAdb(
                        options = PrivilegeAdbConnectionOptions(),
                        startupLogListener = logListener,
                    )
                }
            }
        }
    }

    fun startRoot(onDone: ((Boolean, String?) -> Unit)? = null) {
        launchTask("Root 启动") {
            Privilege.startRoot(startupLogListener = logListener)
        }.invokeOnCompletion { err ->
            val ok = err == null && isConnected()
            onDone?.invoke(ok, err?.message ?: _lastError.value)
        }
    }

    fun startAdb(onDone: ((Boolean, String?) -> Unit)? = null) {
        launchTask("无线调试 ADB 启动") {
            Privilege.startAdb(
                options = PrivilegeAdbConnectionOptions(),
                startupLogListener = logListener,
            )
        }.invokeOnCompletion { err ->
            val ok = err == null && isConnected()
            onDone?.invoke(ok, err?.message ?: _lastError.value)
        }
    }

    fun pairAndStartAdb(
        pairingCode: String,
        port: Int? = null,
        onDone: ((Boolean, String?) -> Unit)? = null,
    ) {
        launchTask("无线调试配对并启动") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw IllegalStateException("无线调试配对需要 Android 11+")
            }
            val mgr = Privilege.createAdbManager()
            val code = pairingCode.trim()
            require(code.isNotEmpty()) { "请输入配对码" }
            appendLog("正在通过 mDNS 配对无线调试...")
            mgr.pair(pairingCode = code, port = port)
            appendLog("配对成功，正在连接 ADB 启动特权服务...")
            Privilege.startAdb(
                options = PrivilegeAdbConnectionOptions(),
                startupLogListener = logListener,
            )
        }.invokeOnCompletion { err ->
            val ok = err == null && isConnected()
            onDone?.invoke(ok, err?.message ?: _lastError.value)
        }
    }

    fun startViaShizuku(onDone: ((Boolean, String?) -> Unit)? = null) {
        launchTask("Shizuku 委托启动") {
            startViaShizukuInternal()
        }.invokeOnCompletion { err ->
            val ok = err == null && isConnected()
            onDone?.invoke(ok, err?.message ?: _lastError.value)
        }
    }

    private suspend fun startViaShizukuInternal() {
        check(::app.isInitialized) { "PrivKitBridge 未初始化" }
        val cmd = Privilege.nativeStarterCommand
        ShizukuExternalStarter(app).use { starter ->
            starter.start(cmd, logListener)
        }
        if (!isConnected()) {
            runCatching { Privilege.connectReadyServer() }
        }
        if (!isConnected()) {
            withTimeoutOrNull(4_000L) {
                Privilege.serverState.filterNotNull().first()
            }
        }
        if (!isConnected()) {
            throw IllegalStateException("Shizuku 启动命令已执行，但未收到特权服务握手")
        }
    }

    fun shutdown() {
        runCatching { Privilege.shutdownServer() }
        cachedConnection = null
        appendLog("已停止 priv-kit 特权服务")
    }

    suspend fun getManualAdbCommand(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        "adb shell ${Privilege.nativeStarterCommand}"
    }

    private fun launchTask(label: String, block: suspend () -> Unit) = scope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        _lastError.value = null
        appendLog("开始：$label")
        try {
            block()
        } catch (t: Throwable) {
            val msg = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            _lastError.value = "$label 失败：$msg"
            appendLog("失败：$msg")
            LogSink.line("PRIV", "$label 失败: $t")
        } finally {
            _busy.value = false
        }
    }

    private fun appendLog(msg: String) {
        val current = _logs.value
        _logs.value = (current + msg).takeLast(40)
    }

    // ---- CastEngine.DirectHelperProvider ----

    override fun available(): Boolean = isConnected()

    @Synchronized
    override fun getHelper(): IVdHelper {
        cachedConnection?.takeIf { runCatching { it.binder.pingBinder() }.getOrDefault(false) }
            ?.let { return IVdHelper.Stub.asInterface(it.binder) }

        val conn = runBlocking {
            Privilege.bindUserService(vdUserServiceSpec)
        }
        cachedConnection = conn
        return IVdHelper.Stub.asInterface(conn.binder)
    }

    // ---- RootShell.Executor ----

    override fun run(cmd: String, shown: String): RootShell.Result {
        if (isConnected()) {
            return try {
                val res = runBlocking {
                    Privilege.startCommand(
                        PrivilegeCommand(listOf("sh", "-c", cmd)),
                        timeoutMillis = 15_000L,
                    ).awaitResult()
                }
                val out = String(res.stdout, StandardCharsets.UTF_8)
                val err = String(res.stderr, StandardCharsets.UTF_8)
                val text = (out + err).trim()
                LogSink.line("PRIV", "sh -c \"$shown\" -> code=${res.exitCode} $text")
                RootShell.Result(res.exitCode == 0, text)
            } catch (t: Throwable) {
                LogSink.line("PRIV", "执行失败: $shown -> $t")
                RootShell.Result(false, t.toString())
            }
        }
        if (ShizukuBridge.available()) {
            return ShizukuBridge.run(cmd, shown)
        }
        return runDirectSu(cmd, shown)
    }

    private fun runDirectSu(cmd: String, shown: String): RootShell.Result {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
            }
            BufferedReader(InputStreamReader(p.errorStream)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
            }
            val code = p.waitFor()
            val text = sb.toString().trim()
            LogSink.line("ROOT", "su -c \"$shown\" -> code=$code $text")
            RootShell.Result(code == 0, text)
        } catch (t: Throwable) {
            LogSink.line("ROOT", "su 执行失败: $shown -> $t")
            RootShell.Result(false, t.toString())
        } finally {
            runCatching { p?.destroy() }
        }
    }

    private fun hasSuBinary(): Boolean =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
            .any { File(it).exists() }
}
