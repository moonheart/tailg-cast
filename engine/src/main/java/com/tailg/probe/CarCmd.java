package com.tailg.probe;

import org.json.JSONObject;

/**
 * 17818 业务指令。
 *
 * func/act 全表来自官方 {@code defpackage/ab.java}（发送侧）与 {@code uc.java}（分发侧），
 * 不是猜的。语义没在真车验证过的，只发只读查询，写操作单独标注。
 */
public final class CarCmd {

    private CarCmd() {
    }

    /** 组一条 msg_id=27 的功能指令；kv 是成对的 key/value，数字会自动转成数字。 */
    public static String json(String func, String act, String... kv) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 27);
            o.put("func", func);
            o.put("act", act);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String k = kv[i];
                String v = kv[i + 1];
                if (v == null) {
                    o.put(k, JSONObject.NULL);
                } else if (v.matches("-?(0|[1-9][0-9]{0,9})")) {
                    o.put(k, Long.parseLong(v));
                } else {
                    o.put(k, v);
                }
            }
            return o.toString();
        } catch (Throwable t) {
            LogSink.line("CAR", "组包失败 " + t);
            return null;
        }
    }

    public static boolean send(String func, String act, String... kv) {
        String js = json(func, act, kv);
        if (js == null) return false;
        return ProbeService.sendCarJson(js);
    }

    /** 直接发一条 JSON（自定义调试用），校验一下再发。 */
    public static boolean sendRaw(String text) {
        if (text == null) return false;
        text = text.trim();
        if (text.length() == 0) return false;
        try {
            new JSONObject(text);
        } catch (Throwable t) {
            LogSink.line("CAR", "不是合法 JSON: " + t);
            return false;
        }
        return ProbeService.sendCarJson(text);
    }

    // ---------------------------------------------------------------- 只读

    /** 车机信息（车型、屏幕形态、能力位）。 */
    public static boolean carInfo() {
        return send("CAR_INFO", "get_car_info");
    }

    /** 车机 4G 模块 SN。 */
    public static boolean tboxSn() {
        return send("TBOX", "get_sn");
    }

    /** 车机 SIM 的 ICCID。 */
    public static boolean simIccid() {
        return send("SIM", "REQUEST");
    }

    /** 当前导航状态（车机自己在画导航时的引导信息）。 */
    public static boolean naviStatus() {
        return send("ROAD_NAVI", "get_status");
    }

    /** 主题/配对信息。 */
    public static boolean themeInfo() {
        return send("THEME", "get_pairinfo");
    }

    /** 自动熄屏状态。 */
    public static boolean autoOffStatus() {
        return send("AUTOOFF", "get_auto_off_status");
    }

    /** 车机屏幕选择（0/1/2 对应不同屏幕，语义待真车确认）。 */
    public static boolean screenChoose(int result) {
        return send("SCREEN", "screenchoose", "result", String.valueOf(result));
    }

    // ---------------------------------------------------------------- 写

    /** 切日间/夜间。 */
    public static boolean dayOrNight(boolean night) {
        return send("ROAD_NAVI", "change_day_or_night", "result", night ? "1" : "0");
    }

    /** 结束车机自己的导航。 */
    public static boolean stopNavi() {
        return ProbeService.sendCarRaw(Codec.frame(3, 0x0D, new byte[0]));
    }

    /** 设置车机语言（BE32）。 */
    public static boolean setLanguage(int lang) {
        return ProbeService.sendCarRaw(Codec.frame(1, 0x13, Codec.be32(lang)));
    }

    // ---------------------------------------------------------------- 仪表互联（音乐 / 通知 / 来电 / 短信）

    /** 推送当前歌曲元数据（title 限 128 字符，对齐官方 JsonManager.c）。 */
    public static boolean musicMsg(String title, String author, String album, String lyrics) {
        try {
            String t = title == null ? "" : title;
            if (t.length() > 128) t = t.substring(0, 128);
            JSONObject o = new JSONObject();
            o.put("msg_id", 27);
            o.put("func", "MUSIC");
            o.put("act", "ret_msg");
            o.put("title", t);
            o.put("author", author == null ? "" : author);
            o.put("album", album == null ? "" : album);
            o.put("lyrics", lyrics == null ? "" : lyrics);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("MUSIC", "组包失败 " + t);
            return false;
        }
    }

    /** 推送音乐播放状态（1=播放中, 0=暂停/停止）。 */
    public static boolean musicStatus(boolean playing) {
        return send("MUSIC", "ret_status", "status", playing ? "1" : "0");
    }

    /** 推送应用通知到仪表盘（msg_id=2）。iconBase64 可为 null（小系统或仅 BLE 时不传）。 */
    public static boolean notifyMsg(
            String appName,
            String title,
            String content,
            String packageName,
            String iconBase64
    ) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 2);
            o.put("app_name", appName == null ? "" : appName);
            o.put("title", title == null ? "" : title);
            o.put("content", content == null ? "" : content);
            o.put("package_name", packageName == null ? "" : packageName);
            if (iconBase64 != null && !iconBase64.isEmpty()) {
                o.put("icon", iconBase64);
            }
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("NOTIFY", "组包失败 " + t);
            return false;
        }
    }

    /** 推送来电提醒（msg_id=3）。 */
    public static boolean inCallMsg(String name, String number) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 3);
            o.put("name", name == null ? "" : name);
            o.put("number", number == null ? "" : number);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("CALL", "组包失败 " + t);
            return false;
        }
    }

    /** 挂断 / 接听后关闭仪表盘来电弹窗（msg_id=9）。 */
    public static boolean hangupMsg() {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 9);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("CALL", "挂断组包失败 " + t);
            return false;
        }
    }

    /** 推送短信提醒（msg_id=6）。 */
    public static boolean smsMsg(String title, String content) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 6);
            o.put("title", title == null ? "" : title);
            o.put("content", content == null ? "" : content);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("SMS", "组包失败 " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 蓝牙钥匙（BT_KEY）

    /** 蓝牙钥匙控锁（type: 1=解锁, 0=锁车/设防）。 */
    public static boolean btKeyLock(String id, int type) {
        return send("BT_KEY", "control_lock", "id", id == null ? "" : id, "type", String.valueOf(type));
    }

    /** 蓝牙无感解锁感应距离档位（gear: 1~5 等档位）。 */
    public static boolean btKeyDistance(String id, int gear) {
        return send("BT_KEY", "distance_set", "id", id == null ? "" : id, "gear", String.valueOf(gear));
    }

    /** 断开蓝牙钥匙连接。 */
    public static boolean btKeyDisconnect() {
        return send("BT_KEY", "disconnect");
    }

    // ---------------------------------------------------------------- 胎压监测（TIRE）

    /** 请求当前胎压数据。 */
    public static boolean tireGetData() {
        return send("TIRE", "get_data");
    }

    /** 查询胎压传感器配对状态。 */
    public static boolean tireGetMatchStatus() {
        return send("TIRE", "get_tire_match_sta");
    }

    /** 设置胎压单位（isBar=true -> unit=1 bar，false -> unit=0 psi；index=0 全部，1 前轮，2 后轮）。 */
    public static boolean tireSetUnit(int index, boolean isBar) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 27);
            o.put("func", "TIRE");
            o.put("act", "set_unit");
            o.put("unit", isBar ? 1 : 0);
            if (index != 0) o.put("index", index);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("TIRE", "set_unit 组包失败 " + t);
            return false;
        }
    }

    /** 设置胎压/胎温报警阈值（对齐 JsonManager.set_limit）。 */
    public static boolean tireSetLimit(
            int index,
            boolean isBar,
            double psiUp,
            double barUp,
            double psiDown,
            double barDown,
            int tempUp
    ) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 27);
            o.put("func", "TIRE");
            o.put("act", "set_limit");
            o.put("unit", isBar ? 1 : 0);
            o.put("psi_pre_up", psiUp);
            o.put("bar_pre_up", barUp);
            o.put("psi_pre_down", psiDown);
            o.put("bar_pre_down", barDown);
            o.put("temp_up", tempUp);
            if (index != 0) o.put("index", index);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("TIRE", "set_limit 组包失败 " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 仪表环境同步（msg_id=25 / GPS）

    /** 推送天气/温度/风力（msg_id=25, msg_type=11）。 */
    public static boolean sendWeather(int weatherCode, String temperature, String windPower) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 25);
            o.put("msg_type", 11);
            o.put("msg_source", 2);
            o.put("weather", weatherCode);
            o.put("temperature", temperature == null ? "" : temperature);
            o.put("wind_power", windPower == null ? "" : windPower);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("ENV", "天气组包失败 " + t);
            return false;
        }
    }

    /** 推送指南针方向角（msg_id=25, msg_type=15）。 */
    public static boolean sendCompass(float angle) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 25);
            o.put("msg_source", 2);
            o.put("msg_type", 15);
            o.put("angle", angle);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("ENV", "指南针组包失败 " + t);
            return false;
        }
    }

    /** 推送海拔与坡度信息（msg_id=25, msg_type=9）。 */
    public static boolean sendElevation(int altitude, int aveAltitude, int maxAltitude) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 25);
            o.put("msg_type", 9);
            o.put("msg_source", 2);
            o.put("altitude", altitude);
            o.put("ave_altitude", aveAltitude);
            o.put("max_altitude", maxAltitude);
            o.put("pond_distance", 0.0);
            o.put("pond_time", 0);
            o.put("head", 0);
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("ENV", "海拔组包失败 " + t);
            return false;
        }
    }

    /** 推送手机 GPS 信号状态。 */
    public static boolean sendGpsSignal(int status) {
        return send("GPS", "signal_status", "status", String.valueOf(status));
    }

    // ---------------------------------------------------------------- 语音播报（AUDIO）

    /** 发送文本到车机语音播报（func=AUDIO, act=send_text）。 */
    public static boolean audioSendText(String content) {
        return send("AUDIO", "send_text", "content", content == null ? "" : content);
    }

    /** 同步车机语音播报状态（func=AUDIO, act=ret_status）。 */
    public static boolean audioStatus(int status) {
        return send("AUDIO", "ret_status", "status", String.valueOf(status));
    }

    // ---------------------------------------------------------------- 车机离线地图（INSIDENAVI）

    /** 拉取车机离线地图城市列表。 */
    public static boolean offlineMapList() {
        return sendInsideNaviCmd("pull_cityList", null);
    }

    /** 删除车机指定城市离线地图。 */
    public static boolean offlineMapDeleteCity(String cityName) {
        return sendInsideNaviCmd("delete_city", cityName);
    }

    /** 清理车机离线地图缓存。 */
    public static boolean offlineMapRemoveCache() {
        return sendInsideNaviCmd("remove_cache", null);
    }

    private static boolean sendInsideNaviCmd(String cmd, String cityName) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg_id", 27);
            o.put("func", "INSIDENAVI");
            o.put("CMD", cmd);
            if (cityName != null && !cityName.isEmpty()) {
                o.put("cityName", cityName);
            }
            return ProbeService.sendCarJson(o.toString());
        } catch (Throwable t) {
            LogSink.line("OFFLINE_MAP", "组包失败 " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- 车机日志 / 崩溃转储（COREDUMP）

    /** 请求车机崩溃/诊断转储（result: 1=请求/确认）。 */
    public static boolean requestCoredump(int result) {
        return send("COREDUMP", "REQUEST", "result", String.valueOf(result));
    }
}
