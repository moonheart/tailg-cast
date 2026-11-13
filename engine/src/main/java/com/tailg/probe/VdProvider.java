package com.tailg.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

/**
 * 给 root 助手用的会合点。
 *
 * 助手（app_process，uid 0）起来后通过 call("hello") 把自己的 binder 塞进来，
 * 我们同时回一个"死亡信物"binder：App 进程一死，助手那边 linkToDeath 触发就自己退出，
 * 不会留一个孤儿进程占着屏。
 */
public class VdProvider extends ContentProvider {

    public static final String AUTHORITY = "com.tailg.cast.vd";

    /** 助手连上后由这里回调（在 binder 线程上）。 */
    public interface OnHelper {
        void onHelper(IBinder helper);
    }

    private static volatile IBinder sHelper;
    private static volatile OnHelper sOnHelper;

    public static IBinder helper() {
        return sHelper;
    }

    public static void setOnHelper(OnHelper cb) {
        sOnHelper = cb;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"hello".equals(method)) return null;
        IBinder helper = extras == null ? null : extras.getBinder("helper");
        if (helper == null) return null;
        sHelper = helper;
        Bundle out = new Bundle();
        out.putBinder("token", new Binder());   // 助手拿它 linkToDeath
        OnHelper cb = sOnHelper;
        if (cb != null) {
            try {
                cb.onHelper(helper);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
