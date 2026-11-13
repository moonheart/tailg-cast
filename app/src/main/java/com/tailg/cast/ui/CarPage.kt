package com.tailg.cast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

@Composable
fun CarPage(
    snap: AppSnapshot,
    onCommand: (String, String) -> Unit,
    onDayNight: (Boolean) -> Unit,
    onStopNavi: () -> Unit,
    onScreenChoose: (Int) -> Unit,
) {
    val dataOnline = snap.carConnected || snap.bleConnected
    val cmdOnline = snap.carConnected
    val battery = if (snap.carBattery >= 0) "${snap.carBattery}" else "—"
    val endurance = if (snap.carEndurance >= 0) "${snap.carEndurance}" else "—"
    val mileage = if (snap.carMileage >= 0) "${snap.carMileage}" else "—"
    val tire = if (snap.carTire >= 0) "${snap.carTire}" else "—"
    var showMore by remember { mutableStateOf(false) }
    var showReply by remember { mutableStateOf(false) }

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
