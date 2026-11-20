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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tailg.cast.GeoSearchHit
import com.tailg.cast.GeocodeHelper
import com.tailg.probe.LogSink
import java.util.Locale

/**
 * 全屏地图选点。地图用高德公开瓦片（GCJ-02），点地图返回经纬度。
 * 顶部搜索框按地名/地址搜索；备注字段用于给收藏点起名或补充地址。
 * 瓦片只是地图背景，不调用高德 SDK，也不需要 key。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapPickerDialog(
    initialLat: Double,
    initialLon: Double,
    initialNote: String = "",
    title: String = "地图选点",
    confirmLabel: String = "使用此点",
    requirePick: Boolean = false,
    onPick: (lat: Double, lon: Double, placeName: String, address: String, note: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pickedLat by remember { mutableStateOf(initialLat) }
    var pickedLon by remember { mutableStateOf(initialLon) }
    var pickedPlaceName by remember { mutableStateOf("") }
    var pickedAddress by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(!requirePick) }
    var note by remember { mutableStateOf(initialNote) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<GeoSearchHit>>(emptyList()) }
    var searchNote by remember { mutableStateOf("") }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val ctx = LocalContext.current
    val leafletCss = remember { readAsset(ctx, "leaflet/leaflet.css") }
    val leafletJs = remember { readAsset(ctx, "leaflet/leaflet.js") }

    val startSearch: () -> Unit = {
        val q = query.trim()
        when {
            q.isEmpty() -> searchNote = "输入地名或地址再搜索"
            searching -> Unit
            else -> {
                searching = true
                searchNote = ""
                results = emptyList()
                GeocodeHelper.search(q) { hits, online ->
                    searching = false
                    results = hits
                    searchNote = when {
                        hits.isNotEmpty() -> ""
                        online -> "没有找到匹配地点，换个关键词试试"
                        else -> "搜索失败，请检查网络后重试"
                    }
                }
            }
        }
    }

    fun selectHit(hit: GeoSearchHit) {
        pickedLat = hit.lat
        pickedLon = hit.lon
        pickedPlaceName = hit.name
        pickedAddress = hit.address
        picked = true
        query = hit.name
        results = emptyList()
        searchNote = ""
        keyboard?.hide()
        webRef?.evaluateJavascript(
            "window.pickAt && window.pickAt(${fmt(hit.lat)}, ${fmt(hit.lon)}, 17)",
            null,
        )
        LogSink.line(
            "MAP",
            "search pick ${hit.source} ${hit.name} ${fmt(hit.lat)},${fmt(hit.lon)}",
        )
    }

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
                        title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (requirePick) "备注" else "备注 / 地址", fontSize = 12.sp) },
                    placeholder = {
                        Text(
                            if (requirePick) "如：家 / 公司（留空则用地点名）" else "可选",
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                )
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
                                                pickedPlaceName = ""
                                                pickedAddress = ""
                                                picked = true
                                                results = emptyList()
                                                searchNote = ""
                                                keyboard?.hide()
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TextField(
                            value = query,
                            onValueChange = {
                                query = it
                                results = emptyList()
                                searchNote = ""
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .shadow(6.dp, RoundedCornerShape(14.dp)),
                            placeholder = {
                                Text("搜索地点或地址", fontSize = 13.sp, maxLines = 1)
                            },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            trailingIcon = {
                                if (searching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (query.isNotEmpty()) {
                                            IconButton(
                                                onClick = {
                                                    query = ""
                                                    results = emptyList()
                                                    searchNote = ""
                                                },
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = "清空",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                keyboard?.hide()
                                                startSearch()
                                            },
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Search,
                                                contentDescription = "搜索",
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    keyboard?.hide()
                                    startSearch()
                                },
                            ),
                        )
                        if (results.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 6.dp,
                            ) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 250.dp),
                                ) {
                                    itemsIndexed(results) { index, hit ->
                                        SearchHitRow(hit) { selectHit(hit) }
                                        if (index != results.lastIndex) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = 0.5f),
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (searchNote.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 4.dp,
                            ) {
                                Text(
                                    searchNote,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (pickedPlaceName.isNotBlank()) {
                    Text(
                        pickedPlaceName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (pickedAddress.isNotBlank()) {
                    Text(
                        pickedAddress,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (picked) {
                    Text(
                        "已选：${fmt(pickedLat)}, ${fmt(pickedLon)} · GCJ-02",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text(
                        "点击地图或搜索地点后保存",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "在地图上点选，或搜索地名/地址后选结果定位。地图瓦片 © 高德地图，搜索由百度/OpenStreetMap 提供；坐标按 GCJ-02 保存。",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        onPick(pickedLat, pickedLon, pickedPlaceName, pickedAddress, note.trim())
                    },
                    enabled = picked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) { Text(confirmLabel) }
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

@Composable
private fun SearchHitRow(hit: GeoSearchHit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hit.name,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hit.source,
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hit.address.isNotBlank()) {
            Text(
                hit.address,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
          #hint { position:fixed; left:50%; bottom:12px; transform:translateX(-50%); z-index:9998;
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
            var map = L.map('map', {zoomControl:false, attributionControl:false}).setView([$la,$lo], 16);
            L.control.zoom({position:'bottomright'}).addTo(map);
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
            window.pickAt = function(lat, lon, zoom) {
              marker.setLatLng([lat, lon]);
              map.setView([lat, lon], zoom || 17);
            };
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
