package com.tailg.cast

import android.content.Context
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 系统地理编码 + 逆地理编码（坐标 -> 中文地址）。 */
object GeocodeHelper {

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
                .header("User-Agent", "TailgCast/0.1 (Android)")
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
