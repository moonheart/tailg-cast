package com.tailg.cast;

import com.tailg.probe.CastEngine;
import com.tailg.probe.PreviewDecoder;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 把虚拟屏（车机看到的那块）搬到手机上显示，并把触摸换算回虚拟屏坐标注入。
 *
 * 画面：直接解我们发给车机的同一路码流 → SurfaceView。
 * 触摸：坐标换算后 su -c 'input -d <displayId> tap/swipe …'。
 *      这块屏是 OWN_FOCUS 的，主屏焦点虽然没被抢，注入照样进得去（实测滑动生效）。
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {

    private SurfaceView view;
    private PreviewDecoder decoder;
    private TextView info;
    private int dispW = 1024;
    private int dispH = 600;
    private int contentTop = 0;
    private int contentH = 600;
    private int displayId = -1;

    private float downX, downY;
    private long downAt;
    private boolean moved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        view = new SurfaceView(this);
        final FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        vlp.gravity = android.view.Gravity.CENTER;
        root.addView(view, vlp);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                int vw = r - l;
                int vh = b - t;
                if (vw <= 0 || vh <= 0) return;
                float s = Math.min(vw / (float) dispW, vh / (float) dispH);
                vlp.width = (int) (dispW * s);
                vlp.height = (int) (dispH * s);
                view.setLayoutParams(vlp);
            }
        });
        view.getHolder().addCallback(this);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                return handleTouch(e);
            }
        });

        // 信息条放底部，别压住画面
        info = new TextView(this);
        info.setTextSize(10);
        info.setTextColor(Color.WHITE);
        info.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        lp.leftMargin = dp(6);
        lp.bottomMargin = dp(4);
        root.addView(info, lp);

        // 操作按钮竖排贴左边，画面左右本来就有黑边，不挡内容
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        blp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
        blp.leftMargin = dp(2);
        root.addView(bar, blp);

        bar.addView(keyButton("返回", 4));
        bar.addView(keyButton("Home", 3));
        bar.addView(keyButton("任务", 187));

        // 自测：长按「任务」触发一次双指缩放，验证多指通道
        bar.getChildAt(bar.getChildCount() - 1).setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                CastEngine e = CastEngine.current();
                if (e != null) {
                    e.debugPinch();
                    Toast.makeText(MirrorActivity.this, "双指自测已发送", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        Button close = keyButton("退出", 0);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(close);

        setContentView(root);
        startPreview();
    }

    private Button keyButton(String text, final int keyCode) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setMinWidth(dp(84));
        b.setMinimumWidth(dp(84));
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                injectKey(keyCode);
            }
        });
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void startPreview() {
        CastEngine e = CastEngine.current();
        if (e == null || !e.isRunning()) {
            Toast.makeText(this, "还没开始投屏", Toast.LENGTH_SHORT).show();
            return;
        }
        displayId = e.displayId();
        dispW = e.castWidth();
        dispH = e.castHeight();
        contentH = e.contentHeight();
        contentTop = e.contentTop();
        decoder = new PreviewDecoder();
        e.setPreviewSink(new CastEngine.PreviewSink() {
            @Override
            public void onFrame(byte[] nal, boolean key) {
                PreviewDecoder d = decoder;
                if (d != null) d.feed(nal, key);
            }
        });
        e.requestKeyFrame();
        info.setText("虚拟屏 #" + displayId + " " + dispW + "x" + dispH
                + "\n内容 " + dispW + "x" + contentH
                + "，上下黑边 " + contentTop + "/" + (dispH - contentTop - contentH)
                + "\n触摸会操作这块屏");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (decoder != null) {
            CastEngine e = CastEngine.current();
            byte[] cfg = e == null ? null : e.codecConfigBytes();
            decoder.start(holder.getSurface(), dispW, dispH, cfg);
            if (e != null) e.requestKeyFrame();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (decoder != null) decoder.stop();
    }

    // ------------------------------------------------------------ 触摸 → 虚拟屏

    private long lastMoveAt = 0;


    /** 把当前所有手指映射成虚拟屏坐标，交给助手注入（支持双指缩放）。 */
    private boolean handleTouch(MotionEvent e) {
        CastEngine eng = CastEngine.current();
        if (eng == null) return true;
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) {
            long now = System.currentTimeMillis();
            if (now - lastMoveAt < 12) return true;    // 约 80Hz 上限
            lastMoveAt = now;
        }
        int count = e.getPointerCount();
        int[] ids = new int[count];
        int[] xs = new int[count];
        int[] ys = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = e.getPointerId(i);
            int[] p = mapToDisplay(e.getX(i), e.getY(i));
            if (p == null) return true;   // 落在上下黑边上，不往虚拟屏里送
            xs[i] = p[0];
            ys[i] = p[1];
        }
        eng.touchMulti(action, e.getActionIndex(), ids, xs, ys);
        return true;
    }

    private void injectKey(int code) {
        CastEngine eng = CastEngine.current();
        if (eng != null) eng.injectKey(code);
    }

    /**
     * SurfaceView 上的点 → 虚拟屏像素坐标。
     * 画面是完整的 1024x600（含上下黑边），应用内容只在 y=contentTop..contentTop+contentH；
     * 所以先按 1024x600 等比映射，再减掉黑边偏移。
     */
    private int[] mapToDisplay(float x, float y) {
        int vw = view.getWidth();
        int vh = view.getHeight();
        if (vw <= 0 || vh <= 0) return null;
        float s = Math.min(vw / (float) dispW, vh / (float) dispH);
        float ox = (vw - dispW * s) / 2f;
        float oy = (vh - dispH * s) / 2f;
        int dx = (int) ((x - ox) / s);
        int fy = (int) ((y - oy) / s);
        if (fy < contentTop || fy >= contentTop + contentH) return null;
        int dy = fy - contentTop;
        dx = Math.max(0, Math.min(dispW - 1, dx));
        dy = Math.max(0, Math.min(contentH - 1, dy));
        return new int[]{dx, dy};
    }

    @Override
    protected void onDestroy() {
        CastEngine e = CastEngine.current();
        if (e != null) e.setPreviewSink(null);
        if (decoder != null) {
            decoder.stop();
            decoder = null;
        }
        super.onDestroy();
    }
}
