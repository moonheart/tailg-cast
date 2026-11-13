package com.tailg.cast.ui

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tailg.cast.RideTrip
import com.tailg.cast.TrackPoint
import com.tailg.cast.WgsGcj
import com.tailg.cast.fmtDuration
import com.tailg.cast.fmtKm
import com.tailg.cast.fmtSpeed
import com.tailg.cast.timeShort
import com.tailg.probe.LogSink
import java.util.Locale

/**
 * 单段轨迹的地图弹窗：高德瓦片 + Leaflet 折线，不依赖高德 SDK/key。
 * 接口返回 WGS-84，画之前统一转 GCJ-02。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrackMapDialog(
    trip: RideTrip,
    points: List<TrackPoint>,
    busy: Boolean,
    error: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val leafletCss = remember { readAsset(ctx, "leaflet/leaflet.css") }
    val leafletJs = remember { readAsset(ctx, "leaflet/leaflet.js") }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val gcjPoints = remember(points) {
        points.map { p ->
            val (lat, lon) = WgsGcj.toGcj(p.lat, p.lon)
            TrackPoint(lat, lon, p.speed, p.reportTime)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                when {
                    busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Text(
                                "正在加载轨迹…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    error.isNotBlank() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            error,
                            modifier = Modifier.padding(24.dp),
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    gcjPoints.isNotEmpty() -> AndroidView(
                        factory = { viewCtx ->
                            WebView(viewCtx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                settings.setSupportZoom(false)
                                settings.builtInZoomControls = false
                                webViewClient = object : WebViewClient() {
                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                        error: android.webkit.WebResourceError?,
                                    ) {
                                        LogSink.line(
                                            "TRACK",
                                            "resource error " + request?.url + " " + error?.description,
                                        )
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                                        LogSink.line(
                                            "TRACK",
                                            "console " + m?.message() + " @" + m?.lineNumber(),
                                        )
                                        return true
                                    }
                                }
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun ready() {
                                            LogSink.line("TRACK", "map ready ${gcjPoints.size} points")
                                        }
                                    },
                                    "Android",
                                )
                                loadDataWithBaseURL(
                                    "https://localhost/",
                                    trackMapHtml(gcjPoints, leafletCss, leafletJs),
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                                webRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "这段轨迹没有坐标点。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "${timeShort(trip.startTime)} → ${timeShort(trip.endTime)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        fmtKm(trip.mileageM),
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        " · ${fmtDuration(trip.durationSec)}",
                                        modifier = Modifier.padding(bottom = 3.dp),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = onDismiss) { Text("关闭") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TrackMetric("平均速度", "${fmtSpeed(trip.avgSpeed)} km/h", Modifier.weight(1f))
                            TrackMetric("最高速度", "${fmtSpeed(trip.maxSpeed)} km/h", Modifier.weight(1f))
                            TrackMetric("轨迹点", "${points.size}", Modifier.weight(1f))
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "● 起点 ${timeShort(trip.startTime)}",
                            fontSize = 11.sp,
                            color = OkGreen,
                        )
                        Text(
                            "● 终点 ${timeShort(trip.endTime)}",
                            fontSize = 11.sp,
                            color = BadRed,
                        )
                        Text(
                            "GPS→GCJ-02",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webRef?.stopLoading()
                webRef?.destroy()
            } catch (_: Throwable) {
            }
            webRef = null
        }
    }
}

@Composable
private fun TrackMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun trackMapHtml(
    points: List<TrackPoint>,
    css: String,
    js: String,
): String {
    val safeCss = css.replace("</style", "<\\/style")
    val safeJs = js.replace("</script", "<\\/script")
    val json = points.joinToString(prefix = "[", postfix = "]") { p ->
        String.format(
            Locale.US,
            "[%.6f,%.6f]",
            p.lat,
            p.lon,
        )
    }
    return """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
        <style>
          $safeCss
          html,body { height:100%; margin:0; background:#eef2f7; }
          #map { position:absolute; inset:0; background:#eef2f7; }
          .leaflet-bottom { bottom: 60px; }
          #err { position:fixed; left:10px; right:10px; bottom:70px; z-index:9999;
                 background:#fee2e2; color:#991b1b; font:12px sans-serif;
                 padding:8px; border-radius:8px; display:none; white-space:pre-wrap; }
        </style>
        </head><body>
        <div id="map"></div>
        <div id="err"></div>
        <script>
          $safeJs
        </script>
        <script>
          window.onerror = function(msg, src, line) {
            var e = document.getElementById('err');
            e.style.display = 'block';
            e.textContent = '地图错误: ' + msg + ' | ' + (src || '') + ':' + line;
          };
          try {
            var pts = $json;
            function fit() {
              var w = window.innerWidth, h = window.innerHeight;
              document.documentElement.style.height = h + 'px';
              document.body.style.height = h + 'px';
              var el = document.getElementById('map');
              el.style.width = w + 'px';
              el.style.height = h + 'px';
            }
            fit();
            var map = L.map('map', { zoomControl: false, attributionControl: false });
            L.control.zoom({ position: 'bottomright' }).addTo(map);
            L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
              subdomains: ['1','2','3','4'], maxZoom: 18, minZoom: 3
            }).addTo(map);
            if (pts.length > 1) {
              L.polyline(pts, { color: '#ffffff', weight: 9, opacity: 0.9 }).addTo(map);
              L.polyline(pts, { color: '#1d4ed8', weight: 5, opacity: 0.95 }).addTo(map);
              var start = pts[0], end = pts[pts.length - 1];
              L.circleMarker(start, { radius: 7, color: '#ffffff', weight: 3,
                  fillColor: '#16a34a', fillOpacity: 1 }).addTo(map);
              L.circleMarker(end, { radius: 7, color: '#ffffff', weight: 3,
                  fillColor: '#dc2626', fillOpacity: 1 }).addTo(map);
              map.fitBounds(L.latLngBounds(pts), { paddingTopLeft: [40, 150], paddingBottomRight: [40, 70], maxZoom: 17 });
              setTimeout(function() {
                map.invalidateSize();
                map.fitBounds(L.latLngBounds(pts), { paddingTopLeft: [40, 150], paddingBottomRight: [40, 70], maxZoom: 17 });
              }, 350);
            } else if (pts.length === 1) {
              map.setView(pts[0], 16);
              L.circleMarker(pts[0], { radius: 7, color: '#fff', weight: 3,
                  fillColor: '#1d4ed8', fillOpacity: 1 }).addTo(map);
            } else {
              map.setView([30.66, 104.06], 12);
            }
            window.addEventListener('resize', function(){ fit(); map.invalidateSize(); });
            map.whenReady(function(){
              if (window.Android && Android.ready) Android.ready();
            });
          } catch (e) {
            var el = document.getElementById('err');
            el.style.display = 'block';
            el.textContent = '地图初始化失败: ' + e.message;
          }
        </script>
        </body></html>
    """.trimIndent()
}
