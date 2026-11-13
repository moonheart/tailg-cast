package com.tailg.cast

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.tailg.probe.CastEngine
import com.tailg.probe.LogSink
import com.tailg.probe.NavAutoState
import com.tailg.probe.RootShell

/**
 * 只在 NavAutoState 的待点击窗口里工作：
 * 找高德摩托车路线页的「开始导航」，取它的屏幕坐标发真实点击。
 *
 * 不能用 ACTION_CLICK：高德这个按钮是 AJX 自绘控件，ACTION_CLICK 会返回
 * true 但页面不响应；必须注入真实触摸（虚拟屏走助手 injectTap，手机屏走
 * dispatchGesture / input tap）。
 *
 * 不点「同意」：第一次摩托车导航的隐私提示必须用户自己确认。
 */
class NavAccessibilityService : AccessibilityService() {

    private var lastConsentLog = 0L
    private var lastScan = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            serviceInfo = serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        } catch (_: Throwable) {
        }
        LogSink.line("NAV", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!NavAutoState.armed()) return
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrEmpty() && pkg !in MAP_PKGS) return
        clickStartIfPresent()
    }

    override fun onInterrupt() {
    }

    private fun clickStartIfPresent() {
        if (!NavAutoState.armed()) return
        val now = System.currentTimeMillis()
        if (now - lastScan < 250) return
        lastScan = now
        val windows = collectWindows(NavAutoState.targetDisplay())
        for (w in windows) {
            try {
                val root = w.root ?: continue
                val rootPkg = root.packageName?.toString()
                if (!rootPkg.isNullOrEmpty() && rootPkg !in MAP_PKGS) continue
                if (hasConsent(root)) {
                    if (now - lastConsentLog > 10000) {
                        lastConsentLog = now
                        LogSink.line("NAV", "等待用户同意摩托车导航提示（不自动点同意）")
                    }
                    return
                }
                val node = findStart(root, 0) ?: continue
                val target = clickableTarget(node) ?: continue
                val rect = Rect()
                target.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) continue
                if (!NavAutoState.allowClick()) return
                val cx = rect.centerX()
                val cy = rect.centerY()
                val ok = tap(w.displayId, cx, cy)
                LogSink.line(
                    "NAV",
                    "点击开始导航 display=${w.displayId} at ($cx,$cy) ok=$ok",
                )
                return
            } catch (_: Throwable) {
            }
        }
    }

    /** 真实触摸：虚拟屏优先用投屏助手，手机屏用无障碍手势。 */
    private fun tap(displayId: Int, x: Int, y: Int): Boolean {
        if (displayId > 0) {
            val engine = CastEngine.current()
            if (engine != null) {
                engine.injectTapOn(displayId, x, y)
                return true
            }
            val r = RootShell.run("input -d $displayId tap $x $y")
            if (r.ok) return true
        }
        if (displayId == 0) {
            try {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                    .build()
                if (dispatchGesture(gesture, null, null)) return true
            } catch (_: Throwable) {
            }
            return RootShell.run("input tap $x $y").ok
        }
        return false
    }

    private fun collectWindows(targetDisplay: Int): List<AccessibilityWindowInfo> {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val all = getWindowsOnAllDisplays()
                val out = ArrayList<AccessibilityWindowInfo>()
                for (i in 0 until all.size()) {
                    val displayId = all.keyAt(i)
                    if (targetDisplay > 0 && displayId != targetDisplay) continue
                    all.valueAt(i)?.let { out.addAll(it) }
                }
                return out
            } catch (_: Throwable) {
            }
        }
        return try {
            getWindows().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun findStart(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (desc in START_LABELS || text in START_LABELS) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findStart(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** 从文字节点往上找真正可点击的父容器（AJX 按钮通常在外层）。 */
    private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable && cur.isVisibleToUser) return cur
            cur = cur.parent
            depth++
        }
        return if (node.isVisibleToUser) node else null
    }

    private fun hasConsent(root: AccessibilityNodeInfo): Boolean {
        return findConsent(root, 0)
    }

    private fun findConsent(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 40) return false
        if (node.isVisibleToUser) {
            val text = node.text?.toString()
            val rid = node.viewIdResourceName
            if (text != null && text.contains("摩托车导航使用提示")) return true
            if (rid != null && rid.endsWith("navi_agreement_agree_btn")) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findConsent(child, depth + 1)) return true
        }
        return false
    }

    companion object {
        private val MAP_PKGS = setOf("com.autonavi.minimap", "com.baidu.BaiduMap")
        private val START_LABELS = setOf("开始导航", "开始骑行")

        @Volatile
        var instance: NavAccessibilityService? = null
            private set
    }
}
