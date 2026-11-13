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
}
