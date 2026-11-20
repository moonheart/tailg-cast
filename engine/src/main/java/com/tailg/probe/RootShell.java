package com.tailg.probe;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** 通过 KernelSU/Magisk 的 su 执行命令；首次会弹授权框。 */
public final class RootShell {

    private RootShell() {}

    public static final class Result {
        public final boolean ok;
        public final String out;

        public Result(boolean ok, String out) {
            this.ok = ok;
            this.out = out;
        }
    }

    /** App 可以把 shell 执行后端换成 Shizuku；为 null 时走 su。 */
    public interface Executor {
        Result run(String cmd, String shown);
    }

    private static volatile Executor sExecutor;

    public static void setExecutor(Executor e) {
        sExecutor = e;
    }

    public static Result run(String cmd) {
        return exec(cmd, cmd);
    }

    /** 同样的执行，但日志里只打 shown（用于带密码的命令）。 */
    public static Result runRedacted(String cmd, String shown) {
        return exec(cmd, shown);
    }

    /** 强制走 su（uid=0），不走 Shizuku（某些系统权限接口需要真 root）。 */
    public static Result runSu(String cmd) {
        return execSu(cmd, cmd);
    }

    private static Result exec(String cmd, String shown) {
        Executor e = sExecutor;
        if (e != null) {
            try {
                return e.run(cmd, shown);
            } catch (Throwable t) {
                LogSink.line("ROOT", "Shizuku 执行失败: " + shown + " -> " + t);
                return new Result(false, String.valueOf(t));
            }
        }
        return execSu(cmd, shown);
    }

    private static Result execSu(String cmd, String shown) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            while ((line = er.readLine()) != null) sb.append(line).append('\n');
            boolean done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
            int code = done ? p.exitValue() : -1;
            Result res = new Result(done && code == 0, sb.toString().trim());
            LogSink.line("ROOT", "su -c \"" + shown + "\" -> code=" + code + " " + res.out);
            return res;
        } catch (Throwable t) {
            LogSink.line("ROOT", "su 不可用: " + t);
            return new Result(false, String.valueOf(t));
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 切到车机比例（逻辑分辨率覆盖）。 */
    public static boolean applyCarRatio(int w, int h, int density) {
        // 关自动旋转并锁到 ROTATION_0，保证 override 尺寸按横向呈现
        String cmd = "settings put system accelerometer_rotation 0"
                + " ; settings put system user_rotation 0"
                + " ; wm size " + w + "x" + h
                + (density > 0 ? " ; wm density " + density : "");
        Result r = run(cmd);
        return r.ok;
    }

    /** 还原手机原始分辨率。 */
    public static boolean resetRatio() {
        Result r = run("wm size reset ; wm density reset ; settings put system accelerometer_rotation 1");
        return r.ok;
    }
}
