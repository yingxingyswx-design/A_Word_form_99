package com.example.yiyan;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 「实时玻璃」页共用的场景：一张会缓慢平移 / 推近的壁纸，加一团漂移的高光。
 *
 * 背景层和玻璃层必须画出**逐像素一致**的内容，玻璃层才能在自己的
 * RenderEffect 里采样到「身后」那张图；所以两边都调这里的 draw()。
 */
final class LiquidGlassScene {

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint HIGHLIGHT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Rect SRC = new Rect();
    private static final RectF DST = new RectF();

    private LiquidGlassScene() {
    }

    /** t 单位秒；同一个 t 画出来的结果必须一致。 */
    static void draw(Canvas canvas, Bitmap wallpaper, int screenW, int screenH, float t) {
        draw(canvas, wallpaper, screenW, screenH, t, true);
    }

    /**
     * @param animated false = 严格按桌面壁纸的位置铺（悬浮窗用），不能推近 / 漂移，
     *                 否则玻璃里的画面和桌面错位
     */
    static void draw(Canvas canvas, Bitmap wallpaper, int screenW, int screenH, float t,
                     boolean animated) {
        if (screenW <= 0 || screenH <= 0) return;

        if (wallpaper != null && !wallpaper.isRecycled()) {
            // Ken Burns：慢慢推近 + 左右漂移，玻璃边缘的折射会跟着动
            float zoom = animated ? 1.06f + 0.07f * (float) Math.sin(t * 0.11f) : 1f;
            float panX = animated ? (float) Math.sin(t * 0.07f) * screenW * 0.06f : 0f;
            float panY = animated ? (float) Math.cos(t * 0.05f) * screenH * 0.04f : 0f;

            float scale = Math.max(screenW / (float) wallpaper.getWidth(),
                    screenH / (float) wallpaper.getHeight()) * zoom;
            float drawW = wallpaper.getWidth() * scale;
            float drawH = wallpaper.getHeight() * scale;
            float left = (screenW - drawW) / 2f + panX;
            float top = (screenH - drawH) / 2f + panY;

            SRC.set(0, 0, wallpaper.getWidth(), wallpaper.getHeight());
            DST.set(left, top, left + drawW, top + drawH);
            canvas.drawBitmap(wallpaper, SRC, DST, PAINT);
        } else {
            // 没有壁纸时也要有东西可折射：一块缓慢流动的渐变
            float drift = (float) Math.sin(t * 0.09f) * 0.25f;
            HIGHLIGHT.setShader(new LinearGradient(
                    screenW * (0.1f + drift), 0,
                    screenW * (0.9f + drift), screenH,
                    new int[]{0xFF2B1B4D, 0xFF1E5B6B, 0xFFB96A4B, 0xFF3C2A5E},
                    new float[]{0f, 0.4f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, screenW, screenH, HIGHLIGHT);
            HIGHLIGHT.setShader(null);
        }

        if (!animated) return;

        // 漂移的高光：玻璃把这块亮斑「折」进去，实时感最明显
        float hx = screenW * (0.5f + 0.32f * (float) Math.sin(t * 0.23f));
        float hy = screenH * (0.35f + 0.22f * (float) Math.cos(t * 0.17f));
        HIGHLIGHT.setShader(new RadialGradient(hx, hy, Math.max(screenW, screenH) * 0.42f,
                new int[]{0x55FFFFFF, 0x11FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, screenW, screenH, HIGHLIGHT);
        HIGHLIGHT.setShader(null);
    }

    /** 画一层压暗，保证白字在花壁纸上也读得出来。 */
    static void drawScrim(Canvas canvas, int screenW, int screenH) {
        PAINT.setShader(new LinearGradient(0, 0, 0, screenH,
                Color.argb(40, 0, 0, 0), Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, screenW, screenH, PAINT);
        PAINT.setShader(null);
    }
}
