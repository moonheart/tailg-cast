package com.tailg.cast.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tailg.probe.BleLink
import com.tailg.probe.Config
import com.tailg.probe.ProbeService
import com.tailg.probe.WifiLink

data class ConfigView(
    val width: Int = 1024,
    val height: Int = 600,
    val safeTop: Int = 0,
    val safeBottom: Int = 0,
    val bitrate: Int = 2_000_000,
    val fps: Int = 30,
    val targetPkg: String = "",
    val useRoot: Boolean = true,
    val useShizuku: Boolean = false,
    val carSsid: String = "",
    val carPass: String = "",
    val nickname: String = "",
    val tuc: String = "",
    val mac: String = "",
    val cloudTokenSet: Boolean = false,
    val cloudTokenAt: Long = 0,
    val cloudTokenCheckedAt: Long = 0,
    val carImei: String = "",
    val forgetCarWifi: Boolean = true,
) {
    val contentHeight: Int get() = height - safeTop - safeBottom

    /** root / shizuku；MediaProjection 已从 UI 移除 */
    val backend: String
        get() = if (useRoot && useShizuku) "shizuku" else "root"
}

data class AppSnapshot(
    val serviceRunning: Boolean = false,
    val carConnected: Boolean = false,
    val casting: Boolean = false,
    val frames: Long = 0,
    val bytes: Long = 0,
    val portConflict: Boolean = false,
    val lastError: String = "",
    val bleConnected: Boolean = false,
    val wifiConnected: Boolean = false,
    val carVersion: String = "",
    val carMac: String = "",
    val carBattery: Int = -1,
    val carEndurance: Int = -1,
    val carMileage: Int = -1,
    val carTire: Int = -1,
    val carRide: String = "",
    val lastNavi: String = "",
    val lastJson: String = "",
    val lastCmd: String = "",
    val lastCmdAnswered: Boolean = false,
    val lastCarJsonAt: Long = 0,
    val permissionsOk: Boolean = false,
    val cfg: ConfigView = ConfigView(),
)

fun snapshot(ctx: Context, fallback: Config): AppSnapshot {
    val cfg = ProbeService.config ?: fallback
    val cast = ProbeService.cast
    return AppSnapshot(
        serviceRunning = ProbeService.running,
        carConnected = ProbeService.carConnected(),
        casting = cast?.isRunning == true,
        frames = cast?.frames() ?: 0L,
        bytes = cast?.bytes() ?: 0L,
        portConflict = ProbeService.portConflict,
        lastError = ProbeService.lastError,
        bleConnected = BleLink.connected,
        wifiConnected = WifiLink.connected,
        carVersion = ProbeService.carVersionText,
        carMac = ProbeService.carMacText,
        carBattery = ProbeService.carBattery,
        carEndurance = ProbeService.carEndurance,
        carMileage = ProbeService.carMileage,
        carTire = ProbeService.carTire,
        carRide = ProbeService.carRide,
        lastNavi = ProbeService.lastNavi,
        lastJson = ProbeService.lastJson,
        lastCmd = ProbeService.lastCmdFunc,
        lastCmdAnswered = ProbeService.lastCmdAnswered,
        lastCarJsonAt = ProbeService.lastCarJsonAt,
        permissionsOk = neededPermissions(ctx).isEmpty(),
        cfg = cfg.toView(),
    )
}

fun Config.toView(): ConfigView = ConfigView(
    width = castWidth,
    height = castHeight,
    safeTop = safeTop,
    safeBottom = safeBottom,
    bitrate = castBitrate,
    fps = castFps,
    targetPkg = appDisplayPkg,
    useRoot = useVdHelper,
    useShizuku = useShizuku,
    carSsid = carSsid,
    carPass = carPass,
    nickname = nickname,
    tuc = tuc,
    mac = mac,
    cloudTokenSet = cloudToken.isNotBlank(),
    cloudTokenAt = cloudTokenAt,
    cloudTokenCheckedAt = cloudTokenCheckedAt,
    carImei = carImei,
    forgetCarWifi = forgetCarWifi,
)


internal fun neededPermissions(ctx: Context): Array<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        list += Manifest.permission.BLUETOOTH_SCAN
        list += Manifest.permission.BLUETOOTH_CONNECT
    }
    if (Build.VERSION.SDK_INT >= 33) {
        list += Manifest.permission.NEARBY_WIFI_DEVICES
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    if (Build.VERSION.SDK_INT <= 30) {
        list += Manifest.permission.ACCESS_FINE_LOCATION
    }
    return list.filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()
}
