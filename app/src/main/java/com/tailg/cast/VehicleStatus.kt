package com.tailg.cast

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ChargeState(val label: String) {
    NOT_CHARGING("未充电"),
    COMPLETE("充电完成"),
    ILLEGAL("非法充电"),
    PROTECTED("电池保护"),
    CHARGING("充电中"),
    UNKNOWN("—"),
}

data class VehicleFeature(val name: String, val supported: Boolean)

data class VehicleField(val label: String, val value: String)

/**
 * 云端 app/centralControl/carStatus 的结构化视图。
 *
 * 字段名来自官方 App 的 CarControlInfoBean，不靠猜；解析时对数字/字符串/布尔
 * 都做兼容，车厂接口偶尔会把同一个字段在 1 和 "1" 之间来回换。
 */
data class VehicleStatus(
    val nickname: String,
    val model: String,
    val online: Boolean?,
    val battery: Int?,
    val voltage: Double?,
    val rangeKm: Double?,
    val maxRangeKm: Double?,
    val locked: Boolean?,
    val accOn: Boolean?,
    val charge: ChargeState,
    val address: String,
    val gpsTime: String,
    val latitude: Double?,
    val longitude: Double?,
    val iccid: String,
    val imei: String,
    val imeiGps: String,
    val frame: String,
    val modelCode: String,
    val wholeCode: String,
    val carId: String,
    val deviceType: String,
    val screenSize: String,
    val screenType: String,
    val btName: String,
    val btMac: String,
    val wifiMac: String,
    val simStatus: Boolean?,
    val simNo: String,
    val simType: String,
    val simBusiness: String,
    val netAuth: String,
    val batteryType: String,
    val batterySpec: String,
    val batteryBindDate: String,
    val buyTime: String,
    val dealer: String,
    val mqHost: String,
    val mqPort: String,
    val batteryCycles: String,
    val modelType: String,    val features: List<VehicleFeature>,
    val extras: List<VehicleField>,
    val updatedAt: Long,
    val raw: String,
    val batteryCurrent: Double? = null,
    val batteryTemperature: Double? = null,
    val batterySoc: Double? = null,
    val batterySoh: Double? = null,
    val batteryCapacityAh: Double? = null,
    val batteryNominalVoltage: Double? = null,
) {
    companion object {
        private val FEATURE_KEYS = linkedMapOf(
            "isNfc" to "NFC",
            "isCushionLock" to "座桶锁",
            "isCushionInduction" to "坐垫感应",
            "isEnhancedKickstand" to "边撑感应",
            "isHillParking" to "坡道驻车",
            "isSlipSlope" to "防滑",
            "isScreen" to "屏幕",
            "isCamera" to "摄像头",
            "isGps" to "GPS",
            "isWarranty" to "整车质保",
            "isHarmony" to "鸿蒙",
            "isSkinChange" to "换肤",
            "isThinkRide" to "智能中控",
            "isThinkRideBLE" to "BLE 中控",
        )

        /** 已经进入结构化卡片，或者不能展示到界面上的字段。 */
        private val CONSUMED_KEYS = setOf(
            "carNickName", "carName", "carType", "online",
            "electricQuantity", "voltage", "mileage", "maxMileage",
            "defenceStatus", "acc", "batteryChargeStatus",
            "carLocationAddress", "location", "cityAddress",
            "gpsReportTime", "latitude", "longitude",
            "iccId", "imei", "imeiGps", "frame",
            "coding", "ftqc", "carId", "deviceType",
            "screenSize", "screenType",
            "btname", "btmac", "mac", "fmac",
            "simStatus", "simNo", "simType", "simBusiness", "netAuthSuccess",
            "batteryTypeLabel", "batterySpecLabel", "batteryBindDate",
            "buyTime", "dealer", "mqHost", "mqPort",
            "modelType",
            // 不下发的敏感/无用字段
            "mqUsername", "mqPassword", "passwordInfo", "carPhoto", "userId",
        ) + FEATURE_KEYS.keys
        private val EXTRA_LABELS = mapOf(
            "trajectory" to "轨迹",
            "perfectStatus" to "车辆完善状态",
            "linkCode" to "连接码",
            "isFirstLoad" to "首次加载",
            "shareCarFlag" to "共享车辆",
            "isTelligence" to "智能服务",
            "isBindStore" to "门店绑定",
            "isCheck" to "检测状态",
            "mfrCode" to "厂商代码",
            "muteStatus" to "静音状态",
            "bmsTlvType" to "BMS 协议号",
            "batteryTypeId" to "电池类型编号",
            "batterySpecCode" to "电池规格编号",
            "maxRssiDistance" to "最大 RSSI 距离",
            "minRssiDistance" to "最小 RSSI 距离",
            "rssiA" to "RSSI-A",
            "rssiFactor" to "RSSI 系数",
        )

        fun parse(raw: String): VehicleStatus? {
            val root = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                return null
            }
            if (root.has("code") && root.optInt("code", -1) != 0) return null
            val d = root.optJSONObject("data") ?: return null

            val nickname = d.text("carNickName").ifBlank { d.text("carName") }
            val model = d.text("carType")
            val charge = chargeState(d.text("batteryChargeStatus"))

            val features = FEATURE_KEYS.mapNotNull { (key, label) ->
                d.bool(key)?.let { VehicleFeature(label, it) }
            }

            val extras = d.keys().asSequence()
                .filterNot { it in CONSUMED_KEYS }
                .sorted()
                .mapNotNull { key ->
                    val value = d.rawValue(key)
                    if (value.isBlank()) null
                    else VehicleField(EXTRA_LABELS[key] ?: key, value)
                }
                .toList()

            return VehicleStatus(
                nickname = nickname,
                model = model,
                online = d.bool("online"),
                battery = d.int("electricQuantity")?.coerceIn(0, 100),
                voltage = d.number("voltage"),
                rangeKm = d.number("mileage"),
                maxRangeKm = d.number("maxMileage"),
                locked = d.int("defenceStatus")?.let { it != 0 },
                accOn = d.int("acc")?.let { it != 0 },
                charge = charge,
                address = firstNonBlank(
                    d.text("carLocationAddress"),
                    d.text("location"),
                    d.text("cityAddress"),
                ),
                gpsTime = dateTime(d.text("gpsReportTime"), withSeconds = true),
                latitude = d.number("latitude")?.takeIf { it != 0.0 },
                longitude = d.number("longitude")?.takeIf { it != 0.0 },
                iccid = d.text("iccId"),
                imei = d.text("imei"),
                imeiGps = d.text("imeiGps"),
                frame = d.text("frame"),
                modelCode = d.text("coding"),
                wholeCode = d.text("ftqc"),
                carId = d.text("carId"),
                deviceType = d.text("deviceType"),
                screenSize = d.text("screenSize").takeUnless { it == "0" }.orEmpty(),
                screenType = d.text("screenType").takeUnless { it == "0" }.orEmpty(),
                btName = d.text("btname"),
                btMac = d.text("btmac"),
                wifiMac = firstNonBlank(d.text("fmac"), d.text("mac")),
                simStatus = d.bool("simStatus"),
                simNo = d.text("simNo"),
                simType = d.text("simType"),
                simBusiness = d.text("simBusiness"),
                netAuth = d.text("netAuthSuccess"),
                batteryType = d.text("batteryTypeLabel"),
                batterySpec = d.text("batterySpecLabel"),
                batteryBindDate = dateOnly(d.text("batteryBindDate")),
                buyTime = dateOnly(d.text("buyTime")),
                dealer = d.text("dealer"),
                mqHost = d.text("mqHost"),
                mqPort = d.text("mqPort"),
                batteryCycles = "",
                modelType = d.text("modelType"),
                features = features,
                extras = extras,
                updatedAt = System.currentTimeMillis(),
                raw = redactRaw(raw),
            )
        }

        /** 原始 JSON 给诊断看，但 MQTT 账号/密码和分享密码不能出现在界面上。 */
        private fun redactRaw(raw: String): String = try {
            val root = JSONObject(raw)
            root.optJSONObject("data")?.let { data ->
                data.remove("mqPassword")
                data.remove("mqUsername")
                data.remove("passwordInfo")
            }
            root.toString(2)
        } catch (_: Throwable) {
            raw
        }

        /**
         * 云端两个接口的 batteryChargeStatus 都会偶尔闪 0（实测 4/0/4/4...），
         * 但 BMS 的 batteryCurrent 稳定在 5A。用电流交叉校验，避免 UI 跟着闪。
         */
        fun mergeBms(base: VehicleStatus, raw: String): VehicleStatus {
            val root = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                return base
            }
            if (root.has("code") && root.optInt("code", -1) != 0) return base
            val data = root.optJSONObject("data") ?: return base
            val detail = data.optJSONArray("details")?.optJSONObject(0) ?: return base

            val current = detail.number("batteryCurrent")
            val bmsCharge = chargeState(detail.text("batteryChargeStatus"))
            val charging = when {
                bmsCharge == ChargeState.CHARGING -> true
                (bmsCharge == ChargeState.NOT_CHARGING ||
                    base.charge == ChargeState.NOT_CHARGING) &&
                    (current ?: 0.0) > 0.5 -> true
                else -> false
            }
            val effective = when {
                charging -> ChargeState.CHARGING
                bmsCharge != ChargeState.UNKNOWN -> bmsCharge
                else -> base.charge
            }

            return base.copy(
                charge = effective,
                battery = detail.number("soc")?.toInt()?.coerceIn(0, 100) ?: base.battery,
                batteryCycles = detail.text("batteryCyclesNum").ifBlank { base.batteryCycles },
                batteryCurrent = current,
                batteryTemperature = detail.number("batteryTemperature"),
                batterySoc = detail.number("soc"),
                batterySoh = detail.number("soh"),
                batteryCapacityAh = detail.number("batteryCapacity"),
                batteryNominalVoltage = detail.number("batteryVoltage"),
            )
        }

        private fun chargeState(value: String): ChargeState = when (value) {
            "0" -> ChargeState.NOT_CHARGING
            "1" -> ChargeState.COMPLETE
            "2" -> ChargeState.ILLEGAL
            "3" -> ChargeState.PROTECTED
            "4" -> ChargeState.CHARGING
            else -> ChargeState.UNKNOWN
        }

        private fun firstNonBlank(vararg values: String): String =
            values.firstOrNull { it.isNotBlank() } ?: ""

        private fun JSONObject.text(key: String): String {
            if (!has(key) || isNull(key)) return ""
            val v = opt(key) ?: return ""
            val s = v.toString().trim()
            return if (s.isEmpty() || s == "null" || s == "NULL") "" else s
        }

        private fun JSONObject.number(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val v = opt(key) ?: return null
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.trim()
                    .removeSuffix("%")
                    .removeSuffix("V")
                    .removeSuffix("v")
                    .removeSuffix("km")
                    .trim()
                    .toDoubleOrNull()
                else -> null
            } ?: return null
            return d.takeIf { !it.isNaN() && !it.isInfinite() }
        }

        private fun JSONObject.int(key: String): Int? = number(key)?.toInt()

        private fun JSONObject.bool(key: String): Boolean? {
            if (!has(key) || isNull(key)) return null
            return when (val v = opt(key)) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> when (v.trim().lowercase(Locale.US)) {
                    "true", "1", "yes", "on", "online" -> true
                    "false", "0", "no", "off", "offline" -> false
                    else -> null
                }
                else -> null
            }
        }

        private fun JSONObject.rawValue(key: String): String {
            if (!has(key) || isNull(key)) return ""
            return when (val v = opt(key)) {
                null, JSONObject.NULL -> ""
                is Boolean -> if (v) "是" else "否"
                is Number -> when {
                    key.startsWith("is") && v.toInt() == 0 -> "否"
                    key.startsWith("is") && v.toInt() == 1 -> "是"
                    v.toDouble() % 1.0 == 0.0 -> v.toLong().toString()
                    else -> String.format(Locale.US, "%.2f", v.toDouble())
                }
                is JSONArray, is JSONObject -> v.toString()
                is String -> when {
                    key.startsWith("is") && v.trim() == "1" -> "是"
                    key.startsWith("is") && v.trim() == "0" -> "否"
                    else -> v.toString()
                }
                else -> v.toString()
            }
        }

        private fun dateOnly(raw: String): String {
            if (raw.isBlank()) return ""
            return parseTime(raw)?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US))
                ?: raw.substringBefore('T').substringBefore(' ').trim()
        }

        private fun dateTime(raw: String, withSeconds: Boolean): String {
            if (raw.isBlank()) return ""
            val pattern = if (withSeconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
            return parseTime(raw)?.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
                ?: raw.replace('T', ' ').substringBefore('.').trim()
        }

        private fun parseTime(raw: String): OffsetDateTime? = try {
            OffsetDateTime.parse(raw)
        } catch (_: Throwable) {
            null
        }
    }
}
