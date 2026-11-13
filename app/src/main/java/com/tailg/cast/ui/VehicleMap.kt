package com.tailg.cast.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tailg.cast.VehicleStatus
import com.tailg.cast.WgsGcj
import com.tailg.probe.NavFavorites
import java.util.Locale

/**
 * 嵌入「车辆位置」卡片的迷你地图：禁用拖拽以免与页面竖滑冲突，点击打开全屏交互地图。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VehicleMiniMap(
    lat: Double,
    lon: Double,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
) {
    val ctx = LocalContext.current
    val leafletCss = remember { readAsset(ctx, "leaflet/leaflet.css") }
    val leafletJs = remember { readAsset(ctx, "leaflet/leaflet.js") }
    val (gcjLat, gcjLon) = remember(lat, lon) { WgsGcj.toGcj(lat, lon) }
    var webRef by remember { mutableStateOf<WebView?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(14.dp)),
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL(
                        "https://localhost/",
                        vehicleMapHtml(gcjLat, gcjLon, interactive = false, css = leafletCss, js = leafletJs),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                    webRef = this
                }
            },
            update = { web ->
                web.evaluateJavascript(
                    String.format(Locale.US, "if(window.updateCarPos){window.updateCarPos(%.6f,%.6f);}", gcjLat, gcjLon),
                    null,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 透明点击层：拦截手势直接打开大图，保证外层列表滑动顺畅
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onExpand),
        )

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .clickable(onClick = onExpand),
        ) {
            Text(
                "查看大图 / 寻车 ›",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
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

/**
 * 全屏车辆位置地图弹窗，支持缩放拖拽、查看地址与拉起高德步行导航寻车。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VehicleMapDialog(
    status: VehicleStatus,
    resolvedAddress: String? = null,
    onDismiss: () -> Unit,
) {
    val lat = status.latitude ?: return
    val lon = status.longitude ?: return
    val ctx = LocalContext.current
    val leafletCss = remember { readAsset(ctx, "leaflet/leaflet.css") }
    val leafletJs = remember { readAsset(ctx, "leaflet/leaflet.js") }
    val (gcjLat, gcjLon) = remember(lat, lon) { WgsGcj.toGcj(lat, lon) }
    var webRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            webViewClient = WebViewClient()
                            loadDataWithBaseURL(
                                "https://localhost/",
                                vehicleMapHtml(gcjLat, gcjLon, interactive = true, css = leafletCss, js = leafletJs),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                            webRef = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                // 顶部悬浮信息卡片
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    status.nickname.ifBlank { status.model.ifBlank { "车辆位置" } },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (status.gpsTime.isNotBlank()) {
                                    Text(
                                        "定位时间 ${status.gpsTime}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            TextButton(onClick = onDismiss) { Text("关闭") }
                        }
                        val displayAddr = status.address.ifBlank { resolvedAddress.orEmpty() }
                        if (displayAddr.isNotBlank()) {
                            Text(
                                displayAddr,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        Text(
                            String.format(Locale.US, "GCJ-02 %.6f, %.6f", gcjLat, gcjLon),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 底部导航寻车按钮（同时支持高德和百度步行导航）
                val preferred = remember { NavFavorites.getMapProvider(ctx) }
                val primaryProvider = preferred
                val secondaryProvider = if (preferred == NavFavorites.MAP_BAIDU) NavFavorites.MAP_AMAP else NavFavorites.MAP_BAIDU
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                launchWalkNav(
                                    ctx = ctx,
                                    gcjLat = gcjLat,
                                    gcjLon = gcjLon,
                                    targetName = status.nickname.ifBlank { "我的车辆" },
                                    provider = primaryProvider,
                                )
                            },
                            modifier = Modifier
                                .weight(1.3f)
                                .height(48.dp),
                        ) {
                            Text("${NavFavorites.shortLabelFor(primaryProvider)}步行寻车", fontWeight = FontWeight.SemiBold)
                        }
                        FilledTonalButton(
                            onClick = {
                                launchWalkNav(
                                    ctx = ctx,
                                    gcjLat = gcjLat,
                                    gcjLon = gcjLon,
                                    targetName = status.nickname.ifBlank { "我的车辆" },
                                    provider = secondaryProvider,
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text("${NavFavorites.shortLabelFor(secondaryProvider)}寻车", fontWeight = FontWeight.Medium)
                        }
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

private fun launchWalkNav(
    ctx: android.content.Context,
    gcjLat: Double,
    gcjLon: Double,
    targetName: String,
    provider: String,
) {
    val encoded = Uri.encode(targetName)
    val uriStr = if (provider == NavFavorites.MAP_BAIDU) {
        String.format(
            Locale.US,
            "baidumap://map/direction?destination=latlng:%.6f,%.6f%%7Cname:%s&coord_type=gcj02&mode=walking&src=andr.tailg.cast",
            gcjLat,
            gcjLon,
            encoded,
        )
    } else {
        String.format(
            Locale.US,
            "amapuri://route/plan/?dlat=%.6f&dlon=%.6f&dname=%s&dev=0&t=2",
            gcjLat,
            gcjLon,
            encoded,
        )
    }
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage(NavFavorites.packageFor(provider)),
        )
    } catch (_: Throwable) {
        Toast.makeText(ctx, "未安装${NavFavorites.labelFor(provider)}", Toast.LENGTH_SHORT).show()
    }
}

private fun vehicleMapHtml(
    lat: Double,
    lon: Double,
    interactive: Boolean,
    css: String,
    js: String,
): String {
    val safeCss = css.replace("</style", "<\\/style")
    val safeJs = js.replace("</script", "<\\/script")
    val latStr = String.format(Locale.US, "%.6f", lat)
    val lonStr = String.format(Locale.US, "%.6f", lon)
    val zoomControlJs = if (interactive) "L.control.zoom({ position: 'bottomright' }).addTo(map);" else ""
    val bottomPad = if (interactive) "96px" else "8px"
    return """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
        <style>
          $safeCss
          html,body { height:100%; margin:0; background:#eef2f7; }
          #map { position:absolute; inset:0; background:#eef2f7; }
          .leaflet-bottom { bottom: $bottomPad; }
        </style>
        </head><body>
        <div id="map"></div>
        <script>$safeJs</script>
        <script>
          function fit() {
            var w = window.innerWidth, h = window.innerHeight;
            if (w > 0 && h > 0) {
              document.documentElement.style.height = h + 'px';
              document.body.style.height = h + 'px';
              var el = document.getElementById('map');
              el.style.width = w + 'px';
              el.style.height = h + 'px';
            }
          }
          fit();
          var map = L.map('map', {
            zoomControl: false,
            attributionControl: false,
            dragging: $interactive,
            touchZoom: $interactive,
            scrollWheelZoom: $interactive,
            doubleClickZoom: $interactive,
            boxZoom: $interactive
          }).setView([$latStr, $lonStr], 16);
          $zoomControlJs
          L.tileLayer('https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}', {
            subdomains: ['1','2','3','4'], maxZoom: 18, minZoom: 3
          }).addTo(map);
          var halo = L.circle([$latStr, $lonStr], {
            radius: 45, color: '#1d4ed8', weight: 1, fillColor: '#3b82f6', fillOpacity: 0.16
          }).addTo(map);
          var dot = L.circleMarker([$latStr, $lonStr], {
            radius: 8, color: '#ffffff', weight: 3, fillColor: '#1d4ed8', fillOpacity: 1
          }).addTo(map);
          window.updateCarPos = function(nlat, nlon) {
            var ll = [nlat, nlon];
            halo.setLatLng(ll);
            dot.setLatLng(ll);
            map.setView(ll, map.getZoom());
          };
          window.addEventListener('resize', function(){ fit(); map.invalidateSize(); });
          setTimeout(function(){ fit(); map.invalidateSize(); map.setView([$latStr, $lonStr], 16); }, 250);
          setTimeout(function(){ fit(); map.invalidateSize(); }, 800);
        </script>
        </body></html>
    """.trimIndent()
}
