package com.tailg.probe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;

/**
 * 连车机热点，但**不动系统的 Wi-Fi 配置**。
 *
 * 官方 App 就是这么干的（`com.eryanet.ite.util.JPEGWifiConnector`）：
 *
 * <pre>
 * new NetworkRequest.Builder()
 *     .addTransportType(TRANSPORT_WIFI)
 *     .removeCapability(NET_CAPABILITY_INTERNET)   ← 关键：声明"这个网没外网"
 *     .setNetworkSpecifier(new WifiNetworkSpecifier.Builder()
 *             .setSsid(ssid).setWpa2Passphrase(pass).build())
 * </pre>
 *
 * 这样系统只是"临时拨一下"这块 Wi-Fi 给本应用用，不写进已保存网络、也不抢默认路由，
 * 移动数据照常走。之前用 {@code cmd wifi add-network} 存成系统网络，等于把手机默认网络
 * 换成了没有外网的车机热点，所以一连上就断网。
 *
 * 注意：官方也没有 bindProcessToNetwork(车机网络)，它绑的是**移动数据**（nc.java）。
 * 我们不绑，默认网络保持原样即可。
 */
public final class WifiLink {

    private static ConnectivityManager.NetworkCallback callback;
    private static volatile boolean want;
    private static String wantSsid;
    private static String wantPass;

    private WifiLink() {
    }

    public static boolean connected;
    private static volatile Network current;

    /** 车机临时网络句柄；连车机自己的服务（11112）必须用它 bindSocket。 */
    public static Network network() {
        return current;
    }
    private static int retries;

    /** 请求连车机热点（API 29+ 走 specifier；更老的用 root 兜底）。 */
    public static void connect(final Context ctx, final String ssid, final String pass) {
        if (ssid == null || ssid.length() == 0) return;
        if (Build.VERSION.SDK_INT < 29) {
            legacyRoot(ssid, pass);
            return;
        }
        release();
        want = true;
        wantSsid = ssid;
        wantPass = pass;

        final ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                connected = true;
                retries = 0;
                current = network;
                LogSink.line("WIFI", "已连临时网络 " + wantSsid + "（不占默认路由，走流量上网）");
                // 车机网络就绪后再读一次仪表版本（11112），必须绑在这块网上
                TftLink.probe(network, "192.168.10.1", 11112);
            }

            @Override
            public void onLost(Network network) {
                connected = false;
                current = null;
                LogSink.line("WIFI", "临时网络断开 " + wantSsid);
                // 后台/锁屏时 Wi-Fi 可能被系统回收；只要还在投屏就多试几次，
                // 5s、10s、15s…最多约 2 分钟，而不是只试 1 次。
                if (want && retries < 10) {
                    retries++;
                    retryLater(ctx, Math.min(15000L, 5000L * retries));
                }
            }

            @Override
            public void onUnavailable() {
                connected = false;
                current = null;
                LogSink.line("WIFI", "车机热点不可用 " + wantSsid + "（没开或密码不对）");
                want = false;   // 不自动重试：每次 requestNetwork 都会再弹一次系统允许框
            }
        };

        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pass == null ? "" : pass)
                    .build();
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(spec)
                    .build();
            cm.requestNetwork(req, callback);
            LogSink.line("WIFI", "请求连接 " + ssid + "（系统会弹一次允许框）");
        } catch (Throwable t) {
            LogSink.line("WIFI", "requestNetwork 失败 " + t + "，改用 root 兜底");
            legacyRoot(ssid, pass);
        }
    }

    private static void retryLater(final Context ctx, final long ms) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ignored) {
                }
                if (want) connect(ctx, wantSsid, wantPass);
            }
        }, "wifi-retry").start();
    }

    public static void release() {
        want = false;
        if (callback != null) {
            try {
                ConnectivityManager cm = ProbeService.instance == null ? null
                        : (ConnectivityManager) ProbeService.instance.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(callback);
            } catch (Throwable ignored) {
            }
            callback = null;
        }
    }

    /**
     * 把以前用 add-network 存进系统的车机网络删掉。
     * 留着它，开机会自动连上没外网的热点，手机就上不了网了。
     */
    public static void forgetSaved(final String ssid) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                RootShell.Result r = RootShell.run("cmd wifi list-networks");
                if (r == null || r.out == null) return;
                for (String line : r.out.split("\n")) {
                    String s = line.trim();
                    if (!s.contains(ssid)) continue;
                    String id = s.split("\\s+")[0].replaceAll("[^0-9]", "");
                    if (id.length() == 0) continue;
                    RootShell.run("cmd wifi forget-network " + id);
                    LogSink.line("WIFI", "已从系统里删掉保存的车机网络 " + ssid + "（id=" + id + "）");
                }
            }
        }, "wifi-forget").start();
    }

    /** API < 29 没有 WifiNetworkSpecifier，只能退回老的保存网络方式。 */
    private static void legacyRoot(String ssid, String pass) {
        LogSink.line("WIFI", "系统版本过低，退回 add-network（会切默认网络）");
        ProbeService.joinCarWifiLegacy(ssid, pass);
    }
}
