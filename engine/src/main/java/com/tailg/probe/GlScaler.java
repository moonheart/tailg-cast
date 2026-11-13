package com.tailg.probe;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * MediaProjection 的 Surface -> GL 裁剪/缩放 -> MediaCodec 输入 Surface。
 * fill=true：裁掉两侧（不留黑边，画面填满目标尺寸）
 * fill=false：留黑边（等比完整显示）
 */
public final class GlScaler implements SurfaceTexture.OnFrameAvailableListener {

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "varying vec2 vTex;\n" +
            "void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main(){ gl_FragColor = texture2D(sTex, vTex); }\n";

    private final int dstW, dstH;
    private final boolean fill;
    private final float insetFrac;   // 顶部要裁掉的黑边占比（0~0.5）
    private final boolean pad;       // true: 不缩放，把源贴在目标画布指定 y 位置
    private final int padY;          // pad 模式下的目标 y（像素）
    private int srcW, srcH;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texId;
    private SurfaceTexture texture;
    private Surface inputSurface;
    private Thread thread;

    private volatile boolean running;
    private final Object frameLock = new Object();
    private boolean frameAvailable;

    private FloatBuffer posBuf;
    private FloatBuffer texBuf;
    private final float[] mtx = new float[16];

    public GlScaler(int srcW, int srcH, int dstW, int dstH, boolean fill, float insetFrac) {
        this(srcW, srcH, dstW, dstH, fill, insetFrac, false, 0);
    }

    private GlScaler(int srcW, int srcH, int dstW, int dstH, boolean fill, float insetFrac,
                     boolean pad, int padY) {
        this.insetFrac = insetFrac;
        this.srcW = srcW;
        this.srcH = srcH;
        this.dstW = dstW;
        this.dstH = dstH;
        this.fill = fill;
        this.pad = pad;
        this.padY = padY;
    }

    /** 不缩放，把 srcW×srcH 的源贴在 dstW×dstH 画布的 (0,padY)，其余留黑。 */
    public static GlScaler pad(int srcW, int srcH, int dstW, int dstH, int padY) {
        return new GlScaler(srcW, srcH, dstW, dstH, false, 0f, true, padY);
    }

    public Surface prepare(Surface encoderSurface) {
        initEgl(encoderSurface);
        initGl();
        // 先把主线程上的 current 释放掉，否则 loop 线程无法接管这个 context
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT);
        texture = new SurfaceTexture(texId);
        texture.setDefaultBufferSize(srcW, srcH);
        texture.setOnFrameAvailableListener(this);
        inputSurface = new Surface(texture);
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "gl-scaler");
        thread.start();
        return inputSurface;
    }

    public void release() {
        running = false;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        try {
            if (thread != null) thread.join(800);
        } catch (InterruptedException ignored) {
        }
        try {
            if (inputSurface != null) inputSurface.release();
        } catch (Throwable ignored) {
        }
        try {
            if (texture != null) texture.release();
        } catch (Throwable ignored) {
        }
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable ignored) {
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameLock) {
            frameAvailable = true;
            frameLock.notifyAll();
        }
    }

    private void loop() {
        // EGL 上下文必须在这个线程上 current，否则 updateTexImage 会失败
        boolean ok = EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        if (!ok) LogSink.line("GL", "eglMakeCurrent 失败: 0x" + Integer.toHexString(EGL14.eglGetError()));
        while (running) {
            synchronized (frameLock) {
                while (!frameAvailable && running) {
                    try {
                        frameLock.wait(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                frameAvailable = false;
            }
            if (!running) return;
            try {
                draw();
            } catch (Throwable t) {
                LogSink.line("GL", "draw 异常: " + t);
            }
        }
    }

    private void draw() {
        texture.updateTexImage();
        texture.getTransformMatrix(mtx);

        // 1) 在"最终画面坐标系"里算出要显示的区域
        float du0 = 0f, du1 = 1f, dv0 = insetFrac, dv1 = 1f;
        float srcAspect = (float) srcW / srcH;
        float dstAspect = (float) dstW / dstH;
        if (pad) {
            // 源不缩放，整张贴到目标画布的指定 y 位置
            du0 = 0f;
            du1 = 1f;
            dv0 = 0f;
            dv1 = 1f;
        } else if (fill) {
            if (srcAspect > dstAspect) {
                float keep = dstAspect / srcAspect;      // 裁宽
                du0 = (1f - keep) / 2f;
                du1 = 1f - du0;
            } else {
                float keep = srcAspect / dstAspect;      // 裁高
                dv0 = insetFrac + (1f - keep) / 2f;
                dv1 = 1f - (1f - keep) / 2f;
            }
        } else {
            if (srcAspect > dstAspect) {
                float keep = dstAspect / srcAspect;      // 左右留黑
                du0 = (1f - keep) / 2f;
                du1 = 1f - du0;
            } else {
                float keep = srcAspect / dstAspect;      // 上下留黑
                dv0 = insetFrac + (1f - keep) / 2f;
                dv1 = 1f - (1f - keep) / 2f;
            }
        }

        // 2) 用变换矩阵的逆，把"画面坐标"换回"纹理坐标"
        float a = mtx[0], b = mtx[4], c = mtx[1], d = mtx[5], tx = mtx[12], ty = mtx[13];
        float det = a * d - b * c;
        if (Math.abs(det) < 1e-6f) det = 1e-6f;

        float[] uv = new float[8];
        float[][] pts = {{du0, dv0}, {du1, dv0}, {du0, dv1}, {du1, dv1}};
        for (int i = 0; i < 4; i++) {
            float X = pts[i][0] - tx, Y = pts[i][1] - ty;
            uv[i * 2] = (d * X - b * Y) / det;
            uv[i * 2 + 1] = (-c * X + a * Y) / det;
        }
        texBuf.clear();
        texBuf.put(uv).position(0);

        GLES20.glViewport(0, 0, dstW, dstH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);

        int aPos = GLES20.glGetAttribLocation(program, "aPos");
        int aTex = GLES20.glGetAttribLocation(program, "aTex");
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glFinish();

        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
    }

    private void initEgl(Surface encoderSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);

        int[] cfgAttr = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, configs, 0, 1, num, 0);

        int[] ctxAttr = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0);

        int[] surfAttr = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfAttr, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void initGl() {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, VS);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FS);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);

        float[] pos;
        if (pad) {
            float y0 = 1f - 2f * (padY + srcH) / (float) dstH;
            float y1 = 1f - 2f * padY / (float) dstH;
            pos = new float[]{-1f, y0, 1f, y0, -1f, y1, 1f, y1};
        } else {
            pos = new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        }
        posBuf = ByteBuffer.allocateDirect(pos.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        posBuf.put(pos).position(0);

        texBuf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int loadShader(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        return sh;
    }
}
