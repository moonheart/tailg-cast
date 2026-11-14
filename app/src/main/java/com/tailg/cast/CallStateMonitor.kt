package com.tailg.cast

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.tailg.probe.BleLink
import com.tailg.probe.CarCmd
import com.tailg.probe.Config
import com.tailg.probe.LogSink
import com.tailg.probe.ProbeService

/**
 * 监听手机来电状态，向仪表盘推送来电提醒（msg_id=3）及挂断/接听关闭信号（msg_id=9）。
 */
object CallStateMonitor {

    @Volatile
    private var started = false

    @Volatile
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    private var callback31: Any? = null
    private var legacyListener: PhoneStateListener? = null

    fun hasPermission(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (started || !hasPermission(ctx)) return
        val appCtx = ctx.applicationContext
        val tm = appCtx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(appCtx, state, null)
                    }
                }
                tm.registerTelephonyCallback(appCtx.mainExecutor, cb)
                callback31 = cb
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(appCtx, state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = listener
            }
            started = true
            LogSink.line("CALL", "来电监听已启动")
        } catch (t: Throwable) {
            LogSink.line("CALL", "启动来电监听失败: ${t.message}")
        }
    }

    private fun handleCallState(ctx: Context, state: Int, number: String?) {
        val prev = lastState
        lastState = state
        val cfg = ProbeService.config ?: Config.load(ctx)
        if (!cfg.syncCall) return
        if (!ProbeService.carOnline() && !BleLink.connected) return

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val num = number?.trim().orEmpty()
                val name = resolveContactName(ctx, num).ifEmpty {
                    num.ifEmpty { "手机来电" }
                }
                CarCmd.inCallMsg(name, num)
                LogSink.line("CALL>>", "来电提醒: $name ($num)")
            }
            TelephonyManager.CALL_STATE_OFFHOOK,
            TelephonyManager.CALL_STATE_IDLE -> {
                if (prev == TelephonyManager.CALL_STATE_RINGING) {
                    CarCmd.hangupMsg()
                    LogSink.line("CALL>>", "来电结束/接听，关闭车机弹窗")
                }
            }
        }
    }

    private fun resolveContactName(ctx: Context, number: String): String {
        if (number.isBlank()) return ""
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ""
        }
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number),
            )
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
