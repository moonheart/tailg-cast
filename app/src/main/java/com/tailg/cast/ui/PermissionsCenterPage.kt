package com.tailg.cast.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tailg.cast.NavAccessibility
import com.tailg.cast.PrivKitBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一授权与权限中心：集中管理特权运行环境、基础系统权限、后台保活与无障碍服务。
 */
@Composable
fun PermissionsCenterPage(
    snap: AppSnapshot,
    onOpenPrivilege: () -> Unit,
    onRequestPermissions: () -> Unit,
    onBatteryWhitelist: () -> Unit,
    onBatterySettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var privConnected by remember { mutableStateOf(PrivKitBridge.isConnected()) }
    var privSummary by remember { mutableStateOf(PrivKitBridge.statusSummary()) }
    var basicOk by remember { mutableStateOf(snap.permissionsOk && neededPermissions(ctx).isEmpty()) }
    var batteryIgnored by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    var a11yOn by remember { mutableStateOf(NavAccessibility.isEnabled(ctx)) }
    var enablingA11y by remember { mutableStateOf(false) }

    fun refreshAll() {
        privConnected = PrivKitBridge.isConnected()
        privSummary = PrivKitBridge.statusSummary()
        basicOk = neededPermissions(ctx).isEmpty()
        batteryIgnored = isIgnoringBattery(ctx)
        a11yOn = NavAccessibility.isEnabled(ctx)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshAll()
    }

    // 1. 特权运行环境（Root / 无线调试 / ADB / Shizuku）
    SectionCard(
        title = "特权运行环境",
        icon = Icons.Filled.Build,
        helpText = "虚拟屏投屏与无障碍自动开启依赖特权服务，支持 Root、无线调试通知栏配对、手动 ADB 与外部 Shizuku。",
    ) {
        StatusLine(
            name = "特权服务",
            state = if (privConnected) "已就绪" else "待授权",
            ok = privConnected,
            warn = !privConnected,
        )
        InfoLine("当前方案", privSummary)
        Button(
            onClick = onOpenPrivilege,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            Text(if (privConnected) "管理 Root / 无线调试 / Shizuku" else "前往授权特权环境")
        }
    }

    // 2. 基础系统权限
    SectionCard(
        title = "基础运行权限",
        icon = Icons.Filled.Lock,
        helpText = "用于蓝牙车机扫描连接、Wi-Fi 状态读取以及前台投屏通知展示。",
    ) {
        StatusLine(
            name = "蓝牙 / Wi-Fi / 通知",
            state = if (basicOk) "已全部授予" else "有缺失项",
            ok = basicOk,
            warn = !basicOk,
        )
        Button(
            onClick = {
                onRequestPermissions()
                refreshAll()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) {
            Text(if (basicOk) "检查基础权限" else "一键请求缺失权限")
        }
    }

    // 3. 后台保活与省电白名单
    SectionCard(
        title = "后台保活与省电",
        icon = Icons.Filled.Notifications,
        helpText = "投屏与车机监听需要在后台持续运行，建议将本应用加入系统省电白名单。",
    ) {
        StatusLine(
            name = "电池优化白名单",
            state = if (batteryIgnored) "已加入白名单" else "未加入",
            ok = batteryIgnored,
            warn = !batteryIgnored,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onBatteryWhitelist,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) {
                Text(if (batteryIgnored) "已加入" else "加入白名单")
            }
            FilledTonalButton(
                onClick = onBatterySettings,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) {
                Text("系统省电列表")
            }
        }
    }

    // 4. 无障碍导航服务
    SectionCard(
        title = "无障碍导航服务",
        icon = Icons.Filled.CheckCircle,
        helpText = "用于投屏时自动触发快捷导航与辅助交互；授权特权环境后可一键自动开启。",
    ) {
        StatusLine(
            name = "王者骑行助手导航服务",
            state = if (a11yOn) "已开启" else "未开启",
            ok = a11yOn,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (enablingA11y) return@Button
                    enablingA11y = true
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            NavAccessibility.enableViaPrivileged(ctx)
                        }
                        a11yOn = NavAccessibility.isEnabled(ctx)
                        enablingA11y = false
                        Toast.makeText(
                            ctx,
                            if (res.ok) "无障碍已通过 ${res.via} 自动开启"
                            else "自动开启失败：${res.detail.ifBlank { "请先授权特权环境或手动开启" }}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                enabled = !a11yOn && !enablingA11y,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) {
                Text(if (enablingA11y) "开启中…" else if (a11yOn) "已开启" else "特权自动开启")
            }
            FilledTonalButton(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) {
                Text("系统无障碍设置")
            }
        }
    }
}

private fun isIgnoringBattery(ctx: Context): Boolean {
    val pm = ctx.getSystemService(PowerManager::class.java)
    return pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
}
