package com.tailg.cast;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 13 的 newProcess 还是私有兼容 API（API 14 计划移除）。
 * 这里先用它把现有的 VdHelperMain 以 shell/root 身份拉起来；
 * 以后 Shizuku 14 再迁到 UserService。
 */
public final class ShizukuProcess {

    private ShizukuProcess() {
    }

    public static Process start(String[] cmd, String[] env) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, env, null);
    }
}
