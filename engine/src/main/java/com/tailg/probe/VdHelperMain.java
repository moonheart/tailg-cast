package com.tailg.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * root 助手进程入口（由 App 用 su 启动）：
 *
 *   su -c 'CLASSPATH=<apk> app_process /system/bin --nice-name=tailg-vd \
 *           com.tailg.probe.VdHelperMain com.tailg.probe.vd'
 *
 * 它做的事：拿系统 Context → 建虚拟屏（可用 TRUSTED/OWN_FOCUS/PUBLIC 等隐藏 flag）
 * → 以 shell 身份把 App 启动/搬到这块屏 → 定期喂 userActivity 保活。
 * 画面不经过它：App 把自己的编码器 Surface 通过 binder 递进来，屏直接渲染进编码器。
 */
public final class VdHelperMain {

    private static Context sCtx;
    private static final Map<Integer, Object> DISPLAYS = new HashMap<>();
    private static final Map<Integer, Boolean> AWAKE = new HashMap<>();
    private static final Map<Integer, Boolean> IGNORE_APPLIED = new HashMap<>();
    private static Handler sMain;   // 静态初始化阶段还没有 Looper，必须延迟创建
    private static HandlerThread sAwakeThread;
    private static Object sPower;
    private static Method sUserActivity;

    public static void main(String[] args) {
        Looper.prepareMainLooper();
        final String authority = args.length > 0 ? args[0] : VdProvider.AUTHORITY;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            sCtx = (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            System.err.println("[helper] systemMain 失败: " + t);
            System.exit(2);
            return;
        }
        try {
            init(sCtx);
            Bundle in = new Bundle();
            in.putBinder("helper", new Impl());
            // 不能用 ContentResolver.call：本进程没有 ActivityManager 里的 app 记录，
            // 会报 "Unable to find app for caller"；绕过它直接拿 provider 的 binder。
            Object provider = getContentProviderExternal(authority);
            Bundle out = providerCall(provider, authority, "hello", in);
            IBinder token = out == null ? null : out.getBinder("token");
            if (token != null) {
                try {
                    token.linkToDeath(new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            System.err.println("[helper] App 退出，助手跟着退出");
                            System.exit(0);
                        }
                    }, 0);
                } catch (Throwable ignored) {
                }
            }
            System.err.println("[helper] 已就绪 authority=" + authority + " uid=" + android.os.Process.myUid());
        } catch (Throwable t) {
            System.err.println("[helper] 会合失败: " + t);
            System.exit(3);
            return;
        }
        Looper.loop();
    }


    static void init(Context ctx) {
        sCtx = ctx;
        preparePower();
        prepareInput();
    }

    /**
     * 以 root 建屏时直接用系统 Context；以 shell（Shizuku/ADB）建屏时，
     * DisplayManagerService 会校验 packageName 必须属于调用 UID，
     * 所以要用一个 getPackageName()="com.android.shell" 的 Context 走 DisplayManagerGlobal。
     */
    private static Object createVirtualDisplayCompat(
            android.hardware.display.VirtualDisplayConfig config) throws Exception {
        int uid = android.os.Process.myUid();
        if (uid == 0 || uid == 1000) {
            Object dm = sCtx.getSystemService(Context.DISPLAY_SERVICE);
            Method m = dm.getClass().getMethod("createVirtualDisplay",
                    android.hardware.display.VirtualDisplayConfig.class);
            return m.invoke(dm, config);
        }
        String pkg = null;
        try {
            String[] pkgs = sCtx.getPackageManager().getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) pkg = pkgs[0];
        } catch (Throwable ignored) {
        }
        if (pkg == null || pkg.length() == 0) pkg = "com.android.shell";
        final String packageName = pkg;
        Context wrapper = new ContextWrapper(sCtx) {
            @Override
            public String getPackageName() {
                return packageName;
            }

            @Override
            public String getOpPackageName() {
                return packageName;
            }
        };
        Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object global = dmg.getMethod("getInstance").invoke(null);
        Method m = dmg.getMethod("createVirtualDisplay", Context.class,
                Class.forName("android.media.projection.MediaProjection"),
                android.hardware.display.VirtualDisplayConfig.class,
                android.hardware.display.VirtualDisplay.Callback.class,
                java.util.concurrent.Executor.class);
        System.err.println("[helper] 非 root 建屏 uid=" + uid + " package=" + packageName);
        return m.invoke(global, wrapper, null, config, null, null);
    }

    /** IActivityManager.getContentProviderExternal(name, userId, token[, tag])，拿 IContentProvider。 */
    private static Object getContentProviderExternal(String authority) throws Exception {
        Object iam = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        IBinder token = new android.os.Binder();
        for (Method m : iam.getClass().getMethods()) {
            if (!"getContentProviderExternal".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            try {
                Object holder;
                if (p.length == 4) {
                    holder = m.invoke(iam, authority, 0, token, "tailg-vd");
                } else if (p.length == 3) {
                    holder = m.invoke(iam, authority, 0, token);
                } else {
                    continue;
                }
                if (holder == null) continue;
                Object provider = holder.getClass().getField("provider").get(holder);
                if (provider != null) return provider;
            } catch (Throwable ignored) {
            }
        }
        throw new RuntimeException("拿不到 provider: " + authority);
    }

    /** IContentProvider.call(...)，兼容 5 参数的不同版本签名。 */
    private static Bundle providerCall(Object provider, String authority, String method, Bundle extras) throws Exception {
        for (Method m : provider.getClass().getMethods()) {
            if (!"call".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 5) continue;
            Object first;
            if (p[0] == String.class) {
                first = "com.android.shell";
            } else {
                Object builder = Class.forName("android.content.AttributionSource$Builder")
                        .getConstructor(int.class).newInstance(android.os.Process.myUid());
                builder.getClass().getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
                first = builder.getClass().getMethod("build").invoke(builder);
            }
            try {
                return (Bundle) m.invoke(provider, first, authority, method, null, extras);
            } catch (Throwable t) {
                System.err.println("[helper] providerCall 失败: " + cause(t));
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- binder 实现

    private static final class Impl extends IVdHelper.Stub {

        @Override
        public int createDisplay(Surface surface, int w, int h, int dpi, int flags) {
            return createDisplay(surface, w, h, dpi, flags, false);
        }

        @Override
        public int createDisplay(Surface surface, int w, int h, int dpi, int flags,
                                 boolean ignoreSizeRestrictions) {
            Object vd = null;
            boolean configMode = false;
            boolean ignoreApplied = false;

            // 新路径：VirtualDisplayConfig + setIgnoreActivitySizeRestrictions。
            // 后者是 @SystemApi/@FlaggedApi；反射不到或被系统忽略时，仍可回退旧接口。
            try {
                android.hardware.display.VirtualDisplayConfig.Builder b =
                        new android.hardware.display.VirtualDisplayConfig.Builder(
                                "tailg-vd", w, h, dpi)
                                .setFlags(flags)
                                .setSurface(surface);
                if (ignoreSizeRestrictions) {
                    try {
                        b.getClass().getMethod("setIgnoreActivitySizeRestrictions", boolean.class)
                                .invoke(b, true);
                    } catch (Throwable t) {
                        System.err.println("[helper] setIgnoreActivitySizeRestrictions 不可用: "
                                + cause(t));
                    }
                }
                android.hardware.display.VirtualDisplayConfig config = b.build();
                try {
                    Object applied = config.getClass()
                            .getMethod("isIgnoreActivitySizeRestrictions").invoke(config);
                    ignoreApplied = Boolean.TRUE.equals(applied);
                } catch (Throwable t) {
                    System.err.println("[helper] isIgnoreActivitySizeRestrictions 不可用: "
                            + cause(t));
                }
                vd = createVirtualDisplayCompat(config);
                configMode = true;
                System.err.println("[helper] 建屏(Config) " + w + "x" + h + "@" + dpi
                        + " flags=" + flags + " 请求ignore=" + ignoreSizeRestrictions
                        + " 生效=" + ignoreApplied);
            } catch (Throwable t) {
                System.err.println("[helper] Config 建屏失败，回退旧接口: " + cause(t));
            }

            if (vd == null) {
                try {
                    Object dm = sCtx.getSystemService(Context.DISPLAY_SERVICE);
                    Method m = dm.getClass().getMethod("createVirtualDisplay",
                            String.class, int.class, int.class, int.class, Surface.class, int.class);
                    vd = m.invoke(dm, "tailg-vd", w, h, dpi, surface, flags);
                    System.err.println("[helper] 建屏(旧接口) " + w + "x" + h + "@" + dpi
                            + " flags=" + flags + " ignore=0");
                } catch (Throwable t) {
                    System.err.println("[helper] 建屏失败: " + cause(t));
                    return -1;
                }
            }
            if (vd == null) return -1;
            try {
                int id = ((android.view.Display) vd.getClass().getMethod("getDisplay").invoke(vd))
                        .getDisplayId();
                synchronized (DISPLAYS) {
                    DISPLAYS.put(id, vd);
                    IGNORE_APPLIED.put(id, ignoreApplied);
                }
                startAwake(id);
                System.err.println("[helper] 建屏 id=" + id + " "
                        + (configMode ? "config" : "legacy")
                        + " ignoreApplied=" + ignoreApplied);
                return id;
            } catch (Throwable t) {
                System.err.println("[helper] 取 displayId 失败: " + cause(t));
                return -1;
            }
        }

        @Override
        public boolean ignoreApplied(int displayId) {
            synchronized (DISPLAYS) {
                return Boolean.TRUE.equals(IGNORE_APPLIED.get(displayId));
            }
        }

        @Override
        public boolean launch(int displayId, String component) {
            ComponentName cn = ComponentName.unflattenFromString(component);
            if (cn == null) return false;
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(cn);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                android.os.Bundle options = android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(displayId).toBundle();
                for (Method m : atm.getClass().getMethods()) {
                    if (!"startActivityAsUser".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 12) continue;
                    // (caller, callingPackage, featureId, intent, resolvedType, resultTo,
                    //  resultWho, requestCode, startFlags, profilerInfo, options, userId)
                    m.invoke(atm, null, "com.android.shell", null, intent, null, null, null,
                            0, 0, null, options, -2 /* USER_CURRENT */);
                    System.err.println("[helper] launch ok display=" + displayId + " " + component);
                    return true;
                }
            } catch (Throwable t) {
                System.err.println("[helper] launch 反射失败: " + cause(t));
            }
            // 兜底：shell 命令
            int rc = exec("am start --display " + displayId + " -n " + component);
            System.err.println("[helper] launch 兜底 rc=" + rc);
            return rc == 0;
        }

        @Override
        public int taskOf(String pkg) {
            String out = execOut("dumpsys activity activities | grep -m1 'type=standard A=[0-9]*:"
                    + pkg + "'");
            Matcher m = Pattern.compile("Task\\{[0-9a-f]+ #(\\d+)").matcher(out);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (Exception ignored) {
                }
            }
            return -1;
        }

        @Override
        public boolean moveTask(int taskId, int displayId) {
            try {
                Object atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                Method m = atm.getClass().getMethod("moveRootTaskToDisplay", int.class, int.class);
                m.invoke(atm, taskId, displayId);
                return true;
            } catch (Throwable t) {
                System.err.println("[helper] moveTask 反射失败: " + cause(t));
            }
            return exec("am display move-stack " + taskId + " " + displayId) == 0;
        }

        @Override
        public boolean resize(int displayId, int w, int h, int dpi) {
            Object vd;
            synchronized (DISPLAYS) {
                vd = DISPLAYS.get(displayId);
            }
            if (vd == null) return false;
            try {
                vd.getClass().getMethod("resize", int.class, int.class, int.class).invoke(vd, w, h, dpi);
                return true;
            } catch (Throwable t) {
                System.err.println("[helper] resize 失败: " + cause(t));
                return false;
            }
        }

        @Override
        public boolean injectTap(int displayId, int x, int y) {
            long t = SystemClock.uptimeMillis();
            boolean ok = sendMotion(displayId, MotionEvent.ACTION_DOWN, x, y, t, t);
            ok &= sendMotion(displayId, MotionEvent.ACTION_UP, x, y, t, t + 40);
            return ok;
        }

        @Override
        public boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) {
            long down = SystemClock.uptimeMillis();
            boolean ok = sendMotion(displayId, MotionEvent.ACTION_DOWN, x1, y1, down, down);
            int steps = Math.max(6, Math.min(24, durationMs / 16));
            for (int i = 1; i <= steps; i++) {
                float k = i / (float) steps;
                int x = (int) (x1 + (x2 - x1) * k);
                int y = (int) (y1 + (y2 - y1) * k);
                ok &= sendMotion(displayId, MotionEvent.ACTION_MOVE, x, y, down,
                        down + (long) (durationMs * k));
            }
            ok &= sendMotion(displayId, MotionEvent.ACTION_UP, x2, y2, down, down + durationMs);
            return ok;
        }

        @Override
        public boolean injectKey(int displayId, int keyCode) {
            try {
                long t = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0);
                KeyEvent up = new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, keyCode, 0);
                boolean ok = injectEvent(displayId, down);
                ok &= injectEvent(displayId, up);
                return ok;
            } catch (Throwable t) {
                System.err.println("[helper] injectKey 失败: " + cause(t));
                return false;
            }
        }

        private long downTime = 0;

        @Override
        public boolean touch(int displayId, int action, int x, int y) {
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN) {
                downTime = now;
            } else if (downTime == 0) {
                downTime = now;   // 丢了 DOWN 也能兜住
            }
            boolean ok = sendMotion(displayId, action, x, y, downTime, now);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                downTime = 0;
            }
            return ok;
        }

        @Override
        public boolean touchMulti(int displayId, int action, int index, int[] ids, int[] xs, int[] ys) {
            if (ids == null || xs == null || ys == null || ids.length == 0) return false;
            int n = ids.length;
            long now = SystemClock.uptimeMillis();
            int masked = action & MotionEvent.ACTION_MASK;
            if (masked == MotionEvent.ACTION_DOWN) {
                downTime = now;
            } else if (downTime == 0) {
                downTime = now;
            }
            int a = action;
            if (masked == MotionEvent.ACTION_POINTER_DOWN || masked == MotionEvent.ACTION_POINTER_UP) {
                a = masked | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
            try {
                MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[n];
                MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[n];
                for (int i = 0; i < n; i++) {
                    props[i] = new MotionEvent.PointerProperties();
                    props[i].id = ids[i];
                    props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                    coords[i] = new MotionEvent.PointerCoords();
                    coords[i].x = xs[i];
                    coords[i].y = ys[i];
                    coords[i].pressure = 1f;
                    coords[i].size = 1f;
                }
                MotionEvent ev = MotionEvent.obtain(downTime, now, a, n,
                        props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
                boolean ok = injectEvent(displayId, ev);
                ev.recycle();
                if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) {
                    downTime = 0;
                }
                return ok;
            } catch (Throwable t) {
                System.err.println("[helper] touchMulti 失败: " + cause(t));
                return false;
            }
        }

        @Override
        public void exit() {
            System.err.println("[helper] 收到退出指令");
            mainHandler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    System.exit(0);
                }
            }, 100);
        }

        @Override
        public void destroy(int displayId) {
            Object vd;
            synchronized (DISPLAYS) {
                vd = DISPLAYS.remove(displayId);
                AWAKE.remove(displayId);
            }
            if (vd == null) return;
            try {
                vd.getClass().getMethod("release").invoke(vd);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 保活

    private static void preparePower() {
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "power");
            Class<?> stub = Class.forName("android.os.IPowerManager$Stub");
            sPower = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
            for (Method m : sPower.getClass().getMethods()) {
                if ("userActivity".equals(m.getName()) && m.getParameterTypes().length == 4) {
                    sUserActivity = m;
                    break;
                }
            }
            System.err.println("[helper] power 就绪 userActivity=" + (sUserActivity != null));
        } catch (Throwable t) {
            System.err.println("[helper] power 不可用: " + t);
        }
    }

    private static Object sInputManager;
    private static Method sInjectInput;

    private static void prepareInput() {
        try {
            Class<?> c = Class.forName("android.hardware.input.InputManager");
            sInputManager = c.getMethod("getInstance").invoke(null);
            for (Method m : sInputManager.getClass().getMethods()) {
                if (!"injectInputEvent".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == android.view.InputEvent.class) {
                    sInjectInput = m;
                    break;
                }
            }
            System.err.println("[helper] input 就绪 injectInputEvent=" + (sInjectInput != null));
        } catch (Throwable t) {
            System.err.println("[helper] input 不可用: " + t);
        }
    }

    private static boolean sendMotion(int displayId, int action, int x, int y, long downTime, long eventTime) {
        try {
            MotionEvent ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            boolean ok = injectEvent(displayId, ev);
            ev.recycle();
            return ok;
        } catch (Throwable t) {
            System.err.println("[helper] sendMotion 失败: " + cause(t));
            return false;
        }
    }

    private static boolean injectEvent(int displayId, android.view.InputEvent ev) throws Exception {
        if (sInjectInput == null) return false;
        ev.getClass().getMethod("setDisplayId", int.class).invoke(ev, displayId);
        return (Boolean) sInjectInput.invoke(sInputManager, ev, 0 /* INJECT_MODE_ASYNC */);
    }

    private static void startAwake(final int displayId) {
        synchronized (AWAKE) {
            if (AWAKE.containsKey(displayId)) return;
            AWAKE.put(displayId, Boolean.TRUE);
        }
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                synchronized (AWAKE) {
                    if (!AWAKE.containsKey(displayId)) return;
                }
                if (sUserActivity != null) {
                    try {
                        sUserActivity.invoke(sPower, displayId, SystemClock.uptimeMillis(), 0, 0);
                    } catch (Throwable ignored) {
                    }
                }
                mainHandler().postDelayed(this, 4000);
            }
        });
    }

    private static synchronized Handler mainHandler() {
        if (sMain == null) {
            // 不用主 Looper：helper 在 app_process 里；Shizuku/UserService 未来接入时也安全。
            sAwakeThread = new HandlerThread("tailg-vd-awake");
            sAwakeThread.start();
            sMain = new Handler(sAwakeThread.getLooper());
        }
        return sMain;
    }

    // ---------------------------------------------------------------- shell 工具

    private static int exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String execOut(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static Throwable cause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c != c.getCause()) c = c.getCause();
        return c;
    }
}
