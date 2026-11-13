package com.tailg.probe;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;

/**
 * App ↔ root 助手 的通道。
 *
 * 为什么需要它：普通应用没有 CREATE_VIRTUAL_DISPLAY，调不了 DisplayManager.createVirtualDisplay；
 * 而 MediaProjection 建的屏是 PRIVATE|PRESENTATION 且每次都要系统授权弹窗。
 * 用 root 起一个 app_process 助手，由它建屏（能用 TRUSTED / OWN_FOCUS / PUBLIC 这些隐藏 flag），
 * App 只把编码器的 inputSurface 递过去，画面直接渲染进编码器，不做任何拷贝。
 */
public interface IVdHelper extends IInterface {

    String DESCRIPTOR = "com.tailg.cast.IVdHelper";

    /** 用给定 Surface 建屏，返回 displayId；失败返回 -1。 */
    int createDisplay(Surface surface, int w, int h, int dpi, int flags) throws RemoteException;

    /**
     * 建屏，并尝试让这块屏忽略应用的 orientation/resizeable/aspectRatio 限制。
     * ignoreSizeRestrictions 只在 flags 含 VIRTUAL_DISPLAY_FLAG_TRUSTED 时有效。
     */
    int createDisplay(Surface surface, int w, int h, int dpi, int flags,
                      boolean ignoreSizeRestrictions) throws RemoteException;

    /** 该 display 是否真的启用了“忽略应用尺寸/方向限制”。 */
    boolean ignoreApplied(int displayId) throws RemoteException;

    /** 以 shell 身份把组件启动到指定屏（不受 am start --display 的 from-null 限制）。 */
    boolean launch(int displayId, String component) throws RemoteException;

    /** 目标包的根任务号，找不到返回 -1。 */
    int taskOf(String pkg) throws RemoteException;

    /** 把根任务搬到指定屏（不重启 App）。 */
    boolean moveTask(int taskId, int displayId) throws RemoteException;

    /** 运行期改尺寸，不用重建屏。 */
    boolean resize(int displayId, int w, int h, int dpi) throws RemoteException;

    /** 释放屏（DESTROY_CONTENT_ON_REMOVAL 会顺带结束上面的 App）。 */
    void destroy(int displayId) throws RemoteException;

    /** 往虚拟屏注入一次点击（不走 input 命令，直接 injectInputEvent）。 */
    boolean injectTap(int displayId, int x, int y) throws RemoteException;

    /** 注入一次滑动。 */
    boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) throws RemoteException;

    /** 注入按键（4=返回 3=Home 187=最近任务）。 */
    boolean injectKey(int displayId, int keyCode) throws RemoteException;

    /** 让助手进程自己退出（停止投屏时调用）。 */
    void exit() throws RemoteException;

    /** 流式触摸：action 用 MotionEvent.ACTION_*，连续调用即可实现跟手拖动。 */
    boolean touch(int displayId, int action, int x, int y) throws RemoteException;

    /** 多指触摸：ids/xs/ys 是当前所有手指；index 用于 ACTION_POINTER_DOWN/UP。 */
    boolean touchMulti(int displayId, int action, int index, int[] ids, int[] xs, int[] ys) throws RemoteException;

    abstract class Stub extends Binder implements IVdHelper {

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IVdHelper asInterface(IBinder b) {
            if (b == null) return null;
            IInterface i = b.queryLocalInterface(DESCRIPTOR);
            if (i instanceof IVdHelper) return (IVdHelper) i;
            return new Proxy(b);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            data.enforceInterface(DESCRIPTOR);
            switch (code) {
                case 1: {
                    Surface s = data.readInt() != 0 ? Surface.CREATOR.createFromParcel(data) : null;
                    int w = data.readInt();
                    int h = data.readInt();
                    int dpi = data.readInt();
                    int f = data.readInt();
                    int r = createDisplay(s, w, h, dpi, f);
                    reply.writeNoException();
                    reply.writeInt(r);
                    return true;
                }
                case 2: {
                    int id = data.readInt();
                    String comp = data.readString();
                    boolean r = launch(id, comp);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case 3: {
                    String pkg = data.readString();
                    int r = taskOf(pkg);
                    reply.writeNoException();
                    reply.writeInt(r);
                    return true;
                }
                case 4: {
                    int task = data.readInt();
                    int id = data.readInt();
                    boolean r = moveTask(task, id);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case 5: {
                    int id = data.readInt();
                    int w = data.readInt();
                    int h = data.readInt();
                    int dpi = data.readInt();
                    boolean r = resize(id, w, h, dpi);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case 6: {
                    int id = data.readInt();
                    destroy(id);
                    reply.writeNoException();
                    return true;
                }
                case 7: {
                    int id = data.readInt();
                    int x = data.readInt();
                    int y = data.readInt();
                    boolean r = injectTap(id, x, y);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case 8: {
                    int id = data.readInt();
                    int x1 = data.readInt();
                    int y1 = data.readInt();
                    int x2 = data.readInt();
                    int y2 = data.readInt();
                    int dur = data.readInt();
                    boolean r = injectSwipe(id, x1, y1, x2, y2, dur);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case 9: {
                    int id = data.readInt();
                    int key = data.readInt();
                    boolean r = injectKey(id, key);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case 10: {
                    exit();
                    reply.writeNoException();
                    return true;
                }
                case 11: {
                    int id = data.readInt();
                    int act = data.readInt();
                    int x = data.readInt();
                    int y = data.readInt();
                    boolean r = touch(id, act, x, y);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case 12: {
                    int id = data.readInt();
                    int act = data.readInt();
                    int index = data.readInt();
                    int[] ids = data.createIntArray();
                    int[] xs = data.createIntArray();
                    int[] ys = data.createIntArray();
                    boolean r = touchMulti(id, act, index, ids, xs, ys);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                case 13: {
                    Surface s = data.readInt() != 0 ? Surface.CREATOR.createFromParcel(data) : null;
                    int w = data.readInt();
                    int h = data.readInt();
                    int dpi = data.readInt();
                    int f = data.readInt();
                    boolean ignore = data.readInt() != 0;
                    int r = createDisplay(s, w, h, dpi, f, ignore);
                    reply.writeNoException();
                    reply.writeInt(r);
                    return true;
                }
                case 14: {
                    int id = data.readInt();
                    boolean r = ignoreApplied(id);
                    reply.writeNoException();
                    reply.writeInt(r ? 1 : 0);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static final class Proxy implements IVdHelper {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public int createDisplay(Surface surface, int w, int h, int dpi, int flags) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    if (surface != null) {
                        d.writeInt(1);
                        surface.writeToParcel(d, 0);
                    } else {
                        d.writeInt(0);
                    }
                    d.writeInt(w);
                    d.writeInt(h);
                    d.writeInt(dpi);
                    d.writeInt(flags);
                    remote.transact(1, d, r, 0);
                    r.readException();
                    return r.readInt();
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public int createDisplay(Surface surface, int w, int h, int dpi, int flags,
                                     boolean ignoreSizeRestrictions) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    if (surface != null) {
                        d.writeInt(1);
                        surface.writeToParcel(d, 0);
                    } else {
                        d.writeInt(0);
                    }
                    d.writeInt(w);
                    d.writeInt(h);
                    d.writeInt(dpi);
                    d.writeInt(flags);
                    d.writeInt(ignoreSizeRestrictions ? 1 : 0);
                    remote.transact(13, d, r, 0);
                    r.readException();
                    return r.readInt();
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean ignoreApplied(int displayId) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    remote.transact(14, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean launch(int displayId, String component) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    d.writeString(component);
                    remote.transact(2, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public int taskOf(String pkg) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeString(pkg);
                    remote.transact(3, d, r, 0);
                    r.readException();
                    return r.readInt();
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean moveTask(int taskId, int displayId) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(taskId);
                    d.writeInt(displayId);
                    remote.transact(4, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean resize(int displayId, int w, int h, int dpi) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    d.writeInt(w);
                    d.writeInt(h);
                    d.writeInt(dpi);
                    remote.transact(5, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean injectTap(int displayId, int x, int y) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    d.writeInt(x);
                    d.writeInt(y);
                    remote.transact(7, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    d.writeInt(x1);
                    d.writeInt(y1);
                    d.writeInt(x2);
                    d.writeInt(y2);
                    d.writeInt(durationMs);
                    remote.transact(8, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean injectKey(int displayId, int keyCode) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    d.writeInt(keyCode);
                    remote.transact(9, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean touch(int displayId, int action, int x, int y) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    d.writeInt(action);
                    d.writeInt(x);
                    d.writeInt(y);
                    remote.transact(11, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public boolean touchMulti(int displayId, int action, int index, int[] ids, int[] xs, int[] ys)
                    throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    d.writeInt(action);
                    d.writeInt(index);
                    d.writeIntArray(ids);
                    d.writeIntArray(xs);
                    d.writeIntArray(ys);
                    remote.transact(12, d, r, 0);
                    r.readException();
                    return r.readInt() != 0;
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public void exit() throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(10, d, r, 0);
                    r.readException();
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }

            @Override
            public void destroy(int displayId) throws RemoteException {
                Parcel d = Parcel.obtain();
                Parcel r = Parcel.obtain();
                try {
                    d.writeInterfaceToken(DESCRIPTOR);
                    d.writeInt(displayId);
                    remote.transact(6, d, r, 0);
                    r.readException();
                } finally {
                    r.recycle();
                    d.recycle();
                }
            }
        }
    }
}
