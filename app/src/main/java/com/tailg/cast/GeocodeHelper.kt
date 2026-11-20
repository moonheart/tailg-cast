package com.tailg.cast

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import com.tailg.probe.LogSink
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 地图选点搜索的一条结果，坐标已统一为 GCJ-02（与地图瓦片一致）。 */
data class GeoSearchHit(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val source: String,
)

/** 系统地理编码 + 逆地理编码（坐标 -> 中文地址）+ 地点搜索。 */
object GeocodeHelper {

    private const val SEARCH_LIMIT = 8
    private const val UA = "TailgCast/0.1 (Android)"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    fun resolve(
        ctx: Context,
        query: String,
        onResult: (lat: Double, lon: Double, address: String?) -> Unit,
    ) {
        if (query.isBlank()) {
            onResult(Double.NaN, Double.NaN, null)
            return
        }
        Thread {
            var lat = Double.NaN
            var lon = Double.NaN
            var addr: String? = null
            try {
                val geocoder = Geocoder(ctx, Locale.getDefault())
                val list = geocoder.getFromLocationName(query, 1)
                val a = list?.firstOrNull()
                if (a != null) {
                    lat = a.latitude
                    lon = a.longitude
                    addr = a.getAddressLine(0)
                }
            } catch (_: Throwable) {
            }
            Handler(Looper.getMainLooper()).post { onResult(lat, lon, addr) }
        }.start()
    }

    /**
     * 按名称/地址搜索地点，返回 GCJ-02 坐标。
     *
     * <p>先用百度地图网页搜索（中文 POI/门牌覆盖好，坐标经 BD-09 转 GCJ-02），
     * 失败或无结果时回退到 OpenStreetMap Nominatim（WGS-84 转 GCJ-02）。
     * 两个源都不需要申请 key。</p>
     *
     * @param onResult 主线程回调：结果列表 + 是否成功连上网络
     */
    fun search(
        query: String,
        onResult: (List<GeoSearchHit>, Boolean) -> Unit,
    ) {
        val q = query.trim()
        if (q.isEmpty()) {
            onResult(emptyList(), true)
            return
        }
        Thread {
            var online = false
            var hits = emptyList<GeoSearchHit>()
            try {
                val raw = httpGet(buildBaiduSearchUrl(q), baiduHeaders())
                online = true
                if (raw != null) hits = parseBaiduSearch(raw)
            } catch (t: Throwable) {
                LogSink.line("MAP", "百度地点搜索失败: $t")
            }
            if (hits.isEmpty()) {
                try {
                    val raw = httpGet(buildNominatimSearchUrl(q), mapOf("User-Agent" to UA))
                    online = true
                    if (raw != null) hits = parseNominatimSearch(raw)
                } catch (t: Throwable) {
                    LogSink.line("MAP", "Nominatim 地点搜索失败: $t")
                }
            }
            Handler(Looper.getMainLooper()).post { onResult(hits, online) }
        }.start()
    }

    internal fun buildBaiduSearchUrl(query: String): String {
        val encoded = try {
            URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        } catch (_: Throwable) {
            query
        }
        return "https://map.baidu.com/?newmap=1&reqflag=pcmap&biz=1&from=webmap&qt=s" +
            "&da_src=searchBox.button&wd=" + encoded +
            "&c=1&src=0&pn=0&sug=0&l=12&tn=B_NORMAL_MAP&nn=0&ie=utf-8&t=" +
            System.currentTimeMillis()
    }

    internal fun buildNominatimSearchUrl(query: String): String {
        val encoded = try {
            URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        } catch (_: Throwable) {
            query
        }
        return "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1" +
            "&limit=$SEARCH_LIMIT&accept-language=zh-CN&q=$encoded"
    }

    private fun baiduHeaders(): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Referer" to "https://map.baidu.com/",
        "Accept" to "application/json, text/plain, */*",
        "Cookie" to "BAIDUID=00000000000000000000000000000000:FG=1",
    )

    private fun httpGet(url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    /** 百度网页搜索返回的 content[]，x/y 是 BD-09 墨卡托厘米坐标。 */
    internal fun parseBaiduSearch(json: String): List<GeoSearchHit> {
        val out = ArrayList<GeoSearchHit>()
        val root = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return out
        }
        val arr = root.optJSONArray("content")
            ?: root.optJSONObject("content")?.let { JSONArray().put(it) }
            ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            val x = o.optDouble("x", Double.NaN)
            val y = o.optDouble("y", Double.NaN)
            if (!x.isFinite() || !y.isFinite() || x == 0.0 || y == 0.0) continue
            val (lat, lon) = bd09mcToGcj(x, y)
            if (!validLatLon(lat, lon)) continue
            out.add(
                GeoSearchHit(
                    name = name,
                    address = o.optString("addr").trim(),
                    lat = lat,
                    lon = lon,
                    source = "百度",
                ),
            )
            if (out.size >= SEARCH_LIMIT) break
        }
        return dedupe(out)
    }

    /** Nominatim 搜索返回的对象数组，坐标是 WGS-84。 */
    internal fun parseNominatimSearch(json: String): List<GeoSearchHit> {
        val out = ArrayList<GeoSearchHit>()
        val arr = try {
            JSONArray(json)
        } catch (_: Throwable) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optString("lat").toDoubleOrNull() ?: continue
            val lon = o.optString("lon").toDoubleOrNull() ?: continue
            if (!validLatLon(lat, lon)) continue
            val display = o.optString("display_name").trim()
            val name = o.optString("name").trim().ifBlank {
                display.substringBefore(",").trim()
            }
            if (name.isEmpty()) continue
            val (gcjLat, gcjLon) = WgsGcj.toGcj(lat, lon)
            out.add(GeoSearchHit(name, display, gcjLat, gcjLon, "OSM"))
            if (out.size >= SEARCH_LIMIT) break
        }
        return dedupe(out)
    }

    private fun dedupe(hits: List<GeoSearchHit>): List<GeoSearchHit> {
        val seen = HashSet<String>()
        val out = ArrayList<GeoSearchHit>(hits.size)
        for (h in hits) {
            val key = h.name + "|" + String.format(Locale.US, "%.5f,%.5f", h.lat, h.lon)
            if (seen.add(key)) out.add(h)
        }
        return out
    }

    internal fun validLatLon(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0 &&
            !(lat == 0.0 && lon == 0.0)

    // ---- BD-09 墨卡托 -> BD-09 经纬度 -> GCJ-02 -------------------------
    // 百度网页接口只给 BD-09 墨卡托坐标，这里用百度 JS 的 MC2LL 参数反算，
    // 再套常用的 BD-09 -> GCJ-02 近似公式。

    private val MC_BAND = doubleArrayOf(
        12890594.86, 8362377.87, 5591021.0, 3481989.83, 1678043.12, 0.0,
    )

    private val MC2LL = arrayOf(
        doubleArrayOf(
            1.410526172116255e-8, 0.00000898305509648872, -1.9939833816331,
            200.9824383106796, -187.2403703815547, 91.6087516669843,
            -23.38765649603339, 2.57121317296198, -0.03801003308653, 17337981.2,
        ),
        doubleArrayOf(
            -7.435856389565537e-9, 0.000008983055097726239, -0.78625201886289,
            96.32687599759846, -1.85204757529826, -59.36935905485877,
            47.40033549296737, -16.50741931063887, 2.28786674699375, 10260144.86,
        ),
        doubleArrayOf(
            -3.030883460898826e-8, 0.00000898305509983578, 0.30071316287616,
            59.74293618442277, 7.357984074871, -25.38371002664745,
            13.45380521110908, -3.29883767235584, 0.32710905363475, 6856817.37,
        ),
        doubleArrayOf(
            -1.981981304930552e-8, 0.000008983055099779535, 0.03278182852591,
            40.31678527705744, 0.65659298677277, -4.44255534477492,
            0.85341911805263, 0.12923347998204, -0.04625736007561, 4482777.06,
        ),
        doubleArrayOf(
            3.09191371068437e-9, 0.000008983055096812155, 0.00006995724062,
            23.10934304144901, -0.00023663490511, -0.6321817810242,
            -0.00663494467273, 0.03430082397953, -0.00466043876332, 2555164.4,
        ),
        doubleArrayOf(
            2.890871144776878e-9, 0.000008983055095805407, -3.068298e-8,
            7.47137025468032, -0.00000353937994, -0.02145144861037,
            -0.00001234426596, 0.00010322952773, -0.00000323890364, 826088.5,
        ),
    )

    private const val X_PI = Math.PI * 3000.0 / 180.0

    internal fun bd09mcToGcj(mx: Double, my: Double): Pair<Double, Double> {
        val x = mx / 100.0
        val y = my / 100.0
        var cF: DoubleArray? = null
        for (i in MC_BAND.indices) {
            if (abs(y) >= MC_BAND[i]) {
                cF = MC2LL[i]
                break
            }
        }
        if (cF == null) return 0.0 to 0.0
        var bdLon = cF[0] + cF[1] * abs(x)
        val cD = abs(y) / cF[9]
        var bdLat = cF[2] +
            cF[3] * cD +
            cF[4] * cD.pow(2) +
            cF[5] * cD.pow(3) +
            cF[6] * cD.pow(4) +
            cF[7] * cD.pow(5) +
            cF[8] * cD.pow(6)
        if (x < 0) bdLon = -bdLon
        if (y < 0) bdLat = -bdLat
        if (bdLat == 0.0 && bdLon == 0.0) return 0.0 to 0.0
        // 国外坐标不做国测偏移，直接返回 BD-09 近似值。
        if (bdLon < 73.66 || bdLon > 135.05 || bdLat < 3.86 || bdLat > 53.55) {
            return bdLat to bdLon
        }
        val gx = bdLon - 0.0065
        val gy = bdLat - 0.006
        val z = sqrt(gx * gx + gy * gy) - 0.00002 * sin(gy * X_PI)
        val theta = atan2(gy, gx) - 0.000003 * cos(gx * X_PI)
        return (z * sin(theta)) to (z * cos(theta))
    }

    /**
     * 逆地理编码：WGS-84 坐标 -> 中文地址。
     * 先尝试系统 Geocoder，不可用或无结果时回退到 Nominatim。
     */
    @Suppress("DEPRECATION")
    fun reverse(
        ctx: Context,
        wgsLat: Double,
        wgsLon: Double,
        onResult: (address: String?) -> Unit,
    ) {
        Thread {
            var addr: String? = null
            try {
                if (Geocoder.isPresent()) {
                    val geocoder = Geocoder(ctx, Locale.SIMPLIFIED_CHINESE)
                    val (gcjLat, gcjLon) = WgsGcj.toGcj(wgsLat, wgsLon)
                    val a = geocoder.getFromLocation(gcjLat, gcjLon, 1)?.firstOrNull()
                        ?: geocoder.getFromLocation(wgsLat, wgsLon, 1)?.firstOrNull()
                    if (a != null) {
                        addr = a.getAddressLine(0)?.takeIf { it.isNotBlank() }
                            ?: listOfNotNull(
                                a.adminArea,
                                a.locality?.takeIf { it != a.adminArea },
                                a.subLocality,
                                a.thoroughfare,
                                a.featureName?.takeIf { it != a.thoroughfare },
                            ).distinct().joinToString("").takeIf { it.isNotBlank() }
                    }
                }
            } catch (_: Throwable) {
            }

            if (addr.isNullOrBlank()) {
                addr = reverseNominatim(wgsLat, wgsLon)
            }
            Handler(Looper.getMainLooper()).post { onResult(addr) }
        }.start()
    }

    private fun reverseNominatim(lat: Double, lon: Double): String? {
        return try {
            val url = String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1&accept-language=zh-CN",
                lat,
                lon,
            )
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val a = root.optJSONObject("address")
                if (a != null) {
                    val parts = listOf(
                        a.optString("state"),
                        a.optString("city").takeIf { it != a.optString("state") }.orEmpty(),
                        a.optString("county").ifBlank { a.optString("suburb") },
                        a.optString("town").ifBlank { a.optString("village") },
                        a.optString("road"),
                        a.optString("house_number"),
                        a.optString("amenity").ifBlank { a.optString("building") },
                    ).filter { it.isNotBlank() }.distinct()
                    if (parts.isNotEmpty()) return parts.joinToString("")
                }
                root.optString("display_name").takeIf { it.isNotBlank() }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
