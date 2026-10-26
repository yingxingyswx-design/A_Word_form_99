package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.File;

/**
 * 自定义玻璃背景：用户挑一张图片（GIF 取第一帧）当玻璃背后的画面。
 *
 * 和高斯模糊分开控制：{@link #KEY_BLUR} 打开后按 {@link #KEY_BLUR_AMOUNT} 做三遍盒式模糊
 * （近似高斯），玻璃本身的光学层（折射 / rim / 高光）不变。
 */
final class CustomBackground {

    static final String KEY_ENABLED = "customBgEnabled";
    static final String KEY_NAME = "customBgName";
    static final String KEY_BLUR = "customBgBlur";
    static final String KEY_BLUR_AMOUNT = "customBgBlurAmount";

    private static final int MAX_WIDTH = 900;

    private static String cachedName;
    private static long cachedModified;
    private static Bitmap cached;

    private CustomBackground() {
    }

    static boolean enabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    static String name(SharedPreferences prefs) {
        return prefs.getString(KEY_NAME, "");
    }

    static boolean blurred(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_BLUR, false);
    }

    static int blurAmount(SharedPreferences prefs) {
        return Math.max(0, Math.min(100, prefs.getInt(KEY_BLUR_AMOUNT, 40)));
    }

    /** 读自定义背景；没设置 / 读失败返回 null，调用方退回壁纸。 */
    static synchronized Bitmap load(Context context, SharedPreferences prefs) {
        if (!enabled(prefs)) return null;
        String name = name(prefs);
        File file = ImageStore.resolve(context, name);
        if (file == null || !file.isFile()) {
            android.util.Log.w("99YIYAN_LOG", "自定义背景文件不存在：" + name);
            return null;
        }

        if (cached != null && name.equals(cachedName) && file.lastModified() == cachedModified) {
            return cached;
        }
        Bitmap raw = ImageStore.decode(context, name, MAX_WIDTH);
        if (raw == null) {
            android.util.Log.w("99YIYAN_LOG", "自定义背景解码失败：" + name + " @ " + file.getAbsolutePath()
                    + " exists=" + file.exists() + " len=" + file.length());
            return null;
        }
        Bitmap result = blurred(prefs) ? gaussian(raw, blurAmount(prefs)) : raw;
        cached = result;
        cachedName = name;
        cachedModified = file.lastModified();
        return result;
    }

    static void invalidate() {
        cached = null;
        cachedName = null;
        cachedModified = 0;
    }

    /** 缩到 1/3 做三遍盒式模糊再放大：接近高斯，CPU 上也就是几十毫秒。 */
    static Bitmap gaussian(Bitmap source, int amount01) {
        if (source == null || amount01 <= 0) return source;
        int w = Math.max(1, source.getWidth() / 3);
        int h = Math.max(1, source.getHeight() / 3);
        Bitmap small = Bitmap.createScaledBitmap(source, w, h, true);
        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        if (small != source) small.recycle();

        int radius = Math.max(1, Math.round(amount01 / 100f * Math.min(w, h) * 0.18f));
        boxBlur(pixels, w, h, radius);
        boxBlur(pixels, w, h, Math.max(1, radius / 2));
        boxBlur(pixels, w, h, Math.max(1, radius / 3));

        Bitmap blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        blurred.setPixels(pixels, 0, w, 0, 0, w, h);
        Bitmap out = Bitmap.createScaledBitmap(blurred, source.getWidth(), source.getHeight(), true);
        if (out != blurred) blurred.recycle();
        return out;
    }

    /**
     * 自定义背景模式下不再走玻璃光学层：直接把图片裁成圆角卡片（可选高斯模糊 + 1px 描边）。
     */
    static Bitmap renderCard(Context context, int widthPx, int heightPx, Bitmap source,
                             boolean blur, int blurAmount, float cornerDp) {
        int w = Math.max(1, widthPx);
        int h = Math.max(1, heightPx);
        Bitmap base = blur ? gaussian(source, blurAmount) : source;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float density = context.getResources().getDisplayMetrics().density;
        float radius = Math.max(0f, Math.min(cornerDp * density, Math.min(w, h) / 2f));

        Path shape = new Path();
        shape.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(shape);

        float scale = Math.max(w / (float) base.getWidth(), h / (float) base.getHeight());
        float drawW = base.getWidth() * scale;
        float drawH = base.getHeight() * scale;
        float left = (w - drawW) / 2f;
        float top = (h - drawH) / 2f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(base, null, new RectF(left, top, left + drawW, top + drawH), paint);
        canvas.restore();

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, density));
        border.setColor(0x33FFFFFF);
        canvas.drawPath(shape, border);
        return out;
    }

    private static void boxBlur(int[] pixels, int w, int h, int radius) {
        if (radius <= 0) return;
        int[] temp = new int[pixels.length];
        int window = radius * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int r = 0, g = 0, b = 0, a = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = pixels[row + clamp(i, w)];
                a += android.graphics.Color.alpha(color);
                r += android.graphics.Color.red(color);
                g += android.graphics.Color.green(color);
                b += android.graphics.Color.blue(color);
            }
            for (int x = 0; x < w; x++) {
                temp[row + x] = android.graphics.Color.argb(a / window, r / window, g / window, b / window);
                int outIndex = pixels[row + clamp(x - radius, w)];
                int inIndex = pixels[row + clamp(x + radius + 1, w)];
                int out = outIndex;
                int in = inIndex;
                a += android.graphics.Color.alpha(in) - android.graphics.Color.alpha(out);
                r += android.graphics.Color.red(in) - android.graphics.Color.red(out);
                g += android.graphics.Color.green(in) - android.graphics.Color.green(out);
                b += android.graphics.Color.blue(in) - android.graphics.Color.blue(out);
            }
        }
        for (int x = 0; x < w; x++) {
            int r = 0, g = 0, b = 0, a = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = temp[clamp(i, h) * w + x];
                a += android.graphics.Color.alpha(color);
                r += android.graphics.Color.red(color);
                g += android.graphics.Color.green(color);
                b += android.graphics.Color.blue(color);
            }
            for (int y = 0; y < h; y++) {
                pixels[y * w + x] = android.graphics.Color.argb(a / window, r / window, g / window, b / window);
                int out = temp[clamp(y - radius, h) * w + x];
                int in = temp[clamp(y + radius + 1, h) * w + x];
                a += android.graphics.Color.alpha(in) - android.graphics.Color.alpha(out);
                r += android.graphics.Color.red(in) - android.graphics.Color.red(out);
                g += android.graphics.Color.green(in) - android.graphics.Color.green(out);
                b += android.graphics.Color.blue(in) - android.graphics.Color.blue(out);
            }
        }
    }

    private static int clamp(int index, int size) {
        if (index < 0) return 0;
        if (index >= size) return size - 1;
        return index;
    }
}
