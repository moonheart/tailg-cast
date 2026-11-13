package com.tailg.probe;

import android.content.Context;

/**
 * 投屏保活：对齐 ColorOS/OPPO 的 BPM 白名单，并关闭系统对 App 的 idle/后台限制。
 *
 * <p>普通电池白名单（deviceidle whitelist）在 ColorOS 上不够：实测
 * {@code isFreezeExempt=false}，App 退到后台 3 秒就会被
 * {@code OplusHansManager freeze uid: ...} 冻结。ColorOS 真正生效的是
 * {@code /data/oplus/os/bpm/bpm.xml} 里的 {@code <p att="包名" />} 白名单；
 * 加进去后同一台手机后台 40+ 秒不再出现 freeze 日志。</p>
 *
 * <p>需要 root/Shizuku（root 身份）才能写 /data/oplus。写失败只记日志，
 * 不影响投屏本身。</p>
 */
public final class KeepAlive {

    private static volatile boolean applied;

    private KeepAlive() {
    }

    public static void apply(Context ctx) {
        if (ctx == null || applied) return;
        applied = true;
        final String pkg = ctx.getPackageName();
        new Thread(new Runnable() {
            @Override
            public void run() {
                // ColorOS BPM 白名单：/data/oplus/os/bpm/bpm.xml
                String bpm = "B=/data/oplus/os/bpm/bpm.xml; "
                        + "if [ -f \"$B\" ]; then "
                        + "grep -q '" + pkg + "' \"$B\" || { "
                        + "cp \"$B\" \"$B.bak\"; "
                        + "sed -i 's#</gs>#<p att=\"" + pkg + "\" /></gs>#' \"$B\"; }; "
                        + "fi";
                RootShell.Result r = RootShell.run(bpm);
                LogSink.line("KEEP", "ColorOS BPM 白名单: "
                        + (r.ok ? "ok" : "失败 " + r.out));

                // ColorOS 的 LcdOn fast-freezer 是另一套配置：ffPkg type="black"
                // 才是"跳过 fast freeze"的名单。这个文件只在开机时读，
                // 第一次写入后需要重启一次才生效。
                String fast = "F=/data/oplus/os/bpm/sys_elsa_config_list.xml; "
                        + "if [ -f \"$F\" ]; then "
                        + "grep -q 'type=\"black\" pkg=\"" + pkg + "\"' \"$F\" || { "
                        + "cp \"$F\" \"$F.bak\"; "
                        + "sed -i 's#<ffPkg type=\"black\" pkg=\"android\" />#"
                        + "<ffPkg type=\"black\" pkg=\"" + pkg + "\" />\\n"
                        + "    <ffPkg type=\"black\" pkg=\"android\" />#' \"$F\"; "
                        + "echo added; }; "
                        + "fi";
                RootShell.Result fr = RootShell.run(fast);
                LogSink.line("KEEP", "ColorOS fast-freezer 黑名单: "
                        + (fr.ok ? "ok" + (fr.out.contains("added") ? "（已写入，需重启生效）" : "")
                        : "失败 " + fr.out));

                KeepAlive.run("am set-inactive " + pkg + " false", "am set-inactive false");
                KeepAlive.run("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                        "appops RUN_ANY_IN_BACKGROUND");
                KeepAlive.run("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow",
                        "appops RUN_IN_BACKGROUND");
                KeepAlive.run("cmd deviceidle whitelist +" + pkg, "deviceidle whitelist");
            }
        }, "keepalive").start();
    }

    private static void run(String cmd, String tag) {
        RootShell.Result r = RootShell.run(cmd);
        LogSink.line("KEEP", tag + ": " + (r.ok ? "ok" : "失败 " + r.out));
    }
}
