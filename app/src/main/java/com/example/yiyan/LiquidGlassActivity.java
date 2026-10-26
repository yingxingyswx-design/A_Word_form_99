package com.example.yiyan;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;

/**
 * 实时玻璃页：玻璃不是提前算好的位图，而是 GPU 上每帧跑的 RuntimeShader。
 *
 * 这里没有桌面壁纸的实时画面可用（小组件和普通 Activity 都拿不到身后的窗口），
 * 所以自己画一个「会动的背景」当被折射的内容：壁纸缓慢推近 + 漂移高光。
 * 着色器采样的是同一份内容，所以玻璃边缘的折射、色散、亮边每帧都在变。
 */
public class LiquidGlassActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private LiquidGlassBackdrop backdrop;
    private LiquidGlassPane pane;
    private TextView hint;
    private String material;

    private final Choreographer choreographer = Choreographer.getInstance();
    private long startNanos;
    private boolean running;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            float t = (frameTimeNanos - startNanos) / 1_000_000_000f;
            backdrop.setTime(t);
            pane.setTime(t);
            // 30fps 足够看出折射在动，又不会让这台老机器把主线程拖住
            choreographer.postFrameCallbackDelayed(this, 33);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_liquid_glass);
        prefs = getSharedPreferences("settings", 0);
        material = prefs.getString(GlassRenderer.KEY_MATERIAL, GlassRenderer.MATERIAL_FROST);

        backdrop = findViewById(R.id.liquid_backdrop);
        pane = findViewById(R.id.liquid_pane);
        hint = findViewById(R.id.liquid_hint);
        findViewById(R.id.liquid_close).setOnClickListener(v -> finish());

        MaterialButtonToggleGroup group = findViewById(R.id.liquid_material_group);
        group.check(GlassRenderer.MATERIAL_LIQUID.equals(material)
                ? R.id.liquid_material_liquid : R.id.liquid_material_frost);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            material = checkedId == R.id.liquid_material_liquid
                    ? GlassRenderer.MATERIAL_LIQUID : GlassRenderer.MATERIAL_FROST;
            prefs.edit().putString(GlassRenderer.KEY_MATERIAL, material).apply();
            applyGlass();
        });

        setupGlassCard();

        if (!LiquidGlassPane.supported()) {
            hint.setText("这台机器的 Android 版本低于 13，没有 RuntimeShader：只能看到背景，玻璃效果不可用。");
        } else if (!WallpaperSource.canRead(this)) {
            hint.setText("没有「所有文件访问」权限：用演示渐变当背景。去「个性」页授权后就是你的真实壁纸。");
        }

        loadWallpaper();
        applyGlass();
    }

    /** 玻璃卡片：自由拖动位置、拖右下角改长宽，几何存进 prefs。 */
    private void setupGlassCard() {
        final android.view.View card = findViewById(R.id.liquid_card);
        final android.view.View handle = findViewById(R.id.liquid_resize_handle);
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int screenH = getResources().getDisplayMetrics().heightPixels;
        final int density = Math.round(getResources().getDisplayMetrics().density);

        int width = prefs.getInt("liveGlassW", screenW - 12 * density);
        int height = prefs.getInt("liveGlassH", Math.round(screenH * 0.30f));
        int left = prefs.getInt("liveGlassX", 6 * density);
        int top = prefs.getInt("liveGlassY", Math.round(screenH * 0.52f));
        applyGeometry(card, left, top, width, height, screenW, screenH, density);

        final int[] startGeom = new int[4];
        final float[] down = new float[2];
        final boolean[] resizing = new boolean[1];

        card.setOnTouchListener((v, event) -> {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) card.getLayoutParams();
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    startGeom[0] = lp.leftMargin;
                    startGeom[1] = lp.topMargin;
                    startGeom[2] = lp.width;
                    startGeom[3] = lp.height;
                    int[] hLoc = new int[2];
                    handle.getLocationOnScreen(hLoc);
                    resizing[0] = event.getRawX() >= hLoc[0] - density * 8
                            && event.getRawY() >= hLoc[1] - density * 8;
                    return true;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    int dx = Math.round(event.getRawX() - down[0]);
                    int dy = Math.round(event.getRawY() - down[1]);
                    if (resizing[0]) {
                        applyGeometry(card, startGeom[0], startGeom[1],
                                startGeom[2] + dx, startGeom[3] + dy, screenW, screenH, density);
                    } else {
                        applyGeometry(card, startGeom[0] + dx, startGeom[1] + dy,
                                startGeom[2], startGeom[3], screenW, screenH, density);
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    prefs.edit()
                            .putInt("liveGlassX", lp.leftMargin)
                            .putInt("liveGlassY", lp.topMargin)
                            .putInt("liveGlassW", lp.width)
                            .putInt("liveGlassH", lp.height)
                            .apply();
                    return true;
                }
            }
            return false;
        });
    }

    private void applyGeometry(android.view.View card, int left, int top, int width, int height,
                               int screenW, int screenH, int density) {
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) card.getLayoutParams();
        int minW = 140 * density;
        int minH = 90 * density;
        lp.width = Math.max(minW, Math.min(screenW - left, width));
        lp.height = Math.max(minH, Math.min(screenH - top, height));
        lp.leftMargin = Math.max(0, Math.min(screenW - lp.width, left));
        lp.topMargin = Math.max(0, Math.min(screenH - lp.height, top));
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        card.setLayoutParams(lp);
    }

    private void loadWallpaper() {
        new Thread(() -> {
            Bitmap bitmap = WallpaperSource.load(this, 1000);
            runOnUiThread(() -> {
                backdrop.setWallpaper(bitmap);
                pane.setWallpaper(bitmap);
            });
        }, "liquid-wallpaper").start();
    }

    private void applyGlass() {
        GlassRenderer.Spec spec = GlassRenderer.spec(this, prefs);
        boolean frost = GlassRenderer.MATERIAL_FROST.equals(material);
        float frostDp = frost
                ? 8f + spec.blur * 18f
                : 1.0f + spec.blur * 3f;
        pane.setGlass(spec.cornerDp + 6f, frostDp, spec.blur, spec.density, spec.highlight,
                spec.dark);
        ((TextView) findViewById(R.id.liquid_quote))
                .setTextColor(spec.dark ? 0xFFFDFCFF : 0xFFFFFFFF);
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        startNanos = System.nanoTime();
        choreographer.postFrameCallback(frameCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        choreographer.removeFrameCallback(frameCallback);
    }
}
