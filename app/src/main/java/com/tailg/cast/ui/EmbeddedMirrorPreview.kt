package com.tailg.cast.ui

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tailg.probe.CastEngine
import com.tailg.probe.PreviewDecoder

/**
 * 将正在投屏的虚拟屏画面直接嵌入到投屏卡片中，支持单指拖动与双指缩放触控回传。
 */
@Composable
fun EmbeddedMirrorPreview(
    dispW: Int,
    dispH: Int,
    contentTop: Int,
    contentH: Int,
    modifier: Modifier = Modifier,
    zoom: Float = 1f,
    panX: Float = 0.5f,
    showBadge: Boolean = false,
) {
    val safeW = dispW.coerceAtLeast(1)
    val safeH = dispH.coerceAtLeast(1)
    val aspect = safeW.toFloat() / safeH.toFloat()

    var activeSurface by remember { mutableStateOf<Surface?>(null) }
    var decoder by remember { mutableStateOf<PreviewDecoder?>(null) }
    var sink by remember { mutableStateOf<CastEngine.PreviewSink?>(null) }

    fun detach() {
        val eng = CastEngine.current()
        if (eng != null && sink != null) {
            eng.setPreviewSink(null)
        }
        sink = null
        decoder?.stop()
        decoder = null
    }

    fun attach(surface: Surface) {
        val eng = CastEngine.current() ?: return
        if (!eng.isRunning) return
        detach()
        val dec = PreviewDecoder()
        val newSink = CastEngine.PreviewSink { nal, key ->
            dec.feed(nal, key)
        }
        decoder = dec
        sink = newSink
        eng.setPreviewSink(newSink)
        dec.start(surface, safeW, safeH, eng.codecConfigBytes())
        eng.requestKeyFrame()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        activeSurface?.let { attach(it) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        detach()
    }

    DisposableEffect(Unit) {
        onDispose {
            detach()
            activeSurface?.release()
            activeSurface = null
        }
    }

    val safeZoom = zoom.coerceIn(1f, 2.5f)
    val safePan = panX.coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black),
    ) {
        val baseH = maxWidth / aspect
        val viewW = maxWidth * safeZoom
        val viewH = baseH * safeZoom
        val offsetX = -(viewW - maxWidth) * safePan
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewH),
        ) {
            AndroidView(
                modifier = Modifier
                    .requiredWidth(viewW)
                    .height(viewH)
                    .offset(x = offsetX),
                factory = { context ->
                var lastMoveAt = 0L
                TextureView(context).apply {
                    isOpaque = true
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val s = Surface(st)
                            activeSurface = s
                            attach(s)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            detach()
                            activeSurface?.release()
                            activeSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                    setOnTouchListener { v, event ->
                        val eng = CastEngine.current() ?: return@setOnTouchListener true
                        val action = event.actionMasked
                        if (action == MotionEvent.ACTION_DOWN) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        if (action == MotionEvent.ACTION_MOVE) {
                            val now = System.currentTimeMillis()
                            if (now - lastMoveAt < 12) return@setOnTouchListener true
                            lastMoveAt = now
                        }
                        val vw = v.width
                        val vh = v.height
                        if (vw <= 0 || vh <= 0) return@setOnTouchListener true
                        val s = minOf(vw / safeW.toFloat(), vh / safeH.toFloat())
                        val ox = (vw - safeW * s) / 2f
                        val oy = (vh - safeH * s) / 2f
                        val count = event.pointerCount
                        val ids = IntArray(count)
                        val xs = IntArray(count)
                        val ys = IntArray(count)
                        for (i in 0 until count) {
                            ids[i] = event.getPointerId(i)
                            val dx = ((event.getX(i) - ox) / s).toInt().coerceIn(0, safeW - 1)
                            val fy = ((event.getY(i) - oy) / s).toInt()
                            if (fy < contentTop || fy >= contentTop + contentH) {
                                return@setOnTouchListener true
                            }
                            val dy = (fy - contentTop).coerceIn(0, contentH - 1)
                            xs[i] = dx
                            ys[i] = dy
                        }
                        eng.touchMulti(action, event.actionIndex, ids, xs, ys)
                        true
                    }
                }
            },
        )

        if (showBadge) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                Text(
                    "实时画面 · 可直接触控",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
        }
    }
}
