package com.tailg.probe;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 投屏编码：MediaProjection -> VirtualDisplay -> MediaCodec(surface) -> 裸 H.264 Annex-B。
 * 每个 15456 连接先拿 69 字节头，然后持续收 NAL。
 */
public final class CastEngine {

    public interface Listener {
        void onCastState(String state);
    }

    private final Context ctx;
    private final Config cfg;
    private final Listener listener;

    private MediaProjection projection;
    private MediaCodec codec;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private GlScaler scaler;
    private CarlifeBridge carlifeBridge;
    private int vdContentH = 600;   // 虚拟屏实际内容高度（安全区时为 castHeight-safeTop-safeBottom）
    private Thread drainThread;

    /** 手机端预览：把同一路码流再喂一份给解码器。 */
    public interface PreviewSink {
        void onFrame(byte[] nal, boolean keyFrame);
    }

    private volatile PreviewSink previewSink;
    private static volatile CastEngine sCurrent;

    public static CastEngine current() {
        return sCurrent;
    }

    public void setPreviewSink(PreviewSink s) {
        previewSink = s;
    }

    public int displayId() {
        if (helperDisplayId > 0) return helperDisplayId;
        try {
            return virtualDisplay == null ? -1 : virtualDisplay.getDisplay().getDisplayId();
        } catch (Throwable t) {
            return -1;
        }
    }

    public int castWidth() {
        return cfg.castWidth;
    }

    public int castHeight() {
        return cfg.castHeight;
    }

    /** 编码画布里的应用内容高度（安全区时小于 castHeight）。 */
    public int contentHeight() {
        return vdContentH > 0 ? vdContentH : cfg.castHeight;
    }

    /** 应用内容在编码画布里的起始 y（上下黑边时等于 safeTop，否则 0）。 */
    public int contentTop() {
        return vdContentH < cfg.castHeight ? Math.max(0, cfg.safeTop) : 0;
    }

    public byte[] codecConfigBytes() {
        return codecConfig;
    }

    /** 往虚拟屏注入一次点击：助手在就用 binder 直调，最快；否则退回 input 命令（约 400ms）。 */
    public void injectTap(final int x, final int y) {
        injectTapOn(displayId(), x, y);
    }

    /** 指定 display 的点击，避免自动导航窗口和当前投屏屏号不一致时点错屏。 */
    public void injectTapOn(final int id, final int x, final int y) {
        final CarlifeBridge cb = carlifeBridge;
        if (cb != null) {
            cb.sendTouch(0, x, y);
            cb.sendTouch(1, x, y);
            return;
        }
        final IVdHelper h = helper;
        if (h != null && id > 0) {
            try {
                h.injectTap(id, x, y);
                return;
            } catch (Throwable ignored) {
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                RootShell.run("input -d " + id + " tap " + x + " " + y);
            }
        }, "inject-tap").start();
    }

    public void injectSwipe(final int x1, final int y1, final int x2, final int y2, final int ms) {
        final int id = displayId();
        final IVdHelper h = helper;
        if (h != null && id > 0) {
            try {
                h.injectSwipe(id, x1, y1, x2, y2, ms);
                return;
            } catch (Throwable ignored) {
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                RootShell.run("input -d " + id + " swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms);
            }
        }, "inject-swipe").start();
    }

    /** 流式触摸：action 直接用 MotionEvent.ACTION_*，每次移动都调一次。 */
    public void touch(final int action, final int x, final int y) {
        final CarlifeBridge cb = carlifeBridge;
        if (cb != null) {
            int carAct;
            if (action == android.view.MotionEvent.ACTION_DOWN) carAct = 0;
            else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) carAct = 1;
            else if (action == android.view.MotionEvent.ACTION_MOVE) carAct = 2;
            else return;
            int cw = Math.max(1, cfg.castWidth);
            int ch = Math.max(1, cfg.castHeight);
            int cx = x * cb.videoWidth() / cw;
            int cy = y * cb.videoHeight() / ch;
            cb.sendTouch(carAct, cx, cy);
            return;
        }
        final int id = displayId();
        final IVdHelper h = helper;
        if (h != null && id > 0) {
            try {
                h.touch(id, action, x, y);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 多指流式触摸：ids/xs/ys 是当前所有手指，index 用于 POINTER_DOWN/UP。 */
    public void touchMulti(final int action, final int index,
                           final int[] ids, final int[] xs, final int[] ys) {
        final int id = displayId();
        final IVdHelper h = helper;
        if (h != null && id > 0) {
            try {
                h.touchMulti(id, action, index, ids, xs, ys);
                return;
            } catch (Throwable ignored) {
            }
        }
        // 没有助手时退化成单点
        if (xs.length > 0) {
            touch(action == android.view.MotionEvent.ACTION_MOVE
                    ? android.view.MotionEvent.ACTION_MOVE : action, xs[0], ys[0]);
        }
    }

    /**
     * 自测用：在虚拟屏上打一次双指张开（50 → 350 像素）。
     * 只用来验证多指通道，正常使用时由镜像页的真实触摸驱动。
     */
    public void debugPinch() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                int cx = castWidth() / 2;
                int cy = castHeight() / 2;
                int[] ids = {0, 1};
                touchMulti(android.view.MotionEvent.ACTION_DOWN, 0, new int[]{0}, new int[]{cx}, new int[]{cy});
                sleepMs(40);
                int[] xs = {cx - 50, cx + 50};
                int[] ys = {cy, cy};
                touchMulti(android.view.MotionEvent.ACTION_POINTER_DOWN, 1, ids, xs, ys);
                for (int step = 1; step <= 12; step++) {
                    int d = 50 + step * 25;
                    touchMulti(android.view.MotionEvent.ACTION_MOVE, 0, ids,
                            new int[]{cx - d, cx + d}, ys);
                    sleepMs(20);
                }
                touchMulti(android.view.MotionEvent.ACTION_POINTER_UP, 1, ids,
                        new int[]{cx - 350, cx + 350}, ys);
                touchMulti(android.view.MotionEvent.ACTION_UP, 0,
                        new int[]{0}, new int[]{cx - 350}, new int[]{cy});
                LogSink.line("PREVIEW", "双指自测完成");
            }
        }, "debug-pinch").start();
    }

    public void injectKey(final int keyCode) {
        final int id = displayId();
        final IVdHelper h = helper;
        if (h != null && id > 0) {
            try {
                h.injectKey(id, keyCode);
                return;
            } catch (Throwable ignored) {
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                RootShell.run("input -d " + id + " keyevent " + keyCode);
            }
        }, "inject-key").start();
    }

    /**
     * 每个接收端一个发送线程 + 有界队列。
     *
     * 原来是 drain 线程直接写 socket：车机 Wi-Fi 一慢，write 阻塞就把编码器一起拖住，
     * 延迟一路攒到两三秒。现在编码线程只入队，队列满了丢旧帧，画面永远追最新。
     */
    private static final int SINK_QUEUE_MAX = 6;

    private final List<Sink> sinks = new ArrayList<Sink>();
    // 调试用：把码流落成 cast.h264，拿 PC 上看真实画面有没有黑边
    private java.io.OutputStream dump;
    private int dumpFrames;
    private boolean dumpHeader;
    private volatile boolean running;
    private volatile byte[] codecConfig;
    private volatile long frames;
    private volatile long bytes;
    private volatile long dropped;

    /** 一路视频接收端：队列 + 独立发送线程。 */
    private final class Sink extends Thread {
        final OutputStream os;
        final java.util.ArrayDeque<byte[]> q = new java.util.ArrayDeque<byte[]>();
        volatile boolean closed;
        /** 刚挂上的接收端要等一个"新的"关键帧再送 P 帧，否则接的是别的 GOP，画面会跳 */
        volatile boolean awaitingKey = true;

        Sink(OutputStream os) {
            super("cast-sink");
            this.os = os;
            setDaemon(true);
        }

        private long lastKeyAsk;

        void offer(byte[] nal, boolean keyFrame) {
            if (closed) return;
            boolean askKey = false;
            synchronized (q) {
                if (awaitingKey && !keyFrame) return;
                if (keyFrame) awaitingKey = false;
                if (q.size() >= SINK_QUEUE_MAX) {
                    if (!keyFrame) {
                        // 追不上了：丢掉积压，等下一个关键帧重新对齐。
                        // 编码出来的 P 帧是"预测帧"，跳着发会让车机花屏，
                        // 所以宁可短暂停住，也要从一个完整的 IDR 重新开始。
                        dropped += q.size() + 1;
                        q.clear();
                        awaitingKey = true;
                        long now = System.currentTimeMillis();
                        if (now - lastKeyAsk > 200) {
                            lastKeyAsk = now;
                            askKey = true;
                        }
                        if (!askKey) return;
                    } else {
                        // 关键帧到了：积压的旧 GOP 直接丢掉，别让车机从旧的 I 帧慢慢追
                        dropped += q.size();
                        q.clear();
                    }
                }
                if (!askKey) {
                    q.add(nal);
                    q.notifyAll();
                }
            }
            if (askKey) requestKeyFrame();
        }

        void shutdown() {
            closed = true;
            synchronized (q) {
                q.clear();
                q.notifyAll();
            }
            try {
                os.close();
            } catch (Exception ignored) {
            }
        }

        @Override
        public void run() {
            try {
                int vw = cfg.castWidth;
                int vh = cfg.castHeight;
                CarlifeBridge cb = carlifeBridge;
                if (cb != null) {
                    vw = cb.videoWidth();
                    vh = cb.videoHeight();
                }
                os.write(videoHeader(vw, vh));
                byte[] c = codecConfig;
                if (c != null) os.write(c);
                byte[] k = lastKey;
                if (k != null) {
                    os.write(k);
                    long age = lastKeyAt > 0 ? (System.currentTimeMillis() - lastKeyAt) : -1;
                    LogSink.line("CAST", "补发关键帧 " + k.length + "B（" + age + "ms 前）给新接收端");
                    bytes += k.length;
                    awaitingKey = false;
                } else {
                    LogSink.line("CAST", "还没有关键帧，等编码器出帧");
                }
                os.flush();
            } catch (Exception e) {
                LogSink.line("CAST", "补发失败: " + e);
                dropSink(this);
                return;
            }
            while (!closed && running) {
                byte[] nal;
                synchronized (q) {
                    while (q.isEmpty() && !closed && running) {
                        try {
                            q.wait(500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (closed || !running) break;
                    nal = q.poll();
                }
                try {
                    os.write(nal);
                    os.flush();
                    bytes += nal.length;
                } catch (Exception e) {
                    LogSink.line("15456", "发送异常: " + e);
                    break;
                }
            }
            dropSink(this);
        }
    }

    public CastEngine(Context ctx, Config cfg, Listener l) {
        this.ctx = ctx;
        this.cfg = cfg;
        this.listener = l;
    }

    public boolean isRunning() {
        return running;
    }

    public long frames() {
        return frames;
    }

    public long bytes() {
        return bytes;
    }

    public static Intent requestIntent(Context ctx) {
        MediaProjectionManager m = (MediaProjectionManager)
                ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return m == null ? null : m.createScreenCaptureIntent();
    }

    public synchronized void start(int resultCode, Intent data) {
        if (running) return;
        sCurrent = this;
        frames = 0;
        bytes = 0;
        dropped = 0;
        if (cfg.carlifeMode) {
            running = true;
            codecConfig = null;
            lastKey = null;
            lastKeyAt = 0L;
            carlifeBridge = new CarlifeBridge(cfg.castWidth, cfg.castHeight, cfg.castFps,
                    new CarlifeBridge.Callback() {
                        @Override
                        public void onState(String s) {
                            state(s);
                        }

                        @Override
                        public void onH264Frame(byte[] nalu) {
                            pushExternalH264(nalu);
                        }
                    },
                    () -> {
                        ensureOcarCarlifeWithRoot();
                        return kotlin.Unit.INSTANCE;
                    });
            carlifeBridge.start();
            state("CarLife 连接中…");
            LogSink.line("CAST", "已启动 CarLife 车联转发模式 (" + cfg.castWidth + "x" + cfg.castHeight + ")");
            return;
        }
        if (cfg.carMode) {
            boolean ok = RootShell.applyCarRatio(cfg.carWidth, cfg.carHeight, cfg.carDensity);
            LogSink.line("CAST", "车机比例 " + cfg.carWidth + "x" + cfg.carHeight + " @" + cfg.carDensity
                    + (ok ? " 已切换" : " 切换失败（无 root 或未授权）"));
            if (ok) {
                // 把自己拉回前台，避免切尺寸期间被系统冻结
                try {
                    Intent back = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                    if (back != null) {
                        back.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(back);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(1500);   // 等系统重新布局
                } catch (InterruptedException ignored) {
                }
                carRatioApplied = true;
                LogSink.line("CAST", "按配置使用源尺寸 " + cfg.carWidth + "x" + cfg.carHeight);
            }
        }
        try {
            if (!cfg.useVdHelper) {
                MediaProjectionManager m = (MediaProjectionManager)
                        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                projection = m.getMediaProjection(resultCode, data);
                if (projection == null) {
                    state("投屏授权失败");
                    return;
                }
            }

            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", cfg.castWidth, cfg.castHeight);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, cfg.castBitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.castFps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.castIFrameInterval);
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100000L);
            // 关键：虚拟屏按手机刷新率出图（120Hz 的手机实测编码到 120fps），
            // KEY_FRAME_RATE 只是提示，surface 输入要 max-fps-to-encoder 才真的限速。
            // 官方 ProjectionEncoder 也是这么写的（30fps + max-fps-to-encoder）。
            try {
                fmt.setFloat("max-fps-to-encoder", cfg.castFps);
                fmt.setInteger("operating-rate", cfg.castFps);
            } catch (Throwable ignored) {
            }
            if (cfg.castProfileHigh) {
                try {
                    fmt.setInteger(MediaFormat.KEY_PROFILE, 8);      // AVCProfileHigh
                    fmt.setInteger(MediaFormat.KEY_LEVEL, 4096);     // AVCLevel4
                } catch (Throwable ignored) {
                }
            }

            codec = MediaCodec.createEncoderByType("video/avc");
            try {
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable ce) {
                LogSink.line("CAST", "configure(High) 失败，去掉 profile/level 重试: " + ce);
                fmt.removeKey(MediaFormat.KEY_PROFILE);
                fmt.removeKey(MediaFormat.KEY_LEVEL);
                codec = MediaCodec.createEncoderByType("video/avc");
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            inputSurface = codec.createInputSurface();
            codec.start();

            if (projection != null) {
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        LogSink.line("CAST", "MediaProjection 被系统停止");
                        stop();
                    }
                }, null);
            }

            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            boolean appDisplayMode = cfg.appDisplayPkg.length() > 0;
            int navVdId = -1;
            Surface projSurface;
            int vdW, vdH, vdDpi, vdFlags;
            if (appDisplayMode) {
                // 自建虚拟屏；有安全区时虚拟屏只做内容高度，编码画布仍是 1024x600，
                // 由 GlScaler.pad 把内容贴到 y=safeTop，上下补黑。
                int contentH = cfg.castHeight - cfg.safeTop - cfg.safeBottom;
                vdDpi = cfg.appDisplayDensity;
                vdFlags = 0;
                vdW = cfg.castWidth;
                if (contentH >= 120 && contentH < cfg.castHeight) {
                    vdH = contentH;
                    vdContentH = contentH;
                    scaler = GlScaler.pad(cfg.castWidth, contentH,
                            cfg.castWidth, cfg.castHeight, cfg.safeTop);
                    projSurface = scaler.prepare(inputSurface);
                    LogSink.line("CAST", "虚拟屏模式: " + vdW + "x" + vdH + " @" + vdDpi
                            + " 目标包 " + cfg.appDisplayPkg
                            + "；安全区 y=" + cfg.safeTop + "，编码画布 "
                            + cfg.castWidth + "x" + cfg.castHeight);
                } else {
                    vdH = cfg.castHeight;
                    vdContentH = cfg.castHeight;
                    projSurface = inputSurface;
                    LogSink.line("CAST", "虚拟屏模式: " + vdW + "x" + vdH + " @" + vdDpi
                            + " 目标包 " + cfg.appDisplayPkg);
                }
            } else {
                vdContentH = cfg.castHeight;
                int[] src = sourceSize();
                float inset = statusBarFraction(src[0], src[1]);
                scaler = new GlScaler(src[0], src[1], cfg.castWidth, cfg.castHeight, cfg.castFill, inset);
                projSurface = scaler.prepare(inputSurface);
                vdW = src[0];
                vdH = src[1];
                vdDpi = 320;
                vdFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
                LogSink.line("CAST", "GL 缩放: 源 " + src[0] + "x" + src[1]
                        + " -> 目标 " + cfg.castWidth + "x" + cfg.castHeight
                        + (cfg.castFill ? " (裁边填满)" : " (留黑边)")
                        + "，顶部裁掉 " + String.format("%.3f", inset));
            }
            if (cfg.useVdHelper) {
                vdFlags = VD_FLAGS;
                helperDisplayId = createDisplayByHelper(projSurface, vdW, vdH, vdDpi, vdFlags);
                if (helperDisplayId <= 0) {
                    state("助手建屏失败");
                    stop();
                    return;
                }
                LogSink.line("CAST", (cfg.useShizuku ? "Shizuku" : "root")
                        + " 助手建屏 displayId=" + helperDisplayId + " flags=" + vdFlags);
            } else
            try {
                virtualDisplay = projection.createVirtualDisplay("tailg-probe",
                        vdW, vdH, vdDpi, vdFlags, projSurface, null, null);
            } catch (Throwable te) {
                LogSink.line("CAST", "建屏失败(flags=" + vdFlags + "): " + te
                        + "，改用 AUTO_MIRROR 重试");
                virtualDisplay = projection.createVirtualDisplay("tailg-probe",
                        vdW, vdH, vdDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        projSurface, null, null);
            }
            if (appDisplayMode) {
                int vdId = helperDisplayId > 0 ? helperDisplayId : -1;
                if (vdId <= 0) {
                    try {
                        vdId = virtualDisplay.getDisplay().getDisplayId();
                    } catch (Throwable ignored) {
                    }
                }
                LogSink.line("CAST", "虚拟屏 displayId=" + vdId);
                try {
                    for (android.view.Display d : dm.getDisplays()) {
                        android.util.DisplayMetrics met = new android.util.DisplayMetrics();
                        d.getRealMetrics(met);
                        LogSink.line("CAST", "display " + d.getDisplayId() + " " + d.getName()
                                + " " + met.widthPixels + "x" + met.heightPixels);
                    }
                } catch (Throwable ignored) {
                }
                if (vdId > 0) {
                    navVdId = vdId;
                    if (helperDisplayId > 0) {
                        helperMoveApp();
                    } else {
                        startAppOnDisplay(vdId);
                    }
                }
            }

            running = true;
            drainThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainLoop();
                }
            }, "cast-drain");
            drainThread.start();
            startStatsLogger();
            state("投屏已启动 " + cfg.castWidth + "x" + cfg.castHeight);
            if (navVdId > 0) startNavigationWhenReady(navVdId);
        } catch (Throwable t) {
            LogSink.line("CAST", "启动失败: " + t);
            state("投屏启动失败: " + t.getClass().getSimpleName());
            stop();
        }
    }


    /**
     * 投屏启动后，如果开了自动导航并且有选中收藏点，就在虚拟屏上打开
     * 高德/百度骑行导航路线页。无障碍服务会在 45 秒窗口内点「开始导航」。
     */
    private void startNavigationWhenReady(final int vdId) {
        final String pkg = cfg.appDisplayPkg;
        final String provider;
        if (NavFavorites.PKG_AMAP.equals(pkg)) {
            provider = NavFavorites.MAP_AMAP;
        } else if (NavFavorites.PKG_BAIDU.equals(pkg)) {
            provider = NavFavorites.MAP_BAIDU;
        } else {
            return;
        }
        if (!NavFavorites.isAutoStart(ctx)) return;
        final NavFavorites.Favorite fav = NavFavorites.active(ctx);
        if (fav == null) {
            LogSink.line("NAV", "自动导航已开启，但没有收藏点");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 等目标 App 上屏/首帧；地图启动页通常 1~3 秒
                for (int i = 0; i < 12 && running; i++) sleepMs(500);
                if (!running) return;
                int id = displayId();
                if (id <= 0) {
                    LogSink.line("NAV", "虚拟屏未就绪，跳过自动导航");
                    return;
                }
                NavAutoState.arm(id, 45000);
                String uri = fav.motorUri(provider);
                String cmd = "am start --display " + id
                        + " -a android.intent.action.VIEW -d \"" + uri + "\""
                        + " -p " + pkg;
                RootShell.Result r = RootShell.run(cmd);
                LogSink.line("NAV", "自动导航(" + provider + ") " + fav.name + ": "
                        + (r.ok ? "ok" : "fail") + " " + oneLine(r.out));
                if (!r.ok) NavAutoState.disarm();
            }
        }, "nav-start").start();
    }

    /** 目标应用在配置里的 -n 参数（没有就返回 null，用 -p 兜底）。 */
    private String compArg() {        String act = cfg.appDisplayActivity;
        if (act == null || act.length() == 0) return null;
        return act.contains("/") ? act : act + "/";
    }

    /**
     * 把目标应用放到虚拟屏上。
     *
     * 关键：先在默认屏启动它，等它进入 NOSENSOR 状态，再用 move-stack 搬到虚拟屏——
     * 直接往副屏上启动的话，系统按清单里的 portrait 把它关进竖条（letterbox），
     * 搬过去的任务则按屏的自然方向铺满，高德自己就会画横屏布局。
     */
    private void startAppOnDisplay(final int vdId) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String pkg = cfg.appDisplayPkg;
                String comp = compArg();
                String carUiArg = carUiLaunchArg();
                String startArg = carUiArg != null
                        ? carUiArg
                        : (comp != null ? "-n " + comp : "-p " + pkg);
                try {
                    if (cfg.appDisplayMove) {
                        // freeform 小窗不受应用方向请求约束：搬到虚拟屏后 resize 拉满，
                        // 导航界面也能拿到 land 配置（普通全屏启动会被关进竖条）
                        if (cfg.appDisplayFreeform) {
                            // ColorOS 默认关掉 freeform，这个设置打开立即生效（不用重启），
                            // 没有 freeform 的话 task resize 是空操作，导航仍会被关进竖条
                            RootShell.run("settings put global enable_freeform_support 1");
                        }
                        String launch = cfg.appDisplayFreeform
                                ? "am start --windowingMode 5 " + startArg
                                : "am start " + startArg;
                        RootShell.Result rs = RootShell.run(launch);
                        LogSink.line("CAST", "默认屏启动" + (cfg.appDisplayFreeform ? "(freeform)" : "")
                                + ": " + (rs.ok ? "ok" : "fail") + " " + oneLine(rs.out));
                        int taskId = waitTask(pkg, 12000);
                        LogSink.line("CAST", "任务号 " + taskId);
                        // 高德启动后有几秒是 SplashActivity(portrait)，等它切到可旋转状态再搬
                        // freeform 不受应用 orientation 约束，不需要等 NOSENSOR
                        boolean ready = cfg.appDisplayFreeform || waitNoSensor(2000);
                        LogSink.line("CAST", "方向就绪: " + ready);
                        if (taskId > 0) {
                            boolean land = moveAndFill(taskId, vdId);
                            if (!land) {
                                LogSink.line("CAST", "未铺满，重试一次");
                                RootShell.run("cmd activity display move-stack " + taskId + " 0");
                                sleepMs(1000);
                                land = moveAndFill(taskId, vdId);
                            }
                            bringSelfToFront();
                            // 搬屏动作本身成功就不再折腾任务（再 launch 一次会把它打回竖屏/首页）
                            if (movedOnce) return;
                        }
                    }
                    // 兜底：直接放到虚拟屏（ColorOS 允许；AOSP 会因权限被拒）
                    LogSink.line("CAST", "没有可用任务，直接启动到虚拟屏");
                    RootShell.Result lr = RootShell.run("am start --display " + vdId
                            + " --windowingMode 1 " + startArg);
                    LogSink.line("CAST", "直接启动到虚拟屏: " + (lr.ok ? "ok" : "fail")
                            + " " + oneLine(lr.out));
                    sleepMs(1500);
                    logLayout(vdId);
                } catch (Throwable t) {
                    LogSink.line("CAST", "启动到虚拟屏异常: " + t);
                }
            }
        }, "cast-launch").start();
    }



    /** 搬到虚拟屏并拉满，返回是否已横屏铺满。 */
    private volatile boolean movedOnce = false;

    private boolean moveAndFill(int taskId, int vdId) {
        // 任务号可能在启动/等待期间变过，用之前一定要重新取
        int fresh = findTaskId(cfg.appDisplayPkg);
        if (fresh > 0) {
            if (fresh != taskId) {
                LogSink.line("CAST", "任务号刷新 " + taskId + " -> " + fresh);
            }
            taskId = fresh;
        }
        RootShell.Result mv = RootShell.run(
                "cmd activity display move-stack " + taskId + " " + vdId);
        LogSink.line("CAST", "搬屏 move-stack " + taskId + " -> " + vdId + ": "
                + (mv.ok ? "ok" : "fail") + " " + oneLine(mv.out));
        if (mv.ok) movedOnce = true;
        sleepMs(800);
        if (cfg.appDisplayFreeform) {
            RootShell.Result rz = RootShell.run("cmd activity task resize " + taskId
                    + " 0 0 " + cfg.castWidth + " " + cfg.castHeight);
            LogSink.line("CAST", "freeform 拉满: " + (rz.ok ? "ok" : "fail")
                    + " " + oneLine(rz.out));
        }
        sleepMs(1500);
        return logLayout(vdId);
    }

    /** 等目标应用把请求方向切成 NOSENSOR（高德启动后约 3~8 秒才切）。 */
    private boolean waitNoSensor(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            RootShell.Result r = RootShell.run(
                    "dumpsys activity activities | grep -c overrideOrientation=SCREEN_ORIENTATION_NOSENSOR");
            if (r.out != null && r.out.trim().startsWith("1")) return true;
            sleepMs(700);
        }
        return false;
    }


    /** 当前包的任务号（取不到返回 -1）。 */
    private int findTaskId(String pkg) {
        RootShell.Result r = RootShell.run(
                "dumpsys activity activities | grep -m1 'type=standard A=[0-9]*:" + pkg + "'");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Task\\{[0-9a-f]+ #(\\d+)").matcher(r.out);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    /** 轮询目标包的任务号；找不到返回 -1。 */
    private int waitTask(String pkg, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            RootShell.Result r = RootShell.run(
                    "dumpsys activity activities | grep -m1 'A=[0-9]*:" + pkg + "'");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("Task\\{[0-9a-f]+ #(\\d+)").matcher(r.out);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (Exception ignored) {
                }
            }
            sleepMs(600);
        }
        return -1;
    }

    /** 打印该屏上应用的布局配置，返回是否已横屏铺满。 */
    /** 目标活动在虚拟屏上的实际高度（px）。 */
    private int contentHeight(int vdId) {
        RootShell.Result r = RootShell.run("dumpsys activity activities | grep -A30 'Display #"
                + vdId + "' | grep -m1 CurrentConfiguration=");
        String raw = r.out == null ? "" : r.out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("mBounds=Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)")
                .matcher(raw);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(4)) - Integer.parseInt(m.group(2));
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean logLayout(int vdId) {
        RootShell.Result r = RootShell.run("dumpsys activity activities | grep -A30 'Display #"
                + vdId + "' | grep -m1 CurrentConfiguration=");
        String raw = r.out == null ? "" : r.out;
        boolean land = raw.contains(" land ");
        // 密度 320 时窗口宽是 512dp=1024px，所以按 mBounds 的像素值判断是否铺满
        int w = 0;
        int h = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("mBounds=Rect\\((-?\\d+), (-?\\d+) - (-?\\d+), (-?\\d+)\\)")
                .matcher(raw);
        if (m.find()) {
            try {
                w = Integer.parseInt(m.group(3)) - Integer.parseInt(m.group(1));
                h = Integer.parseInt(m.group(4)) - Integer.parseInt(m.group(2));
            } catch (Exception ignored) {
            }
        }
        boolean full = w >= cfg.castWidth - 8 && h >= vdContentH - 8;
        LogSink.line("CAST", "布局: " + (land ? "横屏" : "竖屏") + " " + w + "x" + h
                + (full ? " 铺满" : " 未铺满"));
        return land && full;
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 搬完屏把探针自己拉回前台，避免后台被冻结。 */
    private void bringSelfToFront() {
        try {
            Intent back = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (back != null) {
                back.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(back);
            }
        } catch (Throwable ignored) {
        }
    }

    private int[] rawSize() {
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point p = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            return new int[]{p.x, p.y};
        } catch (Throwable t) {
            return new int[]{0, 0};
        }
    }

    /** 顶部黑边（状态栏被系统涂黑）占源高度的比例；0 表示不裁。 */
    private float statusBarFraction(int srcW, int srcH) {
        try {
            if (cfg.castCropTop >= 0) {
                return Math.min(0.45f, (float) cfg.castCropTop / srcH);
            }
            int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
            int px = id > 0 ? ctx.getResources().getDimensionPixelSize(id) : 0;
            if (px <= 0) return 0f;
            // 换算到源画面高度
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point p = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            int realH = Math.max(p.x, p.y);
            return Math.min(0.45f, (float) px / realH);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** 默认屏幕的真实尺寸，长边限制到 1600，宽高都取偶数。 */
    private volatile boolean carRatioApplied = false;

    /** PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES_WITH_CONTENT|DESTROY_CONTENT_ON_REMOVAL
     *  |TRUSTED|OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED|OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED */
    private static final int VD_FLAGS = 1 | 8 | 64 | 128 | 256 | 1024 | 2048 | 4096 | 16384 | 65536;

    /** App 注入的助手启动器：root 走 su，Shizuku 走 shell/root 的新进程。 */
    public interface HelperLauncher {
        boolean available();

        Process start(String apkPath, String authority) throws Exception;
    }

    /** App 通过 priv-kit UserService 直接提供已连接的 IVdHelper Binder。 */
    public interface DirectHelperProvider {
        boolean available();

        IVdHelper getHelper() throws Exception;
    }

    private static volatile HelperLauncher sHelperLauncher;
    private static volatile DirectHelperProvider sDirectHelperProvider;

    public static void setHelperLauncher(HelperLauncher l) {
        sHelperLauncher = l;
    }

    public static void setDirectHelperProvider(DirectHelperProvider p) {
        sDirectHelperProvider = p;
    }

    private Process helperProc;
    private volatile IVdHelper helper;
    private int helperDisplayId = -1;
    private volatile boolean helperIgnoreApplied = false;

    /** 起 root 助手并等它把 binder 送过来。 */
    private boolean startHelper() {
        if (helper != null && helper.asBinder().pingBinder()) return true;
        helper = null;
        DirectHelperProvider dp = sDirectHelperProvider;
        if (dp != null && dp.available()) {
            try {
                IVdHelper direct = dp.getHelper();
                if (direct != null && direct.asBinder().pingBinder()) {
                    helper = direct;
                    LogSink.line("CAST", "priv-kit UserService 助手已就绪");
                    return true;
                }
            } catch (Throwable t) {
                LogSink.line("CAST", "priv-kit UserService 获取失败，回退旧路径: " + t);
            }
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        VdProvider.setOnHelper(new VdProvider.OnHelper() {
            @Override
            public void onHelper(IBinder b) {
                helper = IVdHelper.Stub.asInterface(b);
                latch.countDown();
            }
        });
        String apk = ctx.getApplicationInfo().sourceDir;
        try {
            if (cfg.useShizuku) {
                HelperLauncher l = sHelperLauncher;
                if (l == null || !l.available()) {
                    LogSink.line("CAST", "Shizuku 助手不可用（未连接/未授权）");
                    return false;
                }
                helperProc = l.start(apk, VdProvider.AUTHORITY);
                pumpHelperLog(helperProc);
                LogSink.line("CAST", "Shizuku 助手已启动，等会合…");
            } else {
                String cmd = "CLASSPATH=" + apk + " app_process /system/bin --nice-name=tailg-vd "
                        + VdHelperMain.class.getName() + " " + VdProvider.AUTHORITY
                        + " >> /data/local/tmp/tailg-vd.log 2>&1";
                helperProc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                LogSink.line("CAST", "root 助手已启动，等会合…");
            }
        } catch (Throwable t) {
            LogSink.line("CAST", "起不来助手: " + t);
            return false;
        }
        try {
            if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                LogSink.line("CAST", "等助手超时（看 /data/local/tmp/tailg-vd.log）");
                return false;
            }
        } catch (InterruptedException e) {
            return false;
        }
        return helper != null;
    }

    /** Shizuku 新进程的 stderr 没有重定向，读到 App 日志里方便排查。 */
    private void pumpHelperLog(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        LogSink.line("HELPER", line);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "helper-log");
        t.setDaemon(true);
        t.start();
    }

    private int createDisplayByHelper(Surface s, int w, int h, int dpi, int flags) {
        if (!startHelper()) return -1;
        helperIgnoreApplied = false;
        try {
            int id = helper.createDisplay(s, w, h, dpi, flags,
                    cfg.appDisplayIgnoreSizeRestrictions);
            helperIgnoreApplied = id > 0 && cfg.appDisplayIgnoreSizeRestrictions
                    && helper.ignoreApplied(id);
            LogSink.line("CAST", "助手建屏 id=" + id + " ignoreApplied=" + helperIgnoreApplied);
            return id;
        } catch (Throwable t) {
            LogSink.line("CAST", "createDisplay 调用失败: " + t);
            return -1;
        }
    }

    /** 直接全屏启动到虚拟屏，并等它变成横屏铺满；失败返回 false。 */
    private String carUiLaunchArg() {
        if (!cfg.amapCarUi) return null;
        if ("com.autonavi.minimap".equals(cfg.appDisplayPkg)) {
            return "-a samsung.intent.action.carlink.kit"
                    + " -n com.autonavi.minimap/com.autonavi.map.activity.UcarSplashActivity";
        }
        if ("com.baidu.BaiduMap".equals(cfg.appDisplayPkg)) {
            return "-a vivo.intent.action.carlink.kit -c vivo.intent.category.carlink.kit"
                    + " -n com.baidu.BaiduMap/com.baidu.hicar.HiCarWelcomeScreen";
        }
        return null;
    }

    private boolean launchOnDisplay(int vdId, String component) throws android.os.RemoteException {
        String carUi = carUiLaunchArg();
        if (carUi != null) {
            return RootShell.run("am start --display " + vdId + " --windowingMode 1 " + carUi).ok;
        }
        return helper.launch(vdId, component);
    }

    private boolean tryDirectLaunch(int vdId, String component) {
        try {
            // 先不动已经在跑的高德：launch 会把已有任务带到目标屏，导航不会丢。
            boolean launched = launchOnDisplay(vdId, component);
            LogSink.line("CAST", "直接启动: " + (launched ? "ok" : "fail")
                    + " display=" + vdId);
            if (launched && waitLayout(vdId, 4000)) return true;

            // 已有任务没被带过去（例如它还在默认屏），再 force-stop 重来一次。
            LogSink.line("CAST", "直接启动未铺满，force-stop 后重试");
            RootShell.run("am force-stop " + cfg.appDisplayPkg);
            sleepMs(300);
            launched = launchOnDisplay(vdId, component);
            LogSink.line("CAST", "直接启动重试: " + (launched ? "ok" : "fail"));
            return launched && waitLayout(vdId, 4000);
        } catch (Throwable t) {
            LogSink.line("CAST", "直接启动异常: " + t);
            return false;
        }
    }

    /** 轮询目标屏的布局，直到横屏铺满或超时。 */
    private boolean waitLayout(int vdId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (logLayout(vdId)) return true;
            sleepMs(500);
        }
        return false;
    }

    /**
     * 安全区直启：把目标 App 以 freeform 启动到虚拟屏，并 resize 成
     * 0,safeTop - castWidth,safeTop+contentHeight，上下留给车机 HUD。
     * 不依赖“默认屏启动再搬”，也不会有小窗。
     */
    private boolean tryDirectFreeform(int vdId, String component) {
        int contentH = cfg.castHeight - cfg.safeTop - cfg.safeBottom;
        if (contentH < 120 || cfg.safeTop < 0 || cfg.safeBottom < 0) return false;
        try {
            RootShell.Result lr = RootShell.run("am start --display " + vdId
                    + " --windowingMode 5 -n " + component);
            LogSink.line("CAST", "直接 freeform 启动到虚拟屏: " + (lr.ok ? "ok" : "fail")
                    + " " + oneLine(lr.out));
            if (!lr.ok) return false;

            int task = waitTask(cfg.appDisplayPkg, 4000);
            LogSink.line("CAST", "安全区任务号 " + task);
            if (task <= 0) return false;

            int bottom = cfg.safeTop + contentH;
            RootShell.run("cmd activity task resize " + task + " 0 " + cfg.safeTop
                    + " " + cfg.castWidth + " " + bottom);
            sleepMs(400);
            int got = contentHeight(vdId);
            if (got > 0 && Math.abs(got - contentH) >= 2) {
                int fix = contentH + (contentH - got);
                if (cfg.safeTop + fix <= cfg.castHeight) {
                    bottom = cfg.safeTop + fix;
                    RootShell.run("cmd activity task resize " + task + " 0 " + cfg.safeTop
                            + " " + cfg.castWidth + " " + bottom);
                    sleepMs(400);
                    LogSink.line("CAST", "安全区窗口补偿: 实际 " + got + "，改到 " + fix);
                }
            }
            boolean ok = waitContentHeight(vdId, contentH, 2500);
            LogSink.line("CAST", "安全区: 应用窗口 0," + cfg.safeTop + " "
                    + cfg.castWidth + "x" + contentH + "（编码仍 " + cfg.castWidth
                    + "x" + cfg.castHeight + "）" + (ok ? " ok" : " fail"));
            return ok;
        } catch (Throwable t) {
            LogSink.line("CAST", "安全区 direct 异常: " + t);
            return false;
        }
    }

    /** 等虚拟屏上应用的实际内容高度到预期值。 */
    private boolean waitContentHeight(int vdId, int expect, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int got = contentHeight(vdId);
            if (got >= expect - 2 && got <= expect + 2) return true;
            sleepMs(200);
        }
        return false;
    }

    /** 通过助手把 App 搬到助手建的屏上。 */
    private void helperMoveApp() {
        if (helper == null || helperDisplayId <= 0) return;
        final int id = helperDisplayId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String comp = cfg.appDisplayActivity;
                    if (comp != null && comp.length() > 0 && !comp.contains("/")) comp = null;
                    // 新路径：display 级忽略应用的 orientation/resizeable 限制，
                    // 直接把目标 App 全屏启动到虚拟屏；不再在默认屏建 freeform 任务。
                    if (cfg.appDisplayDirect && helperIgnoreApplied && comp != null) {
                        LogSink.line("CAST", "尝试直接启动到虚拟屏（ignoreSizeRestrictions=true）");
                        if (tryDirectLaunch(id, comp)) {
                            bringSelfToFront();
                            return;
                        }
                        LogSink.line("CAST", "直接启动未铺满，回退 freeform+搬屏");
                        RootShell.run("am force-stop " + cfg.appDisplayPkg);
                        sleepMs(400);
                    }
                    if (!cfg.appDisplayFreeform) {
                        if (comp != null) helper.launch(id, comp);
                        return;
                    }
                    // freeform 起在默认屏，再搬到助手屏并拉满
                    RootShell.run("settings put global enable_freeform_support 1");
                    RootShell.run("am start --windowingMode 5 " + (comp != null ? "-n " + comp
                            : "-p " + cfg.appDisplayPkg));
                    long waitAt = System.currentTimeMillis();
                    int task = -1;
                    while (task <= 0 && System.currentTimeMillis() - waitAt < 5000) {
                        task = helper.taskOf(cfg.appDisplayPkg);
                        if (task <= 0) sleepMs(100);
                    }
                    LogSink.line("CAST", "助手取任务号 " + task
                            + "（" + (System.currentTimeMillis() - waitAt) + "ms）");
                    if (task > 0) {
                        helper.moveTask(task, id);
                        sleepMs(800);
                        // 虚拟屏本身已经是“内容高度”（安全区时 480），不要再 resize 成 600；
                        // 上下黑边由 GlScaler.pad 在编码画布上补。
                        int winH = vdContentH > 0 ? vdContentH : cfg.castHeight;
                        RootShell.run("cmd activity task resize " + task + " 0 0 "
                                + cfg.castWidth + " " + winH);
                        // freeform 窗口可能带标题栏；内容少了就按差值补任务高度
                        sleepMs(400);
                        int got = contentHeight(id);
                        if (got > 0 && Math.abs(got - winH) >= 2) {
                            int fix = winH + (winH - got);
                            if (fix <= vdContentH) {
                                RootShell.run("cmd activity task resize " + task + " 0 0 "
                                        + cfg.castWidth + " " + fix);
                                sleepMs(400);
                                LogSink.line("CAST", "窗口补偿: 实际 " + got + "，任务改成 " + fix
                                        + "，内容应为 " + winH);
                            } else {
                                winH = got;
                                LogSink.line("CAST", "窗口补偿放不下，内容按 " + got + " 处理");
                            }
                        }
                        if (vdContentH != cfg.castHeight) {
                            LogSink.line("CAST", "安全区: 虚拟屏内容 " + cfg.castWidth + "x"
                                    + vdContentH + "，编码画布 " + cfg.castWidth + "x"
                                    + cfg.castHeight + "，上下黑边由 GL 补");
                        }
                        sleepMs(600);
                        logLayout(id);
                    }
                    bringSelfToFront();
                } catch (Throwable t) {
                    LogSink.line("CAST", "助手搬屏异常: " + t);
                }
            }
        }, "vd-move").start();
    }

    private int[] sourceSize() {
        int w = cfg.castWidth;
        int h = cfg.castHeight;
        if (carRatioApplied) {
            float k = Math.min(1f, 1600f / Math.max(cfg.carWidth, cfg.carHeight));
            return new int[]{Math.max(2, ((int) (cfg.carWidth * k)) & ~1),
                    Math.max(2, ((int) (cfg.carHeight * k)) & ~1)};
        }
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point p = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            if (p.x > 1 && p.y > 1) {
                w = p.x;
                h = p.y;
            }
        } catch (Throwable ignored) {
        }
        float k = Math.min(1f, 1600f / Math.max(w, h));
        int sw = Math.max(2, ((int) (w * k)) & ~1);
        int sh = Math.max(2, ((int) (h * k)) & ~1);
        return new int[]{sw, sh};
    }

    /** 最近的关键帧与普通帧：中途接入的车机先用它们把画面补上，
     *  否则画面静止时编码器没有新帧，车机会一直黑屏。 */
    private volatile byte[] lastKey;
    private volatile long lastKeyAt;
    private volatile byte[] lastFrame;

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            try {
                int idx = codec.dequeueOutputBuffer(info, 100000);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) continue;
                if (idx < 0) continue;
                ByteBuffer buf = codec.getOutputBuffer(idx);
                if (buf != null && info.size > 0) {
                    byte[] out = new byte[info.size];
                    buf.position(info.offset);
                    buf.get(out, 0, info.size);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codecConfig = out;   // 缓存 SPS/PPS
                        dumpWrite(out);
                        LogSink.line("CAST", "SPS/PPS " + out.length + "B " + hexHead(out, 24));
                    } else {
                        frames++;
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (key) {
                            lastKey = out;
                            lastKeyAt = System.currentTimeMillis();
                        }
                        lastFrame = out;
                        dumpWrite(out);
                        broadcast(out, key);
                    }
                }
                codec.releaseOutputBuffer(idx, false);
            } catch (Throwable t) {
                if (running) {
                    LogSink.line("CAST", "drain 异常: " + t);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }

    private void dumpWrite(byte[] data) {
        if (!cfg.dumpStream || dumpFrames > 200) return;
        try {
            if (dump == null) {
                java.io.File dir = LogSink.dir();
                if (dir == null) return;
                dump = new java.io.FileOutputStream(new java.io.File(dir, "cast.h264"));
                LogSink.line("CAST", "调试：码流写入 " + new java.io.File(dir, "cast.h264"));
            }
            if (data == null || data.length == 0) return;
            dump.write(data);
            dump.flush();
            dumpFrames++;
        } catch (Throwable t) {
            try {
                if (dump != null) dump.close();
            } catch (Throwable ignored) {
            }
            dump = null;
            LogSink.line("CAST", "码流写入失败 " + t);
        }
    }

    private void dumpClose() {
        try {
            if (dump != null) dump.close();
        } catch (Throwable ignored) {
        }
        dump = null;
    }

    private void pushExternalH264(byte[] nalu) {
        if (!running || nalu == null || nalu.length < 5) return;
        boolean hasSpsPps = false;
        boolean isKey = false;
        int firstIdrOffset = -1;
        int limit = Math.min(nalu.length, 4096);
        for (int i = 0; i + 4 < limit; i++) {
            if (nalu[i] == 0 && nalu[i + 1] == 0) {
                int sc = 0;
                if (nalu[i + 2] == 1) sc = 3;
                else if (nalu[i + 2] == 0 && i + 4 < nalu.length && nalu[i + 3] == 1) sc = 4;
                if (sc > 0) {
                    int t = nalu[i + sc] & 0x1F;
                    if (t == 7 || t == 8) hasSpsPps = true;
                    if (t == 5) {
                        isKey = true;
                        if (firstIdrOffset < 0) firstIdrOffset = i;
                    }
                }
            }
        }
        if (hasSpsPps && !isKey) {
            codecConfig = nalu;
            dumpWrite(nalu);
            broadcast(nalu, false);
            return;
        }
        if (hasSpsPps && firstIdrOffset > 0) {
            codecConfig = java.util.Arrays.copyOf(nalu, firstIdrOffset);
        }
        frames++;
        if (isKey) {
            lastKey = nalu;
            lastKeyAt = System.currentTimeMillis();
        }
        lastFrame = nalu;
        dumpWrite(nalu);
        broadcast(nalu, isKey);
    }

    private void broadcast(byte[] nal, boolean keyFrame) {
        PreviewSink ps = previewSink;
        if (ps != null) {
            try {
                ps.onFrame(nal, keyFrame);
            } catch (Throwable ignored) {
            }
        }
        List<Sink> copy;
        synchronized (sinks) {
            copy = new ArrayList<Sink>(sinks);
        }
        for (Sink s : copy) s.offer(nal, keyFrame);
    }

    private void dropSink(Sink s) {
        if (s != null) s.closed = true;
        synchronized (sinks) {
            sinks.remove(s);
        }
    }

    private int cfgWidth() {
        return cfg.castWidth;
    }

    private int cfgHeight() {
        return cfg.castHeight;
    }

    /** 69 字节视频参数头：00 + "android"(64B) + 宽(BE16) + 高(BE16)。 */
    public static byte[] videoHeader(int w, int h) {
        byte[] out = new byte[69];
        byte[] name = "android".getBytes();
        System.arraycopy(name, 0, out, 1, name.length);
        out[65] = (byte) (w >> 8);
        out[66] = (byte) w;
        out[67] = (byte) (h >> 8);
        out[68] = (byte) h;
        return out;
    }

    public void addSink(OutputStream os) {
        Sink s = new Sink(os);
        synchronized (sinks) {
            sinks.add(s);
        }
        s.start();
        requestKeyFrame();
    }

    public void removeSink(OutputStream os) {
        Sink hit = null;
        synchronized (sinks) {
            for (Sink s : sinks) {
                if (s.os == os) {
                    hit = s;
                    break;
                }
            }
        }
        if (hit != null) {
            dropSink(hit);
            hit.shutdown();
        }
    }

    public int sinkCount() {
        synchronized (sinks) {
            return sinks.size();
        }
    }

    public long dropped() {
        return dropped;
    }

    /** 强制关键帧，方便中途接入的接收端立刻出画面。 */
    public void requestKeyFrame() {
        if (carlifeBridge != null) {
            PreviewSink ps = previewSink;
            byte[] k = lastKey;
            if (ps != null && k != null) {
                try {
                    ps.onFrame(k, true);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        try {
            if (codec != null && Build.VERSION.SDK_INT >= 19) {
                android.os.Bundle b = new android.os.Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                codec.setParameters(b);
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureOcarCarlifeWithRoot() {
        String cmd = "CAR_ID=$(sed -n 's/.*name=\"\\([0-9A-Fa-f:]\\{17\\}\\)\".*/\\1/p'"
                + " /data/user/0/com.oplus.ocar/shared_prefs/auto_connect_data.xml | head -n 1); "
                + "am start -a oplus.intent.action.START_CAR_CONNECT"
                + " -n com.oplus.ocar/.settings.connect.union.CarUnionConnectRouteActivity"
                + " --es carId \"${CAR_ID:-F8:00:0A:D3:80:00}\"";
        RootShell.Result r = RootShell.runSu(cmd);
        LogSink.line("CARLIFE", "su 拉起 START_CAR_CONNECT: ok=" + r.ok + " out=" + r.out);
    }

    public synchronized void stop() {
        if (!running && codec == null && carlifeBridge == null) return;
        running = false;
        if (carlifeBridge != null) {
            try {
                carlifeBridge.stop();
            } catch (Throwable ignored) {
            }
            carlifeBridge = null;
        }
        NavAutoState.disarm();
        dumpClose();
        try {
            if (virtualDisplay != null) virtualDisplay.release();
        } catch (Throwable ignored) {
        }
        virtualDisplay = null;
        if (helper != null && helperDisplayId > 0) {
            try {
                helper.destroy(helperDisplayId);
            } catch (Throwable ignored) {
            }
        }
        helperDisplayId = -1;
        if (helperProc != null) {
            try {
                if (helper != null) helper.exit();   // 让助手自己退，避免留孤儿进程
            } catch (Throwable ignored) {
            }
            try {
                helperProc.destroy();
            } catch (Throwable ignored) {
            }
            helperProc = null;
        }
        helper = null;
        VdProvider.setOnHelper(null);
        try {
            if (scaler != null) scaler.release();
        } catch (Throwable ignored) {
        }
        scaler = null;
        try {
            if (codec != null) codec.stop();
        } catch (Throwable ignored) {
        }
        try {
            if (codec != null) codec.release();
        } catch (Throwable ignored) {
        }
        codec = null;
        try {
            if (inputSurface != null) inputSurface.release();
        } catch (Throwable ignored) {
        }
        inputSurface = null;
        try {
            if (projection != null) projection.stop();
        } catch (Throwable ignored) {
        }
        projection = null;
        List<Sink> alive;
        synchronized (sinks) {
            alive = new ArrayList<Sink>(sinks);
            sinks.clear();
        }
        for (Sink s : alive) s.shutdown();
        if (cfg.carMode) {
            RootShell.resetRatio();
            LogSink.line("CAST", "显示尺寸已还原");
        }
        state("投屏已停止");
    }

    private static String hexHead(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(n, b.length);
        for (int i = 0; i < len; i++) sb.append(String.format("%02x ", b[i]));
        return sb.toString().trim();
    }

    /** 每 5 秒把编码/发送计数写进日志，离线也能判断车机有没有收到画面。 */
    private void startStatsLogger() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long lastFrames = -1;
                long lastDropped = 0;
                while (running) {
                    sleepMs(5000);
                    if (!running) break;
                    long f = frames;
                    long d = dropped;
                    String drop = d > lastDropped ? " 丢=" + (d - lastDropped) : "";
                    lastDropped = d;
                    if (f != lastFrames) {
                        long add = lastFrames < 0 ? 0 : f - lastFrames;
                        LogSink.line("STAT", "帧=" + f + " 增=" + add
                                + " 编码fps=" + (add / 5) + " 丢弃=" + drop
                                + " 字节=" + (bytes / 1024) + "KB 接收端=" + sinkCount());
                        lastFrames = f;
                    } else {
                        LogSink.line("STAT", "帧停住 " + f + " 字节=" + (bytes / 1024)
                                + "KB 接收端=" + sinkCount() + " 丢弃=" + drop);
                    }
                }
            }
        }, "cast-stats");
        t.setDaemon(true);
        t.start();
    }

    private void state(String s) {
        LogSink.line("CAST", s);
        if (listener != null) listener.onCastState(s);
    }
}
