package com.tailg.probe;

/** 自动导航的一次性“待点击”状态，避免无障碍服务平时乱点高德。 */
public final class NavAutoState {

    private static volatile int targetDisplay = -1;
    private static volatile long until = 0;
    private static volatile long lastClick = 0;

    private NavAutoState() {
    }

    /** 允许无障碍服务在 displayId 上找「开始导航」，windowMs 后自动失效。 */
    public static void arm(int displayId, long windowMs) {
        targetDisplay = displayId;
        until = System.currentTimeMillis() + Math.max(5000, windowMs);
        lastClick = 0;
        LogSink.line("NAV", "自动导航待点击 display=" + displayId
                + "，窗口 " + Math.max(5000, windowMs) + "ms");
    }

    public static boolean armed() {
        return System.currentTimeMillis() < until;
    }

    public static int targetDisplay() {
        return targetDisplay;
    }

    /** 防抖：同一个窗口最多 2.5 秒点一次。 */
    public static synchronized boolean allowClick() {
        long now = System.currentTimeMillis();
        if (now >= until) return false;
        if (now - lastClick < 2500) return false;
        lastClick = now;
        return true;
    }

    public static void disarm() {
        until = 0;
        targetDisplay = -1;
        lastClick = 0;
        LogSink.line("NAV", "自动导航待点击已结束");
    }
}
