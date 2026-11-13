package com.tailg.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailg.cast.ChargeState
import com.tailg.cast.GeocodeHelper
import com.tailg.cast.VehicleStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehiclePage(
    snap: AppSnapshot,
    status: VehicleStatus?,
    cloudNote: String,
    cloudBusy: Boolean,
    cloudExpired: Boolean,
    onRefresh: () -> Unit,
    onTire: () -> Unit,
    onRemote: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCar: () -> Unit,
    onOpenTheme: () -> Unit,
    onLogin: () -> Unit,
) {
    var showArchive by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showBattery by remember { mutableStateOf(false) }

    if (!snap.cfg.cloudTokenSet) {
        SectionCard(
            title = "云端车辆状态",
            subtitle = "登录后自动展示电量、续航、位置、设备信息和全部车型参数。",
            icon = Icons.Filled.LocationOn,
        ) {
            EmptyHint("还没有配置云端 token。可以直接手机号登录，或去设置里手动粘贴 token。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("去设置") }
                Button(
                    onClick = onLogin,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) { Text("手机号登录", fontWeight = FontWeight.SemiBold) }
            }
        }
        LocalCarQuickCard(snap = snap, onOpenCar = onOpenCar, onOpenTheme = onOpenTheme)
        return
    }

    if (cloudExpired) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "云端登录已过期",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "云端 v1 token 没有刷新接口，401 后只能重新登录。车机投屏/BLE 不受影响。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Button(
                    onClick = onLogin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) { Text("重新登录") }
            }
        }
    }

    if (status == null) {
        SectionCard("车辆状态", "进入页面会自动拉取，不需要先点刷新。") {
            if (cloudBusy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在读取车辆状态…", fontSize = 13.sp)
                }
            } else {
                if (cloudNote.isNotBlank()) {
                    NoteBox(cloudNote, error = cloudNote.isErrorNote())
                } else {
                    EmptyHint("还没有车辆数据。车辆需要 4G 在线。")
                }
                Button(
                    onClick = onRefresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) { Text("重新获取") }
            }
        }
        return
    }

    val batItems = remember(status) { batteryItems(status) }

    VehicleHeader(
        s = status,
        totalMileage = snap.carMileage,
        busy = cloudBusy,
        onRefresh = onRefresh,
        onVoltageClick = if (batItems.isNotEmpty()) ({ showBattery = true }) else null,
    )
    if (cloudNote.isNotBlank() && cloudNote.isErrorNote()) {
        NoteBox(cloudNote, error = true)
    }
    VehicleRemote(
        busy = cloudBusy,
        note = cloudNote,
        onRemote = onRemote,
        onTire = onTire,
    )
    LocalCarQuickCard(snap = snap, onOpenCar = onOpenCar, onOpenTheme = onOpenTheme)
    VehiclePosition(status)

    SectionCard("车辆信息", icon = Icons.Filled.Info) {
        ActionRow(
            "车辆档案与配置",
            listOfNotNull(
                status.model.takeIf { it.isNotBlank() },
                status.frame.takeIf { it.isNotBlank() },
                if (status.features.isNotEmpty()) "智能功能 ${status.features.count { it.supported }} 项" else null,
            ).joinToString(" · ").ifBlank { "车型 / 车架号 / 智能功能" },
        ) { showArchive = true }
        ActionRow(
            "账号与诊断",
            "登录状态与原始车况数据",
        ) { showDiagnostics = true }
    }

    if (showArchive) {
        DetailDialog("车辆档案与配置", onClose = { showArchive = false }) {
            val profile = profileItems(status)
            if (profile.isNotEmpty()) {
                Text("车辆档案", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                InfoGrid(profile)
            }
            val device = deviceItems(status)
            if (device.isNotEmpty()) {
                Text("设备与网络", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                InfoGrid(device)
            }
            if (status.features.isNotEmpty()) {
                Text("智能功能", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val supported = status.features.filter { it.supported }
                val missing = status.features.filterNot { it.supported }
                if (supported.isNotEmpty()) {
                    Text(
                        "已支持",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        supported.forEach { StatusPill(it.name, PillTone.Ok) }
                    }
                }
                if (missing.isNotEmpty()) {
                    Text(
                        "未配备",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        missing.forEach { StatusPill(it.name, PillTone.Neutral) }
                    }
                }
            }
        }
    }

    if (showBattery && batItems.isNotEmpty()) {
        DetailDialog("电池详情", onClose = { showBattery = false }) {
            InfoGrid(batItems)
        }
    }

    if (showDiagnostics) {
        var showRaw by remember { mutableStateOf(false) }
        DetailDialog("云端与诊断", onClose = { showDiagnostics = false }) {
            VehicleDiagnosticsContent(
                s = status,
                snap = snap,
                showRaw = showRaw,
                onToggleRaw = { showRaw = !showRaw },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleHeader(
    s: VehicleStatus,
    totalMileage: Int,
    busy: Boolean,
    onRefresh: () -> Unit,
    onVoltageClick: (() -> Unit)? = null,
) {
    SectionCard(
        title = s.nickname.ifBlank { s.model.ifBlank { "车辆状态" } },
        subtitle = listOfNotNull(
            s.model.takeIf { it.isNotBlank() && it != s.nickname },
            "更新于 ${clockText(s.updatedAt)}",
        ).joinToString(" · "),
        action = {
            TextButton(onClick = onRefresh, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("刷新", fontSize = 13.sp)
                }
            }
        },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            s.online?.let {
                StatusPill(if (it) "在线" else "离线", if (it) PillTone.Ok else PillTone.Bad)
            }
            s.locked?.let {
                StatusPill(if (it) "已设防" else "未设防", if (it) PillTone.Ok else PillTone.Warn)
            }
            s.accOn?.let {
                StatusPill(if (it) "ACC 开" else "ACC 关", if (it) PillTone.Info else PillTone.Neutral)
            }
            if (s.charge != ChargeState.UNKNOWN) {
                StatusPill(s.charge.label, chargeTone(s.charge))
            }
        }

        val battery = s.battery
        val batteryColor = when {
            battery == null -> MutedDot
            battery <= 20 -> BadRed
            battery <= 50 -> WarnAmber
            else -> OkGreen
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (battery == null) "—" else "$battery",
                fontSize = 44.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Bold,
                color = batteryColor,
            )
            Text(
                "%",
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("续航 ${kmText(s.rangeKm)}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "满电 ${kmText(s.maxRangeKm)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LinearProgressIndicator(
            progress = { (battery ?: 0).coerceIn(0, 100) / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50)),
            color = batteryColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "电池电压",
                s.voltage?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                if (s.voltage == null) "" else "V",
                Modifier.weight(1f),
                onClick = onVoltageClick,
            )
            StatTile(
                "仪表总里程",
                if (totalMileage >= 0) "$totalMileage" else "待连车机",
                if (totalMileage >= 0) "km" else "",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VehiclePosition(s: VehicleStatus) {
    val ctx = LocalContext.current
    var showMap by remember { mutableStateOf(false) }
    val lat = s.latitude
    val lon = s.longitude
    val hasCoord = lat != null && lon != null
    var resolvedAddress by remember(lat, lon) { mutableStateOf<String?>(null) }
    var resolving by remember(lat, lon) { mutableStateOf(false) }

    LaunchedEffect(lat, lon, s.address) {
        if (s.address.isBlank() && lat != null && lon != null && resolvedAddress == null) {
            resolving = true
            GeocodeHelper.reverse(ctx, lat, lon) { addr ->
                resolvedAddress = addr
                resolving = false
            }
        }
    }

    val displayAddress = s.address.ifBlank { resolvedAddress.orEmpty() }

    SectionCard(
        title = "车辆位置",
        icon = Icons.Filled.LocationOn,
        helpText = "显示车辆最近一次 4G 上报的 GPS 定位；若接口未返回文字地址会自动按坐标逆地理编码，点击地图可放大查看并拉起高德或百度步行导航寻车。",
    ) {
        if (s.address.isBlank() && !hasCoord) {
            EmptyHint("暂无定位信息，车辆可能处于休眠状态。")
        } else {
            if (hasCoord) {
                VehicleMiniMap(
                    lat = lat,
                    lon = lon,
                    onExpand = { showMap = true },
                )
            }
            when {
                displayAddress.isNotBlank() -> {
                    Text(displayAddress, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                }
                resolving -> {
                    EmptyHint("正在解析地址…")
                }
                else -> {
                    EmptyHint("暂无文字地址")
                }
            }
            if (hasCoord) {
                InfoLine(
                    "定位坐标",
                    String.format(Locale.US, "%.6f, %.6f", lat, lon),
                )
            }
            if (s.gpsTime.isNotBlank()) InfoLine("更新时间", s.gpsTime)
        }
    }

    if (showMap && hasCoord) {
        VehicleMapDialog(
            status = s,
            resolvedAddress = resolvedAddress,
            onDismiss = { showMap = false },
        )
    }
}

private fun profileItems(s: VehicleStatus): List<Pair<String, String>> = listOf(
    "车型" to s.model,
    "车型代码" to s.modelCode,
    "车型编号" to s.modelType,
    "车架号" to s.frame,
    "整车编码" to s.wholeCode,
    "车辆 ID" to s.carId,
    "设备类型" to s.deviceType,
    "屏幕尺寸" to s.screenSize,
    "屏幕类型" to s.screenType,
    "购买时间" to s.buyTime,
    "经销商" to s.dealer,
).filter { it.second.isNotBlank() }

private fun deviceItems(s: VehicleStatus): List<Pair<String, String>> {
    val mqServer = if (s.mqHost.isBlank()) {
        ""
    } else if (s.mqPort.isBlank()) {
        s.mqHost
    } else {
        "${s.mqHost}:${s.mqPort}"
    }
    return listOf(
        "IMEI" to s.imei,
        "IMEI（GPS）" to s.imeiGps.takeIf { it != s.imei }.orEmpty(),
        "ICCID" to s.iccid,
        "Wi-Fi MAC" to s.wifiMac,
        "蓝牙名称" to s.btName,
        "蓝牙 MAC" to s.btMac,
        "SIM 状态" to simText(s.simStatus),
        "SIM 号码" to s.simNo,
        "SIM 类型" to s.simType,
        "运营商业务" to s.simBusiness,
        "网络认证" to netAuthText(s.netAuth),
        "MQTT 服务器" to mqServer,
    ).filter { it.second.isNotBlank() }
}

private fun batteryItems(s: VehicleStatus): List<Pair<String, String>> = listOf(
    "电池类型" to s.batteryType,
    "电池规格" to s.batterySpec,
    "电池循环次数" to cyclesText(s.batteryCycles),
    "电池电流" to currentText(s.batteryCurrent),
    "电池温度" to tempText(s.batteryTemperature),
    "BMS SOC" to percentText(s.batterySoc),
    "SOH" to percentText(s.batterySoh),
    "电池容量" to ahText(s.batteryCapacityAh),
    "额定电压" to voltText(s.batteryNominalVoltage),
    "绑定日期" to s.batteryBindDate,
    "充电状态" to if (s.charge == ChargeState.UNKNOWN) "" else s.charge.label,
).filter { it.second.isNotBlank() }

@Composable
private fun LocalCarQuickCard(
    snap: AppSnapshot,
    onOpenCar: () -> Unit,
    onOpenTheme: () -> Unit,
) {
    val online = snap.carConnected || snap.bleConnected
    SectionCard(
        title = "仪表互联",
        icon = Icons.Filled.Build,
        helpText = "手机连上车机热点或蓝牙后，可同步仪表总里程、切换日夜模式、结束车机导航以及更换仪表盘主题。",
    ) {
        if (online) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("仪表电量", if (snap.carBattery >= 0) "${snap.carBattery}" else "—", if (snap.carBattery >= 0) "%" else "", Modifier.weight(1f))
                StatTile("仪表续航", if (snap.carEndurance >= 0) "${snap.carEndurance}" else "—", if (snap.carEndurance >= 0) "km" else "", Modifier.weight(1f))
            }
        }
        ActionRow(
            title = "仪表控制",
            subtitle = "实时车况、日夜模式、结束导航",
            badge = when {
                snap.carConnected -> "Wi-Fi 已连"
                snap.bleConnected -> "蓝牙已连"
                else -> "未连接"
            },
        ) { onOpenCar() }
        ActionRow(
            title = "仪表主题与壁纸",
            subtitle = "更换仪表盘皮肤",
        ) { onOpenTheme() }
    }
}

@Composable
private fun VehicleRemote(
    busy: Boolean,
    note: String,
    onRemote: (String) -> Unit,
    onTire: () -> Unit,
) {
    SectionCard(
        title = "常用控车",
        icon = Icons.Filled.Lock,
        helpText = "远控指令通过车辆 4G 网络下发；部分车型需打开电门（ACC）才响应开座桶或胎压查询。",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTileButton("一键解锁", Icons.Filled.Check, !busy, Modifier.weight(1f), PillTone.Ok) { onRemote("unlock") }
            QuickTileButton("设防锁车", Icons.Filled.Lock, !busy, Modifier.weight(1f), PillTone.Warn) { onRemote("lock") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTileButton("一键寻车", Icons.Filled.Search, !busy, Modifier.weight(1f), PillTone.Info) { onRemote("search") }
            QuickTileButton("打开座桶", Icons.Filled.Star, !busy, Modifier.weight(1f), PillTone.Info) { onRemote("openCushion") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickTileButton("刷新车况", Icons.Filled.Refresh, !busy, Modifier.weight(1f), PillTone.Neutral) { onRemote("status") }
            QuickTileButton("查看胎压", Icons.Filled.Info, !busy, Modifier.weight(1f), PillTone.Neutral, onTire)
        }
        if (note.isNotBlank() && !note.isErrorNote()) {
            NoteBox(formatRemoteNote(note), error = false, mono = false)
        }
    }
}

private fun formatRemoteNote(raw: String): String {
    val idx = raw.indexOf('{')
    if (idx < 0) return raw
    return try {
        val obj = org.json.JSONObject(raw.substring(idx))
        val msg = obj.optString("msg").ifBlank { obj.optString("message") }
        val code = obj.optInt("code", -1)
        val prefix = raw.substring(0, idx).trim().removeSuffix("：").removeSuffix(":")
        when {
            msg.isNotBlank() && prefix.isNotBlank() -> "$prefix：$msg"
            msg.isNotBlank() -> msg
            code == 200 || code == 0 -> "$prefix：指令已下发"
            else -> raw
        }
    } catch (_: Throwable) {
        raw
    }
}

@Composable
private fun VehicleDiagnosticsContent(
    s: VehicleStatus,
    snap: AppSnapshot,
    showRaw: Boolean,
    onToggleRaw: () -> Unit,
) {
    val ageDays = if (snap.cfg.cloudTokenAt > 0) {
        ((System.currentTimeMillis() - snap.cfg.cloudTokenAt) / 86_400_000L).toInt()
    } else {
        -1
    }
    InfoLine("登录时间", formatTokenTime(snap.cfg.cloudTokenAt))
    InfoLine("已用", if (ageDays >= 0) "$ageDays 天" else "未知（旧 token）")
    InfoLine("车机 IMEI", snap.cfg.carImei.ifBlank { s.imei.ifBlank { "—" } })
    if (ageDays >= 25) {
        Text(
            "token 已用 $ageDays 天，原厂接口约 30 天有效；可能很快要重新登录。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (s.extras.isNotEmpty()) {
        Text("其他参数", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        InfoGrid(s.extras.map { it.label to it.value })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "原始 JSON",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleRaw) {
            Text(if (showRaw) "收起" else "查看", fontSize = 13.sp)
        }
    }
    if (showRaw) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    s.raw,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RemoteButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
    ) { Text(label) }
}

@Composable
private fun NoteBox(text: String, error: Boolean, mono: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (mono && text.length > 1200) text.take(1200) + "…" else text,
            modifier = Modifier.padding(12.dp),
            fontSize = if (mono) 10.sp else 12.sp,
            lineHeight = if (mono) 15.sp else 18.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = if (error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun String.isErrorNote(): Boolean =
    startsWith("失败") || contains("过期") || contains("无法解析") || contains("HTTP 4")

private fun chargeTone(state: ChargeState): PillTone = when (state) {
    ChargeState.CHARGING -> PillTone.Ok
    ChargeState.COMPLETE -> PillTone.Info
    ChargeState.NOT_CHARGING -> PillTone.Neutral
    ChargeState.ILLEGAL -> PillTone.Bad
    ChargeState.PROTECTED -> PillTone.Warn
    ChargeState.UNKNOWN -> PillTone.Neutral
}

private fun simText(value: Boolean?): String = when (value) {
    true -> "正常"
    false -> "异常"
    null -> ""
}

private fun netAuthText(value: String): String = when (value.lowercase(Locale.US)) {
    "1", "true", "yes" -> "已认证"
    "0", "false", "no" -> "未认证"
    else -> value
}

private fun kmText(value: Double?): String = when {
    value == null -> "—"
    value % 1.0 == 0.0 -> "${value.toInt()} km"
    else -> String.format(Locale.US, "%.1f km", value)
}

private fun cyclesText(value: String): String {
    if (value.isBlank()) return ""
    return if (value.toIntOrNull() != null) "$value 次" else value
}

private fun currentText(value: Double?): String =
    value?.let { String.format(Locale.US, "%.1f A", it) } ?: ""

private fun tempText(value: Double?): String =
    value?.let { "${it.toInt()} ℃" } ?: ""

private fun percentText(value: Double?): String =
    value?.let { "${it.toInt()}%" } ?: ""

private fun ahText(value: Double?): String = value?.let {
    if (it % 1.0 == 0.0) "${it.toInt()} Ah" else String.format(Locale.US, "%.1f Ah", it)
} ?: ""

private fun voltText(value: Double?): String = value?.let {
    if (it % 1.0 == 0.0) "${it.toInt()} V" else String.format(Locale.US, "%.1f V", it)
} ?: ""

private fun clockText(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

private fun formatTokenTime(ts: Long): String {
    if (ts <= 0) return "未知"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(ts))
}
