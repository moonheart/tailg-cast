package com.tailg.cast

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 官方骑行统计 / 历史轨迹接口的解析。
 *
 * 端点（反编译自 com.tailg.run.intelligence）：
 *   app/appRiding/getRidingDetail   {model, key, carFrame}
 *   app/centralControl/deviceTravel {queryMonth, frame, uid}
 *   app/centralControl/deviceTravelDetail {deviceTravelId}
 *
 * 距离单位是米，时间单位是秒（统计里的 ridingTime 是分钟）；
 * 轨迹点官方按 GPS（WGS-84）存，高德瓦片是 GCJ-02，画图前要转换。
 */
data class RideSummary(
    val mileageM: Double,
    val totalMileageM: Double,
    val rideCount: Int,
    val ridingMinutes: Int,
    val maxSpeed: Double,
    val avgSpeed: Double,
    val carbonSaving: Double,
    val carbonTrees: Double,
) {
    val mileageKm: Double get() = mileageM / 1000.0
    val totalMileageKm: Double get() = totalMileageM / 1000.0

    companion object {
        fun parse(raw: String, model: String): RideSummary? {
            val root = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                return null
            }
            if (!okCode(root)) return null
            val d = root.optJSONObject("data") ?: return RideSummary(
                mileageM = 0.0,
                totalMileageM = 0.0,
                rideCount = 0,
                ridingMinutes = 0,
                maxSpeed = 0.0,
                avgSpeed = 0.0,
                carbonSaving = 0.0,
                carbonTrees = 0.0,
            )
            val mileageKey = when (model) {
                "weeks" -> "weekMileage"
                "months" -> "monthsMileage"
                else -> "dayMileage"
            }
            return RideSummary(
                mileageM = d.num(mileageKey),
                totalMileageM = d.num("totalMileage"),
                rideCount = d.intVal("ridingCount"),
                ridingMinutes = d.intVal("ridingTime"),
                maxSpeed = d.num("maxSpeed"),
                avgSpeed = d.num("avgSpeed"),
                carbonSaving = d.num("carbonSaving"),
                carbonTrees = d.num("carbonAbsorption"),
            )
        }
    }
}

data class RideTrip(
    val id: String,
    val startTime: String,
    val endTime: String,
    val mileageM: Double,
    val durationSec: Int,
    val avgSpeed: Double,
    val maxSpeed: Double,
) {
    val mileageKm: Double get() = mileageM / 1000.0
}

data class RideDay(
    val date: String,
    val mileageM: Double,
    val durationSec: Int,
    val trips: List<RideTrip>,
) {
    val mileageKm: Double get() = mileageM / 1000.0
}

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val speed: Double,
    val reportTime: String,
)

object RideStats {
    fun parseSummary(raw: String, model: String): RideSummary? = RideSummary.parse(raw, model)

    fun parseDays(raw: String): List<RideDay>? {
        val root = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return null
        }
        if (!okCode(root)) return null
        val arr = root.optJSONArray("data") ?: return emptyList()
        val days = ArrayList<RideDay>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val trips = ArrayList<RideTrip>()
            val tripArr = d.optJSONArray("deviceTravelDtoList")
            if (tripArr != null) {
                for (j in 0 until tripArr.length()) {
                    val t = tripArr.optJSONObject(j) ?: continue
                    val id = t.optString("deviceTravelId")
                    if (id.isBlank()) continue
                    trips += RideTrip(
                        id = id,
                        startTime = t.optString("startTime"),
                        endTime = t.optString("endTime"),
                        mileageM = t.num("mileage"),
                        durationSec = durationSec(t),
                        avgSpeed = t.num("averageSpeed"),
                        maxSpeed = t.num("maxSpeed"),
                    )
                }
            }
            val date = d.optString("travelDate")
            if (date.isBlank()) continue
            val dayMileage = d.num("totalMileage").takeIf { it > 0 }
                ?: trips.sumOf { it.mileageM }
            val daySeconds = d.intVal("totalTime").takeIf { it > 0 }
                ?: trips.sumOf { it.durationSec }
            days += RideDay(
                date = date,
                mileageM = dayMileage,
                durationSec = daySeconds,
                trips = trips,
            )
        }
        return days.sortedByDescending { it.date }
    }

    fun parsePoints(raw: String): List<TrackPoint>? {
        val root = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return null
        }
        if (!okCode(root)) return null
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<TrackPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val lat = p.num("lat")
            val lon = p.num("lng")
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            if (abs(lat) < 0.000001 && abs(lon) < 0.000001) continue
            out += TrackPoint(
                lat = lat,
                lon = lon,
                speed = p.num("speed"),
                reportTime = p.optString("reportTime"),
            )
        }
        return out
    }

    /** 点太多时按步长抽稀，保留首尾，WebView 画线不至于卡。 */
    fun decimate(points: List<TrackPoint>, maxPoints: Int = 3000): List<TrackPoint> {
        if (points.size <= maxPoints || maxPoints < 3) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        val out = ArrayList<TrackPoint>(maxPoints)
        for (i in 0 until maxPoints) {
            out += points[(i * step).toInt().coerceIn(0, points.size - 1)]
        }
        if (out.last().reportTime != points.last().reportTime ||
            out.last().lat != points.last().lat
        ) {
            out[out.lastIndex] = points.last()
        }
        return out
    }
}

private fun okCode(root: JSONObject): Boolean {
    val code = root.opt("code")?.toString() ?: return true
    return code == "0"
}

private fun JSONObject.num(key: String): Double {
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull() ?: 0.0
        else -> 0.0
    }.let { if (it.isNaN() || it.isInfinite()) 0.0 else it }
}

private fun JSONObject.intVal(key: String): Int {
    return when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: 0
        else -> 0
    }
}

private fun durationSec(o: JSONObject): Int {
    var sec = o.intVal("days") * 86_400 +
        o.intVal("hours") * 3_600 +
        o.intVal("min") * 60 +
        o.intVal("sec")
    if (sec <= 0) sec = o.intVal("totalTime")
    if (sec <= 0) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        try {
            val start = fmt.parse(o.optString("startTime"))
            val end = fmt.parse(o.optString("endTime"))
            if (start != null && end != null) {
                sec = ((end.time - start.time) / 1000L).toInt().coerceAtLeast(0)
            }
        } catch (_: Throwable) {
        }
    }
    return sec
}

fun fmtKm(meters: Double): String = when {
    meters <= 0 -> "0.0 km"
    meters < 100 -> String.format(Locale.US, "%.0f m", meters)
    else -> String.format(Locale.US, "%,.1f km", meters / 1000.0)
}

fun fmtDuration(seconds: Int): String {
    if (seconds <= 0) return "—"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h 小时 $m 分"
        h > 0 -> "$h 小时"
        m > 0 -> "$m 分"
        else -> "$seconds 秒"
    }
}

fun fmtSpeed(value: Double): String = when {
    value <= 0 -> "—"
    value % 1.0 == 0.0 -> value.toInt().toString()
    else -> String.format(Locale.US, "%.1f", value)
}

fun timeShort(value: String): String =
    if (value.length >= 16) value.substring(11, 16) else value

/** WGS-84 → GCJ-02，和高德 CoordinateConverter(CoordType.GPS) 等价。 */
object WgsGcj {
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun toGcj(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return lat + dLat to lon + dLon
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 73.66 || lon > 135.05 || lat < 3.86 || lat > 53.55

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}

fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

fun weekKey(): String {
    val cal = java.util.Calendar.getInstance()
    cal.firstDayOfWeek = java.util.Calendar.MONDAY
    cal.minimalDaysInFirstWeek = 7
    val week = cal.get(java.util.Calendar.WEEK_OF_YEAR)
    val month = SimpleDateFormat("yyyyMM", Locale.US).format(Date())
    return month + String.format(Locale.US, "%02d", week)
}

fun monthKey(): String = SimpleDateFormat("yyyyMM", Locale.US).format(Date())
    .let { it.substring(0, 4) + "-" + it.substring(4, 6) }
