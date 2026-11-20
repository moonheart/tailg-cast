package com.tailg.cast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailg.cast.NavAccessibility
import com.tailg.cast.PrivKitBridge
import com.tailg.probe.NavFavorites
import kotlinx.coroutines.delay
import java.util.Locale

private val HEIGHT_PRESETS = listOf(600, 560, 520, 480, 432, 384)
private val BITRATE_OPTIONS = listOf(
    "1.5M" to 1_500_000,
    "2M" to 2_000_000,
    "3M" to 3_000_000,
    "4M" to 4_000_000,
    "6M" to 6_000_000,
    "8M" to 8_000_000,
)

@Composable
fun CastPage(
    snap: AppSnapshot,
    mapProvider: String,
    onSwitchMap: (String) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onStartCast: () -> Unit,
    onStartServiceAndCast: () -> Unit,
    onStopCast: () -> Unit,
    onCarlifeMode: (Boolean) -> Unit,
    onAmapCarUi: (Boolean) -> Unit,
    onHeight: (Int) -> Unit,
    onBitrate: (Int) -> Unit,
    onFps: (Int) -> Unit,
    onPreview: () -> Unit,
    onOpenNav: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val ctx = LocalContext.current
    val cfg = snap.cfg
    val latestCasting by rememberUpdatedState(snap.casting)
    val latestBytes by rememberUpdatedState(snap.bytes)
    var bitrateSamples by remember { mutableStateOf(emptyList<Pair<Long, Long>>()) }
    var bitrateKbps by remember { mutableStateOf(0L) }
    var showQuality by remember { mutableStateOf(false) }
    var previewZoom by remember { mutableStateOf(1f) }
    var previewPanX by remember { mutableStateOf(0.5f) }

    var favorites by remember { mutableStateOf(NavFavorites.list(ctx)) }
    var activeFavId by remember { mutableStateOf(NavFavorites.activeId(ctx)) }
    var navAuto by remember { mutableStateOf(NavFavorites.isAutoStart(ctx)) }
    val accessibilityOn = NavAccessibility.isEnabled(ctx)
    var privSettled by remember { mutableStateOf(PrivKitBridge.isConnected()) }

    LaunchedEffect(Unit) {
        favorites = NavFavorites.list(ctx)
        activeFavId = NavFavorites.activeId(ctx)
        navAuto = NavFavorites.isAutoStart(ctx)
    }

    LaunchedEffect(Unit) {
        if (!privSettled) {
            delay(1200)
            privSettled = true
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            if (!latestCasting) {
                bitrateSamples = emptyList()
                bitrateKbps = 0
                continue
            }
            val now = System.currentTimeMillis()
            var samples = bitrateSamples
            if (samples.isNotEmpty() && latestBytes < samples.last().second) {
                samples = emptyList()
            }
            samples = (samples + (now to latestBytes))
                .filter { now - it.first <= 3000 }
            bitrateSamples = samples
            if (samples.size >= 2) {
                val dt = (samples.last().first - samples.first().first).coerceAtLeast(1)
                bitrateKbps = ((samples.last().second - samples.first().second)
                    .coerceAtLeast(0) * 8) / dt
            } else {
                bitrateKbps = 0
            }
        }
    }

    val privMissing = privSettled && !PrivKitBridge.isConnected()
    val basicMissing = !snap.permissionsOk
    if (privMissing || basicMissing) {
        val missingText = listOfNotNull(
            if (privMissing) "特权运行环境" else null,
            if (basicMissing) "蓝牙/Wi-Fi/通知权限" else null,
        ).joinToString("、")
        Surface(
            onClick = onOpenPermissions,
            shape = RoundedCornerShape(16.dp),
            color = WarnAmber.copy(alpha = 0.14f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = WarnAmber,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "有授权尚未就绪，点击前往配置",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "缺少：$missingText",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (snap.casting) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChipRow(
                    options = listOf("1x 全览", "1.6x 放大"),
                    selected = if (previewZoom > 1.05f) "1.6x 放大" else "1x 全览",
                ) { picked ->
                    previewZoom = if (picked == "1.6x 放大") 1.6f else 1f
                }
                if (previewZoom > 1.05f) {
                    ChipRow(
                        options = listOf("左", "中", "右"),
                        selected = when {
                            previewPanX < 0.25f -> "左"
                            previewPanX > 0.75f -> "右"
                            else -> "中"
                        },
                    ) { picked ->
                        previewPanX = when (picked) {
                            "左" -> 0f
                            "右" -> 1f
                            else -> 0.5f
                        }
                    }
                }
            }
            EmbeddedMirrorPreview(
                dispW = cfg.width,
                dispH = cfg.height,
                contentTop = if (cfg.carlifeMode) 0 else cfg.safeTop,
                contentH = if (cfg.carlifeMode) cfg.height else cfg.contentHeight,
                zoom = previewZoom,
                panX = previewPanX,
                showBadge = false,
            )
        }
    }

    // 1. 骑行投屏主控台（Gradient Hero Card）
    val heroColors = when {
        snap.casting -> listOf(Color(0xFF0F766E), Color(0xFF0EA5E9))
        snap.serviceRunning -> listOf(Color(0xFF1D4ED8), Color(0xFF0284C7))
        else -> listOf(Color(0xFF1E3A8A), Color(0xFF2563EB))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(heroColors))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        when {
                            snap.casting -> "正在投屏到仪表"
                            snap.serviceRunning -> "车机连接已就绪"
                            else -> "准备出发"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color.White,
                    )
                    Text(
                        when {
                            snap.casting && cfg.carlifeMode -> "CarLife 车联 · ${cfg.fps} fps"
                            snap.casting -> "${NavFavorites.shortLabelFor(mapProvider)}导航 · ${cfg.width}×${cfg.contentHeight} · ${cfg.fps} fps"
                            snap.serviceRunning -> "车机已就绪，可随时开始投屏"
                            else -> "将${NavFavorites.shortLabelFor(mapProvider)}导航投射到车机仪表"
                        },
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.18f),
                ) {
                    Text(
                        when {
                            snap.casting -> "投屏中"
                            snap.serviceRunning -> "待投屏"
                            else -> "未启动"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    )
                }
            }

            if (snap.casting) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroMetric("实时码率", formatBitrate(bitrateKbps), Modifier.weight(1f))
                    HeroMetric("已发帧数", "${snap.frames} 帧", Modifier.weight(1f))
                    HeroMetric(
                        "累计流量",
                        String.format(Locale.US, "%.1f MB", snap.bytes / 1024.0 / 1024.0),
                        Modifier.weight(1f),
                    )
                }
            }

            when {
                snap.casting -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onPreview,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF0F766E),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) { Text("全屏虚拟屏", fontWeight = FontWeight.SemiBold) }
                        FilledTonalButton(
                            onClick = onStopCast,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.White.copy(alpha = 0.2f),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) { Text("仅停投屏", fontWeight = FontWeight.SemiBold) }
                    }
                    TextButton(
                        onClick = onStopService,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "断开车机连接",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                snap.serviceRunning -> {
                    Button(
                        onClick = onStartCast,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF1D4ED8),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(" 开始导航投屏", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = onPreview,
                            enabled = false,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.White.copy(alpha = 0.16f),
                                contentColor = Color.White,
                                disabledContainerColor = Color.White.copy(alpha = 0.10f),
                                disabledContentColor = Color.White.copy(alpha = 0.45f),
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                        ) { Text("查看虚拟屏", fontSize = 13.sp) }
                        FilledTonalButton(
                            onClick = onStopService,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.White.copy(alpha = 0.16f),
                                contentColor = Color.White,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                        ) { Text("断开车机", fontSize = 13.sp) }
                    }
                }
                else -> {
                    Button(
                        onClick = onStartServiceAndCast,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF1E3A8A),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(" 一键导航投屏", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                    TextButton(
                        onClick = onStartService,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "仅连接车机（暂不投屏）",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            if (snap.lastError.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        snap.lastError,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }
        }
    }

    // 1.5 CarLife 车联转发模式
    SectionCard(
        title = "CarLife 车联模式",
        subtitle = if (cfg.carlifeMode) {
            "已启用：已 Root 自动拉起 Car+；未 Root 需先在 Car+ 手动点去连接"
        } else {
            "接收本机 CarLife (127.0.0.1:7240) 转发给台铃（可选 Root 自动拉起）"
        },
        icon = Icons.Filled.PlayArrow,
        helpText = "有两种启动方式：\n" +
            "1) 已 Root（推荐）：在 KernelSU/Magisk 给本 App 授权后，直接点「开始投屏」，会自动拉起 Car+ 车联并转发画面。\n" +
            "2) 未 Root：受系统签名权限（CAR_LINK）限制，第三方 App 无法自动拉起或直跳 Car+ 连接页，需先手动到系统「设置 → 连接与共享 → Car+ 车联」点「去连接（百度 CarLife+）」，再切回点「开始投屏」（免 Root 全自动建议使用默认的虚拟屏 + Shizuku 模式）。",
        action = {
            Switch(
                checked = cfg.carlifeMode,
                onCheckedChange = onCarlifeMode,
            )
        },
    ) {}

    // 1.6 导航精简车机版（UCar / CarLink 入口，高德 & 百度均支持）
    if (!cfg.carlifeMode) {
        SectionCard(
            title = "导航精简车机版 (UCar)",
            subtitle = if (cfg.amapCarUi) {
                "已启用：高德 / 百度均以精简车机界面启动（无广告/大地图）"
            } else {
                "用精简车机界面启动高德 / 百度地图（免 Root / 免 Car+）"
            },
            icon = Icons.Filled.LocationOn,
            helpText = "开启后，虚拟屏模式会调用高德（UcarSplashActivity）或百度（HiCarWelcomeScreen）内置的精简车机入口，画面只有地图与大按钮，没有手机版广告和底部栏。",
            action = {
                Switch(
                    checked = cfg.amapCarUi,
                    onCheckedChange = onAmapCarUi,
                )
            },
        ) {}
    }

    // 2. 快捷出发（导航地图、目的地与自动导航）
    val activeFav = favorites.firstOrNull { it.id == activeFavId }
    val mapLabel = NavFavorites.shortLabelFor(mapProvider)
    SectionCard(
        title = "快捷出发",
        subtitle = if (navAuto && activeFav != null) {
            "投屏后用${mapLabel}前往「${activeFav.name}」"
        } else {
            "投屏时自动打开${mapLabel}导航"
        },
        icon = Icons.Filled.LocationOn,
        helpText = "可切换高德地图或百度地图作为投屏导航应用；开启自动导航后，每次开始投屏会自动打开对应路线页前往选中的目的地。",
        action = {
            Switch(
                checked = navAuto,
                onCheckedChange = { on ->
                    navAuto = on
                    NavFavorites.setAutoStart(ctx, on)
                },
            )
        },
    ) {
        ChipRow(
            options = listOf("高德地图", "百度地图"),
            selected = NavFavorites.labelFor(mapProvider),
        ) { picked ->
            onSwitchMap(if (picked == "百度地图") NavFavorites.MAP_BAIDU else NavFavorites.MAP_AMAP)
        }
        if (favorites.isNotEmpty()) {
            ChipRow(
                options = favorites.map { it.name },
                selected = activeFav?.name,
            ) { pickedName ->
                val target = favorites.firstOrNull { it.name == pickedName } ?: return@ChipRow
                activeFavId = target.id
                NavFavorites.setActive(ctx, target.id)
            }
        } else {
            EmptyHint("添加家或公司地址，投屏时自动开始导航。")
        }
        ActionRow(
            title = "管理常用目的地",
            subtitle = "已存 ${favorites.size} 个 · ${if (accessibilityOn) "自动开始导航已开启" else "手动点击开始导航"}",
            badge = if (accessibilityOn) "全自动" else null,
        ) { onOpenNav() }
    }

    // 3. 画面显示
    SectionCard(
        title = "画面显示",
        icon = Icons.Filled.Settings,
        helpText = "车机仪表画布固定为 1024×600。若部分内容被车机底部状态栏遮挡，可适当调小画面高度（上下会自动补黑边）；码率和帧率越高画面越清晰流畅。",
        action = {
            TextButton(onClick = { showQuality = true }) {
                Text("调整", fontSize = 13.sp)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("画面高度", "${cfg.contentHeight}", "px", Modifier.weight(1f))
            StatTile("清晰度", bitrateLabel(cfg.bitrate), "", Modifier.weight(1f))
            StatTile("帧率", "${cfg.fps}", "fps", Modifier.weight(1f))
        }
    }

    // 4. 连接与高级设置
    SectionCard(
        title = "车机与高级设置",
        icon = Icons.Filled.Build,
    ) {
        ActionRow(
            title = "车机 Wi-Fi / 蓝牙配对",
            subtitle = if (cfg.carSsid.isNotBlank()) "当前车机：${cfg.carSsid}" else "扫码或输入车机热点",
            badge = if (snap.wifiConnected || snap.bleConnected) "已连接" else null,
        ) { onOpenSettings() }
    }

    if (showQuality) {
        var customText by remember { mutableStateOf(cfg.contentHeight.toString()) }
        var customError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showQuality = false },
            title = { Text("画质设置") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "画面高度（px）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipFlow(
                        HEIGHT_PRESETS.map { if (it == 600) "600 全屏" else it.toString() },
                        heightLabel(cfg.contentHeight),
                    ) { picked ->
                        onHeight(picked.substringBefore(" ").toInt())
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { v ->
                                customText = v.filter { it.isDigit() }.take(3)
                                customError = null
                            },
                            label = { Text("自定义 160–600") },
                            singleLine = true,
                            isError = customError != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                val h = customText.toIntOrNull()
                                if (h == null || h !in 160..600) {
                                    customError = "请输入 160–600 的整数"
                                } else {
                                    customError = null
                                    onHeight(h)
                                }
                            },
                        ) { Text("应用") }
                    }
                    if (customError != null) {
                        Text(
                            customError.orEmpty(),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Text(
                        "码率",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipFlow(
                        BITRATE_OPTIONS.map { it.first },
                        bitrateLabel(cfg.bitrate),
                    ) { picked ->
                        onBitrate(BITRATE_OPTIONS.first { it.first == picked }.second)
                    }

                    Text(
                        "帧率",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChipFlow(
                        listOf("24", "30"),
                        cfg.fps.toString(),
                    ) { picked -> onFps(picked.toInt()) }

                    Text(
                        "选 600 为全屏；数值越小，上下黑边越多。改完立即生效。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showQuality = false }) { Text("完成") }
            },
        )
    }
}

@Composable
private fun HeroMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.16f),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.78f))
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

private fun heightLabel(height: Int): String? = when {
    height == 600 -> "600 全屏"
    height in HEIGHT_PRESETS -> height.toString()
    else -> null
}

private fun bitrateLabel(value: Int): String =
    BITRATE_OPTIONS.firstOrNull { it.second == value }?.first ?: "${value / 1000}k"

private fun formatBitrate(kbps: Long): String =
    if (kbps >= 1000) {
        String.format(Locale.US, "%.1f Mbps", kbps / 1000.0)
    } else {
        "$kbps kbps"
    }
