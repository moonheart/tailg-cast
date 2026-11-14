package com.tailg.probe;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;

import java.util.UUID;

/**
 * 车机的 BLE 通道。
 *
 * 官方 App 发 CAR_INFO / SIM / TBOX 这类查询走的是 BLE，不是 TCP：
 * {@code t3.a(json)} → {@code g.get(mac).a(json)} → BleConnectWrapper。
 *
 * 参数都是从 decompiled 的 BleConnectWrapper / z1 里抄的：
 *
 * <pre>
 * service   0000e0ff-3c17-d293-8e48-14fe2e4da212（找不到换 ...213）
 * write     0000ffe1-0000-1000-8000-00805f9b34fb  writeType=1
 * notify    0000ffe2-0000-1000-8000-00805f9b34fb  + CCCD 2902
 * 载荷      纯 JSON 文本，按 MTU 分片；收也是文本，靠 '}' 切包
 * </pre>
 */
public final class BleLink {

    private static final UUID SVC1 = UUID.fromString("0000e0ff-3c17-d293-8e48-14fe2e4da212");
    private static final UUID SVC2 = UUID.fromString("0000e0ff-3c17-d293-8e48-14fe2e4da213");
    private static final UUID WRITE = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static volatile boolean connected;
    private static volatile boolean connecting;
    private static volatile BluetoothDevice target;
    private static volatile BluetoothGatt gatt;
    private static volatile BluetoothGattCharacteristic writeChar;
    private static volatile int mtu = 23;
    private static final StringBuilder RX = new StringBuilder();
    private static final Object TX = new Object();

    private BleLink() {
    }

    /** 扫 name（车机蓝牙名 = 热点名 = TL_xxx），找到就连。 */
    public static void start(final Context ctx, final String name) {
        if (name == null || name.length() == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                    final BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                    if (ad == null) {
                        LogSink.line("BLE", "本机没有蓝牙");
                        return;
                    }
                    if (!ad.isEnabled()) {
                        LogSink.line("BLE", "蓝牙没开，跳过");
                        return;
                    }
                    final ScanSettings st = new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                    final ScanCallback cb = new ScanCallback() {
                        @Override
                        public void onScanResult(int type, ScanResult r) {
                            BluetoothDevice d = r.getDevice();
                            String n = null;
                            try {
                                n = d.getName();
                            } catch (Throwable ignored) {
                            }
                            if (n != null && n.equals(name)) {
                                if (connecting || connected || (target != null && target.equals(d))) return;
                                connecting = true;
                                target = d;
                                LogSink.line("BLE", "扫到 " + n + " " + d.getAddress() + "，连接");
                                try {
                                    ad.getBluetoothLeScanner().stopScan(this);
                                } catch (Throwable ignored) {
                                }
                                connect(ctx, d);
                            }
                        }
                    };
                    LogSink.line("BLE", "扫描 " + name);
                    ad.getBluetoothLeScanner().startScan(null, st, cb);
                    Thread.sleep(15000);
                    try {
                        ad.getBluetoothLeScanner().stopScan(cb);
                    } catch (Throwable ignored) {
                    }
                    if (!connected) LogSink.line("BLE", "15 秒没扫到 " + name);
                } catch (Throwable t) {
                    LogSink.line("BLE", "扫描失败 " + t);
                }
            }
        }, "ble-scan").start();
    }

    private static void connect(Context ctx, BluetoothDevice d) {
        try {
            gatt = d.connectGatt(ctx, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    LogSink.line("BLE", "连接状态 status=" + status + " state=" + newState);
                    if (newState == 2) {
                        try {
                            g.requestMtu(185);
                        } catch (Throwable ignored) {
                        }
                        try {
                            g.discoverServices();
                        } catch (Throwable e) {
                            LogSink.line("BLE", "discoverServices 失败 " + e);
                        }
                    } else if (newState == 0) {
                        connected = false;
                        connecting = false;
                        writeChar = null;
                    }
                }

                @Override
                public void onMtuChanged(BluetoothGatt g, int m, int status) {
                    mtu = m;
                    LogSink.line("BLE", "MTU=" + m);
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    BluetoothGattService svc = g.getService(SVC1);
                    if (svc == null) svc = g.getService(SVC2);
                    if (svc == null) {
                        LogSink.line("BLE", "没找到 e0ff 服务，status=" + status);
                        return;
                    }
                    writeChar = svc.getCharacteristic(WRITE);
                    BluetoothGattCharacteristic notify = svc.getCharacteristic(NOTIFY);
                    if (writeChar == null || notify == null) {
                        LogSink.line("BLE", "缺 ffe1/ffe2 特征");
                        return;
                    }
                    writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    g.setCharacteristicNotification(notify, true);
                    BluetoothGattDescriptor d = notify.getDescriptor(CCCD);
                    if (d != null) {
                        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        g.writeDescriptor(d);
                        LogSink.line("BLE", "已开通知，等服务发现完成");
                    } else {
                        LogSink.line("BLE", "没有 CCCD 描述符");
                    }
                }

                @Override
                public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
                    if (!CCCD.equals(d.getUuid())) return;
                    if (connected) return;
                    connected = true;
                    LogSink.line("BLE", "通道就绪 status=" + status);
                    send("{\"msg_id\":27,\"func\":\"TUC\",\"act\":\"GET\"}");
                    send("{\"msg_id\":27,\"func\":\"CAR_INFO\",\"act\":\"get_car_info\"}");
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                    byte[] v = c.getValue();
                    if (v == null) return;
                    LogSink.raw("BLE", "RX", v);
                    onBytes(v);
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            LogSink.line("BLE", "connectGatt 失败 " + t);
        }
    }

    /** 收到的是纯 JSON 文本，可能被 MTU 切碎，攒到 '}' 再切。 */
    private static void onBytes(byte[] v) {
        String s = new String(v, java.nio.charset.Charset.forName("UTF-8"));
        synchronized (RX) {
            RX.append(s);
            String all = RX.toString();
            int start = all.indexOf('{');
            while (start >= 0) {
                int depth = 0;
                int end = -1;
                for (int i = start; i < all.length(); i++) {
                    char ch = all.charAt(i);
                    if (ch == '{') depth++;
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0) {
                            end = i;
                            break;
                        }
                    }
                }
                if (end < 0) break;
                String js = all.substring(start, end + 1);
                logQuiet(js);
                ProbeService.onCarJson(js, "BLE");
                if (js.contains("send_pairresult")) probeOnce();
                all = all.substring(end + 1);
                start = all.indexOf('{');
            }
            RX.setLength(0);
            RX.append(all.length() > 8192 ? all.substring(all.length() - 8192) : all);
        }
    }

    private static String lastLogged = "";
    private static int repeat;

    /** msg_id 10 / MOTOR_SIGNAL 一秒好几条，原样落盘日志会被刷爆。 */
    private static void logQuiet(String js) {
        String one = js.replaceAll("\\s+", "");
        if (one.equals(lastLogged)) {
            repeat++;
            if (repeat % 100 == 0) LogSink.line("BLE<<", "同上 ×" + (repeat + 1));
            return;
        }
        lastLogged = one;
        repeat = 0;
        LogSink.line("BLE<<", js);
    }

    private static volatile boolean probed;

    /** 配对成功后再问一圈，看这台车机到底支持哪几条（只读，只发一次）。 */
    private static void probeOnce() {
        if (probed) return;
        probed = true;
        String[] qs = {
                "{\"msg_id\":27,\"func\":\"SIM\",\"act\":\"REQUEST\"}",
                "{\"msg_id\":27,\"func\":\"TBOX\",\"act\":\"get_sn\"}",
                "{\"msg_id\":27,\"func\":\"THEME\",\"act\":\"get_pairinfo\"}",
                "{\"msg_id\":27,\"func\":\"AUTOOFF\",\"act\":\"get_auto_off_status\"}",
                "{\"msg_id\":27,\"func\":\"ROAD_NAVI\",\"act\":\"get_status\"}",
                "{\"msg_id\":27,\"func\":\"TIRE\",\"act\":\"get_data\"}",
                "{\"msg_id\":27,\"func\":\"CAR_INFO\",\"act\":\"get_car_info\"}",
        };
        for (String q : qs) {
            send(q);
            try {
                Thread.sleep(350);
            } catch (InterruptedException ignored) {
            }
        }
        LogSink.line("BLE", "探测指令已发完，看哪些有回包");
    }

    /** 发一条 JSON（按 MTU 分片，串行写）。 */
    public static boolean send(String json) {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic w = writeChar;
        if (g == null || w == null || !connected) return false;
        byte[] data;
        try {
            data = json.getBytes("UTF-8");
        } catch (Exception e) {
            return false;
        }
        synchronized (TX) {
            int chunk = Math.max(20, mtu - 3);
            try {
                for (int off = 0; off < data.length; off += chunk) {
                    int n = Math.min(chunk, data.length - off);
                    byte[] part = new byte[n];
                    System.arraycopy(data, off, part, 0, n);
                    LogSink.raw("BLE", "TX", part);
                    w.setValue(part);
                    g.writeCharacteristic(w);
                    Thread.sleep(40);   // 不 sleep 会丢包
                }
                LogSink.line("BLE>>", json);
                return true;
            } catch (Throwable t) {
                LogSink.line("BLE>>", "发送失败 " + t);
                return false;
            }
        }
    }
}
