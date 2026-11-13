package com.tailg.probe;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 监听 17818/15456/15457/18888/19000，完整记录协议，并按官方逻辑应答。 */
public class ProbeService extends Service {

    public static final String ACTION_START = "com.tailg.probe.START";
    public static final String ACTION_STOP = "com.tailg.probe.STOP";

    private static final int NOTIF_ID = 47001;
    private static final String CHANNEL = "probe";

    public static volatile boolean running;
    public static volatile Config config;
    public static volatile CastEngine cast;
    public static volatile String lastError = "";
    public static volatile long startedAt;
    public static volatile int frames17818, framesOther;
    public static volatile String lastNavi = "";
    public static volatile String lastJson = "";
    /** 被别的进程占住的口（官方 App 的 :wifi 就是常客） */
    private static final java.util.Set<Integer> PORT_CONFLICT =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    public static volatile boolean portConflict;

    // 车机主动上报的信息（17818 上确实收得到的那些）
    public static volatile String carVersionText = "";
    public static volatile String carMacText = "";
    public static volatile int carBattery = -1;      // remaining_oil，百分比
    public static volatile int carEndurance = -1;
    public static volatile int carMileage = -1;      // total，单位未知，按原值显示
    public static volatile int carTire = -1;
    public static volatile String carRide = "";      // current/max/average
    public static volatile long lastCarJsonAt;
    // 面板指令的应答情况
    public static volatile String lastCmdFunc = "";
    public static volatile long lastCmdAt;
    public static volatile boolean lastCmdAnswered;
    /** 车机最近一次连上 17818 的时间（看门狗用） */
    public static volatile long lastMainAcceptAt;

    /** 17818 上现在是否有一条活着的车机连接。 */
    public static boolean carConnected() {
        Socket s = MAIN_SOCK;
        return s != null && s.isConnected() && !s.isClosed();
    }
    /** 本次投屏开始时间（看门狗用） */
    private static volatile long castStartAt;

    private final List<ServerSocket> servers = Collections.synchronizedList(new ArrayList<ServerSocket>());
    private final List<Socket> clients = Collections.synchronizedList(new ArrayList<Socket>());
    private final static List<OutputStream> VIDEO_SINKS = Collections.synchronizedList(new ArrayList<OutputStream>());
    /** 当前 17818 连接（车机连上来的那条），面板指令从这里发。 */
    private static volatile OutputStream MAIN_OUT;
    private static volatile Socket MAIN_SOCK;
    /** 19000（OTA 口）：官方 DeviceWrapper 的 CAR_INFO/ICCID 查询都从这里发 */
    private static volatile OutputStream OTA_OUT;
    public static volatile ProbeService instance;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        startAsForeground();
        config = Config.load(this);
        cast = new CastEngine(this, config, new CastEngine.Listener() {
            @Override
            public void onCastState(String state) {
                lastError = state;
            }
        });
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "probe:work");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            try {
                wifiLock = wm.createWifiLock(mode, "probe:wifi");
            } catch (Throwable t) {
                // 个别 ROM 不认 LOW_LATENCY，退回 HIGH_PERF
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "probe:wifi");
            }
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        KeepAlive.apply(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            startedAt = System.currentTimeMillis();
            new Session().start();
        }
        return START_STICKY;
    }

    /**
     * 从最近任务划掉时，ColorOS 可能把任务和服务一起收掉。安排一次
     * 2 秒后的检查：如果服务还活着，这次启动是无害的；如果已经被杀，
     * 尝试把前台服务拉回来。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (running) {
            try {
                Intent restart = new Intent(this, ProbeService.class).setAction(ACTION_START);
                PendingIntent pi = PendingIntent.getForegroundService(
                        this, 42, restart,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am != null) {
                    am.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            System.currentTimeMillis() + 2000,
                            pi);
                    LogSink.line("SERVICE", "任务被移除，已安排 2s 后检查/拉起服务");
                }
            } catch (Throwable t) {
                LogSink.line("SERVICE", "安排任务移除重启失败: " + t);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        VIDEO_SINKS.clear();
        running = false;
        try {
            if (cast != null) cast.stop();
        } catch (Throwable ignored) {
        }
        synchronized (servers) {
            for (ServerSocket s : servers) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            servers.clear();
        }
        synchronized (clients) {
            for (Socket s : clients) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            clients.clear();
        }
        try {
            if (wakeLock != null) wakeLock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (wifiLock != null) wifiLock.release();
        } catch (Throwable ignored) {
        }
        LogSink.line("SERVICE", "已停止");
        LogSink.close();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void startCast(int resultCode, Intent data) {
        CastEngine c = cast;
        if (c == null) return;
        ProbeService svc = instance;
        if (svc != null) {
            // Android 14+: 使用 mediaProjection 类型前必须先以该类型进入前台
            try {
                Notification n = new Notification.Builder(svc, CHANNEL)
                        .setContentTitle("台铃投屏运行中")
                        .setContentText("投屏进行中")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setOngoing(true)
                        .build();
                if (Build.VERSION.SDK_INT >= 29) {
                    // 车机投屏属于 connectedDevice；dataSync 在 Android 15+ 有 6 小时限制，
                    // 而且后台优先级更低。保留 MediaProjection 分支兼容旧路径。
                    int fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                    if (!config.useVdHelper) {
                        fgsType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                    }
                    try {
                        svc.startForeground(NOTIF_ID, n, fgsType);
                    } catch (Throwable t) {
                        LogSink.line("CAST", "connectedDevice 前台失败，退回 dataSync: " + t);
                        svc.startForeground(NOTIF_ID, n,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    }
                } else {
                    svc.startForeground(NOTIF_ID, n);
                }
            } catch (Throwable t) {
                LogSink.line("CAST", "提升前台类型失败: " + t);
            }
        }
        castStartAt = System.currentTimeMillis();
        c.start(resultCode, data);
        synchronized (VIDEO_SINKS) {
            for (OutputStream os : VIDEO_SINKS) {
                c.addSink(os);
                LogSink.line("15456", "补挂已连接的视频口");
            }
        }
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "台铃探针", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("协议抓取与投屏");
        nm.createNotificationChannel(ch);
    }

    /**
     * 看门狗：车机明明连着 17818，却迟迟不开 15456（2026-11-03 晚上实测卡了 27 分钟），
     * 就自动 stop → start 一次投屏。实测手动重开能立刻恢复。
     * <p>
     * 注意：车机自己会周期性地断开 15456/15457（热点重启、车机应用重开），
     * 实测 10~64 秒内会自己连回来。所以只有“视频口连续 90 秒没有”才重启，
     * 不能用“投屏开了多久”来判断，否则车机一断线就会把整块虚拟屏和导航重启掉。
     */
    private void startCastWatchdog() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long nextTry = 0;
                long noVideoSince = 0;
                while (running) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored) {
                    }
                    if (!running) break;
                    CastEngine c = cast;
                    if (c == null || !c.isRunning()) {
                        nextTry = 0;
                        noVideoSince = 0;
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    // 车机必须“现在”还连着（不能用 lastMainAcceptAt，车机长连时那个值会过期）。
                    // 车机断开期间不计时；重新连上后重新给 90 秒等它开视频口。
                    if (!carConnected()) {
                        noVideoSince = 0;
                        continue;
                    }
                    if (c.sinkCount() > 0) {
                        noVideoSince = 0;
                        continue;
                    }
                    if (noVideoSince == 0) noVideoSince = now;
                    if (now - castStartAt < 20000 || now < nextTry) continue;
                    long noVideo = now - noVideoSince;
                    if (noVideo < 90000) continue;
                    LogSink.line("CAST", "看门狗：车机连着，但视频口 " + (noVideo / 1000)
                            + "s 没连回来（投屏已 " + ((now - castStartAt) / 1000)
                            + "s），自动重启投屏");
                    try {
                        c.stop();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ignored) {
                    }
                    startCast(-1, null);
                    noVideoSince = 0;
                    nextTry = System.currentTimeMillis() + 60000;
                }
            }
        }, "cast-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 连车机热点。官方 App 每次都替用户做这件事，才能拿到 192.168.10.x。
     * 有 root 就直接用 cmd wifi connect-network，静默、无系统弹窗。
     * 日志里只打 SSID，密码走 runRedacted，不落盘。
     */
    /** 连车机热点：API 29+ 用临时网络（不占默认路由），老系统退回保存网络。 */
    public static boolean joinCarWifi(Config c) {
        if (c == null || c.carSsid == null || c.carSsid.length() == 0) return false;
        if (Build.VERSION.SDK_INT >= 29 && instance != null) {
            if (c.forgetCarWifi) WifiLink.forgetSaved(c.carSsid);
            WifiLink.connect(instance, c.carSsid, c.carPass);
            return true;
        }
        return joinCarWifiLegacy(c.carSsid, c.carPass);
    }

    /** 老办法：把热点存进系统再连（会抢默认网络，手机上不了网，只在 API<29 用）。 */
    public static boolean joinCarWifiLegacy(String ssid, String pass) {
        if (ssid == null || ssid.length() == 0) return false;
        RootShell.run("svc wifi enable");
        String quoted = "\"" + ssid + "\"";
        if (pass == null) pass = "";
        String sec = pass.length() == 0 ? "open" : "wpa2";
        String cmd = "cmd wifi " + (pass.length() == 0 ? "connect-network " + quoted + " open"
                : "connect-network " + quoted + " wpa2 \"" + pass + "\"");
        String shown = "cmd wifi connect-network " + quoted + " " + sec
                + (pass.length() == 0 ? "" : " ******");
        // 不在扫描结果里就别切网：connect-network 会先把当前 Wi-Fi 踢掉
        RootShell.run("cmd wifi start-scan");
        sleepMs(1500);
        RootShell.Result scan = RootShell.run("cmd wifi list-scan-results");
        if (scan.out == null || !scan.out.contains(ssid)) {
            LogSink.line("WIFI", "扫描里没有 " + ssid + "（车机热点没开？）先不切网，等它出现");
            RootShell.runRedacted("cmd wifi " + (pass.length() == 0
                    ? "add-network " + quoted + " open"
                    : "add-network " + quoted + " wpa2 \"" + pass + "\""),
                    "cmd wifi add-network " + quoted + " " + sec + (pass.length() == 0 ? "" : " ******"));
            return false;
        }
        RootShell.Result r = RootShell.runRedacted(cmd, shown);
        LogSink.line("WIFI", "连车机热点 " + ssid + (r.ok ? " 已发起" : " 失败"));
        return r.ok;
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void startAsForeground() {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("台铃投屏运行中")
                .setContentText("监听 17818 / 15456 / 15457")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } catch (Throwable t) {
                LogSink.line("SERVICE", "connectedDevice 前台失败，退回 dataSync: " + t);
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            }
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private final class Session extends Thread {
        Session() {
            super("probe-session");
        }

        @Override
        public void run() {
            List<String> ips = localIps();
            LogSink.open(new java.io.File(getExternalFilesDir(null), ""));
            LogSink.note("notes.txt", "started=" + new java.util.Date() + "\n"
                    + "device=" + Build.MANUFACTURER + " " + Build.MODEL + " api=" + Build.VERSION.SDK_INT + "\n"
                    + "ips=" + ips + "\n"
                    + "ports=" + config.portMain + "," + config.portVideo + "," + config.portHeart
                    + "," + config.portDvr + "," + config.portOta + "\n"
                    + "identity nick=" + config.nickname + " tuc=" + config.tuc + " tucs=" + config.tucs
                    + " mac=" + config.mac + " shape=" + config.productShape + "\n\n");
            LogSink.line("NET", "本机地址: " + ips);
            LogSink.line("CFG", "17818 identity: nick=" + config.nickname + ", tuc=" + config.tuc
                    + ", mac=" + config.mac + ", shape=" + config.productShape
                    + ", cast=" + config.castEnabled + " " + config.castWidth + "x" + config.castHeight);

            if (config.carSsid != null && config.carSsid.length() > 0) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        joinCarWifi(config);
                    }
                }, "wifi-join").start();
                // 车机蓝牙名和热点名是同一个字符串
                BleLink.start(ProbeService.this, config.carSsid);
            }

            // 车机热点下顺手读一下仪表版本 JSON（官方 EryaSDK 的 11112），
            // 拿到 sn/pid 才能去云端换整包下载地址
            for (String ip : ips) {
                if (ip.startsWith("192.168.10.")) {
                    TftLink.probe("192.168.10.1", 11112);
                    break;
                }
            }

            listen(config.portMain, "17818");
            listen(config.portVideo, "15456");
            listen(config.portHeart, "15457");
            listen(config.portDvr, "18888");
            listen(config.portOta, "19000");
            startCastWatchdog();
        }
    }

    private static boolean kicked;

    private static boolean isAddrInUse(Throwable e) {
        if (e instanceof java.net.BindException) return true;
        String m = String.valueOf(e);
        return m.contains("EADDRINUSE") || m.contains("Address already in use");
    }

    private void listen(final int port, final String tag) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int attempts = 0;
                while (running) {
                    ServerSocket ss = null;
                    try {
                        ss = new ServerSocket();
                        ss.setReuseAddress(true);
                        ss.bind(new java.net.InetSocketAddress("0.0.0.0", port));
                        servers.add(ss);
                        attempts = 0;
                        PORT_CONFLICT.remove(port);
                        portConflict = !PORT_CONFLICT.isEmpty();
                        LogSink.line(tag, "listening 0.0.0.0:" + port);
                        while (running) {
                            final Socket s = ss.accept();
                            s.setTcpNoDelay(true);
                            s.setKeepAlive(true);
                            clients.add(s);
                            if (port == config.portMain) lastMainAcceptAt = System.currentTimeMillis();
                            final String peer = String.valueOf(s.getRemoteSocketAddress());
                            LogSink.line(tag, "accept " + peer);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    handle(port, tag, s, peer);
                                }
                            }, tag + "-" + peer).start();
                        }
                    } catch (Throwable e) {
                        if (running) {
                            LogSink.line(tag, "监听异常(" + port + "): " + e);
                            if (isAddrInUse(e)) {
                                PORT_CONFLICT.add(port);
                                portConflict = true;
                                if (!kicked && config != null && config.kickOfficial) {
                                    kicked = true;
                                    LogSink.line(tag, "端口被占，先停掉官方 App 再重试");
                                    RootShell.run("am force-stop com.tailg.run.intelligence");
                                }
                                attempts++;
                                if (attempts > 8) {
                                    LogSink.line(tag, "放弃监听 " + port + "：端口一直被占用");
                                    return;
                                }
                                sleepMs(1000);
                                continue;
                            }
                            try {
                                Thread.sleep(1200);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    } finally {
                        if (ss != null) {
                            try {
                                ss.close();
                            } catch (Exception ignored) {
                            }
                            servers.remove(ss);
                        }
                    }
                }
            }
        }, "listen-" + port);
        t.start();
    }

    private void handle(int port, String tag, Socket s, String peer) {
        // 和官方 App 的 SocketListenThread 对齐：小包立即发、开 keepalive，
        // 否则车机在链路慢时容易把延迟当成掉线。
        try {
            s.setTcpNoDelay(true);
        } catch (Throwable ignored) {
        }
        try {
            s.setKeepAlive(true);
        } catch (Throwable ignored) {
        }
        try {
            if (port == config.portMain) {
                handleMain(s, peer);
            } else if (port == config.portVideo) {
                handleVideo(s, peer);
            } else if (port == config.portHeart) {
                handleHeart(s, peer);
            } else {
                handleOther(port, s, peer);
            }
        } catch (Throwable e) {
            LogSink.line(tag, "会话异常 " + peer + ": " + e);
        } finally {
            clients.remove(s);
            if (s == MAIN_SOCK) {
                MAIN_SOCK = null;
                MAIN_OUT = null;
            }
            if (port == config.portOta && OTA_OUT != null) {
                OTA_OUT = null;
            }
            try {
                s.close();
            } catch (Exception ignored) {
            }
            LogSink.line(tag, "close " + peer);
        }
    }

    // ------------------------------------------------------------ 17818

    private void handleMain(final Socket s, final String peer) throws Exception {
        final InputStream in = new java.io.PushbackInputStream(s.getInputStream(), 8);
        final OutputStream out = s.getOutputStream();
        MAIN_OUT = out;
        MAIN_SOCK = s;

        LogSink.event("17818.connect", "\"peer\":\"" + LogSink.esc(peer) + "\"");
        send(out, Codec.jsonFrame("{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"GET\"}"), "TUC GET");
        send(out, Codec.frame(1, 1, new byte[0]), "01 01 请求版本");
        send(out, Codec.frame(1, 0x17, Codec.be32(2)), "01 17 设备类型=2");
        send(out, Codec.frame(1, 0x0C, Codec.fixedString(config.nickname, 256)), "01 0C 昵称");

        final boolean[] versionSeen = {false};
        final boolean[] macSeen = {false};

        Thread hb = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (running && !s.isClosed()) {
                        Thread.sleep(2000);
                        if (s.isClosed()) break;   // 连接已断开，别往死 socket 写（日志会误导）
                        if (!versionSeen[0]) {
                            send(out, Codec.frame(1, 1, new byte[0]), "心跳:要版本");
                        } else if (!macSeen[0]) {
                            send(out, Codec.frame(1, 0x11, new byte[0]), "心跳:要MAC");
                        } else {
                            send(out, Codec.frame(2, 1, new byte[0]), "心跳 02 01");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "hb-17818");
        hb.start();

        while (running && !s.isClosed()) {
            Codec.RawFrame f = Codec.readFrame(in);
            if (f == null) break;
            frames17818++;
            if (f.badLength) {
                LogSink.line("17818<<", "非法长度 " + f.head());
                break;
            }
            if (Codec.isJson(f)) {
                String js = Codec.jsonText(f);
                lastJson = js;
                LogSink.line("17818<<", "JSON" + (Codec.jsonTailOk(f) ? "" : "(缺FF)") + " " + js);
                LogSink.event("17818.json", "\"text\":\"" + LogSink.esc(js) + "\"");
                onJsonFromCar(js, out);
            } else if (f.type == 0x03 && f.cmd == 0x01 && f.payload.length >= 268) {
                int icon = Codec.be32(f.payload, 0);
                String road = new String(f.payload, 4, 256, Codec.UTF8);
                int z = road.indexOf(0);
                if (z >= 0) road = road.substring(0, z);
                int cur = Codec.be32(f.payload, 260);
                int remain = Codec.be32(f.payload, 264);
                lastNavi = "icon=" + icon + " road=" + road + " " + cur + "m/" + remain + "m";
                LogSink.line("17818<<", "导航包 " + lastNavi);
                LogSink.event("17818.navi", "\"icon\":" + icon + ",\"road\":\"" + LogSink.esc(road)
                        + "\",\"cur\":" + cur + ",\"remain\":" + remain);
            } else {
                LogSink.line("17818<<", f.head() + " | " + LogSink.hex(f.payload, Math.min(f.len, 256)));
                LogSink.event("17818.raw", "\"ftype\":" + f.type + ",\"cmd\":" + f.cmd
                        + ",\"hex\":\"" + LogSink.hex(f.payload, Math.min(f.len, 4096)) + "\"");
                onBinaryFromCar(f, out, versionSeen, macSeen);
            }
        }
    }

    /** BLE 通道收到的 JSON 也走这里。 */
    public static void onCarJson(String js, String from) {
        parseCarJson(js);
        LogSink.event("car.json", "\"from\":\"" + from + "\",\"text\":\"" + LogSink.esc(js) + "\"");
    }

    private static void parseCarJson(String js) {
        try {
            lastCarJsonAt = System.currentTimeMillis();
            // 只有 msg_id=27 的业务应答才算"回了"；车机每 30 秒推送的骑行数据不算
            if (js.contains("\"msg_id\":27")) lastCmdAnswered = true;
            try {
                org.json.JSONObject o = new org.json.JSONObject(js);
                if (o.optInt("msg_id", -1) == 10) {
                    int item = o.optInt("item", -1);
                    if (item == 1) {
                        carRide = o.optInt("current", 0) + "/" + o.optInt("max", 0)
                                + "/" + o.optInt("average", 0);
                    } else if (item == 2) {
                        carMileage = o.optInt("total", -1);
                    } else if (item == 3) {
                        carBattery = o.optInt("remaining_oil", -1);
                        carEndurance = o.optInt("endurance", -1);
                        carTire = o.optInt("tire_pressure", -1);
                    }
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private void onJsonFromCar(String js, OutputStream out) {
        try {
            parseCarJson(js);
            if (js.contains("\"TUC\"") && js.contains("\"GET\"")) {
                send(out, Codec.jsonFrame("{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"SEND\",\"tuc\":\""
                        + config.tuc + "\",\"tucs\":" + config.tucs + "}"), "TUC SEND");
                send(out, Codec.jsonFrame("{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"STATUS\",\"status\":1}"), "TUC STATUS");
            }
        } catch (Throwable t) {
            LogSink.line("17818", "JSON 应答异常 " + t);
        }
    }

    private void onBinaryFromCar(Codec.RawFrame f, OutputStream out, boolean[] versionSeen, boolean[] macSeen) {
        try {
            if (f.type == 1 && f.len == 0) {
                if (f.cmd == 1) {
                    send(out, Codec.frame(1, 2, config.versionPayload()), "01 02 版本");
                    versionSeen[0] = true;
                } else if (f.cmd == 0x11) {
                    send(out, Codec.frame(1, 0x12, Codec.fixedString(config.mac, 32)), "01 12 MAC");
                    macSeen[0] = true;
                } else if (f.cmd == 0x0E) {
                    send(out, Codec.frame(1, 0x0F, Codec.be32(config.productShape)), "01 0F 产品形态");
                } else if (f.cmd == 0x14) {
                    send(out, Codec.frame(1, 0x15, Codec.be32(config.language)), "01 15 语言");
                }
            } else if (f.type == 1 && (f.cmd == 2)) {
                versionSeen[0] = true;
                carVersionText = Codec.versionBlocks(f.payload);
                LogSink.line("17818<<", "车机版本: " + carVersionText);
            } else if (f.type == 1 && f.cmd == 0x12) {
                macSeen[0] = true;
                String mac = new String(f.payload, 0, Math.min(32, f.payload.length), Codec.UTF8).trim();
                carMacText = mac;
                LogSink.line("17818<<", "车机 MAC: " + mac);
            } else if (f.type == 1 && f.cmd == 0x0F) {
                config.productShape = Codec.be32(f.payload, 0);
                LogSink.line("17818<<", "车机产品形态: " + config.productShape);
            } else if (f.type == 1 && f.cmd == 0x15) {
                LogSink.line("17818<<", "车机语言: " + Codec.be32(f.payload, 0));
            } else if (f.type == 3 && f.cmd == 1) {
                LogSink.line("17818<<", "type3 状态包 len=" + f.len);
            }
        } catch (Throwable t) {
            LogSink.line("17818", "二进制应答异常 " + t);
        }
    }

    private synchronized void send(OutputStream out, byte[] data, String what) {
        try {
            out.write(data);
            out.flush();
            LogSink.line("17818>>", what + " (" + data.length + "B) " + LogSink.hex(data, Math.min(data.length, 64)));
        } catch (Throwable t) {
            LogSink.line("17818>>", what + " 发送失败: " + t);
        }
    }

    // ------------------------------------------------------------ 面板指令

    /** 发一条 JSON 到当前 17818 连接（车机没连就返回 false）。 */
    /** 车机此刻有没有连着 17818。 */
    public static boolean carOnline() {
        return MAIN_OUT != null;
    }

    /** 面板指令统一从这条线程发：主线程写 socket 会触发 NetworkOnMainThreadException。 */
    private static final java.util.concurrent.ExecutorService CAR_TX =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    public static boolean sendCarJson(final String json) {
        try {
            lastCmdFunc = new org.json.JSONObject(json).optString("func", "");
        } catch (Throwable ignored) {
            lastCmdFunc = "";
        }
        lastCmdAt = System.currentTimeMillis();
        lastCmdAnswered = false;
        final boolean tcp = MAIN_OUT != null || OTA_OUT != null;
        final boolean ble = BleLink.connected;
        if (!tcp && !ble) {
            LogSink.line("CAR>>", "车机没连（17818 / 19000 / BLE 都没有）: " + json);
            return false;
        }
        final byte[] frame = Codec.jsonFrame(json);
        CAR_TX.execute(new Runnable() {
            @Override
            public void run() {
                if (tcp) writeCar(frame, "JSON " + json);
                if (ble) BleLink.send(json);
            }
        });
        return true;
    }

    public static boolean sendCarRaw(final byte[] data) {
        if (MAIN_OUT == null && OTA_OUT == null) return false;
        CAR_TX.execute(new Runnable() {
            @Override
            public void run() {
                writeCar(data, LogSink.hex(data, Math.min(data.length, 32)));
            }
        });
        return true;
    }

    private static synchronized void writeCar(byte[] data, String what) {
        boolean any = false;
        OutputStream[] outs = {MAIN_OUT, OTA_OUT};
        String[] tags = {"17818", "19000"};
        for (int i = 0; i < outs.length; i++) {
            if (outs[i] == null) continue;
            try {
                outs[i].write(data);
                outs[i].flush();
                any = true;
                LogSink.line("CAR>>", tags[i] + " " + what + " (" + data.length + "B)");
            } catch (Throwable t) {
                LogSink.line("CAR>>", tags[i] + " 发送失败 " + t);
            }
        }
        if (!any) LogSink.line("CAR>>", "车机没连，发不出去: " + what);
    }

    // ------------------------------------------------------------ 15456

    private void handleVideo(Socket s, String peer) throws Exception {
        LogSink.event("15456.connect", "\"peer\":\"" + LogSink.esc(peer) + "\"");
        // 和官方 App 一致用 32KB 发送缓冲：128KB 在车机慢链路上会攒出
        // 好几秒延迟，车机侧容易把"没新画面"当成断流。丢帧交给 Sink 队列处理。
        try {
            s.setSendBufferSize(32 * 1024);
        } catch (Throwable ignored) {
        }
        OutputStream out = s.getOutputStream();
        CastEngine c = cast;
        synchronized (VIDEO_SINKS) {
            VIDEO_SINKS.add(out);
        }
        if (c != null && c.isRunning()) {
            c.addSink(out);
            LogSink.line("15456", "挂上投屏输出 " + peer);
        } else {
            LogSink.line("15456", "投屏未启动，先记录；开播后会自动补挂");
        }
        InputStream in = s.getInputStream();
        byte[] buf = new byte[4096];
        while (running) {
            int r = in.read(buf);
            if (r < 0) break;
            if (r > 0) {
                LogSink.line("15456<<", "上行 " + r + "B " + LogSink.hex(buf, Math.min(r, 64)));
            }
        }
        if (c != null) c.removeSink(out);
        synchronized (VIDEO_SINKS) {
            VIDEO_SINKS.remove(out);
        }
    }

    // ------------------------------------------------------------ 15457

    private void handleHeart(Socket s, String peer) throws Exception {
        LogSink.event("15457.connect", "\"peer\":\"" + LogSink.esc(peer) + "\"");
        final OutputStream out = s.getOutputStream();
        final boolean[] alive = {true};
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (alive[0] && running) {
                        out.write(new byte[]{2, 1, 0, 0, 0, 0});
                        out.flush();
                        Thread.sleep(450);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, "hb-15457");
        t.start();
        InputStream in = s.getInputStream();
        byte[] buf = new byte[256];
        while (running) {
            int r = in.read(buf);
            if (r < 0) break;
            if (r > 0) LogSink.line("15457<<", LogSink.hex(buf, r));
        }
        alive[0] = false;
    }

    // ------------------------------------------------------------ 18888 / 19000

    private void handleOther(int port, Socket s, String peer) throws Exception {
        String tag = String.valueOf(port);
        LogSink.event("port.connect", "\"port\":" + port + ",\"peer\":\"" + LogSink.esc(peer) + "\"");
        InputStream in = new java.io.PushbackInputStream(s.getInputStream(), 8);
        final OutputStream out = s.getOutputStream();
        if (port == config.portOta) {
            OTA_OUT = out;
            // 官方 DeviceWrapper 连上就发：TUC GET + 请求版本号
            send(out, Codec.jsonFrame("{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"GET\"}"), "19000 TUC GET");
            send(out, Codec.frame(1, 1, new byte[0]), "19000 01 01 请求版本");
        }
        while (running) {
            Codec.RawFrame f = Codec.readFrame(in);
            if (f == null) break;
            framesOther++;
            if (Codec.isJson(f)) {
                String js = Codec.jsonText(f);
                LogSink.line(tag + "<<", "JSON " + js);
                LogSink.event("port.json", "\"port\":" + port + ",\"text\":\"" + LogSink.esc(js) + "\"");
            } else {
                LogSink.line(tag + "<<", f.head() + " | " + LogSink.hex(f.payload, Math.min(f.len, 256)));
                LogSink.event("port.raw", "\"port\":" + port + ",\"ftype\":" + f.type + ",\"cmd\":" + f.cmd
                        + ",\"hex\":\"" + LogSink.hex(f.payload, Math.min(f.len, 4096)) + "\"");
            }
        }
    }

    // ------------------------------------------------------------ utils

    public static List<String> localIps() {
        List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        out.add(a.getHostAddress() + "%" + ni.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
