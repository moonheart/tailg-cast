package com.tailg.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 车机仪表（TFT）的 11112 端口。
 *
 * 官方 EryaSDK 就是从这里拿车机的版本 JSON（sn / pid / hardware_version / 各分区版本），
 * 再拿它去云端 {@code app/firmVersionInfo/getTftFirmVersion} 换整包下载地址。
 * 帧格式（CarUpgradeManager）：
 *
 * <pre>
 * 45 59 | jsonLen(BE32) | streamLen(BE32) | json[jsonLen] | stream[streamLen]
 * </pre>
 *
 * 我们只读不写：连上去把车机主动推过来的 JSON 收下来落盘。
 */
public final class TftLink {

    private TftLink() {}

    /** 连一次，最多收 8 秒 / 10 帧。 */
    public static void probe(final String host, final int port) {
        probe(null, host, port);
    }

    /**
     * net 非空时把 socket 绑到车机那块 Wi-Fi 上。
     * 不绑的话系统会按默认网络（流量）去连 192.168.10.1，必然超时。
     */
    public static void probe(final android.net.Network net, final String host, final int port) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket s = null;
                try {
                    s = new Socket();
                    if (net != null) net.bindSocket(s);
                    s.connect(new InetSocketAddress(host, port), 3000);
                    s.setSoTimeout(8000);
                    LogSink.line("TFT", "连上 " + host + ":" + port + "，等版本 JSON");
                    InputStream in = s.getInputStream();
                    File out = new File(LogSink.dir(), "tft_" + port + ".jsonl");
                    FileOutputStream fos = new FileOutputStream(out, true);
                    for (int i = 0; i < 10; i++) {
                        byte[] head = readN(in, 10);
                        if (head == null) break;
                        if ((head[0] & 0xFF) != 0x45 || (head[1] & 0xFF) != 0x59) {
                            LogSink.line("TFT", "帧头不对: " + LogSink.hex(head, 10));
                            break;
                        }
                        int jsonLen = be32(head, 2);
                        int streamLen = be32(head, 6);
                        if (jsonLen <= 0 || jsonLen > 1024 * 1024
                                || streamLen < 0 || streamLen > 32 * 1024 * 1024) {
                            LogSink.line("TFT", "长度异常 json=" + jsonLen + " stream=" + streamLen);
                            break;
                        }
                        byte[] js = readN(in, jsonLen);
                        if (js == null) break;
                        String text = new String(js, "UTF-8");
                        LogSink.line("TFT", "json " + (text.length() > 900 ? text.substring(0, 900) + "…" : text));
                        fos.write(text.getBytes("UTF-8"));
                        fos.write('\n');
                        fos.flush();
                        if (streamLen > 0 && readN(in, streamLen) == null) break;
                    }
                    fos.close();
                    LogSink.line("TFT", "结束，落盘 " + out.getName());
                } catch (Throwable t) {
                    LogSink.line("TFT", "失败 " + host + ":" + port + " " + t);
                } finally {
                    if (s != null) {
                        try {
                            s.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, "tft-link").start();
    }

    private static byte[] readN(InputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return b;
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
}
