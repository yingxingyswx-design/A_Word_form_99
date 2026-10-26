package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「玻璃里那张壁纸」对齐到桌面上的真实位置。
 *
 * 桌面小组件身后只有壁纸，而 AOSP Launcher3 的壁纸是不滚动的居中铺满，
 * 所以只要知道小组件在屏幕上的矩形，就能从壁纸里裁出它背后那一条 ——
 * 玻璃里的折射 / 模糊就和桌面严丝合缝，看上去就是一块真玻璃。
 *
 * 矩形有两个来源：
 * - auto：{@link WidgetLocatorService}（无障碍）从 launcher 的 AppWidgetHostView 读到的屏幕坐标；
 * - manual：用户在「个性」页用水平 / 垂直偏移手动对准（零权限）。
 */
final class GlassAlign {

    private static final String TAG = "99YIYAN_LOG";

    /** off / manual / auto */
    static final String KEY_MODE = "glassAlignMode";
    static final String KEY_OFFSET_X = "glassAlignX";
    static final String KEY_OFFSET_Y = "glassAlignY";
    static final String KEY_RECTS = "glassWidgetRects";
    static final String KEY_RECT_AT = "glassWidgetRectAt";

    static final String MODE_OFF = "off";
    static final String MODE_MANUAL = "manual";
    static final String MODE_AUTO = "auto";

    private GlassAlign() {
    }

    static String mode(SharedPreferences prefs) {
        return prefs.getString(KEY_MODE, MODE_OFF);
    }

    static int offsetX(SharedPreferences prefs) {
        return prefs.getInt(KEY_OFFSET_X, 0);
    }

    static int offsetY(SharedPreferences prefs) {
        return prefs.getInt(KEY_OFFSET_Y, 0);
    }

    static void storeCandidates(Context context, List<Rect> rects) {
        if (rects == null || rects.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Rect rect : rects) {
            if (sb.length() > 0) sb.append(';');
            sb.append(rect.left).append(',').append(rect.top).append(',')
                    .append(rect.right).append(',').append(rect.bottom);
        }
        context.getSharedPreferences("settings", 0).edit()
                .putString(KEY_RECTS, sb.toString())
                .putLong(KEY_RECT_AT, System.currentTimeMillis())
                .apply();
    }

    /** 无障碍读到的候选矩形（可能有好几个，来自不同小组件）。 */
    static List<Rect> candidates(SharedPreferences prefs) {
        List<Rect> out = new ArrayList<>();
        String raw = prefs.getString(KEY_RECTS, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(";")) {
            String[] p = part.split(",");
            if (p.length != 4) continue;
            try {
                Rect rect = new Rect(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                        Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
                if (rect.width() > 0 && rect.height() > 0) out.add(rect);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** 在候选里挑一个和小组件尺寸最接近的（容差 35%）。 */
    static Rect bestCandidate(SharedPreferences prefs, int widgetW, int widgetH) {
        Rect best = null;
        float bestScore = Float.MAX_VALUE;
        for (Rect rect : candidates(prefs)) {
            float dw = Math.abs(rect.width() - widgetW) / (float) widgetW;
            float dh = Math.abs(rect.height() - widgetH) / (float) widgetH;
            if (dw > 0.35f || dh > 0.35f) continue;
            if (dw + dh < bestScore) {
                bestScore = dw + dh;
                best = rect;
            }
        }
        return best;
    }

    /**
     * 小组件在屏幕上的矩形（px）。
     *
     * @param sizeDp 小组件尺寸（dp），用来自动挑选无障碍候选、以及手动模式换算像素
     */
    static Rect widgetScreenRect(Context context, SharedPreferences prefs, int[] sizeDp) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;
        int widgetW = Math.max(1, Math.round(sizeDp[0] * metrics.density));
        int widgetH = Math.max(1, Math.round(sizeDp[1] * metrics.density));

        String mode = mode(prefs);
        if (MODE_OFF.equals(mode)) return null;

        if (MODE_AUTO.equals(mode)) {
            Rect detected = bestCandidate(prefs, widgetW, widgetH);
            if (detected != null) {
                Log.i(TAG, "对齐：无障碍矩形 " + detected);
                return detected;
            }
            Log.i(TAG, "对齐：没有匹配的无障碍矩形，退回手动偏移");
        }

        int left = (screenW - widgetW) / 2 + Math.round(screenW * offsetX(prefs) / 100f);
        int top = (screenH - widgetH) / 2 + Math.round(screenH * offsetY(prefs) / 100f);
        Rect rect = new Rect(left, top, left + widgetW, top + widgetH);
        Log.i(TAG, "对齐：手动矩形 " + rect + " (dx=" + offsetX(prefs) + "%, dy=" + offsetY(prefs) + "%)");
        return rect;
    }

    /**
     * 把小组件的屏幕矩形换算成壁纸位图里的采样矩形。
     *
     * @return 壁纸坐标（壁纸按屏幕居中铺满、不滚动）
     */
    static Rect wallpaperCrop(Bitmap wallpaper, int screenW, int screenH, Rect widget) {
        if (wallpaper == null || widget == null || screenW <= 0 || screenH <= 0) return null;
        float scale = Math.max(screenW / (float) wallpaper.getWidth(),
                screenH / (float) wallpaper.getHeight());
        float drawW = wallpaper.getWidth() * scale;
        float drawH = wallpaper.getHeight() * scale;
        float originX = (screenW - drawW) / 2f;
        float originY = (screenH - drawH) / 2f;

        int left = Math.round((widget.left - originX) / scale);
        int top = Math.round((widget.top - originY) / scale);
        int right = Math.round((widget.right - originX) / scale);
        int bottom = Math.round((widget.bottom - originY) / scale);

        left = Math.max(0, Math.min(wallpaper.getWidth() - 1, left));
        top = Math.max(0, Math.min(wallpaper.getHeight() - 1, top));
        right = Math.max(left + 1, Math.min(wallpaper.getWidth(), right));
        bottom = Math.max(top + 1, Math.min(wallpaper.getHeight(), bottom));
        return new Rect(left, top, right, bottom);
    }
}
