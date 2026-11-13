package com.tailg.cast

import android.content.Context
import androidx.annotation.Keep
import com.tailg.probe.VdHelperMain

/**
 * 运行在 priv-kit 特权服务进程里的虚拟屏助手 UserService。
 * 无论是 Root、内置无线调试 ADB 还是 Shizuku 委托启动，都通过它直接向 App 提供 IVdHelper Binder。
 */
internal class VdUserService : VdHelperMain.Impl {
    @Keep
    constructor() : super()

    @Keep
    constructor(context: Context) : super(context)

    override fun exit() {
        // UserService 生命周期由 priv-kit 管理，不能在断开时 System.exit(0) 杀掉整个特权服务。
    }
}
