package com.tailg.cast.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import com.tailg.probe.LogSink
import java.util.Locale

/**
 * 全屏地图选点。地图用高德公开瓦片（GCJ-02），点地图/拖蓝点返回经纬度。
 * 瓦片只是地图背景，不调用高德 SDK，也不需要 key。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapPickerDialog(
    initialLat: Double,
    initialLon: Double,
    onPick: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickedLat by remember { mutableStateOf(initialLat) }
    var pickedLon by remember { mutableStateOf(initialLon) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val ctx = LocalContext.current
    val leafletCss = remember { readAsset(ctx, "leaflet/leaflet.css") }
    val leafletJs = remember { readAsset(ctx, "leaflet/leaflet.js") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "地图选点",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = true
                                settings.allowContentAccess = true
                                settings.allowFileAccessFromFileURLs = true
                                settings.allowUniversalAccessFromFileURLs = true
                                settings.setSupportZoom(false)
                                settings.builtInZoomControls = false
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        LogSink.line("MAP", "page finished " + url)
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        LogSink.line(
                                            "MAP",
                                            "resource error " + request?.url + " " + error?.description,
                                        )
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                                        LogSink.line(
                                            "MAP",
                                            "console " + m?.message() + " @" + m?.sourceId()
                                                + ":" + m?.lineNumber(),
                                        )
                                        return true
                                    }
                                }
                                addJavascriptInterface(
                                    object {
                                        @JavascriptInterface
                                        fun onPick(lat: Double, lon: Double) {
                                            handler.post {
                                                pickedLat = lat
                                                pickedLon = lon
                                            }
                                        }
                                    },
                                    "Android",
                                )
                                loadDataWithBaseURL(
                                    "https://localhost/",
                                    mapHtml(initialLat, initialLon, leafletCss, leafletJs),
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                                webRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    "已选：${fmt(pickedLat)}, ${fmt(pickedLon)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "点地图或拖动蓝点选点。地图瓦片 © 高德地图；坐标按 GCJ-02 保存。",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onPick(pickedLat, pickedLon) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) { Text("使用此点") }
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
        }
    }
}

internal fun readAsset(ctx: Context, name: String): String = try {
    ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
} catch (t: Throwable) {
    LogSink.line("MAP", "asset $name 读取失败: $t")
    ""
}

private fun mapHtml(lat: Double, lon: Double, css: String, js: String): String {
    val la = String.format(Locale.US, "%.6f", lat)
    val lo = String.format(Locale.US, "%.6f", lon)
    val safeCss = css.replace("</style", "<\\/style")
    val safeJs = js.replace("</script", "<\\/script")
    return """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
        <style>
          $safeCss
          html,body { height:100%; margin:0; background:#eef2f7; }
          #map { position:absolute; top:0; left:0; right:0; bottom:0;
                 width:100%; height:100%; background:#eef2f7; }
          .leaflet-container { font-family: sans-serif; }
          #hint { position:fixed; left:50%; top:12px; transform:translateX(-50%); z-index:9998;
                  background:rgba(17,24,39,.85); color:#fff; font:12px sans-serif;
                  padding:6px 10px; border-radius:999px; }
          #err { position:fixed; left:8px; right:8px; bottom:8px; z-index:9999;
                 background:#fee2e2; color:#991b1b; font:12px sans-serif;
                 padding:8px; border-radius:8px; display:none; white-space:pre-wrap; }
        </style>
        </head><body>
        <div id="map"></div>
        <div id="hint">地图加载中…</div>
        <div id="err"></div>
        <script>
          $safeJs
        </script>
        <script>
          window.onerror = function(msg, src, line, col, err) {
            var e = document.getElementById('err');
            e.style.display = 'block';
            e.textContent = '地图错误: ' + msg + ' | ' + (src || '') + ':' + line;
          };
          try {
            if (typeof L === 'undefined') throw new Error('Leaflet 未加载');
            function fit() {
              var w = window.innerWidth, h = window.innerHeight;
              document.documentElement.style.height = h + 'px';
              document.body.style.height = h + 'px';
              document.body.style.margin = '0';
              var el = document.getElementById('map');
              el.style.position = 'absolute';
              el.style.top = '0';
              el.style.left = '0';
              el.style.width = w + 'px';
              el.style.height = h + 'px';
            }
            fit();
            var map = L.map('map', {zoomControl:true, attributionControl:false}).setView([$la,$lo], 16);
            L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
              subdomains: ['1','2','3','4'], maxZoom: 18, minZoom: 3
            }).addTo(map);
            var marker = L.circleMarker([$la,$lo], {
              radius: 9, color: '#1d4ed8', weight: 3,
              fillColor: '#3b82f6', fillOpacity: 0.9
            }).addTo(map);
            function report(lat, lon) {
              marker.setLatLng([lat, lon]);
              if (window.Android && Android.onPick) Android.onPick(lat, lon);
            }
            map.on('click', function(e) { report(e.latlng.lat, e.latlng.lng); });
            window.addEventListener('resize', function(){ fit(); map.invalidateSize(); });
            map.whenReady(function(){ document.getElementById('hint').style.display = 'none'; });
            console.log('viewport ' + window.innerWidth + 'x' + window.innerHeight
                        + ' body ' + document.body.clientHeight
                        + ' html ' + document.documentElement.clientHeight
                        + ' map ' + document.getElementById('map').clientHeight
                        + ' css ' + getComputedStyle(document.getElementById('map')).height);
            setTimeout(function(){ fit(); map.invalidateSize(); }, 300);
            setTimeout(function(){
              fit();
              map.invalidateSize();
              console.log('map size ' + map.getSize().x + 'x' + map.getSize().y);
            }, 1200);
          } catch (e) {
            var el = document.getElementById('err');
            el.style.display = 'block';
            el.textContent = '地图初始化失败: ' + e.message;
          }
        </script>
        </body></html>
    """.trimIndent()
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)
