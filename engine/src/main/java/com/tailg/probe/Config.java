package com.tailg.probe;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/** 配置：外部 files/config.properties，改完重启服务生效。 */
public final class Config {

    public String nickname = "TL-PROBE";
    public String tuc = "PROBE00000000001";     // 必须 >=16 字符
    public int tucs = 1;
    public String version = "V1.2.5-PROBE";     // 版本块 1
    public String mcuVersion = "MCU-PROBE";     // 版本块 2
    public String ucVersion = "UC-PROBE";       // 版本块 3
    public String tucVersion = "TUC-PROBE";     // 版本块 4
    public String mac = "AA:BB:CC:DD:EE:FF";
    public int productShape = 7;                // 7 => 1024x600
    public int language = 2;

    public boolean castEnabled = true;
    public boolean castFill = true;   // true=裁边填满, false=留黑边
    public int castCropTop = -1;      // 顶部黑边像素（源尺寸）；-1=按状态栏高度自动
    public boolean carMode = false;   // true=投屏前用 su 改显示尺寸，让高德按车机比例渲染
    public int carWidth = 1280;
    public int carHeight = 752;
    public int carDensity = 280;
    // 新方案：自己建虚拟屏，把目标 App 启动到这块屏上，原样采集（不缩放）
    public String appDisplayPkg = "";                                  // 非空则启用
    public String appDisplayActivity = "com.autonavi.minimap/com.autonavi.map.activity.SplashActivity";
    public int appDisplayDensity = 160;   // 1024x600 @160dpi = 1024x600dp，高德走平板布局
    public boolean appDisplayMove = true;
    public boolean appDisplayFreeform = true;
    public boolean appDisplayIgnoreSizeRestrictions = true;  // 建 trusted 屏时尝试 setIgnoreActivitySizeRestrictions
    public boolean appDisplayDirect = true;  // true=直接全屏启动到虚拟屏，失败再回退 freeform+搬屏
    public boolean castProfileHigh = true;   // 官方会设 profile=8(High)/level=4096，失败自动回退
    public boolean useVdHelper = false;      // true=用助手调 DisplayManager 建屏（不要投影授权弹窗）
    public boolean useShizuku = false;       // true=助手由 Shizuku 以 shell/root 身份启动；false=su
    public int castWidth = 1024;
    public int castHeight = 600;
    public int castFps = 30;
    public int castBitrate = 2000000;
    public boolean kickOfficial = true;
    public boolean forgetCarWifi = true;
    public boolean dumpStream = false;   // 调试：把码流存成 cast.h264，用来查黑边  // 连车机热点前，把以前存进系统的同名网络删掉  // 端口被官方 App 占住时，自动 am force-stop 它
    public String carSsid = "";    // 车机热点名（填了就在启动时自动连）
    public String carPass = "";    // 车机热点密码
    public int safeTop = 0;      // 车机 HUD 盖住的上边高度（px，按 castHeight 计）
    public int safeBottom = 0;   // 车机 HUD 盖住的下边高度
    public int castIFrameInterval = 2;   // 秒；1 秒一个关键帧≈每帧都塞 60~120KB，容易把链路顶住

    public int portMain = 17818;
    public int portVideo = 15456;
    public int portHeart = 15457;
    public int portDvr = 18888;
    public int portOta = 19000;

    // 云端（台铃服务端，需要登录 token；token 失效就在界面上重填）
    public String cloudToken = "";                 // authorization 头
    public String carImei = "";                    // 车机 4G 的 IMEI，远控指令用它定位车辆
    public String carFrame = "";                   // 车架号，骑行统计/历史轨迹接口要用
    public String cloudUid = "";                   // 官方账号 uid，历史轨迹接口要用
    public long cloudTokenAt = 0L;                 // token 获取时间（毫秒）
    public long cloudTokenCheckedAt = 0L;          // 最近一次云端检查时间（毫秒）
    public String cloudBase = "https://www.tailgdd.com/v1/api/";

    /** root / shizuku，界面和日志用。 */
    public String vdBackend() {
        return useShizuku ? "shizuku" : "root";
    }

    /** 只把投屏相关的几项拷进来，用于"改完立刻生效"而不必重启进程。 */
    public void copyFrom(Config o) {
        castWidth = o.castWidth;
        castHeight = o.castHeight;
        castFps = o.castFps;
        castBitrate = o.castBitrate;
        castIFrameInterval = o.castIFrameInterval;
        carSsid = o.carSsid;
        carPass = o.carPass;
        safeTop = o.safeTop;
        safeBottom = o.safeBottom;
        castProfileHigh = o.castProfileHigh;
        useVdHelper = o.useVdHelper;
        useShizuku = o.useShizuku;
        appDisplayDensity = o.appDisplayDensity;
        appDisplayIgnoreSizeRestrictions = o.appDisplayIgnoreSizeRestrictions;
        appDisplayDirect = o.appDisplayDirect;
        cloudToken = o.cloudToken;
        cloudTokenAt = o.cloudTokenAt;
        cloudTokenCheckedAt = o.cloudTokenCheckedAt;
        carFrame = o.carFrame;
        cloudUid = o.cloudUid;
    }

    /** 改一项并写回 config.properties（其它项原样保留）。 */
    public static boolean saveValue(Context ctx, String key, String value) {
        File f = new File(ctx.getExternalFilesDir(null), "config.properties");
        Properties p = new Properties();
        if (f.exists()) {
            try {
                FileInputStream in = new FileInputStream(f);
                p.load(in);
                in.close();
            } catch (Exception ignored) {
            }
        }
        p.setProperty(key, value);
        try {
            FileOutputStream out = new FileOutputStream(f);
            p.store(out, "tailg probe config");
            out.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Config load(Context ctx) {
        Config c = new Config();
        File f = new File(ctx.getExternalFilesDir(null), "config.properties");
        Properties p = new Properties();
        if (f.exists()) {
            try {
                FileInputStream in = new FileInputStream(f);
                p.load(in);
                in.close();
            } catch (Exception ignored) {
            }
        }
        c.nickname = p.getProperty("nickname", c.nickname);
        c.tuc = p.getProperty("tuc", c.tuc);
        c.tucs = parseInt(p.getProperty("tucs"), c.tucs);
        c.version = p.getProperty("version", c.version);
        c.mcuVersion = p.getProperty("mcuVersion", c.mcuVersion);
        c.ucVersion = p.getProperty("ucVersion", c.ucVersion);
        c.tucVersion = p.getProperty("tucVersion", c.tucVersion);
        c.mac = p.getProperty("mac", c.mac);
        c.productShape = parseInt(p.getProperty("productShape"), c.productShape);
        c.language = parseInt(p.getProperty("language"), c.language);
        c.castEnabled = !"0".equals(p.getProperty("castEnabled", "1"));
        c.castFill = !"0".equals(p.getProperty("castFill", "1"));
        c.castCropTop = parseInt(p.getProperty("castCropTop"), c.castCropTop);
        c.carMode = "1".equals(p.getProperty("carMode", c.carMode ? "1" : "0"));
        c.carWidth = parseInt(p.getProperty("carWidth"), c.carWidth);
        c.carHeight = parseInt(p.getProperty("carHeight"), c.carHeight);
        c.carDensity = parseInt(p.getProperty("carDensity"), c.carDensity);
        c.appDisplayPkg = p.getProperty("appDisplayPkg", c.appDisplayPkg).trim();
        c.appDisplayActivity = p.getProperty("appDisplayActivity", c.appDisplayActivity).trim();
        c.appDisplayDensity = parseInt(p.getProperty("appDisplayDensity"), c.appDisplayDensity);
        c.appDisplayMove = "1".equals(p.getProperty("appDisplayMove", c.appDisplayMove ? "1" : "0"));
        c.appDisplayFreeform = "1".equals(p.getProperty("appDisplayFreeform", c.appDisplayFreeform ? "1" : "0"));
        c.appDisplayIgnoreSizeRestrictions = "1".equals(p.getProperty(
                "appDisplayIgnoreSizeRestrictions", c.appDisplayIgnoreSizeRestrictions ? "1" : "0"));
        c.appDisplayDirect = "1".equals(p.getProperty("appDisplayDirect", c.appDisplayDirect ? "1" : "0"));
        c.castProfileHigh = "1".equals(p.getProperty("castProfileHigh", c.castProfileHigh ? "1" : "0"));
        c.useVdHelper = "1".equals(p.getProperty("useVdHelper", c.useVdHelper ? "1" : "0"));
        c.useShizuku = "1".equals(p.getProperty("useShizuku", c.useShizuku ? "1" : "0"));
        // UI 已移除 MediaProjection；旧配置里的 useVdHelper=0 迁移到助手模式。
        c.useVdHelper = true;
        c.castWidth = parseInt(p.getProperty("castWidth"), c.castWidth);
        c.castHeight = parseInt(p.getProperty("castHeight"), c.castHeight);
        c.castFps = parseInt(p.getProperty("castFps"), c.castFps);
        c.castBitrate = parseInt(p.getProperty("castBitrate"), c.castBitrate);
        c.castIFrameInterval = parseInt(p.getProperty("castIFrameInterval"), c.castIFrameInterval);
        c.kickOfficial = !"0".equals(p.getProperty("kickOfficial", "1"));
        c.forgetCarWifi = !"0".equals(p.getProperty("forgetCarWifi", "1"));
        c.dumpStream = "1".equals(p.getProperty("dumpStream", "0"));
        c.carSsid = p.getProperty("carSsid", c.carSsid).trim();
        c.carPass = p.getProperty("carPass", c.carPass).trim();
        c.safeTop = parseInt(p.getProperty("safeTop"), c.safeTop);
        c.safeBottom = parseInt(p.getProperty("safeBottom"), c.safeBottom);
        c.portMain = parseInt(p.getProperty("portMain"), c.portMain);
        c.portVideo = parseInt(p.getProperty("portVideo"), c.portVideo);
        c.portHeart = parseInt(p.getProperty("portHeart"), c.portHeart);
        c.portDvr = parseInt(p.getProperty("portDvr"), c.portDvr);
        c.portOta = parseInt(p.getProperty("portOta"), c.portOta);
        c.cloudToken = p.getProperty("cloudToken", c.cloudToken).trim();
        c.cloudTokenAt = parseLong(p.getProperty("cloudTokenAt"), c.cloudTokenAt);
        c.cloudTokenCheckedAt = parseLong(p.getProperty("cloudTokenCheckedAt"), c.cloudTokenCheckedAt);
        c.carImei = p.getProperty("carImei", c.carImei).trim();
        c.carFrame = p.getProperty("carFrame", c.carFrame).trim();
        c.cloudUid = p.getProperty("cloudUid", c.cloudUid).trim();
        c.cloudBase = p.getProperty("cloudBase", c.cloudBase).trim();

        if (!f.exists()) {
            try {
                FileOutputStream out = new FileOutputStream(f);
                p.setProperty("nickname", c.nickname);
                p.setProperty("tuc", c.tuc);
                p.setProperty("tucs", String.valueOf(c.tucs));
                p.setProperty("version", c.version);
                p.setProperty("mcuVersion", c.mcuVersion);
                p.setProperty("ucVersion", c.ucVersion);
                p.setProperty("tucVersion", c.tucVersion);
                p.setProperty("mac", c.mac);
                p.setProperty("productShape", String.valueOf(c.productShape));
                p.setProperty("language", String.valueOf(c.language));
                p.setProperty("castEnabled", c.castEnabled ? "1" : "0");
                p.setProperty("castFill", c.castFill ? "1" : "0");
                p.setProperty("castCropTop", String.valueOf(c.castCropTop));
                p.setProperty("carMode", c.carMode ? "1" : "0");
                p.setProperty("carWidth", String.valueOf(c.carWidth));
                p.setProperty("carHeight", String.valueOf(c.carHeight));
                p.setProperty("carDensity", String.valueOf(c.carDensity));
                p.setProperty("appDisplayPkg", c.appDisplayPkg);
                p.setProperty("appDisplayActivity", c.appDisplayActivity);
                p.setProperty("appDisplayDensity", String.valueOf(c.appDisplayDensity));
                p.setProperty("appDisplayMove", c.appDisplayMove ? "1" : "0");
                p.setProperty("appDisplayFreeform", c.appDisplayFreeform ? "1" : "0");
                p.setProperty("appDisplayIgnoreSizeRestrictions",
                        c.appDisplayIgnoreSizeRestrictions ? "1" : "0");
                p.setProperty("appDisplayDirect", c.appDisplayDirect ? "1" : "0");
                p.setProperty("castProfileHigh", c.castProfileHigh ? "1" : "0");
                p.setProperty("useVdHelper", c.useVdHelper ? "1" : "0");
                p.setProperty("useShizuku", c.useShizuku ? "1" : "0");
                p.setProperty("appDisplayMove", c.appDisplayMove ? "1" : "0");
                p.setProperty("castWidth", String.valueOf(c.castWidth));
                p.setProperty("castHeight", String.valueOf(c.castHeight));
                p.setProperty("castFps", String.valueOf(c.castFps));
                p.setProperty("castBitrate", String.valueOf(c.castBitrate));
                p.setProperty("castIFrameInterval", String.valueOf(c.castIFrameInterval));
                p.setProperty("carSsid", c.carSsid);
                p.setProperty("carPass", c.carPass);
                p.setProperty("safeTop", String.valueOf(c.safeTop));
                p.setProperty("safeBottom", String.valueOf(c.safeBottom));
                p.setProperty("portMain", String.valueOf(c.portMain));
                p.setProperty("portVideo", String.valueOf(c.portVideo));
                p.setProperty("portHeart", String.valueOf(c.portHeart));
                p.setProperty("portDvr", String.valueOf(c.portDvr));
                p.setProperty("portOta", String.valueOf(c.portOta));
                p.store(out, "tailg probe config");
                out.close();
            } catch (Exception ignored) {
            }
        }
        return c;
    }

    private static long parseLong(String s, long d) {
        try { return Long.parseLong(s); } catch (Exception e) { return d; }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public byte[] versionPayload() {
        byte[] out = new byte[512];
        System.arraycopy(Codec.fixedString(version, 128), 0, out, 0, 128);
        System.arraycopy(Codec.fixedString(mcuVersion, 128), 0, out, 128, 128);
        System.arraycopy(Codec.fixedString(ucVersion, 128), 0, out, 256, 128);
        System.arraycopy(Codec.fixedString(tucVersion, 128), 0, out, 384, 128);
        return out;
    }
}
