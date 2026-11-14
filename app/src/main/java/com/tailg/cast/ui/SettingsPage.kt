package com.tailg.cast.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tailg.cast.CarQr
import com.tailg.cast.NavAccessibility
import com.tailg.cast.PrivKitBridge
import com.tailg.probe.NavFavorites

@Composable
fun SettingsPage(
    snap: AppSnapshot,
    onSave: (String, String) -> Unit,
    onConnectCar: (String, String) -> Unit,
    onForgetWifi: () -> Unit,
    onCloudToken: (String) -> Unit,
    onClearCloudToken: () -> Unit,
    onLogin: () -> Unit,
    onOpenNav: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenCar: () -> Unit,
    onOpenPermissions: () -> Unit,
    onShareLog: () -> Unit,
) {
    val cfg = snap.cfg
    var ssid by remember(cfg.carSsid) { mutableStateOf(cfg.carSsid) }
    var pass by remember(cfg.carPass) { mutableStateOf(cfg.carPass) }
    var token by remember { mutableStateOf("") }
    var nickname by remember(cfg.nickname) { mutableStateOf(cfg.nickname) }
    var tuc by remember(cfg.tuc) { mutableStateOf(cfg.tuc) }
    var mac by remember(cfg.mac) { mutableStateOf(cfg.mac) }
    var showCloudDetail by remember { mutableStateOf(false) }
    var showCarPair by remember { mutableStateOf(false) }
    var showIdentity by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val ctx = LocalContext.current
    val appVersion = remember(ctx) {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "0.2.0"
    }
    val favCount = remember { NavFavorites.list(ctx).size }
    val accessibilityOn = NavAccessibility.isEnabled(ctx)

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrBlank()) return@rememberLauncherForActivityResult
        val info = CarQr.parse(text)
        if (info == null) {
            Toast.makeText(
                ctx,
                "这个二维码只有下载地址，没有 Wi-Fi 信息；请扫车机 Wi-Fi 页的二维码",
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        ssid = info.ssid
        pass = info.password
        Toast.makeText(ctx, "已识别 ${info.ssid}，正在连接…", Toast.LENGTH_SHORT).show()
        onConnectCar(info.ssid, info.password)
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(scanOptions())
        } else {
            Toast.makeText(ctx, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show()
        }
    }
    fun launchScan() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            scanLauncher.launch(scanOptions())
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    // 1. 台铃账号（用户习惯优先展示账号状态）
    SectionCard(
        title = "台铃账号",
        icon = Icons.Filled.Lock,
        helpText = "登录后可同步车辆状态、远控与历史骑行轨迹；未登录不影响车机投屏与蓝牙连接。",
    ) {
        val ageDays = if (cfg.cloudTokenAt > 0) {
            ((System.currentTimeMillis() - cfg.cloudTokenAt) / 86_400_000L).toInt()
        } else {
            -1
        }
        StatusLine(
            "账号状态",
            if (cfg.cloudTokenSet) "已登录" else "未登录",
            cfg.cloudTokenSet,
            warn = !cfg.cloudTokenSet,
        )
        if (ageDays >= 25) {
            Text(
                "登录凭证已使用 $ageDays 天，原厂接口约 30 天有效，建议重新登录。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (cfg.cloudTokenSet) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onLogin,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("切换 / 重登") }
                TextButton(
                    onClick = onClearCloudToken,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("退出登录") }
            }
        } else {
            Button(
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) { Text("手机号登录", fontWeight = FontWeight.SemiBold) }
        }
        ActionRow(
            "账号与设备详情",
            if (cfg.cloudTokenAt > 0) {
                "登录于 ${formatTokenTime(cfg.cloudTokenAt)}"
            } else {
                "支持手动粘贴 token"
            },
        ) { showCloudDetail = true }
    }

    // 2. 我的车机与连接
    SectionCard(
        title = "我的车机",
        icon = Icons.Filled.Settings,
        helpText = "车机热点名和蓝牙名相同，通常以 TL_ 开头。扫码或填写保存后，连接时系统会弹窗确认，点允许即可。",
    ) {
        ActionRow(
            title = "车机 Wi-Fi / 蓝牙配对",
            subtitle = if (cfg.carSsid.isNotBlank()) "已保存：${cfg.carSsid}" else "扫码或输入车机热点",
            badge = when {
                snap.wifiConnected || snap.bleConnected -> "已连接"
                cfg.carSsid.isNotBlank() -> "已配对"
                else -> "去配对"
            },
        ) { showCarPair = true }
        ActionRow(
            title = "仪表主题与壁纸",
            subtitle = "更换仪表盘皮肤",
        ) { onOpenTheme() }
        ActionRow(
            title = "仪表控制",
            subtitle = "实时车况、日夜模式、结束导航",
        ) { onOpenCar() }
    }

    // 3. 导航与辅助
    SectionCard(
        title = "出行与导航",
        icon = Icons.Filled.Build,
    ) {
        ActionRow(
            title = "常用目的地",
            subtitle = "已存 $favCount 个 · ${if (accessibilityOn) "自动开始导航已开启" else "手动点击开始导航"}",
            icon = Icons.Filled.LocationOn,
        ) { onOpenNav() }
    }

    // 4. 授权与权限（统一入口）
    SectionCard(
        title = "授权与权限",
        icon = Icons.Filled.Notifications,
    ) {
        ActionRow(
            title = "授权与权限中心",
            subtitle = PrivKitBridge.statusSummary() + if (snap.permissionsOk) " · 基础权限完整" else " · 有基础权限未授予",
            badge = if (PrivKitBridge.isConnected() && snap.permissionsOk) "已就绪" else "待配置",
        ) { onOpenPermissions() }
    }

    // 5. 高级与关于
    SectionCard(
        title = "通用与关于",
        icon = Icons.Filled.Info,
    ) {
        ActionRow(
            "设备身份",
            cfg.nickname.ifBlank { "未设置" },
        ) { showIdentity = true }
        ActionRow(
            "运行日志",
            "导出与分享最近会话日志",
        ) { showLogs = true }
        ActionRow(
            "关于与免责声明",
            "王者骑行助手 v$appVersion · 非官方开源工具",
        ) { showAbout = true }
    }

    if (showCloudDetail) {
        DetailDialog("云端 token", onClose = { showCloudDetail = false }) {
            InfoLine("状态", if (cfg.cloudTokenSet) "已配置" else "未配置")
            InfoLine("登录时间", formatTokenTime(cfg.cloudTokenAt))
            InfoLine("上次检查", formatTokenTime(cfg.cloudTokenCheckedAt))
            InfoLine("车机 IMEI", cfg.carImei.ifBlank { "刷新车辆状态后自动带出" })
            Text(
                "手动粘贴 token（兜底）",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingField(
                "Authorization token",
                token,
                { token = it },
                if (cfg.cloudTokenSet) "已配置；留空不改" else "粘贴 token",
                password = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (token.isNotBlank()) {
                            onCloudToken(token.trim())
                            token = ""
                        }
                    },
                    enabled = token.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("保存 token") }
                TextButton(
                    onClick = onClearCloudToken,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("清除") }
            }
            EmptyHint("云端 v1 token 没有 refresh 接口；App 每 7 天检查一次，只能提前发现过期，不能保证续期。")
        }
    }

    if (showIdentity) {
        DetailDialog("身份", onClose = { showIdentity = false }) {
            InfoLine("当前昵称", cfg.nickname.ifBlank { "—" })
            InfoLine("当前 TUC", cfg.tuc.ifBlank { "—" })
            SettingField("昵称", nickname, { nickname = it }, "TL-PROBE")
            SettingField("TUC", tuc, { tuc = it }, "至少 16 字符")
            SettingField("MAC", mac, { mac = it }, "AA:BB:CC:DD:EE:FF")
            Button(
                onClick = {
                    onSave("nickname", nickname.trim())
                    onSave("tuc", tuc.trim())
                    onSave("mac", mac.trim())
                    Toast.makeText(ctx, "身份已保存，改完请重启监听", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) { Text("保存身份") }
            EmptyHint("车机按这些字段认手机；改完要重启监听。")
        }
    }

    if (showLogs) {
        var rawCap by remember(cfg.rawCapture) { mutableStateOf(cfg.rawCapture) }
        DetailDialog("运行日志", onClose = { showLogs = false }) {
            Text(
                "目录：Android/data/com.tailg.cast/files/sessions\n开启原始抓包后，Wi-Fi (17818/19000) 与蓝牙 BLE 的完整收发原始字节将全部落盘到 raw_packets.log。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = {
                    val next = !rawCap
                    rawCap = next
                    com.tailg.probe.LogSink.rawCapture = next
                    onSave("rawCapture", if (next) "1" else "0")
                    Toast.makeText(
                        ctx,
                        if (next) "已开启 Wi-Fi & 蓝牙原始抓包 (raw_packets.log)" else "已关闭原始抓包",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) {
                Text(if (rawCap) "原始抓包：已开启（点击关闭）" else "原始抓包：已关闭（点击开启）")
            }
            Button(
                onClick = onShareLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) { Text("分享最近日志") }
        }
    }

    if (showAbout) {
        DetailDialog("关于与免责声明", onClose = { showAbout = false }) {
            InfoLine("应用名称", "王者骑行助手（非官方第三方工具）")
            InfoLine("版本", appVersion)
            InfoLine("协议", "17818 / 15456 / 15457 / 18888 / 19000")
            InfoLine("构建", "Kotlin 2.4 · Compose · AGP 8.13")
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "免责声明",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "1. 非官方声明：本应用为个人开源的第三方骑行与车机投屏辅助工具，与台铃科技（TAILG）、高德地图、百度地图等任何厂商均无隶属、合作或官方授权关系。\n\n" +
                            "2. 安全驾驶提示：骑行途中请严格遵守交通法规，切勿在行驶过程中操作手机或分心观看屏幕；导航路线与车况数据仅供参考。\n\n" +
                            "3. 接口与风险说明：车机通信协议与云端接口并非公开稳定 API，原厂固件或服务端升级后可能随时失效；登录凭证仅保存在本机应用私有目录，使用本软件产生的一切风险由使用者自行承担。\n\n" +
                            "4. 使用范围：本项目仅供个人学习、研究与自用交流，严禁用于商业用途或任何违法违规行为。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showCarPair) {
        DetailDialog("车机 Wi-Fi / 蓝牙配对", onClose = { showCarPair = false }) {
            FilledTonalButton(
                onClick = { launchScan() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) { Text("扫车机二维码配对") }
            SettingField("热点 / 蓝牙名称", ssid, { ssid = it }, "TL_c37282ba633")
            SettingField("热点密码", pass, { pass = it }, "车机热点密码", password = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        onConnectCar(ssid.trim(), pass)
                        showCarPair = false
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("保存并连接") }
                FilledTonalButton(
                    onClick = {
                        onForgetWifi()
                        showCarPair = false
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("忘记网络") }
            }
        }
    }
}

private fun scanOptions(): ScanOptions = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("对准车机屏幕上的二维码")
    setBeepEnabled(false)
    setOrientationLocked(false)
    setBarcodeImageEnabled(false)
}

private fun formatTokenTime(ts: Long): String {
    if (ts <= 0) return "未知"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))
}
