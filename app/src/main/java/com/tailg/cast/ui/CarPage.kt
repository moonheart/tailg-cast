package com.tailg.cast.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailg.cast.CallStateMonitor
import com.tailg.cast.CarNotificationService
import com.tailg.probe.CarCmd

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarPage(
    snap: AppSnapshot,
    onCommand: (String, String) -> Unit,
    onDayNight: (Boolean) -> Unit,
    onStopNavi: () -> Unit,
    onScreenChoose: (Int) -> Unit,
    onSave: (String, String) -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val ctx = LocalContext.current
    val cfg = snap.cfg
    val dataOnline = snap.carConnected || snap.bleConnected
    val cmdOnline = snap.carConnected
    val syncOnline = snap.carConnected || snap.bleConnected
    val battery = if (snap.carBattery >= 0) "${snap.carBattery}" else "—"
    val endurance = if (snap.carEndurance >= 0) "${snap.carEndurance}" else "—"
    val mileage = if (snap.carMileage >= 0) "${snap.carMileage}" else "—"
    val tire = if (snap.carTire >= 0) "${snap.carTire}" else "—"
    var showMore by remember { mutableStateOf(false) }
    var showReply by remember { mutableStateOf(false) }
    var syncMusic by remember(cfg.syncMusic) { mutableStateOf(cfg.syncMusic) }
    var syncNotify by remember(cfg.syncNotify) { mutableStateOf(cfg.syncNotify) }
    var syncCall by remember(cfg.syncCall) { mutableStateOf(cfg.syncCall) }
    var syncSms by remember(cfg.syncSms) { mutableStateOf(cfg.syncSms) }
    var notifyApps by remember(cfg.notifyApps) { mutableStateOf(cfg.notifyApps) }

    SectionCard(
        title = "实时数据",
        icon = Icons.Filled.Info,
        helpText = "数据通过车机 Wi-Fi (17818) 或蓝牙实时同步。",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("电量", battery, "%", Modifier.weight(1f))
            StatTile("续航", endurance, "km", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("总里程", mileage, "", Modifier.weight(1f))
            StatTile("胎压", tire, "", Modifier.weight(1f))
        }
        InfoLine("骑行 当前/峰值/平均", snap.carRide.ifBlank { "—" })
        if (!dataOnline) {
            EmptyHint("车机还没连上。可以先用 BLE 或等车机连 17818。")
        }
    }

    SectionCard("控制", icon = Icons.Filled.Build) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { onDayNight(false) },
                enabled = cmdOnline,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) { Text("日间") }
            FilledTonalButton(
                onClick = { onDayNight(true) },
                enabled = cmdOnline,
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) { Text("夜间") }
        }
        Button(
            onClick = onStopNavi,
            enabled = cmdOnline,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
        ) { Text("结束车机导航") }
    }

    SectionCard(
        title = "车机查询",
        icon = Icons.Filled.Search,
        helpText = "通过车机 Wi-Fi 当前连接发送查询指令；若无弹窗可点下方「最近应答」查看回包。",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CommandButton("车辆信息", cmdOnline, Modifier.weight(1f)) {
                onCommand("CAR_INFO", "get_car_info")
            }
            CommandButton("胎压", cmdOnline, Modifier.weight(1f)) {
                onCommand("TIRE", "get_data")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CommandButton("导航状态", cmdOnline, Modifier.weight(1f)) {
                onCommand("ROAD_NAVI", "get_status")
            }
            OutlinedButton(
                onClick = { showMore = true },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
            ) { Text("更多查询…", fontSize = 13.sp) }
        }
        ActionRow(
            "最近应答",
            snap.lastCmd.ifBlank { "还没有发送过指令" },
        ) { showReply = true }
    }

    val notifyEnabled = CarNotificationService.isEnabled(ctx)
    val callPermOk = CallStateMonitor.hasPermission(ctx)
    SectionCard(
        title = "仪表互联（音乐 / 通知 / 来电）",
        icon = Icons.Filled.Notifications,
        helpText = "通过 17818 或蓝牙 BLE 将正在播放的歌名、微信/QQ通知、短信与来电弹窗推送到车机仪表。",
    ) {
        StatusLine(
            name = "通知使用权",
            state = if (notifyEnabled) "已开启" else "未开启",
            ok = notifyEnabled,
        )
        StatusLine(
            name = "来电权限",
            state = if (callPermOk) "已授予" else "未授予",
            ok = callPermOk,
        )
        InfoLine("当前曲目", CarNotificationService.currentSongSummary)
        InfoLine("最近推送", CarNotificationService.lastPushSummary)

        SyncSwitchRow("同步手机音乐 & 车机切歌", syncMusic) { checked ->
            syncMusic = checked
            onSave("syncMusic", if (checked) "1" else "0")
        }
        SyncSwitchRow("同步应用通知（Wi-Fi 下带图标）", syncNotify) { checked ->
            syncNotify = checked
            onSave("syncNotify", if (checked) "1" else "0")
        }
        if (syncNotify) {
            val allApps = notifyApps.trim() == "*"
            val selectedSet = remember(notifyApps) {
                notifyApps.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = allApps,
                    onClick = {
                        val next = if (allApps) "com.tencent.mm,com.tencent.mobileqq" else "*"
                        notifyApps = next
                        onSave("notifyApps", next)
                    },
                    label = { Text("全部应用", fontSize = 12.sp) },
                )
                PRESET_NOTIFY_APPS.forEach { (pkg, label) ->
                    val checked = allApps || selectedSet.contains(pkg)
                    FilterChip(
                        selected = checked,
                        onClick = {
                            val nextSet = if (allApps) {
                                PRESET_NOTIFY_APPS.map { it.first }.toMutableSet()
                            } else {
                                selectedSet.toMutableSet()
                            }
                            if (checked && !allApps) nextSet.remove(pkg) else nextSet.add(pkg)
                            val next = nextSet.joinToString(",")
                            notifyApps = next
                            onSave("notifyApps", next)
                        },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
        }
        SyncSwitchRow("来电弹窗提醒", syncCall) { checked ->
            syncCall = checked
            onSave("syncCall", if (checked) "1" else "0")
        }
        SyncSwitchRow("短信内容推送", syncSms) { checked ->
            syncSms = checked
            onSave("syncSms", if (checked) "1" else "0")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = {
                    val ok = CarNotificationService.syncMusicNow()
                    Toast.makeText(
                        ctx,
                        if (ok) "已同步当前音乐状态" else "车机未连接或通知服务未启用",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                enabled = syncOnline && notifyEnabled,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) { Text("同步当前音乐", fontSize = 13.sp) }
            OutlinedButton(
                onClick = onOpenPermissions,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) { Text("去授权", fontSize = 13.sp) }
        }
    }

    var keyGear by remember { mutableStateOf(3) }
    SectionCard(
        title = "蓝牙钥匙控车（BT_KEY）",
        icon = Icons.Filled.Build,
        helpText = "通过 17818 或 BLE 发送控制锁车/解锁与无感解锁感应距离档位。",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val ok = CarCmd.btKeyLock(cfg.tuc, 1)
                    Toast.makeText(ctx, if (ok) "已发送解锁" else "车机未连接", Toast.LENGTH_SHORT).show()
                },
                enabled = syncOnline,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) { Text("解锁", fontSize = 13.sp) }
            FilledTonalButton(
                onClick = {
                    val ok = CarCmd.btKeyLock(cfg.tuc, 0)
                    Toast.makeText(ctx, if (ok) "已发送锁车" else "车机未连接", Toast.LENGTH_SHORT).show()
                },
                enabled = syncOnline,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) { Text("锁车 / 设防", fontSize = 13.sp) }
        }
        Text("无感解锁感应距离档位", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { g ->
                FilterChip(
                    selected = keyGear == g,
                    onClick = {
                        keyGear = g
                        val ok = CarCmd.btKeyDistance(cfg.tuc, g)
                        Toast.makeText(ctx, if (ok) "已设置距离档位 $g" else "车机未连接", Toast.LENGTH_SHORT).show()
                    },
                    enabled = syncOnline,
                    label = { Text("${g}档", fontSize = 12.sp) },
                )
            }
        }
    }

    var tireBar by remember { mutableStateOf(true) }
    var showTireLimit by remember { mutableStateOf(false) }
    SectionCard(
        title = "胎压监测与报警设置（TIRE）",
        icon = Icons.Filled.Info,
        helpText = "展示前后轮胎压/胎温与配对状态，支持切换单位及设置报警阈值。",
    ) {
        InfoLine("前轮", snap.frontTireSummary.ifBlank { "尚未收到" })
        InfoLine("后轮", snap.rearTireSummary.ifBlank { "尚未收到" })
        InfoLine("传感器配对", snap.tireMatchSummary.ifBlank { "尚未查询" })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CommandButton("查胎压", syncOnline, Modifier.weight(1f)) { CarCmd.tireGetData() }
            CommandButton("查配对", syncOnline, Modifier.weight(1f)) { CarCmd.tireGetMatchStatus() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = {
                    tireBar = !tireBar
                    val ok = CarCmd.tireSetUnit(0, tireBar)
                    Toast.makeText(
                        ctx,
                        if (ok) "已切换胎压单位为 ${if (tireBar) "bar" else "psi"}" else "车机未连接",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                enabled = syncOnline,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) { Text("单位: ${if (tireBar) "bar" else "psi"}", fontSize = 13.sp) }
            OutlinedButton(
                onClick = { showTireLimit = true },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) { Text("报警阈值…", fontSize = 13.sp) }
        }
    }

    var speechText by remember { mutableStateOf("电量提醒：请注意前方路况") }
    var cityToDelete by remember { mutableStateOf("") }
    SectionCard(
        title = "车机扩展工具（语音 / 环境 / 地图 / 日志）",
        icon = Icons.Filled.Build,
        helpText = "包含语音文本播报、仪表环境信息同步、离线地图管理与车机诊断日志请求。",
    ) {
        OutlinedTextField(
            value = speechText,
            onValueChange = { speechText = it },
            label = { Text("车机语音播报文本") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val ok = CarCmd.audioSendText(speechText)
                    Toast.makeText(ctx, if (ok) "已发送语音播报" else "车机未连接", Toast.LENGTH_SHORT).show()
                },
                enabled = syncOnline && speechText.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) { Text("发送播报", fontSize = 13.sp) }
            FilledTonalButton(
                onClick = {
                    CarCmd.sendWeather(1, "24", "3级")
                    CarCmd.sendCompass(90f)
                    CarCmd.sendGpsSignal(4)
                    Toast.makeText(ctx, "已推送天气/指南针/GPS信号", Toast.LENGTH_SHORT).show()
                },
                enabled = syncOnline,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) { Text("同步环境信息", fontSize = 13.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CommandButton("离线城市列表", cmdOnline, Modifier.weight(1f)) { CarCmd.offlineMapList() }
            CommandButton("清地图缓存", cmdOnline, Modifier.weight(1f)) { CarCmd.offlineMapRemoveCache() }
        }
        if (snap.offlineMapSummary.isNotBlank()) {
            InfoLine("离线地图回包", snap.offlineMapSummary.take(120))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = cityToDelete,
                onValueChange = { cityToDelete = it },
                label = { Text("要删除的城市名") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = {
                    val ok = CarCmd.offlineMapDeleteCity(cityToDelete.trim())
                    Toast.makeText(ctx, if (ok) "已请求删除 ${cityToDelete.trim()}" else "车机未连接", Toast.LENGTH_SHORT).show()
                },
                enabled = cmdOnline && cityToDelete.isNotBlank(),
                modifier = Modifier.height(52.dp),
            ) { Text("删城市", fontSize = 13.sp) }
        }
        OutlinedButton(
            onClick = {
                val ok = CarCmd.requestCoredump(1)
                Toast.makeText(ctx, if (ok) "已请求车机 Coredump 日志" else "17818 未连接", Toast.LENGTH_SHORT).show()
            },
            enabled = cmdOnline,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) { Text("请求车机诊断日志（COREDUMP）", fontSize = 13.sp) }
    }

    if (showTireLimit) {
        var barUp by remember { mutableStateOf("2.8") }
        var barDown by remember { mutableStateOf("1.8") }
        var tempUp by remember { mutableStateOf("65") }
        DetailDialog("胎压 / 胎温报警阈值", onClose = { showTireLimit = false }) {
            OutlinedTextField(
                value = barUp,
                onValueChange = { barUp = it },
                label = { Text("高压上限 (bar)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = barDown,
                onValueChange = { barDown = it },
                label = { Text("低压下限 (bar)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tempUp,
                onValueChange = { tempUp = it },
                label = { Text("高温上限 (℃)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val bu = barUp.toDoubleOrNull() ?: 2.8
                    val bd = barDown.toDoubleOrNull() ?: 1.8
                    val tu = tempUp.toIntOrNull() ?: 65
                    val ok = CarCmd.tireSetLimit(0, tireBar, bu * 14.5038, bu, bd * 14.5038, bd, tu)
                    Toast.makeText(ctx, if (ok) "已下发胎压阈值" else "车机未连接", Toast.LENGTH_SHORT).show()
                    showTireLimit = false
                },
                enabled = syncOnline,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存并下发") }
        }
    }

    if (showMore) {
        DetailDialog("更多查询", onClose = { showMore = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CommandButton("SIM ICCID", cmdOnline, Modifier.weight(1f)) {
                    onCommand("SIM", "REQUEST")
                }
                CommandButton("TBOX SN", cmdOnline, Modifier.weight(1f)) {
                    onCommand("TBOX", "get_sn")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CommandButton("主题配对", cmdOnline, Modifier.weight(1f)) {
                    onCommand("THEME", "get_pairinfo")
                }
                CommandButton("自动熄屏", cmdOnline, Modifier.weight(1f)) {
                    onCommand("AUTOOFF", "get_auto_off_status")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CommandButton("导航状态", cmdOnline, Modifier.weight(1f)) {
                    onCommand("ROAD_NAVI", "get_status")
                }
                CommandButton("屏幕选择 0", cmdOnline, Modifier.weight(1f)) {
                    onScreenChoose(0)
                }
            }
            EmptyHint("「屏幕选择」和夜间的具体参数语义还没在真车确认，先留作调试。")
        }
    }

    if (showReply) {
        DetailDialog("最近应答", onClose = { showReply = false }) {
            InfoLine("最近指令", snap.lastCmd.ifBlank { "—" })
            InfoLine("是否回包", if (snap.lastCmdAnswered) "是" else "否/还没")
            val json = snap.lastJson
            if (json.isBlank()) {
                EmptyHint("还没有收到车机 JSON。")
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                json,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SyncSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private val PRESET_NOTIFY_APPS = listOf(
    "com.tencent.mm" to "微信",
    "com.tencent.mobileqq" to "QQ",
    "com.tencent.wework" to "企业微信",
    "com.alibaba.android.rimet" to "钉钉",
    "com.ss.android.lark" to "飞书",
)
