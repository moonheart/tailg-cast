package com.tailg.cast

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.core.PrivilegeExternalStartup
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener
import rikka.shizuku.Shizuku
import java.io.Closeable
import kotlin.time.Duration.Companion.milliseconds

/**
 * 通过 Shizuku UserService 作为外部委托启动器，拉起 priv-kit 的特权服务。
 */
internal class ShizukuExternalStarter(
    context: Context,
) : Closeable {
    private val applicationContext = context.applicationContext

    @Volatile
    private var service: IShizukuStartService? = null

    @Volatile
    private var serviceConnection: ServiceConnection? = null

    fun isAvailable(): Boolean =
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.getVersion() >= SHIZUKU_USER_SERVICE_MIN_VERSION &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    suspend fun start(
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): String {
        if (!isAvailable()) {
            throw PrivilegeStartupException("Shizuku 未连接或未授权")
        }
        val activeService = bindOrGetService()
        return PrivilegeExternalStartup.runThroughBridge(
            commandLine = commandLine,
            bridge = { bridgeCommand, stdout, stderr, resultReceiver ->
                activeService.start(bridgeCommand, stdout, stderr, resultReceiver)
            },
            startupLogListener = startupLogListener,
        )
    }

    override fun close() {
        val connection = serviceConnection ?: return
        serviceConnection = null
        service = null
        removeUserService(connection)
    }

    private suspend fun bindOrGetService(): IShizukuStartService {
        service?.takeIf { runCatching { it.asBinder().pingBinder() }.getOrDefault(false) }
            ?.let { return it }

        val binding = CompletableDeferred<IShizukuStartService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val startService = IShizukuStartService.Stub.asInterface(binder)
                if (startService == null) {
                    binding.completeExceptionally(
                        PrivilegeStartupException("Shizuku UserService 返回了无效 Binder"),
                    )
                } else {
                    binding.complete(startService)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                binding.completeExceptionally(
                    PrivilegeStartupException("Shizuku UserService 在绑定期间断开"),
                )
            }
        }

        try {
            serviceConnection = connection
            Shizuku.bindUserService(userServiceArgs(), connection)
        } catch (throwable: Throwable) {
            serviceConnection = null
            throw PrivilegeStartupException("绑定 Shizuku UserService 失败", throwable)
        }

        val bound = try {
            withTimeoutOrNull(SHIZUKU_BIND_TIMEOUT_MILLIS.milliseconds) { binding.await() }
                ?: throw PrivilegeStartupException("绑定 Shizuku UserService 超时")
        } catch (throwable: Throwable) {
            removeUserService(connection)
            serviceConnection = null
            if (throwable is CancellationException) throw throwable
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("绑定 Shizuku UserService 失败", throwable)
        }
        service = bound
        return bound
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                applicationContext.packageName,
                ShizukuStartService::class.java.name,
            ),
        )
            .daemon(false)
            .tag(SHIZUKU_START_TAG)
            .processNameSuffix(SHIZUKU_START_PROCESS_SUFFIX)
            .debuggable(applicationContext.isDebuggable())
            .version(SHIZUKU_START_SERVICE_VERSION)

    private fun removeUserService(connection: ServiceConnection) {
        val args = userServiceArgs()
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    private fun Context.isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        const val SHIZUKU_USER_SERVICE_MIN_VERSION = 10
        private const val SHIZUKU_BIND_TIMEOUT_MILLIS = 10_000L
        private const val SHIZUKU_START_TAG = "tailg-priv-kit-start"
        private const val SHIZUKU_START_PROCESS_SUFFIX = "priv-shizuku-start"
        private const val SHIZUKU_START_SERVICE_VERSION = 1
    }
}
