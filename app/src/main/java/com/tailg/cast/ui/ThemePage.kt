package com.tailg.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailg.cast.theme.ThemeUploadMode
import com.tailg.protocol.ThemePackageInfo

data class ThemeUiState(
    val running: Boolean = false,
    val status: String = "未开始",
    val progress: Float = 0f,
    val log: List<String> = emptyList(),
    val done: Boolean = false,
    val error: String = "",
)

@Composable
fun ThemePage(
    pkg: ThemePackageInfo?,
    mode: ThemeUploadMode,
    state: ThemeUiState,
    busy: Boolean,
    onModeChange: (ThemeUploadMode) -> Unit,
    onPick: () -> Unit,
    onBuildWallpaper: () -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit,
    onRestartService: () -> Unit,
    onCheckAndClean: () -> Unit,
    onBuildTinyWallpaper: () -> Unit,
    onOverwriteThemeStaging: () -> Unit,
    onOverwriteWallpaperStaging: () -> Unit,
    onCleanNaviCache: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }

    SectionCard(
        title = "仪表主题与壁纸",
        helpText = "请选择上传类型（仪表主题 THEME / 仪表壁纸 WALLPAPER）和外层 zip 包（内含 config.ini 与 package.zip）。上传期间会先停止投屏，建议提前备份原厂资源。",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeUploadMode.entries.forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { onModeChange(item) },
                    enabled = !state.running && !busy,
                    label = { Text("${item.label} (${item.func})") },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Button(
            onClick = onPick,
            enabled = !state.running && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) { Text(if (pkg == null) "选择${mode.label} zip" else "重新选择") }

        if (mode == ThemeUploadMode.WALLPAPER) {
            OutlinedButton(
                onClick = onBuildWallpaper,
                enabled = !state.running && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            ) { Text("从图片 / 视频一键制作壁纸包") }
        }

        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(22.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        if (pkg == null) {
            EmptyHint("还没有选择主题包。只支持普通 zip；加密主题包请先用原工具导出。")
        } else {
            InfoLine("名称", pkg.customName)
            InfoLine(
                "大小 / 校验",
                "${"%.2f".format(pkg.packageSize / 1024.0 / 1024.0)} MB · " +
                    if (pkg.md5Matched) "MD5 匹配" else "MD5 不匹配",
            )
            ActionRow("主题包详情", "ThemeType / StartVersion / 文件信息") {
                showDetails = true
            }
        }

        InfoLine("上传状态", state.status)
        if (state.running || state.progress > 0f) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${(state.progress * 100).toInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.running) {
            FilledTonalButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) { Text("停止上传") }
        } else {
            Button(
                onClick = { confirmUpload = true },
                enabled = pkg != null && !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) { Text("开始上传", fontWeight = FontWeight.SemiBold) }
        }

        if (state.done) {
            Button(
                onClick = onRestartService,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            ) { Text("重新启动监听") }
        }

        if (state.error.isNotBlank()) {
            Text(
                state.error,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "上传前确认",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "请确认主题适配当前车型，上传期间会暂停投屏。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    SectionCard(
        title = "空间诊断与急救",
        helpText = "如果提示存储空间不足（官方安装要求剩余空间 > 54.83 MB），可依次尝试以下 5 种清理/覆盖方式：",
    ) {
        OutlinedButton(
            onClick = onCheckAndClean,
            enabled = !state.running && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) { Text("1. 查询空间/分区 & 清理残留 (act=5/6)") }

        OutlinedButton(
            onClick = onOverwriteThemeStaging,
            enabled = !state.running && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) { Text("2. 覆盖清理 THEME 暂存区 (不安装)") }

        OutlinedButton(
            onClick = onOverwriteWallpaperStaging,
            enabled = !state.running && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) { Text("3. 覆盖清理 WALLPAPER 暂存区 (不安装)") }

        OutlinedButton(
            onClick = onCleanNaviCache,
            enabled = !state.running && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) { Text("4. 清理车机导航缓存 & 查离线地图") }

        OutlinedButton(
            onClick = onBuildTinyWallpaper,
            enabled = !state.running && !busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) { Text("5. 生成极小占位壁纸包 (~1KB 上传覆盖)") }
    }

    ActionRow(
        "上传日志",
        "${state.log.size} 条 · ${state.status}",
    ) { showLog = true }

    if (showDetails && pkg != null) {
        DetailDialog("主题包详情", onClose = { showDetails = false }) {
            InfoLine("名称", pkg.customName)
            InfoLine("ThemeName", pkg.themeName.ifBlank { "—" })
            InfoLine("ThemeType", pkg.themeType.ifBlank { "—" })
            InfoLine("StartVersion", pkg.startVersion.ifBlank { "—" })
            InfoLine(
                "作者/版本",
                listOf(pkg.customAuthor, pkg.customVersion)
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                    .ifBlank { "—" },
            )
            InfoLine("package", pkg.packageName)
            InfoLine("大小", "${"%.2f".format(pkg.packageSize / 1024.0 / 1024.0)} MB")
            InfoLine("MD5", if (pkg.md5Matched) "匹配" else "不匹配（仍会发送，车机可能拒绝）")
            if (pkg.packageName != "package.zip") {
                Text(
                    "注意：内层文件名不是 package.zip，车机可能只认 package.zip。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showLog) {
        DetailDialog("上传日志", onClose = { showLog = false }) {
            if (state.log.isEmpty()) {
                EmptyHint("还没有日志。")
            } else {
                state.log.takeLast(60).forEach { line ->
                    Text(
                        line,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmUpload && pkg != null) {
        AlertDialog(
            onDismissRequest = { confirmUpload = false },
            title = { Text("确认上传${mode.label}？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        pkg.customName,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    )
                    Text(
                        "协议：${mode.func} · " +
                            "ThemeType ${pkg.themeType.ifBlank { "—" }} · " +
                            "StartVersion ${pkg.startVersion.ifBlank { "—" }}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "上传会先停止投屏；资源与车机不匹配可能导致车机 UI 异常，请确认能回退。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUpload = false
                        onUpload()
                    },
                ) { Text("开始上传") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUpload = false }) { Text("取消") }
            },
        )
    }
}
