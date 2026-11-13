package com.tailg.cast.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.tailg.cast.MirrorActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tailg.cast.ChargeState
import com.tailg.cast.LoginActivity
import com.tailg.cast.PrivKitBridge
import com.tailg.cast.ShizukuBridge
import com.tailg.cast.VehicleStatus
import com.tailg.cast.theme.ThemeServer
import com.tailg.probe.BleLink
import com.tailg.probe.CarCmd
import com.tailg.probe.CastEngine
import com.tailg.probe.CloudApi
import com.tailg.probe.Config
import com.tailg.probe.KeepAlive
import com.tailg.probe.NavFavorites
import com.tailg.probe.ProbeService
import com.tailg.probe.RootShell
import com.tailg.probe.WifiLink
import com.tailg.protocol.Codec
import com.tailg.protocol.ThemePackage
import com.tailg.protocol.ThemePackageInfo
import com.tailg.protocol.VideoHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File

private enum class Tab(val label: String, val icon: ImageVector) {
    Cast("投屏", Icons.Filled.PlayArrow),
    Vehicle("爱车", Icons.Filled.LocationOn),
    Ride("骑行", Icons.Filled.DateRange),
    Settings("我的", Icons.Filled.Settings),
}

/** 二级页面：从某个 Tab 里进入，顶栏出现返回箭头。 */
private enum class SubPage(val label: String, val subtitle: String) {
    Car("仪表控制", "本地 17818 / 蓝牙直连与日夜模式"),
    Theme("仪表主题与壁纸", "选择主题 zip 并上传到车机仪表"),
    Nav("常用目的地", "投屏自动导航与无障碍自动点击"),
    Permissions("授权与权限", "特权环境 / 系统权限 / 后台保活 / 无障碍"),
    Privilege("特权运行环境", "Root / 无线调试 / Shizuku 授权"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(Config.load(ctx)) }
    var snap by remember { mutableStateOf(snapshot(ctx, cfg)) }
    var mapProvider by remember { mutableStateOf(NavFavorites.getMapProvider(ctx)) }
    var tabName by rememberSaveable { mutableStateOf(Tab.Cast.name) }
    var subPageName by rememberSaveable { mutableStateOf<String?>(null) }
    var privilegeReturnToPermissions by rememberSaveable { mutableStateOf(false) }
    var cloudNote by rememberSaveable { mutableStateOf("") }
    var vehicle by remember { mutableStateOf<VehicleStatus?>(null) }
    var cloudBusy by remember { mutableStateOf(false) }
    var showConnection by remember { mutableStateOf(false) }
    var cloudExpired by remember { mutableStateOf(false) }
    var themePkg by remember { mutableStateOf<ThemePackageInfo?>(null) }
    var themeState by remember { mutableStateOf(ThemeUiState()) }
    var themeBusy by remember { mutableStateOf(false) }
    val themeServerRef = remember { arrayOfNulls<ThemeServer>(1) }
    var shizukuReady by remember { mutableStateOf(ShizukuBridge.isReady()) }
    var shizukuGranted by remember { mutableStateOf(ShizukuBridge.isGranted()) }
    var shizukuText by remember { mutableStateOf(ShizukuBridge.status()) }

    fun refreshShizuku() {
        shizukuReady = ShizukuBridge.isReady()
        shizukuGranted = ShizukuBridge.isGranted()
        shizukuText = ShizukuBridge.status()
    }

    DisposableEffect(Unit) {
        onDispose { themeServerRef[0]?.stop() }
    }

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val received = Shizuku.OnBinderReceivedListener { refreshShizuku() }
        val dead = Shizuku.OnBinderDeadListener { refreshShizuku() }
        val permission = Shizuku.OnRequestPermissionResultListener { _, grant ->
            refreshShizuku()
            toast(
                ctx,
                if (grant == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "Shizuku 未授权",
            )
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(received, handler)
            Shizuku.addBinderDeadListener(dead, handler)
            Shizuku.addRequestPermissionResultListener(permission, handler)
        } catch (t: Throwable) {
            shizukuText = "Shizuku 不可用：${t.message ?: t.javaClass.simpleName}"
        }
        onDispose {
            try {
                Shizuku.removeBinderReceivedListener(received)
                Shizuku.removeBinderDeadListener(dead)
                Shizuku.removeRequestPermissionResultListener(permission)
            } catch (_: Throwable) {
            }
        }
    }

    val tab = Tab.entries.firstOrNull { it.name == tabName } ?: Tab.Cast
    val subPage = SubPage.entries.firstOrNull { it.name == subPageName }
    val tabScrolls = remember { Tab.entries.associateWith { ScrollState(0) } }

    BackHandler(enabled = subPage != null) {
        subPageName = null
    }

    LaunchedEffect(cfg.vdBackend(), shizukuReady, shizukuGranted) {
        if (cfg.vdBackend() == "shizuku") {
            CastEngine.setHelperLauncher(ShizukuBridge)
            RootShell.setExecutor(ShizukuBridge)
        } else {
            CastEngine.setHelperLauncher(null)
            RootShell.setExecutor(null)
        }
        // 先设好 root/Shizuku 执行后端，再写 ColorOS BPM 白名单
        KeepAlive.apply(ctx)
    }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            cfg = Config.load(ctx)
            snap = snapshot(ctx, cfg)
            cloudExpired = false
            toast(ctx, "登录成功")
        }
    }

    val themePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        themeBusy = true
        themeState = ThemeUiState(status = "解析主题包…")
        Thread {
            try {
                val dir = File(ctx.cacheDir, "theme_${System.currentTimeMillis()}")
                dir.mkdirs()
                val outer = File(dir, "theme.zip")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    outer.outputStream().use { output -> input.copyTo(output) }
                } ?: error("打不开文件")
                val info = ThemePackage.extract(outer, File(dir, "out"))
                Handler(Looper.getMainLooper()).post {
                    themePkg = info
                    themeBusy = false
                    themeState = ThemeUiState(status = "已选择：${info.customName}")
                }
            } catch (t: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    themeBusy = false
                    themeState = ThemeUiState(error = "解析失败：${t.message}")
                }
            }
        }.start()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            toast(ctx, "还有权限没给：${denied.joinToString()}")
        }
    }

    fun requestPermissions() {
        val needed = neededPermissions(ctx)
        if (needed.isEmpty()) {
            toast(ctx, "权限都齐了")
        } else {
            permissionLauncher.launch(needed)
        }
    }

    fun tokenInvalid(text: String): Boolean {
        if (text.contains("HTTP 401") ||
            text.contains("code=401") ||
            text.contains("\"code\":401") ||
            text.contains("\"code\": 401") ||
            text.contains("TOKEN_LOGIN_OUT") ||
            text.contains("请先登录") ||
            text.contains("登录失效")
        ) {
            return true
        }
        return try {
            org.json.JSONObject(text).optInt("code", 0) == 401
        } catch (_: Throwable) {
            false
        }
    }

    fun maybeCheckCloudToken() {
        val c = Config.load(ctx)
        if (c.cloudToken.isBlank()) return
        val now = System.currentTimeMillis()
        if (c.cloudTokenCheckedAt > 0 && now - c.cloudTokenCheckedAt < 7L * 24 * 3600 * 1000) return
        CloudApi.carStatus(ctx) { ok, text ->
            scope.launch {
                if (tokenInvalid(text)) {
                    cloudExpired = true
                } else if (ok) {
                    Config.saveValue(ctx, "cloudTokenCheckedAt", now.toString())
                    ProbeService.config?.cloudTokenCheckedAt = now
                    cfg = Config.load(ctx)
                    snap = snapshot(ctx, cfg)
                }
            }
        }
    }

    fun save(key: String, value: String) {
        Config.saveValue(ctx, key, value)
        val fresh = Config.load(ctx)
        cfg = fresh
        ProbeService.config?.copyFrom(fresh)
        ProbeService.config?.useVdHelper = fresh.useVdHelper
        ProbeService.config?.useShizuku = fresh.useShizuku
        ProbeService.config?.appDisplayPkg = fresh.appDisplayPkg
        ProbeService.config?.appDisplayActivity = fresh.appDisplayActivity
        ProbeService.config?.cloudToken = fresh.cloudToken
        ProbeService.config?.cloudTokenAt = fresh.cloudTokenAt
        ProbeService.config?.cloudTokenCheckedAt = fresh.cloudTokenCheckedAt
        ProbeService.config?.carImei = fresh.carImei
        snap = snapshot(ctx, cfg)
    }

    fun themeListener(): ThemeServer.Listener = object : ThemeServer.Listener {
        private fun post(block: () -> Unit) {
            Handler(Looper.getMainLooper()).post(block)
        }

        override fun onState(text: String) = post {
            themeState = themeState.copy(
                running = true,
                status = text,
                log = (themeState.log + text).takeLast(40),
            )
        }

        override fun onProgress(label: String, sent: Long, total: Long) = post {
            val p = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
            themeState = themeState.copy(
                progress = p,
                status = "$label ${(p * 100).toInt()}%",
            )
        }

        override fun onLog(line: String) = post {
            themeState = themeState.copy(log = (themeState.log + line).takeLast(40))
        }

        override fun onError(message: String) = post {
            themeState = themeState.copy(
                running = false,
                error = message,
                status = "失败：$message",
                log = (themeState.log + "失败：$message").takeLast(40),
            )
        }

        override fun onDone() = post {
            themeState = themeState.copy(
                running = false,
                done = true,
                progress = 1f,
                status = "上传完成",
                log = (themeState.log + "上传完成").takeLast(40),
            )
        }
    }

    fun startThemeUpload() {
        val info = themePkg ?: return
        themeServerRef[0]?.stop()
        themeServerRef[0] = null
        themeState = ThemeUiState(running = true, status = "准备上传…")

        val begin = {
            val server = ThemeServer(ctx, info, themeListener())
            themeServerRef[0] = server
            server.start()
        }

        if (ProbeService.running) {
            toast(ctx, "已停止投屏，准备主题上传")
            ctx.stopService(Intent(ctx, ProbeService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ begin() }, 800)
        } else {
            begin()
        }
    }

    fun stopThemeUpload() {
        themeServerRef[0]?.stop()
        themeServerRef[0] = null
        themeState = themeState.copy(running = false, status = "已停止")
    }

    fun restartCast() {
        if (!cfg.useVdHelper) {
            toast(ctx, "改完请重新点「开始投屏」")
            return
        }
        Thread {
            ProbeService.cast?.stop()
            Thread.sleep(700)
            ProbeService.startCast(-1, null)
        }.start()
    }

    fun applyHeight(h: Int) {
        val safeTop = (600 - h) / 2
        val safeBottom = 600 - h - safeTop
        Config.saveValue(ctx, "castWidth", "1024")
        Config.saveValue(ctx, "castHeight", "600")
        Config.saveValue(ctx, "safeTop", "$safeTop")
        Config.saveValue(ctx, "safeBottom", "$safeBottom")
        ProbeService.config?.let {
            it.castWidth = 1024
            it.castHeight = 600
            it.safeTop = safeTop
            it.safeBottom = safeBottom
        }
        cfg = Config.load(ctx)
        snap = snapshot(ctx, cfg)
        val bars = if (safeTop == safeBottom) "上下各 $safeTop" else "上 $safeTop / 下 $safeBottom"
        if (snap.casting) {
            restartCast()
        } else {
            toast(ctx, "已保存：${NavFavorites.shortLabelFor(mapProvider)} 1024×$h，$bars 黑边")
        }
    }

    fun applyMapProvider(provider: String) {
        val norm = if (provider == NavFavorites.MAP_BAIDU) NavFavorites.MAP_BAIDU else NavFavorites.MAP_AMAP
        NavFavorites.setMapProvider(ctx, norm)
        mapProvider = norm
        val targetPkg = NavFavorites.packageFor(norm)
        val targetAct = NavFavorites.activityFor(norm)
        if (cfg.appDisplayPkg.isBlank() ||
            cfg.appDisplayPkg == NavFavorites.PKG_AMAP ||
            cfg.appDisplayPkg == NavFavorites.PKG_BAIDU
        ) {
            save("appDisplayPkg", targetPkg)
            save("appDisplayActivity", targetAct)
            ProbeService.config?.let {
                it.appDisplayPkg = targetPkg
                it.appDisplayActivity = targetAct
            }
            cfg = Config.load(ctx)
            snap = snapshot(ctx, cfg)
        }
        if (snap.casting) {
            restartCast()
        } else {
            toast(ctx, "导航地图已切换为${NavFavorites.labelFor(norm)}")
        }
    }

    fun applyBitrate(v: Int) {
        save("castBitrate", v.toString())
        ProbeService.config?.castBitrate = v
        if (snap.casting) restartCast() else toast(ctx, "码率 ${v / 1000} kbps")
    }

    fun applyFps(v: Int) {
        save("castFps", v.toString())
        ProbeService.config?.castFps = v
        if (snap.casting) restartCast() else toast(ctx, "帧率 $v fps")
    }

    fun setBackend(backend: String) {
        if (snap.casting) {
            toast(ctx, "先停止投屏再切换")
            return
        }
        val wasRunning = snap.serviceRunning
        val shizuku = backend == "shizuku"
        Config.saveValue(ctx, "useVdHelper", "1")
        Config.saveValue(ctx, "useShizuku", if (shizuku) "1" else "0")
        ProbeService.config?.useVdHelper = true
        ProbeService.config?.useShizuku = shizuku
        if (shizuku) {
            CastEngine.setHelperLauncher(ShizukuBridge)
        } else {
            CastEngine.setHelperLauncher(null)
        }
        RootShell.setExecutor(PrivKitBridge)
        cfg = Config.load(ctx)
        snap = snapshot(ctx, cfg)
        if (shizuku && !shizukuGranted && ShizukuBridge.isReady()) {
            ShizukuBridge.requestPermission()
        }
        if (wasRunning) {
            ctx.stopService(Intent(ctx, ProbeService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({
                startProbeService(ctx)
            }, 450)
        }
    }

    fun connectCar(ssid: String, pass: String) {
        if (ssid.isBlank()) {
            toast(ctx, "热点名不能为空")
            return
        }
        save("carSsid", ssid)
        save("carPass", pass)
        if (!snap.serviceRunning) startProbeService(ctx)
        Thread {
            Thread.sleep(900)
            val c = Config.load(ctx)
            if (c.forgetCarWifi) WifiLink.forgetSaved(c.carSsid)
            WifiLink.connect(ctx, c.carSsid, c.carPass)
            BleLink.start(ctx, c.carSsid)
        }.start()
        toast(ctx, "已保存，正在连接 $ssid（系统会弹一次允许框）")
    }

    fun forgetWifi() {
        WifiLink.release()
        WifiLink.forgetSaved(cfg.carSsid)
        toast(ctx, "已断开并清除 ${cfg.carSsid.ifBlank { "车机网络" }} 的保存记录")
    }

    fun refreshCloud() {
        if (cloudBusy) return
        cloudBusy = true
        CloudApi.carStatus(ctx) { ok, text ->
            val parsed = if (ok) VehicleStatus.parse(text) else null
            scope.launch {
                val invalid = tokenInvalid(text)
                if (invalid) {
                    cloudBusy = false
                    cloudExpired = true
                    cloudNote = "登录已过期，请重新登录。"
                    return@launch
                }
                if (!ok || parsed == null) {
                    cloudBusy = false
                    if (ok) cloudExpired = false
                    cloudNote = if (!ok) "刷新失败：$text" else "云端返回无法解析：${text.take(500)}"
                    cfg = Config.load(ctx)
                    snap = snapshot(ctx, cfg)
                    return@launch
                }
                cloudExpired = false
                // carStatus 说 4 时先显示，避免等 BMS；说 0 时先别切，等 BMS 电流确认。
                if (parsed.charge != ChargeState.NOT_CHARGING) {
                    vehicle = parsed
                }
                cloudNote = ""

                // BMS 明细：carStatus 的充电状态会偶发闪 0，用电流交叉校验；
                // 循环次数/电流/温度也从这里取。
                CloudApi.bmsTlv(ctx) { bmsOk, bmsText ->
                    val merged = if (bmsOk) {
                        VehicleStatus.mergeBms(parsed, bmsText)
                    } else {
                        parsed
                    }
                    scope.launch {
                        vehicle = merged
                        cloudBusy = false
                        cfg = Config.load(ctx)
                        snap = snapshot(ctx, cfg)
                    }
                }
            }
        }
    }

    fun tireCloud() {
        if (cloudBusy) return
        cloudBusy = true
        CloudApi.tirePressure(ctx) { ok, text ->
            scope.launch {
                cloudBusy = false
                val invalid = tokenInvalid(text)
                if (invalid) cloudExpired = true else if (ok) cloudExpired = false
                cloudNote = if (ok && !invalid) "胎压：$text" else "失败：$text"
                cfg = Config.load(ctx)
                snap = snapshot(ctx, cfg)
            }
        }
    }

    fun remote(action: String) {
        if (cloudBusy) return
        cloudBusy = true
        CloudApi.cmd(ctx, action) { ok, text ->
            scope.launch {
                cloudBusy = false
                val invalid = tokenInvalid(text)
                if (invalid) cloudExpired = true else if (ok) cloudExpired = false
                cloudNote = if (ok && !invalid) "指令 $action：$text" else "失败：$text"
                cfg = Config.load(ctx)
                snap = snapshot(ctx, cfg)
            }
        }
    }

    fun shareLog() {
        val base = File(ctx.getExternalFilesDir(null), "sessions")
        val latest = base.listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name }
        val log = latest?.let { File(it, "session.log") }
        if (log == null || !log.exists()) {
            toast(ctx, "还没有日志")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", log)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, "分享 session.log"))
        } catch (t: Throwable) {
            toast(ctx, "分享失败：$t")
        }
    }

    fun batterySettings() {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (t: Throwable) {
            try {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                toast(ctx, "请在应用详情 → 耗电管理里允许后台活动")
            } catch (t2: Throwable) {
                toast(ctx, "打开省电设置失败：${t2.message ?: t2}")
            }
        }
    }

    fun batteryWhitelist() {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true) {
            toast(ctx, "王者骑行助手已在省电白名单")
            return
        }
        try {
            val i = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return
        } catch (t: Throwable) {
            // 有些 ROM 不给弹窗，退到系统列表
        }
        batterySettings()
        toast(ctx, "请在列表里找到「王者骑行助手」并设为不优化")
    }

    fun doCommand(func: String, act: String) {
        val ok = CarCmd.send(func, act)
        toast(ctx, if (ok) "已发送 $func / $act" else "17818 没有车机连接")
    }

    LaunchedEffect(Unit) {
        ensureDefaults(ctx)
        cfg = Config.load(ctx)
        val needed = neededPermissions(ctx)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed)
        maybeCheckCloudToken()
        while (true) {
            cfg = ProbeService.config ?: cfg
            snap = snapshot(ctx, cfg)
            delay(700)
        }
    }

    // 进入「车辆」页就自动拉一次，不用先点刷新；重新登录后 tokenAt 变化也会重拉。
    LaunchedEffect(tabName, snap.cfg.cloudTokenSet, snap.cfg.cloudTokenAt) {
        if (tabName == Tab.Vehicle.name && snap.cfg.cloudTokenSet && !cloudBusy) {
            refreshCloud()
        }
    }

    if (subPage == SubPage.Privilege) {
        PrivilegePage(
            onBack = {
                subPageName = if (privilegeReturnToPermissions) SubPage.Permissions.name else null
            },
        )
        return
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    AnimatedVisibility(
                        visible = subPage != null,
                        enter = fadeIn(tween(180)) + expandHorizontally(),
                        exit = fadeOut(tween(150)) + shrinkHorizontally(),
                    ) {
                        IconButton(onClick = { subPageName = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            subPage?.label ?: tab.label,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            subPage?.subtitle ?: when (tab) {
                                Tab.Cast -> if (snap.casting) "正在投屏 · ${NavFavorites.shortLabelFor(mapProvider)} ${snap.cfg.contentHeight}p" else if (snap.serviceRunning) "车机连接就绪" else "一键开启${NavFavorites.shortLabelFor(mapProvider)}导航投屏"
                                Tab.Vehicle -> if (snap.cfg.cloudTokenSet) "实时车况与远程控制" else "连接车机或登录账号"
                                Tab.Ride -> "骑行数据与历史轨迹"
                                Tab.Settings -> "账号、车机配对与偏好"
                            },
                            fontSize = 12.sp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (subPage != SubPage.Privilege) {
                        ConnectionBadge(snap = snap) { showConnection = true }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = {
                            if (item != tab) tabName = item.name
                            subPageName = null
                        },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, fontSize = 12.sp) },
                    )
                }
            }
        },
    ) { inner ->
        AnimatedContent(
            targetState = tab to subPage,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            transitionSpec = {
                val fromSub = initialState.second != null
                val toSub = targetState.second != null
                val easing = FastOutSlowInEasing
                when {
                    !fromSub && toSub -> {
                        (slideInHorizontally(tween(260, easing = easing)) { it / 3 } +
                            fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(260, easing = easing)) { -it / 5 } +
                                fadeOut(tween(180)))
                    }
                    fromSub && !toSub -> {
                        (slideInHorizontally(tween(240, easing = easing)) { -it / 5 } +
                            fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(240, easing = easing)) { it / 3 } +
                                fadeOut(tween(180)))
                    }
                    else -> {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                    }
                }
            },
            label = "pageTransition",
        ) { (activeTab, activeSub) ->
            val scrollState = if (activeSub == null) {
                tabScrolls.getValue(activeTab)
            } else {
                rememberScrollState()
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (activeSub) {
                SubPage.Car -> CarPage(
                    snap = snap,
                    onCommand = ::doCommand,
                    onDayNight = { night -> CarCmd.dayOrNight(night) },
                    onStopNavi = { CarCmd.stopNavi() },
                    onScreenChoose = { v -> CarCmd.screenChoose(v) },
                )

                SubPage.Theme -> ThemePage(
                    pkg = themePkg,
                    state = themeState,
                    busy = themeBusy,
                    onPick = {
                        themePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    onUpload = ::startThemeUpload,
                    onCancel = ::stopThemeUpload,
                    onRestartService = {
                        startProbeService(ctx)
                        themeState = themeState.copy(done = false, status = "已重新启动监听")
                    },
                )

                SubPage.Nav -> NavPage(
                    mapProvider = mapProvider,
                    onSwitchMap = ::applyMapProvider,
                )

                SubPage.Permissions -> PermissionsCenterPage(
                    snap = snap,
                    onOpenPrivilege = {
                        privilegeReturnToPermissions = true
                        subPageName = SubPage.Privilege.name
                    },
                    onRequestPermissions = ::requestPermissions,
                    onBatteryWhitelist = ::batteryWhitelist,
                    onBatterySettings = ::batterySettings,
                )

                SubPage.Privilege -> Unit

                null -> when (activeTab) {
                Tab.Cast -> CastPage(
                    snap = snap,
                    mapProvider = mapProvider,
                    onSwitchMap = ::applyMapProvider,
                    onStartService = { startProbeService(ctx) },
                    onStopService = { ctx.stopService(Intent(ctx, ProbeService::class.java)) },
                    onStartCast = {
                        val backend = snap.cfg.backend
                        if (PrivKitBridge.isConnected()) {
                            toast(ctx, "priv-kit 虚拟屏投屏")
                            Thread { ProbeService.startCast(-1, null) }.start()
                        } else if (backend == "shizuku" && !shizukuGranted) {
                            if (shizukuReady) {
                                toast(ctx, "先授权 Shizuku")
                                ShizukuBridge.requestPermission()
                            } else {
                                toast(ctx, "请先在特权运行环境中授权（支持无线调试 / Shizuku / Root）")
                                privilegeReturnToPermissions = false
                                subPageName = SubPage.Privilege.name
                            }
                        } else {
                            toast(ctx, if (backend == "shizuku") "Shizuku 虚拟屏投屏" else "root 虚拟屏投屏")
                            Thread { ProbeService.startCast(-1, null) }.start()
                        }
                    },
                    onStartServiceAndCast = {
                        val backend = snap.cfg.backend
                        if (!PrivKitBridge.isConnected() && backend == "shizuku" && !shizukuGranted) {
                            if (shizukuReady) {
                                toast(ctx, "先授权 Shizuku")
                                ShizukuBridge.requestPermission()
                            } else {
                                toast(ctx, "请先在特权运行环境中授权（支持无线调试 / Shizuku / Root）")
                                privilegeReturnToPermissions = false
                                subPageName = SubPage.Privilege.name
                            }
                        } else {
                            startProbeService(ctx)
                            toast(ctx, "正在启动监听并投屏…")
                            scope.launch {
                                repeat(20) {
                                    if (ProbeService.running) {
                                        Thread { ProbeService.startCast(-1, null) }.start()
                                        return@launch
                                    }
                                    delay(150)
                                }
                                toast(ctx, "监听启动超时，请重试")
                            }
                        }
                    },
                    onStopCast = { Thread { ProbeService.cast?.stop() }.start() },
                    onHeight = ::applyHeight,
                    onBitrate = ::applyBitrate,
                    onFps = ::applyFps,
                    onPreview = {
                        ctx.startActivity(
                            Intent(ctx, MirrorActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onOpenNav = { subPageName = SubPage.Nav.name },
                    onOpenSettings = {
                        subPageName = null
                        tabName = Tab.Settings.name
                    },
                    onOpenPermissions = { subPageName = SubPage.Permissions.name },
                )

                Tab.Vehicle -> VehiclePage(
                    snap = snap,
                    status = vehicle,
                    cloudNote = cloudNote,
                    cloudBusy = cloudBusy,
                    cloudExpired = cloudExpired,
                    onRefresh = ::refreshCloud,
                    onTire = ::tireCloud,
                    onRemote = ::remote,
                    onOpenSettings = {
                        subPageName = null
                        tabName = Tab.Settings.name
                    },
                    onOpenCar = { subPageName = SubPage.Car.name },
                    onOpenTheme = { subPageName = SubPage.Theme.name },
                    onLogin = {
                        loginLauncher.launch(Intent(ctx, LoginActivity::class.java))
                    },
                )

                Tab.Ride -> RidePage(
                    snap = snap,
                    onOpenSettings = {
                        subPageName = null
                        tabName = Tab.Settings.name
                    },
                    onOpenVehicle = {
                        subPageName = null
                        tabName = Tab.Vehicle.name
                    },
                )

                Tab.Settings -> SettingsPage(
                    snap = snap,
                    onSave = ::save,
                    onConnectCar = ::connectCar,
                    onForgetWifi = ::forgetWifi,
                    onCloudToken = { token ->
                        val now = System.currentTimeMillis()
                        save("cloudToken", token)
                        save("cloudTokenAt", now.toString())
                        save("cloudTokenCheckedAt", now.toString())
                        cloudExpired = false
                        toast(ctx, "token 已保存")
                    },
                    onLogin = {
                        loginLauncher.launch(Intent(ctx, LoginActivity::class.java))
                    },
                    onClearCloudToken = {
                        save("cloudToken", "")
                        save("cloudTokenAt", "0")
                        save("cloudTokenCheckedAt", "0")
                        cloudExpired = false
                        cloudNote = ""
                        vehicle = null
                        toast(ctx, "token 已清除")
                    },
                    onOpenNav = { subPageName = SubPage.Nav.name },
                    onOpenTheme = { subPageName = SubPage.Theme.name },
                    onOpenCar = { subPageName = SubPage.Car.name },
                    onOpenPermissions = { subPageName = SubPage.Permissions.name },
                    onShareLog = ::shareLog,
                )
                }
            }
        }
        }
    }

    if (showConnection) {
        ConnectionDialog(
            snap = snap,
            onClose = { showConnection = false },
            onGoCast = {
                subPageName = null
                tabName = Tab.Cast.name
                showConnection = false
            },
            onOpenCar = {
                showConnection = false
                tabName = Tab.Vehicle.name
                subPageName = SubPage.Car.name
            },
        )
    }
}

/** 新装第一次跑：默认用 root 虚拟屏 + 高德 1024×600。 */
private fun ensureDefaults(ctx: Context) {
    val c = Config.load(ctx)
    if (c.appDisplayPkg.isNotBlank()) return
    Config.saveValue(ctx, "useVdHelper", "1")
    Config.saveValue(ctx, "appDisplayPkg", "com.autonavi.minimap")
    Config.saveValue(
        ctx,
        "appDisplayActivity",
        "com.autonavi.minimap/com.autonavi.map.activity.SplashActivity",
    )
    Config.saveValue(ctx, "appDisplayMove", "1")
    Config.saveValue(ctx, "appDisplayFreeform", "1")
    Config.saveValue(ctx, "appDisplayDensity", "160")
    Config.saveValue(ctx, "appDisplayIgnoreSizeRestrictions", "1")
    Config.saveValue(ctx, "appDisplayDirect", "1")
    Config.saveValue(ctx, "castWidth", "1024")
    Config.saveValue(ctx, "castHeight", "600")
    Config.saveValue(ctx, "castEnabled", "1")
}

private fun startProbeService(ctx: Context) {
    val i = Intent(ctx, ProbeService::class.java).setAction(ProbeService.ACTION_START)
    ContextCompat.startForegroundService(ctx, i)
}

private fun toast(ctx: Context, text: String) {
    Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
}
