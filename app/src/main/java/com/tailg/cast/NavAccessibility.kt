package com.tailg.cast

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.tailg.probe.RootShell

/** 无障碍服务的状态检查和开启入口。 */
object NavAccessibility {

    data class EnableResult(
        val ok: Boolean,
        val via: String = "",
        val detail: String = "",
    )

    fun enableViaPrivileged(ctx: Context): EnableResult {
        if (isEnabled(ctx)) return EnableResult(true, "已开启")
        val comp = "${ctx.packageName}/${NavAccessibilityService::class.java.name}"
        val short = "${ctx.packageName}/.${NavAccessibilityService::class.java.simpleName}"
        val cur = try {
            Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val existing = cur.split(':')
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.equals("null", true) &&
                    !it.equals(comp, true) &&
                    !it.equals(short, true)
            }
        val next = (existing + comp).joinToString(":")
        val cmd = "settings put secure enabled_accessibility_services '$next' && " +
            "settings put secure accessibility_enabled 1"

        var lastDetail = ""
        if (ShizukuBridge.isGranted()) {
            val r = ShizukuBridge.run(cmd, "enable-accessibility")
            if (r.ok && isEnabled(ctx)) return EnableResult(true, "Shizuku")
            lastDetail = r.out.orEmpty()
        }
        val rootRes = RootShell.run(cmd)
        if (rootRes.ok && isEnabled(ctx)) return EnableResult(true, "Root")
        if (rootRes.out.isNotBlank()) lastDetail = rootRes.out
        return EnableResult(false, "", lastDetail)
    }

    fun isEnabled(ctx: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                ctx.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0,
            ) == 1
            if (!enabled) return false
            val services = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val short = "${ctx.packageName}/.${NavAccessibilityService::class.java.simpleName}"
            val full = "${ctx.packageName}/${NavAccessibilityService::class.java.name}"
            services.split(':').any { it.equals(short, true) || it.equals(full, true) }
        } catch (_: Throwable) {
            false
        }
    }

    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Throwable) {
        }
    }
}
