package com.tailg.cast

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.suspendCancellableCoroutine
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.ui.PrivilegeUiExternalStartSnapshot
import priv.kit.ui.PrivilegeUiStreamingExternalStartProvider
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal object ShizukuExternalStartProvider : PrivilegeUiStreamingExternalStartProvider {
    override val id: String = "shizuku"
    override val label: CharSequence = "Shizuku"

    override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
        runCatching { shizukuSnapshot() }.getOrElse { throwable ->
            PrivilegeUiExternalStartSnapshot(
                message = throwable.message ?: throwable.javaClass.name,
                exceptionText = throwable.stackTraceToString(),
            )
        }

    override suspend fun requestAuthorization(context: Context): PrivilegeUiExternalStartSnapshot {
        val current = snapshot(context)
        if (!current.available || current.canStart || Shizuku.shouldShowRequestPermissionRationale()) {
            return current
        }

        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var listener: Shizuku.OnRequestPermissionResultListener
            fun finish(result: PrivilegeUiExternalStartSnapshot) {
                if (!completed.compareAndSet(false, true)) return
                Shizuku.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) continuation.resume(result)
            }

            listener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
                if (requestCode == ShizukuBridge.REQUEST_CODE) {
                    finish(shizukuSnapshot())
                }
            }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    finish(shizukuSnapshot())
                } else {
                    Shizuku.requestPermission(ShizukuBridge.REQUEST_CODE)
                }
            } catch (throwable: Throwable) {
                finish(
                    PrivilegeUiExternalStartSnapshot(
                        message = throwable.message ?: throwable.javaClass.name,
                        exceptionText = throwable.stackTraceToString(),
                    ),
                )
            }
        }
    }

    override suspend fun start(context: Context, commandLine: String) {
        ShizukuExternalStarter(context).use { starter ->
            starter.start(commandLine)
        }
    }

    override suspend fun start(
        context: Context,
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener,
    ) {
        ShizukuExternalStarter(context).use { starter ->
            starter.start(commandLine, startupLogListener)
        }
    }

    private fun shizukuSnapshot(): PrivilegeUiExternalStartSnapshot {
        if (!Shizuku.pingBinder()) {
            return PrivilegeUiExternalStartSnapshot(
                message = "Shizuku 未运行，请先启动 Shizuku",
            )
        }
        if (Shizuku.isPreV11()) {
            return PrivilegeUiExternalStartSnapshot(
                message = "Shizuku 版本过旧（需 v11+）",
            )
        }
        val version = Shizuku.getVersion()
        val uid = Shizuku.getUid().takeIf { it >= 0 }
        if (version < ShizukuExternalStarter.SHIZUKU_USER_SERVICE_MIN_VERSION) {
            return PrivilegeUiExternalStartSnapshot(
                available = false,
                uid = uid,
                version = version,
                message = "Shizuku 版本不支持 UserService",
            )
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = true,
                uid = uid,
                version = version,
                message = "Shizuku 已授权，可启动",
            )
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return PrivilegeUiExternalStartSnapshot(
                available = true,
                uid = uid,
                version = version,
                message = "Shizuku 权限被拒绝，请在 Shizuku 中开启授权",
            )
        }
        return PrivilegeUiExternalStartSnapshot(
            available = true,
            uid = uid,
            version = version,
            message = "需要授权 Shizuku 权限",
        )
    }
}
