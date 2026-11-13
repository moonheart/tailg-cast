package com.tailg.probe;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 高德摩托车导航的收藏点。
 *
 * <p>坐标默认按 GCJ-02（高德/腾讯地图地点详情里的坐标）存；如果用户填的是
 * GPS 原始 WGS-84，{@code dev=1} 让高德自己做国测加密偏移。</p>
 */
public final class NavFavorites {

    public static final class Favorite {
        public String id = "";
        public String name = "";
        public double lat = 0;
        public double lon = 0;
        /** 0 = GCJ-02（已加密）；1 = WGS-84（需要高德转换）。 */
        public int dev = 0;
        public String address = "";
        public long createdAt = 0;

        /** 高德摩托车路线规划 URI（内部类型 t=11）。 */
        public String motorUri() {
            return motorUri(MAP_AMAP);
        }

        /** 按指定地图应用生成骑行/摩托车路线规划 URI。 */
        public String motorUri(String provider) {
            String label = name == null ? "" : name.trim();
            if (label.length() == 0) label = "目的地";
            String encoded;
            try {
                encoded = URLEncoder.encode(label, "UTF-8").replace("+", "%20");
            } catch (Throwable t) {
                encoded = label;
            }
            if (MAP_BAIDU.equals(provider)) {
                return "baidumap://map/direction?destination="
                        + String.format(Locale.US, "latlng:%.6f,%.6f%%7Cname:", lat, lon) + encoded
                        + "&coord_type=" + (dev == 1 ? "wgs84" : "gcj02")
                        + "&mode=riding"
                        + "&riding_type=1"
                        + "&src=andr.tailg.cast";
            }
            return "amapuri://route/plan/?sourceApplication=tailg-cast"
                    + "&dlat=" + String.format(Locale.US, "%.6f", lat)
                    + "&dlon=" + String.format(Locale.US, "%.6f", lon)
                    + "&dname=" + encoded
                    + "&dev=" + (dev == 1 ? 1 : 0)
                    + "&t=11";
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("lat", lat);
            o.put("lon", lon);
            o.put("dev", dev);
            o.put("address", address);
            o.put("createdAt", createdAt);
            return o;
        }

        static Favorite fromJson(JSONObject o) {
            Favorite f = new Favorite();
            f.id = o.optString("id", "");
            f.name = o.optString("name", "");
            f.lat = o.optDouble("lat", 0);
            f.lon = o.optDouble("lon", 0);
            f.dev = o.optInt("dev", 0);
            f.address = o.optString("address", "");
            f.createdAt = o.optLong("createdAt", 0);
            return f;
        }
    }

    public static final String MAP_AMAP = "amap";
    public static final String MAP_BAIDU = "baidu";
    public static final String PKG_AMAP = "com.autonavi.minimap";
    public static final String PKG_BAIDU = "com.baidu.BaiduMap";
    public static final String ACT_AMAP = "com.autonavi.minimap/com.autonavi.map.activity.SplashActivity";
    public static final String ACT_BAIDU = "";

    private static final String PREFS = "tailg_nav";
    private static final String KEY_LIST = "favorites";
    private static final String KEY_ACTIVE = "activeId";
    private static final String KEY_AUTO = "autoStart";
    private static final String KEY_MAP_PROVIDER = "mapProvider";

    private NavFavorites() {
    }

    public static synchronized List<Favorite> list(Context ctx) {
        List<Favorite> out = new ArrayList<Favorite>();
        SharedPreferences sp = sp(ctx);
        String raw = sp.getString(KEY_LIST, "");
        if (raw == null || raw.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Favorite f = Favorite.fromJson(o);
                if (f.id.length() == 0) f.id = UUID.randomUUID().toString();
                if (f.name.length() == 0) f.name = "未命名";
                out.add(f);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static synchronized Favorite active(Context ctx) {
        String id = activeId(ctx);
        List<Favorite> all = list(ctx);
        if (all.isEmpty()) return null;
        for (Favorite f : all) {
            if (f.id.equals(id)) return f;
        }
        return all.get(0);
    }

    public static synchronized String activeId(Context ctx) {
        String id = sp(ctx).getString(KEY_ACTIVE, "");
        List<Favorite> all = list(ctx);
        if (id != null && id.length() > 0) {
            for (Favorite f : all) {
                if (f.id.equals(id)) return id;
            }
        }
        return all.isEmpty() ? "" : all.get(0).id;
    }

    public static synchronized void save(Context ctx, Favorite f) {
        if (f == null) return;
        if (f.id == null || f.id.length() == 0) {
            f.id = UUID.randomUUID().toString();
        }
        if (f.createdAt <= 0) f.createdAt = System.currentTimeMillis();
        List<Favorite> all = list(ctx);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(f.id)) {
                all.set(i, f);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(f);
        writeList(ctx, all);
        String active = sp(ctx).getString(KEY_ACTIVE, "");
        if (active == null || active.length() == 0) {
            sp(ctx).edit().putString(KEY_ACTIVE, f.id).apply();
        }
    }

    public static synchronized void delete(Context ctx, String id) {
        if (id == null) return;
        List<Favorite> all = list(ctx);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                all.remove(i);
                break;
            }
        }
        writeList(ctx, all);
        if (id.equals(sp(ctx).getString(KEY_ACTIVE, ""))) {
            sp(ctx).edit().putString(KEY_ACTIVE, all.isEmpty() ? "" : all.get(0).id).apply();
        }
    }

    public static synchronized void setActive(Context ctx, String id) {
        sp(ctx).edit().putString(KEY_ACTIVE, id == null ? "" : id).apply();
    }

    public static synchronized boolean isAutoStart(Context ctx) {
        return sp(ctx).getBoolean(KEY_AUTO, false);
    }

    public static synchronized void setAutoStart(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(KEY_AUTO, on).apply();
    }

    public static synchronized String getMapProvider(Context ctx) {
        String v = sp(ctx).getString(KEY_MAP_PROVIDER, MAP_AMAP);
        return MAP_BAIDU.equals(v) ? MAP_BAIDU : MAP_AMAP;
    }

    public static synchronized void setMapProvider(Context ctx, String provider) {
        String v = MAP_BAIDU.equals(provider) ? MAP_BAIDU : MAP_AMAP;
        sp(ctx).edit().putString(KEY_MAP_PROVIDER, v).apply();
    }

    public static String packageFor(String provider) {
        return MAP_BAIDU.equals(provider) ? PKG_BAIDU : PKG_AMAP;
    }

    public static String activityFor(String provider) {
        return MAP_BAIDU.equals(provider) ? ACT_BAIDU : ACT_AMAP;
    }

    public static String labelFor(String provider) {
        return MAP_BAIDU.equals(provider) ? "百度地图" : "高德地图";
    }

    public static String shortLabelFor(String provider) {
        return MAP_BAIDU.equals(provider) ? "百度" : "高德";
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void writeList(Context ctx, List<Favorite> all) {
        JSONArray arr = new JSONArray();
        for (Favorite f : all) {
            try {
                arr.put(f.toJson());
            } catch (Throwable ignored) {
            }
        }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }
}
