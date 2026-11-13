package com.tailg.probe;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 台铃云端（和官方 App 同一套接口）。
 *
 * 只做"用得到的那几个"：车辆状态、锁车/解锁/寻车、自检。
 * token 来自官方 App 的 authorization 头，存在 config.properties，过期了在界面上换。
 */
public final class CloudApi {

    /** 服务没启动时也要能读配置。 */
    private static android.content.Context APP;

    public static void attach(android.content.Context c) {
        if (c != null) APP = c.getApplicationContext();
    }

    public interface Cb {
        void done(boolean ok, String text);
    }

    private CloudApi() {
    }

    // ---------------------------------------------------------------- 只读

    public static void carStatus(final android.content.Context ctx, final Cb cb) {
        attach(ctx);
        JSONObject body = new JSONObject();
        try {
            body.put("phoneMode", android.os.Build.MODEL);
        } catch (Throwable ignored) {
        }
        post("app/centralControl/carStatus", body, new Cb() {
            @Override
            public void done(boolean ok, String text) {
                // 返回原始 JSON，界面自己解析成结构化卡片；这里只顺手记住 IMEI。
                if (ok) rememberImei(text);
                cb.done(ok, text);
            }
        });
    }

    /**
     * BMS TLV 明细。carStatus 的 batteryChargeStatus 会偶尔闪 0，但这里的
     * batteryCurrent / batteryCyclesNum / batteryTemperature 是稳定的，
     * 用来交叉校验充电状态和显示循环次数。
     */
    public static void bmsTlv(final android.content.Context ctx, final Cb cb) {
        attach(ctx);
        post("app/mine/bmsTlvBatteryInfo", new JSONObject(), cb);
    }

    public static void tirePressure(final android.content.Context ctx, final Cb cb) {        attach(ctx);
        JSONObject body = new JSONObject();
        try {
            body.put("imei", imei());
        } catch (Throwable ignored) {
        }
        post("app/tirePressure/status", body, cb);
    }

    // ---------------------------------------------------------------- 骑行

    /**
     * 骑行统计：官方 app/appRiding/getRidingDetail。
     * model=days/weeks/months，key=yyyyMMdd / yyyyMMww / yyyyMM，carFrame=车架号。
     */
    public static void rideDetail(final android.content.Context ctx, final String model,
                                  final String key, final Cb cb) {
        attach(ctx);
        String frame = carFrame();
        if (frame.length() == 0) {
            cb.done(false, "还没有车架号（先刷新一次车辆状态）");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("model", model);
            body.put("key", key);
            body.put("carFrame", frame);
        } catch (Throwable ignored) {
        }
        post("app/appRiding/getRidingDetail", body, cb);
    }

    /**
     * 某个月的历史轨迹：官方 app/centralControl/deviceTravel。
     * queryMonth=yyyy-MM，frame=车架号，uid=账号 uid。
     */
    public static void travelMonth(final android.content.Context ctx, final String month,
                                   final Cb cb) {
        attach(ctx);
        String frame = carFrame();
        String uid = cloudUid();
        if (frame.length() == 0 || uid.length() == 0) {
            cb.done(false, "缺少车架号或账号 uid（先刷新一次车辆状态）");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("queryMonth", month);
            body.put("frame", frame);
            body.put("uid", uid);
        } catch (Throwable ignored) {
        }
        post("app/centralControl/deviceTravel", body, cb);
    }

    /** 单段轨迹的坐标点：官方 app/centralControl/deviceTravelDetail。 */
    public static void travelDetail(final android.content.Context ctx, final String travelId,
                                    final Cb cb) {
        attach(ctx);
        JSONObject body = new JSONObject();
        try {
            body.put("deviceTravelId", travelId);
        } catch (Throwable ignored) {
        }
        post("app/centralControl/deviceTravelDetail", body, cb);
    }

    // ---------------------------------------------------------------- 远控

    /** action: lock / unlock / search / status / openCushion。车辆必须 4G 在线。 */
    public static void cmd(final android.content.Context ctx, final String action, final Cb cb) {
        attach(ctx);
        String id = imei();
        if (id.length() == 0) {
            cb.done(false, "没填车机 IMEI（先刷新一次车辆状态会自动带出来）");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("imei", id);
        } catch (Throwable ignored) {
        }
        post("app/device/cmd/" + action, body, cb);
    }

    // ---------------------------------------------------------------- 底层

    /** 服务没起时也能用：直接读配置文件。 */
    private static Config cfg() {
        if (ProbeService.config != null) return ProbeService.config;
        if (APP != null) return Config.load(APP);
        return null;
    }

    private static String imei() {
        Config c = cfg();
        return (c == null || c.carImei == null) ? "" : c.carImei;
    }

    private static String carFrame() {
        Config c = cfg();
        return (c == null || c.carFrame == null) ? "" : c.carFrame;
    }

    private static String cloudUid() {
        Config c = cfg();
        return (c == null || c.cloudUid == null) ? "" : c.cloudUid;
    }

    private static String token() {
        Config c = cfg();
        return c == null ? "" : c.cloudToken;
    }

    private static String base() {
        Config c = cfg();
        String b = (c == null || c.cloudBase.length() == 0) ? "https://www.tailgdd.com/v1/api/" : c.cloudBase;
        return b.endsWith("/") ? b : b + "/";
    }

    private static void post(final String path, final JSONObject body, final Cb cb) {
        final String tk = token();
        if (tk.length() == 0) {
            cb.done(false, "没配云端 token");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                boolean ok;
                try {
                    result = doPost(base() + path, body.toString(), tk);
                    ok = true;
                } catch (Throwable t) {
                    result = String.valueOf(t);
                    ok = false;
                }
                LogSink.line("CLOUD", (ok ? "<<" : "!!") + " " + path + " "
                        + clip(redact(result), 600));
                cb.done(ok, result);
            }
        }, "cloud-" + path).start();
    }

    private static String doPost(String url, String body, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", "okhttp/4.2.2");
        c.setRequestProperty("language", "zh_CN");
        c.setRequestProperty("authorization", token);
        OutputStream out = c.getOutputStream();
        out.write(body.getBytes("UTF-8"));
        out.flush();
        out.close();

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
        }
        c.disconnect();
        String text = new String(buf.toByteArray(), "UTF-8");
        if (code >= 400) throw new Exception("HTTP " + code + " " + clip(text, 200));
        return text;
    }

    /** carStatus 里带 IMEI / frame / userId，远控和骑行统计要用，成功后自动记住。 */
    private static void rememberImei(String raw) {
        try {
            JSONObject j = new JSONObject(raw);
            JSONObject d = j.optJSONObject("data");
            if (d == null) return;
            String imei = d.optString("imei", "");
            String frame = d.optString("frame", "");
            String uid = d.optString("userId", "");
            Config existing = cfg();
            boolean uidMissing = existing == null || existing.cloudUid == null
                    || existing.cloudUid.length() == 0;
            android.content.Context host = ProbeService.instance != null ? ProbeService.instance : APP;
            if (host != null) {
                if (imei.length() > 0) Config.saveValue(host, "carImei", imei);
                if (frame.length() > 0) Config.saveValue(host, "carFrame", frame);
                // carStatus 的 userId 只作兜底；登录响应里的 uid 才是当前账号。
                if (uid.length() > 0 && uidMissing) Config.saveValue(host, "cloudUid", uid);
            }
            if (ProbeService.config != null) {
                if (imei.length() > 0) ProbeService.config.carImei = imei;
                if (frame.length() > 0) ProbeService.config.carFrame = frame;
                if (uid.length() > 0 && uidMissing) ProbeService.config.cloudUid = uid;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 日志里不能出现 MQTT 账号/密码和分享密码。 */
    private static String redact(String s) {
        if (s == null) return "";
        return s
                .replaceAll("(\"mqPassword\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(\"mqUsername\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(\"passwordInfo\"\\s*:\\s*\\{)[^}]*\\}", "$1***}");
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
