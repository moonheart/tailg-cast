package com.tailg.probe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/** 台铃 17818 帧编解码：6 字节头 + 负载；JSON 帧为 EE FD + len + json + FF。 */
public final class Codec {

    public static final Charset UTF8 = Charset.forName("UTF-8");

    private Codec() {}

    public static byte[] frame(int type, int cmd, byte[] payload) {
        int n = payload == null ? 0 : payload.length;
        byte[] out = new byte[6 + n];
        out[0] = (byte) type;
        out[1] = (byte) cmd;
        out[2] = (byte) (n >>> 24);
        out[3] = (byte) (n >>> 16);
        out[4] = (byte) (n >>> 8);
        out[5] = (byte) n;
        if (n > 0) System.arraycopy(payload, 0, out, 6, n);
        return out;
    }

    public static byte[] jsonFrame(String json) {
        byte[] body = json.getBytes(UTF8);
        byte[] out = new byte[6 + body.length + 1];
        out[0] = (byte) 0xEE;
        out[1] = (byte) 0xFD;
        out[2] = (byte) (body.length >>> 24);
        out[3] = (byte) (body.length >>> 16);
        out[4] = (byte) (body.length >>> 8);
        out[5] = (byte) body.length;
        System.arraycopy(body, 0, out, 6, body.length);
        out[out.length - 1] = (byte) 0xFF;
        return out;
    }

    public static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static byte[] be32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    /** 读满 n 字节；连接断开返回 null。 */
    public static byte[] readFully(java.io.InputStream in, int n) throws java.io.IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) return off == 0 ? null : java.util.Arrays.copyOf(buf, off);
            off += r;
        }
        return buf;
    }

    /**
     * 从流里读一个完整帧（含 6 字节头）。
     * JSON 帧（EE FD）的长度字段是 JSON 字节数，实际还多一个 0xFF 结尾，
     * 所以要多读 1 字节；若不是 FF 再推回流里，避免错位。
     */
    public static RawFrame readFrame(java.io.InputStream in) throws java.io.IOException {
        byte[] head = readFully(in, 6);
        if (head == null || head.length < 6) return null;
        int type = head[0] & 0xFF;
        int cmd = head[1] & 0xFF;
        int len = be32(head, 2);
        if (len < 0 || len > (32 * 1024 * 1024)) {
            RawFrame f = new RawFrame();
            f.type = type;
            f.cmd = cmd;
            f.len = len;
            f.payload = new byte[0];
            f.badLength = true;
            return f;
        }
        byte[] payload = len == 0 ? new byte[0] : readFully(in, len);
        if (payload == null) return null;

        boolean isJsonFrame = (type == 0xEE && cmd == 0xFD);
        if (isJsonFrame) {
            int tail = in.read();
            if (tail == 0xFF) {
                payload = concat(payload, new byte[]{(byte) 0xFF});
            } else if (tail >= 0) {
                if (in instanceof java.io.PushbackInputStream) {
                    ((java.io.PushbackInputStream) in).unread(tail);
                }
            }
        }
        RawFrame f = new RawFrame();
        f.type = type;
        f.cmd = cmd;
        f.len = len;
        f.payload = payload;
        return f;
    }

    public static boolean isJson(RawFrame f) {
        return f.type == 0xEE && f.cmd == 0xFD;
    }

    /** JSON 帧负载去掉结尾 FF 后的文本。 */
    public static String jsonText(RawFrame f) {
        byte[] p = f.payload;
        int n = p.length;
        while (n > 0 && p[n - 1] == (byte) 0xFF) n--;
        return new String(p, 0, n, UTF8);
    }

    public static boolean jsonTailOk(RawFrame f) {
        return f.payload.length > 0 && f.payload[f.payload.length - 1] == (byte) 0xFF;
    }

    public static byte[] fixedString(String s, int size) {
        byte[] out = new byte[size];
        byte[] b = s == null ? new byte[0] : s.getBytes(UTF8);
        System.arraycopy(b, 0, out, 0, Math.min(b.length, size));
        return out;
    }

    /** 把 128 字节的版本块拼起来（kc.d 的解析方式）。 */
    public static String versionBlocks(byte[] payload) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off + 128 <= payload.length; off += 128) {
            String s = new String(payload, off, 128, UTF8).trim();
            s = s.replace("\u0000", "");
            if (s.length() > 0) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(off / 128).append(":").append(s);
            }
        }
        return sb.toString();
    }

    public static class RawFrame {
        public int type;
        public int cmd;
        public int len;
        public byte[] payload;
        public boolean badLength;

        public String head() {
            return String.format("type=%02X cmd=%02X len=%d", type, cmd, len);
        }
    }

    /** 便于把小段字节拼成完整缓冲。 */
    public static byte[] concat(byte[] a, byte[] b) {
        if (a == null) return b;
        if (b == null) return a;
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static byte[] drain(ByteArrayOutputStream bos) {
        byte[] out = bos.toByteArray();
        bos.reset();
        return out;
    }
}
