package com.tailg.cast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** 顶栏右上角的连接状态胶囊，点开是完整连接详情。 */
@Composable
fun ConnectionBadge(snap: AppSnapshot, onClick: () -> Unit) {
    val color = when {
        snap.casting -> OkGreen
        snap.carConnected -> OkGreen
        snap.bleConnected -> OkGreen
        snap.serviceRunning -> WarnAmber
        else -> MutedDot
    }
    val label = when {
        snap.casting -> "投屏中"
        snap.carConnected -> "车机已连"
        snap.bleConnected -> "蓝牙已连"
        snap.serviceRunning -> "待连接"
        else -> "未连接"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        modifier = Modifier
            .padding(end = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
            )
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ConnectionDialog(
    snap: AppSnapshot,
    onClose: () -> Unit,
    onGoCast: () -> Unit,
    onOpenCar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("连接状态") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                StatusLine(
                    "服务",
                    if (snap.serviceRunning) "运行中" else "未启动",
                    snap.serviceRunning,
                )
                StatusLine(
                    "17818 控制口",
                    when {
                        snap.carConnected -> "已连接"
                        snap.serviceRunning -> "监听中，等车机"
                        else -> "—"
                    },
                    snap.carConnected,
                )
                StatusLine(
                    "BLE 通道",
                    if (snap.bleConnected) "已连接" else "未连接",
                    snap.bleConnected,
                )
                StatusLine(
                    "车机 Wi-Fi",
                    if (snap.wifiConnected) "已连接" else "未连接",
                    snap.wifiConnected,
                )
                StatusLine(
                    "15456 投屏",
                    when {
                        snap.casting -> "推流中"
                        snap.serviceRunning -> "待开始"
                        else -> "—"
                    },
                    snap.casting,
                )
                StatusLine(
                    "端口占用",
                    when {
                        !snap.serviceRunning -> "—"
                        snap.portConflict -> "被别的 App 占住"
                        else -> "正常"
                    },
                    snap.serviceRunning && !snap.portConflict,
                    warn = snap.portConflict,
                )
                InfoLine("车机版本", snap.carVersion.ifBlank { "—" })
                InfoLine("车机 MAC", snap.carMac.ifBlank { "—" })
                InfoLine("车机电量", if (snap.carBattery >= 0) "${snap.carBattery}%" else "—")
                InfoLine("车机续航", if (snap.carEndurance >= 0) "${snap.carEndurance} km" else "—")
                InfoLine("车机总里程", if (snap.carMileage >= 0) "${snap.carMileage} km" else "—")
                InfoLine("车机胎压", if (snap.carTire >= 0) "${snap.carTire}" else "—")
                if (snap.carRide.isNotBlank()) InfoLine("骑行数据", snap.carRide)
                if (snap.lastNavi.isNotBlank()) InfoLine("最近导航", snap.lastNavi)
                if (snap.serviceRunning) {
                    InfoLine(
                        "本次发送",
                        "${snap.frames} 帧 · ${String.format(Locale.US, "%.1f", snap.bytes / 1024.0 / 1024.0)} MB",
                    )
                }
                if (snap.lastError.isNotBlank()) {
                    Text(
                        "最近错误：${snap.lastError}",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ActionRow(
                    "车机控制",
                    "本地 17818 查询、日夜模式、结束车机导航",
                ) { onOpenCar() }
                Text(
                    "右上角状态点随时可以打开这里；各页只保留各自的操作。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onGoCast) { Text("去投屏") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("关闭") }
        },
    )
}
