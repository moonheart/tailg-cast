package com.tailg.cast.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailg.cast.RideDay
import com.tailg.cast.RideStats
import com.tailg.cast.RideSummary
import com.tailg.cast.RideTrip
import com.tailg.cast.TrackPoint
import com.tailg.cast.fmtDuration
import com.tailg.cast.fmtKm
import com.tailg.cast.fmtSpeed
import com.tailg.cast.monthKey
import com.tailg.cast.timeShort
import com.tailg.cast.todayKey
import com.tailg.cast.weekKey
import com.tailg.probe.CloudApi
import com.tailg.probe.Config
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val HeroBlue = Color(0xFF1D4ED8)
private val HeroSky = Color(0xFF0EA5E9)

/**
 * 骑行统计 + 历史轨迹。
 *
 * 统计走官方 getRidingDetail（日/周/月）；历史轨迹走 deviceTravel（按月）。
 * 点某一段再取 deviceTravelDetail 的坐标点，弹窗里用 Leaflet 画线。
 */
@Composable
fun RidePage(
    snap: AppSnapshot,
    onOpenSettings: () -> Unit,
    onOpenVehicle: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by rememberSaveable { mutableStateOf("days") }
    var summary by remember { mutableStateOf<RideSummary?>(null) }
    var summaryBusy by remember { mutableStateOf(false) }
    var summaryNote by remember { mutableStateOf("") }
    var month by rememberSaveable { mutableStateOf(monthKey()) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var pickerYear by remember { mutableStateOf(month.take(4).toIntOrNull() ?: 2026) }
    var days by remember { mutableStateOf<List<RideDay>>(emptyList()) }
    var travelBusy by remember { mutableStateOf(false) }
    var travelNote by remember { mutableStateOf("") }
    var identityReady by remember { mutableStateOf(false) }
    var identityBusy by remember { mutableStateOf(false) }
    var identityNote by remember { mutableStateOf("") }
    var detailTrip by remember { mutableStateOf<RideTrip?>(null) }
    var detailPoints by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var detailBusy by remember { mutableStateOf(false) }
    var detailNote by remember { mutableStateOf("") }

    fun loadSummary() {
        if (summaryBusy) return
        val c = Config.load(ctx)
        if (c.carFrame.isBlank()) return
        summaryBusy = true
        summaryNote = ""
        CloudApi.rideDetail(ctx, model, keyForModel(model)) { ok, text ->
            scope.launch {
                summaryBusy = false
                val parsed = if (ok) RideStats.parseSummary(text, model) else null
                if (parsed != null) {
                    summary = parsed
                    summaryNote = ""
                } else {
                    summaryNote = cloudError("统计", ok, text)
                }
            }
        }
    }

    fun loadMonth() {
        if (travelBusy) return
        travelBusy = true
        travelNote = ""
        CloudApi.travelMonth(ctx, month) { ok, text ->
            scope.launch {
                travelBusy = false
                val parsed = if (ok) RideStats.parseDays(text) else null
                if (parsed != null) {
                    days = parsed
                    travelNote = ""
                } else {
                    travelNote = cloudError("轨迹", ok, text)
                }
            }
        }
    }

    fun openTrip(trip: RideTrip) {
        detailTrip = trip
        detailPoints = emptyList()
        detailNote = ""
        detailBusy = true
        CloudApi.travelDetail(ctx, trip.id) { ok, text ->
            scope.launch {
                detailBusy = false
                val parsed = if (ok) RideStats.parsePoints(text) else null
                if (parsed != null) {
                    detailPoints = parsed
                } else {
                    detailNote = cloudError("轨迹点", ok, text)
                }
            }
        }
    }

    LaunchedEffect(snap.cfg.cloudTokenSet, snap.cfg.cloudTokenAt) {
        if (!snap.cfg.cloudTokenSet) {
            identityReady = false
            return@LaunchedEffect
        }
        val c = Config.load(ctx)
        if (c.carFrame.isNotBlank() && c.cloudUid.isNotBlank()) {
            identityReady = true
            identityNote = ""
            return@LaunchedEffect
        }
        identityBusy = true
        identityNote = ""
        CloudApi.carStatus(ctx) { ok, text ->
            scope.launch {
                identityBusy = false
                val c2 = Config.load(ctx)
                if (ok && c2.carFrame.isNotBlank() && c2.cloudUid.isNotBlank()) {
                    identityReady = true
                    identityNote = ""
                } else {
                    identityReady = false
                    identityNote = if (ok) {
                        "云端没有返回车架号/账号 uid，先到「车辆」页刷新一次状态。"
                    } else {
                        cloudError("车辆信息", false, text)
                    }
                }
            }
        }
    }

    LaunchedEffect(identityReady, model) {
        if (identityReady) loadSummary()
    }
    LaunchedEffect(identityReady, month) {
        if (identityReady) loadMonth()
    }

    if (!snap.cfg.cloudTokenSet) {
        SectionCard("骑行统计", "登录后展示云端记录的骑行数据。") {
            EmptyHint("还没有登录台铃账号。登录后能看今日/本周/本月统计，以及每个月的轨迹。")
            Button(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) { Text("去设置登录", fontWeight = FontWeight.SemiBold) }
        }
        return
    }

    val currentMonth = monthKey()
    val currentYear = currentMonth.take(4).toIntOrNull() ?: 2026
    val currentMonthNum = currentMonth.substring(5).toIntOrNull() ?: 1
    val monthDays = days.sumOf { it.trips.size }
    val monthKm = days.sumOf { it.mileageM }

    if (identityBusy || identityNote.isNotBlank()) {
        SectionCard("骑行数据", "先从车辆状态里取车架号和账号 uid。") {
            if (identityBusy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在读取车辆信息…", fontSize = 13.sp)
                }
            } else {
                Text(
                    identityNote,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = onOpenVehicle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) { Text("去车辆页刷新") }
            }
        }
    }

    if (summary != null || summaryBusy || summaryNote.isNotBlank()) {
        RideHero(
            summary = summary,
            model = model,
            busy = summaryBusy && summary == null,
            note = summaryNote,
            onModel = { next ->
                if (next != model) {
                    model = next
                    summary = null
                    summaryNote = ""
                }
            },
            onRetry = { loadSummary() },
        )
    }

    SectionCard(
        "历史轨迹",
        "按月份查看，点开某一段看地图轨迹。",
        action = {
            TextButton(
                onClick = {
                    loadSummary()
                    loadMonth()
                },
            ) { Text("刷新", fontSize = 13.sp) }
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    days = emptyList()
                    travelNote = ""
                    month = shiftMonth(month, -1)
                },
                modifier = Modifier.width(44.dp),
            ) { Text("‹", fontSize = 24.sp) }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        pickerYear = month.take(4).toIntOrNull() ?: 2026
                        showMonthPicker = true
                    }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        monthLabel(month),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        " ▾",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        travelBusy && days.isEmpty() -> "加载中…"
                        days.isEmpty() -> "暂无轨迹"
                        else -> "${days.size} 天 · $monthDays 段 · ${fmtKm(monthKm)}"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    days = emptyList()
                    travelNote = ""
                    month = shiftMonth(month, 1)
                },
                enabled = month < currentMonth,
                modifier = Modifier.width(44.dp),
            ) { Text("›", fontSize = 24.sp) }
        }

        if (travelBusy && days.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "  正在读取 ${monthLabel(month)} 的轨迹…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (days.isEmpty()) {
            EmptyState()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                days.forEach { day ->
                    DayBlock(day = day, onTrip = ::openTrip)
                }
            }
        }

        if (travelNote.isNotBlank()) {
            Text(
                travelNote,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (showMonthPicker) {
        val months = if (pickerYear == currentYear) {
            (1..currentMonthNum).toList()
        } else {
            (1..12).toList()
        }
        val selectedMonth = if (month.startsWith("$pickerYear-")) {
            month.substring(5).toIntOrNull()?.let { "$it 月" }
        } else {
            null
        }
        AlertDialog(
            onDismissRequest = { showMonthPicker = false },
            title = { Text("选择月份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (pickerYear > 2020) pickerYear -= 1 },
                            enabled = pickerYear > 2020,
                        ) { Text("‹", fontSize = 24.sp) }
                        Text(
                            "$pickerYear 年",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            onClick = { if (pickerYear < currentYear) pickerYear += 1 },
                            enabled = pickerYear < currentYear,
                        ) { Text("›", fontSize = 24.sp) }
                    }
                    ChipGrid(
                        options = months.map { "$it 月" },
                        selected = selectedMonth,
                        columns = 3,
                    ) { picked ->
                        val m = picked.substringBefore(" ").toIntOrNull() ?: return@ChipGrid
                        month = String.format(Locale.US, "%04d-%02d", pickerYear, m)
                        days = emptyList()
                        travelNote = ""
                        showMonthPicker = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMonthPicker = false }) { Text("取消") }
            },
        )
    }

    detailTrip?.let { trip ->
        TrackMapDialog(
            trip = trip,
            points = detailPoints,
            busy = detailBusy,
            error = detailNote,
            onDismiss = {
                detailTrip = null
                detailPoints = emptyList()
                detailNote = ""
            },
        )
    }
}

@Composable
private fun RideHero(
    summary: RideSummary?,
    model: String,
    busy: Boolean,
    note: String,
    onModel: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(HeroBlue, HeroSky)))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "骑行概览",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        when (model) {
                            "weeks" -> "本周 · 云端统计"
                            "months" -> "本月 · 云端统计"
                            else -> "今日 · 云端统计"
                        },
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
                PeriodSwitch(model, onModel)
            }

            if (busy || summary == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        if (note.isBlank()) "正在读取统计数据…" else note,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.86f),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format(Locale.US, "%.1f", summary.mileageKm),
                        fontSize = 46.sp,
                        lineHeight = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        "km",
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${summary.rideCount} 次骑行",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                        Text(
                            fmtDuration(summary.ridingMinutes * 60),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.18f)),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroMetric(
                        "平均速度",
                        fmtSpeed(summary.avgSpeed),
                        "km/h",
                        Modifier.weight(1f),
                    )
                    HeroMetric(
                        "最高速度",
                        fmtSpeed(summary.maxSpeed),
                        "km/h",
                        Modifier.weight(1f),
                    )
                    HeroMetric(
                        "累计里程",
                        fmtKm(summary.totalMileageM).removeSuffix(" km"),
                        "km",
                        Modifier.weight(1f),
                    )
                }

                if (summary.carbonSaving > 0) {
                    Text(
                        "减排 ${String.format(Locale.US, "%.1f", summary.carbonSaving)} kg" +
                            " · 相当于 ${String.format(Locale.US, "%.1f", summary.carbonTrees)} 棵树",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }

            if (note.isNotBlank() && summary != null) {
                Text(
                    note,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.86f),
                )
                TextButton(
                    onClick = onRetry,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Color.White,
                    ),
                ) { Text("重试", fontSize = 12.sp) }
            } else if (note.isNotBlank() && !busy) {
                TextButton(
                    onClick = onRetry,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = Color.White,
                    ),
                ) { Text("重试", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.68f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                unit,
                modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun PeriodSwitch(selected: String, onChange: (String) -> Unit) {
    val options = listOf("days" to "今日", "weeks" to "本周", "months" to "本月")
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (active) Color.White else Color.Transparent)
                    .clickable { onChange(key) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) HeroBlue else Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

@Composable
private fun DayBlock(day: RideDay, onTrip: (RideTrip) -> Unit) {
    val dayNumber = day.date.takeLast(2)
    val weekday = weekdayText(day.date)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.width(44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    dayNumber,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                weekday,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    monthDay(day.date),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${fmtKm(day.mileageM)} · ${fmtDuration(day.durationSec)}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            day.trips.forEach { trip ->
                TripRow(trip) { onTrip(trip) }
            }
        }
    }
}

@Composable
private fun TripRow(trip: RideTrip, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(HeroBlue.copy(alpha = 0.75f)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        timeShort(trip.startTime),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        " → ",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(timeShort(trip.endTime), fontSize = 15.sp)
                }
                Text(
                    "${fmtKm(trip.mileageM)} · ${fmtDuration(trip.durationSec)}" +
                        " · 均速 ${fmtSpeed(trip.avgSpeed)} km/h",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "›",
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.DateRange,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "这个月还没有轨迹",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "车机在线的骑行会自动记录到云端",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun keyForModel(model: String): String = when (model) {
    "weeks" -> weekKey()
    "months" -> monthKey().replace("-", "")
    else -> todayKey()
}

private fun shiftMonth(key: String, delta: Int): String {
    return try {
        val fmt = SimpleDateFormat("yyyy-MM", Locale.US)
        val cal = Calendar.getInstance()
        cal.time = fmt.parse(key) ?: Date()
        cal.add(Calendar.MONTH, delta)
        fmt.format(cal.time)
    } catch (_: Throwable) {
        monthKey()
    }
}

private fun monthLabel(key: String): String = try {
    val fmt = SimpleDateFormat("yyyy-MM", Locale.US)
    val out = SimpleDateFormat("yyyy 年 M 月", Locale.CHINA)
    out.format(fmt.parse(key) ?: Date())
} catch (_: Throwable) {
    key
}

private fun monthDay(date: String): String = try {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val out = SimpleDateFormat("M月d日", Locale.CHINA)
    out.format(fmt.parse(date) ?: Date())
} catch (_: Throwable) {
    date
}

private fun weekdayText(date: String): String = try {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val out = SimpleDateFormat("EEE", Locale.CHINA)
    out.format(fmt.parse(date) ?: Date())
} catch (_: Throwable) {
    ""
}

private fun cloudError(what: String, ok: Boolean, text: String): String {
    val t = text.take(300)
    return when {
        t.contains("401") || t.contains("过期") || t.contains("登录") ->
            "登录已过期，请重新登录。"
        ok -> "$what 解析失败：$t"
        else -> "$what 请求失败：$t"
    }
}
