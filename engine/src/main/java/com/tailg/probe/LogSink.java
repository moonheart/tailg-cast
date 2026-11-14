package com.tailg.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 线程安全的文件日志：session.log 人读，frames.jsonl 机读。 */
public final class LogSink {

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    private static File sessionDir;
    private static PrintWriter txt;
    private static PrintWriter jsonl;
    private static PrintWriter rawLog;
    public static volatile boolean rawCapture = false;

    private LogSink() {}

    public static synchronized File open(File root) {
        close();
        File dir = new File(root, "sessions/" + STAMP.format(new Date()));
        if (!dir.exists() && !dir.mkdirs()) {
            dir = root;
        }
        sessionDir = dir;
        try {
            txt = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "session.log"), true), "UTF-8"), true);
            jsonl = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "frames.jsonl"), true), "UTF-8"), true);
            rawLog = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(dir, "raw_packets.log"), true), "UTF-8"), true);
        } catch (IOException e) {
            txt = null;
            jsonl = null;
            rawLog = null;
        }
        line("SESSION", "log dir = " + dir.getAbsolutePath());
        return dir;
    }

    public static File dir() {
        return sessionDir;
    }

    public static void line(String tag, String msg) {
        String s = TS.format(new Date()) + " [" + tag + "] " + msg;
        synchronized (LOCK) {
            if (txt != null) txt.println(s);
            System.out.println(s);
        }
    }

    public static void event(String type, String detailJsonFields) {
        String s = "{\"t\":\"" + TS.format(new Date()) + "\",\"type\":\"" + type + "\""
                + (detailJsonFields == null || detailJsonFields.isEmpty() ? "" : "," + detailJsonFields) + "}";
        synchronized (LOCK) {
            if (jsonl != null) jsonl.println(s);
        }
    }

    public static void note(String name, String content) {
        if (sessionDir == null) return;
        try {
            FileOutputStream fos = new FileOutputStream(new File(sessionDir, name), true);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (IOException ignored) {
        }
    }

    public static void close() {
        synchronized (LOCK) {
            if (txt != null) { txt.flush(); txt.close(); txt = null; }
            if (jsonl != null) { jsonl.flush(); jsonl.close(); jsonl = null; }
            if (rawLog != null) { rawLog.flush(); rawLog.close(); rawLog = null; }
        }
    }

    /** 原始抓包落盘（仅 rawCapture=true 时写入 raw_packets.log，未截断、未去重）。 */
    public static void raw(String channel, String dir, byte[] data) {
        if (!rawCapture || data == null) return;
        StringBuilder ascii = new StringBuilder(Math.min(data.length, 256));
        for (int i = 0; i < data.length && i < 256; i++) {
            int c = data[i] & 0xFF;
            ascii.append((c >= 32 && c < 127) ? (char) c : '.');
        }
        String line = TS.format(new Date()) + " [" + channel + " " + dir + " len=" + data.length + "] "
                + hex(data, data.length) + " | " + ascii;
        synchronized (LOCK) {
            if (rawLog != null) rawLog.println(line);
        }
    }

    /** 原始帧抓包（还原 EE FD / type cmd len 帧结构后的完整 hex）。 */
    public static void rawFrame(String channel, String dir, int type, int cmd, byte[] payload) {
        if (!rawCapture) return;
        byte[] frame = Codec.frame(type, cmd, payload);
        raw(channel, dir, frame);
    }

    public static String hex(byte[] b, int len) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len && i < b.length; i++) {
            int v = b[i] & 0xFF;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v)).append(' ');
        }
        return sb.toString().trim();
    }

    public static String hex(byte[] b) {
        return hex(b, b == null ? 0 : b.length);
    }

    /** JSON 字符串转义，用于把原始文本塞进 jsonl。 */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
