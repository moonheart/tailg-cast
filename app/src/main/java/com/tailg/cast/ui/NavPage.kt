package com.tailg.cast.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tailg.cast.GeocodeHelper
import com.tailg.cast.NavAccessibility
import com.tailg.cast.NavText
import com.tailg.probe.NavAutoState
import com.tailg.probe.NavFavorites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun NavPage(
    mapProvider: String = NavFavorites.MAP_AMAP,
    onSwitchMap: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var favorites by remember { mutableStateOf(NavFavorites.list(ctx)) }
    var activeFavId by remember { mutableStateOf(NavFavorites.activeId(ctx)) }
    var navAuto by remember { mutableStateOf(NavFavorites.isAutoStart(ctx)) }
    var accessibilityOn by remember { mutableStateOf(NavAccessibility.isEnabled(ctx)) }
    var enablingA11y by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityOn = NavAccessibility.isEnabled(ctx)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var editorOpen by remember { mutableStateOf(false) }
    var editingFav by remember { mutableStateOf<NavFavorites.Favorite?>(null) }
    var deleteFav by remember { mutableStateOf<NavFavorites.Favorite?>(null) }
    var showA11yDialog by remember { mutableStateOf(false) }

    var editName by remember { mutableStateOf("") }
    var editLat by remember { mutableStateOf("") }
    var editLon by remember { mutableStateOf("") }
    var editAddr by remember { mutableStateOf("") }
    var editCoordType by remember { mutableStateOf(0) }
    var mapOpen by remember { mutableStateOf(false) }
    var mapLat by remember { mutableStateOf(30.659) }
    var mapLon by remember { mutableStateOf(104.065) }

    fun reloadNav() {
        favorites = NavFavorites.list(ctx)
        activeFavId = NavFavorites.activeId(ctx)
        navAuto = NavFavorites.isAutoStart(ctx)
        accessibilityOn = NavAccessibility.isEnabled(ctx)
    }

    fun tryAutoEnableA11y() {
        if (enablingA11y) return
        enablingA11y = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                NavAccessibility.enableViaPrivileged(ctx)
            }
            accessibilityOn = NavAccessibility.isEnabled(ctx)
            enablingA11y = false
            if (res.ok) {
                Toast.makeText(ctx, "无障碍已通过 ${res.via} 自动开启", Toast.LENGTH_SHORT).show()
            } else {
                showA11yDialog = true
            }
        }
    }

    fun openAddFavorite() {
        editingFav = null
        editName = ""
        editLat = ""
        editLon = ""
        editAddr = ""
        editCoordType = 0
        editorOpen = true
    }

    fun openEditFavorite(f: NavFavorites.Favorite) {
        editingFav = f
        editName = f.name
        editLat = String.format(Locale.US, "%.6f", f.lat)
        editLon = String.format(Locale.US, "%.6f", f.lon)
        editAddr = f.address
        editCoordType = f.dev
        editorOpen = true
    }

    fun saveFavorite() {
        val lat = editLat.trim().toDoubleOrNull()
        val lon = editLon.trim().toDoubleOrNull()
        if (editName.isBlank() || lat == null || lon == null ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            (lat == 0.0 && lon == 0.0)
        ) {
            Toast.makeText(ctx, "填名称和合法经纬度（不能是 0,0）", Toast.LENGTH_SHORT).show()
            return
        }
        val f = NavFavorites.Favorite()
        f.id = editingFav?.id ?: ""
        f.name = editName.trim()
        f.lat = lat
        f.lon = lon
        f.dev = editCoordType
        f.address = editAddr.trim()
        f.createdAt = editingFav?.createdAt ?: 0
        NavFavorites.save(ctx, f)
        reloadNav()
        editorOpen = false
    }

    fun pasteCoordinates() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
        val c = NavText.parseCoordinates(text)
        if (c == null) {
            Toast.makeText(ctx, "剪贴板里没找到经纬度", Toast.LENGTH_SHORT).show()
            return
        }
        editLat = String.format(Locale.US, "%.6f", c.lat)
        editLon = String.format(Locale.US, "%.6f", c.lon)
        Toast.makeText(ctx, "已填入坐标", Toast.LENGTH_SHORT).show()
    }

    fun coordsLabel(f: NavFavorites.Favorite): String =
        String.format(Locale.US, "%.6f, %.6f", f.lat, f.lon) +
            if (f.dev == 1) " · WGS-84" else " · GCJ-02"

    fun geocodeAddress() {
        val q = editAddr.ifBlank { editName }.trim()
        if (q.isBlank()) {
            Toast.makeText(ctx, "先填名称或地址", Toast.LENGTH_SHORT).show()
            return
        }
        GeocodeHelper.resolve(ctx, q) { lat, lon, addr ->
            if (lat.isNaN() || lon.isNaN()) {
                Toast.makeText(ctx, "系统地址解析不可用，请填经纬度或从地图复制", Toast.LENGTH_LONG).show()
            } else {
                editLat = String.format(Locale.US, "%.6f", lat)
                editLon = String.format(Locale.US, "%.6f", lon)
                if (editAddr.isBlank() && !addr.isNullOrBlank()) editAddr = addr
                editCoordType = 1
                Toast.makeText(ctx, "已解析，坐标类型按 WGS-84 填", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openMapPicker() {
        mapLat = editLat.toDoubleOrNull() ?: favorites.firstOrNull()?.lat ?: 30.659
        mapLon = editLon.toDoubleOrNull() ?: favorites.firstOrNull()?.lon ?: 104.065
        mapOpen = true
    }

    val active = favorites.firstOrNull { it.id == activeFavId }

    SectionCard(
        title = "自动导航",
        icon = Icons.Filled.PlayArrow,
        helpText = "支持高德地图和百度地图。投屏启动后自动打开对应骑行/摩托车导航，首次使用需在手机上点一次「同意」。开启无障碍服务后可自动帮你点击「开始导航」。",
    ) {
        Text("导航地图", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(
            options = listOf("高德地图", "百度地图"),
            selected = NavFavorites.labelFor(mapProvider),
        ) { picked ->
            onSwitchMap(if (picked == "百度地图") NavFavorites.MAP_BAIDU else NavFavorites.MAP_AMAP)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("投屏后自动打开导航", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    active?.let { "目的地：${it.name}" } ?: "请先在下方选择默认目的地",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = navAuto,
                onCheckedChange = { on ->
                    navAuto = on
                    NavFavorites.setAutoStart(ctx, on)
                    if (on && !accessibilityOn) tryAutoEnableA11y()
                },
            )
        }
        StatusLine(
            "自动点「开始导航」",
            if (accessibilityOn) "无障碍已开启" else "未开启（只打开路线页）",
            accessibilityOn,
            warn = !accessibilityOn,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!accessibilityOn) {
                FilledTonalButton(
                    onClick = { tryAutoEnableA11y() },
                    enabled = !enablingA11y,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Text(
                        if (enablingA11y) "正在开启…" else "一键开启无障碍",
                        fontSize = 13.sp,
                    )
                }
            }
            if (accessibilityOn && active != null) {
                FilledTonalButton(
                    onClick = {
                        NavAutoState.arm(0, 30000)
                        try {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(active.motorUri(mapProvider)))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    .setPackage(NavFavorites.packageFor(mapProvider)),
                            )
                            Toast.makeText(ctx, "已打开${NavFavorites.shortLabelFor(mapProvider)}路线页，等待自动点击", Toast.LENGTH_SHORT).show()
                        } catch (t: Throwable) {
                            Toast.makeText(ctx, "打不开${NavFavorites.labelFor(mapProvider)}：${t.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) { Text("测试自动点击（手机屏）", fontSize = 13.sp) }
            }
        }
    }

    SectionCard(
        title = "常用目的地",
        icon = Icons.Filled.LocationOn,
        helpText = "高德或百度地图地点详情复制的坐标为 GCJ-02，手机 GPS 原始坐标为 WGS-84。建议直接使用「在地图上选点」。",
        action = {
            IconButton(onClick = { reloadNav() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        },
    ) {
        if (favorites.isEmpty()) {
            EmptyHint("还没有收藏点。点下方「添加收藏点」填名称和经纬度，或直接在地图上选点。")
        } else {
            favorites.forEach { f ->
                val isSelected = f.id == activeFavId
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                activeFavId = f.id
                                NavFavorites.setActive(ctx, f.id)
                            },
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(f.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                f.address.ifBlank { coordsLabel(f) },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { openEditFavorite(f) }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "编辑",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { deleteFav = f }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = { openAddFavorite() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(" 添加收藏点", fontWeight = FontWeight.SemiBold)
        }
    }

    if (editorOpen) {
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text(if (editingFav == null) "添加收藏点" else "编辑收藏点") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SettingField("名称", editName, { editName = it }, "家 / 公司 / 客户")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingField(
                            "纬度", editLat, { editLat = it }, "30.123456",
                            decimal = true, modifier = Modifier.weight(1f),
                        )
                        SettingField(
                            "经度", editLon, { editLon = it }, "104.123456",
                            decimal = true, modifier = Modifier.weight(1f),
                        )
                    }
                    ChipRow(
                        listOf("高德/百度 GCJ-02", "GPS WGS-84"),
                        if (editCoordType == 0) "高德/百度 GCJ-02" else "GPS WGS-84",
                    ) { picked ->
                        editCoordType = if (picked.startsWith("GPS")) 1 else 0
                    }
                    SettingField("备注/地址", editAddr, { editAddr = it }, "可选")
                    Text(
                        "从高德或百度地点详情复制坐标，或点「粘贴坐标」从剪贴板解析。GCJ-02 选高德/百度；手机 GPS 原始坐标选 WGS-84。地址解析依赖系统 geocoder，国产 ROM 可能不可用。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { openMapPicker() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                    ) { Text("在地图上选点") }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { geocodeAddress() }) { Text("地址解析") }
                    TextButton(onClick = { pasteCoordinates() }) { Text("粘贴坐标") }
                    TextButton(onClick = { saveFavorite() }) { Text("保存") }
                }
            },
            dismissButton = {
                TextButton(onClick = { editorOpen = false }) { Text("取消") }
            },
        )
    }

    if (mapOpen) {
        MapPickerDialog(
            initialLat = mapLat,
            initialLon = mapLon,
            onPick = { lat, lon ->
                editLat = String.format(Locale.US, "%.6f", lat)
                editLon = String.format(Locale.US, "%.6f", lon)
                editCoordType = 0
                mapOpen = false
                Toast.makeText(ctx, "已选点，坐标按 GCJ-02 填", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { mapOpen = false },
        )
    }

    deleteFav?.let { f ->
        AlertDialog(
            onDismissRequest = { deleteFav = null },
            title = { Text("删除收藏点") },
            text = { Text("确定删除「${f.name}」？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        NavFavorites.delete(ctx, f.id)
                        reloadNav()
                        deleteFav = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFav = null }) { Text("取消") }
            },
        )
    }

    if (showA11yDialog) {
        AlertDialog(
            onDismissRequest = { showA11yDialog = false },
            title = { Text("开启无障碍服务") },
            text = {
                Text(
                    "未能通过 Root/Shizuku 自动开启（请确认已授予 Root 或 Shizuku 权限），也可以手动前往系统设置开启：\n\n设置 → 无障碍 → 已下载的应用 → 王者骑行助手导航服务。\n\nAndroid 13+ 如果提示受限，去 应用信息 → 允许受限设置。",
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showA11yDialog = false
                        NavAccessibility.openSettings(ctx)
                    },
                ) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showA11yDialog = false }) { Text("以后再说") }
            },
        )
    }
}
