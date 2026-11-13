package com.tailg.probe;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * 把投屏出去的那路 H.264 在手机上解出来，贴到 SurfaceView 上。
 *
 * 直接复用发给车机的码流，所以"手机上看到的"就是"车机看到的"，调试时也直观。
 */
public final class PreviewDecoder {

    private MediaCodec codec;
    private Surface surface;
    private volatile boolean running;
    private byte[] config;
    private boolean awaitingKey = true;
    private Thread thread;

    public interface Listener {
        void onPreviewSize(int w, int h);
    }

    private Listener listener;
    private int width = 1024;
    private int height = 600;

    public void setListener(Listener l) {
        listener = l;
    }

    public synchronized void start(Surface s, int w, int h, byte[] codecConfig) {
        stop();
        surface = s;
        width = w;
        height = h;
        config = codecConfig;
        awaitingKey = true;
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
            codec = MediaCodec.createDecoderByType("video/avc");
            codec.configure(fmt, s, null, 0);
            codec.start();
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            }, "preview-dec");
            thread.start();
            if (listener != null) listener.onPreviewSize(w, h);
            LogSink.line("PREVIEW", "解码器已启动 " + w + "x" + h);
        } catch (Throwable t) {
            LogSink.line("PREVIEW", "解码器启动失败: " + t);
            stop();
        }
    }

    /** 送一帧编码数据；关键帧会自动补 SPS/PPS。 */
    public void feed(byte[] nal, boolean key) {
        MediaCodec c = codec;
        if (!running || c == null) return;
        if (awaitingKey) {
            if (!key) return;            // 先等关键帧，否则解出来是花屏
            awaitingKey = false;
        }
        try {
            int in = c.dequeueInputBuffer(0);   // 不阻塞编码线程，满了就丢
            if (in < 0) return;
            ByteBuffer buf = c.getInputBuffer(in);
            if (buf == null) return;
            buf.clear();
            int total = 0;
            if (key && config != null) {
                buf.put(config);
                total += config.length;
            }
            if (nal.length > buf.remaining()) return;
            buf.put(nal);
            total += nal.length;
            c.queueInputBuffer(in, 0, total, 0, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        } catch (Throwable t) {
            LogSink.line("PREVIEW", "送帧失败: " + t);
        }
    }

    /** 换了显示尺寸（例如重新投屏）时，等下一个关键帧重新对齐。 */
    public void resetCodecConfig(byte[] cfg) {
        config = cfg;
        awaitingKey = true;
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            MediaCodec c = codec;
            if (c == null) break;
            try {
                int idx = c.dequeueOutputBuffer(info, 100000);
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true);
                }
            } catch (Throwable t) {
                if (!running) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        if (codec != null) {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            codec = null;
        }
        surface = null;
        LogSink.line("PREVIEW", "解码器已停止");
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }
}
