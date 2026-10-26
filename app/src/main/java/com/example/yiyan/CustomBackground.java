package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

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

    static final String KEY_TYPE = "customBgType";
    static final String TYPE_IMAGE = "image";
    static final String TYPE_SOLID = "solid";
    static final String TYPE_GRADIENT = "gradient";
    static final String KEY_SOLID = "customBgSolid";
    static final String KEY_GRADIENT_START = "customBgGradientStart";
    static final String KEY_GRADIENT_END = "customBgGradientEnd";
    static final String KEY_GRADIENT_NAME = "customBgGradientName";

    /** 纯色色卡：浅色纸张系 + 深色 + 主题色，覆盖桌面浅色/深色两种场景。 */
    static final int[] SOLID_COLORS = {
            0xFFFDF8F3, 0xFFF3EBE2, 0xFFEADDFF, 0xFFDDE8F7,
            0xFFD7F0E3, 0xFFF9DEE5, 0xFF2B2930, 0xFF3B4351,
            0xFF6750A4, 0xFF006A6A, 0xFFA33A32, 0xFF4F6A00
    };

    static final String[] SOLID_NAMES = {
            "米白", "奶油", "浅紫", "雾蓝", "薄荷", "樱粉",
            "墨黑", "深灰", "紫罗兰", "青碧", "珊瑚", "橄榄"
    };

    /** 渐变：起始色、结束色、角度。统一用表达式强的双色线性渐变。 */
    static final int[][] GRADIENTS = {
            {0xFFF6D5F7, 0xFFD9E4FF, 135}, // 晨雾
            {0xFFFFD8E4, 0xFFFFF0D9, 135}, // 蜜桃
            {0xFFD7F5E9, 0xFFD6E4FF, 135}, // 薄荷海
            {0xFFFFE7C2, 0xFFFFB4AB, 135}, // 日落
            {0xFFE8DEF8, 0xFFB9C7FF, 160}, // 薰衣草
            {0xFF2B2930, 0xFF6750A4, 135}, // 夜色紫
            {0xFF0F2027, 0xFF2C5364, 135}, // 深海
            {0xFFFFF9F0, 0xFFE8DCCB, 135}  // 纸感
    };

    static final String[] GRADIENT_NAMES = {
            "晨雾", "蜜桃", "薄荷海", "日落", "薰衣草", "夜色紫", "深海", "纸感"
    };

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

    static String type(SharedPreferences prefs) {
        String saved = prefs.getString(KEY_TYPE, TYPE_IMAGE);
        if (TYPE_SOLID.equals(saved) || TYPE_GRADIENT.equals(saved)) return saved;
        return TYPE_IMAGE;
    }

    static boolean isColorStyle(SharedPreferences prefs) {
        return !TYPE_IMAGE.equals(type(prefs));
    }

    /** 当前样式有没有可渲染的内容；决定「使用自定义背景」开关是否可用。 */
    static boolean hasContent(SharedPreferences prefs) {
        if (TYPE_SOLID.equals(type(prefs))) return prefs.contains(KEY_SOLID);
        if (TYPE_GRADIENT.equals(type(prefs))) {
            return prefs.contains(KEY_GRADIENT_START) && prefs.contains(KEY_GRADIENT_END);
        }
        return !name(prefs).isEmpty();
    }

    static int solidColor(SharedPreferences prefs) {
        return prefs.getInt(KEY_SOLID, SOLID_COLORS[0]);
    }

    static int gradientStart(SharedPreferences prefs) {
        return prefs.getInt(KEY_GRADIENT_START, GRADIENTS[0][0]);
    }

    static int gradientEnd(SharedPreferences prefs) {
        return prefs.getInt(KEY_GRADIENT_END, GRADIENTS[0][1]);
    }

    static int gradientAngle(SharedPreferences prefs) {
        return prefs.getInt("customBgGradientAngle", GRADIENTS[0][2]);
    }

    static String gradientName(SharedPreferences prefs) {
        return prefs.getString(KEY_GRADIENT_NAME, GRADIENT_NAMES[0]);
    }

    static String styleLabel(SharedPreferences prefs) {
        if (TYPE_SOLID.equals(type(prefs))) {
            return "纯色 · " + ThemeColors.hex(solidColor(prefs));
        }
        if (TYPE_GRADIENT.equals(type(prefs))) {
            return "渐变 · " + gradientName(prefs);
        }
        return name(prefs);
    }

    /** 纯色/渐变背景大致是深色还是浅色，用来决定小组件文字用白还是黑。 */
    static boolean isDarkStyle(SharedPreferences prefs) {
        int color;
        if (TYPE_SOLID.equals(type(prefs))) {
            color = solidColor(prefs);
        } else if (TYPE_GRADIENT.equals(type(prefs))) {
            color = average(gradientStart(prefs), gradientEnd(prefs));
        } else {
            return false;
        }
        return androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.45;
    }

    /** 读自定义背景；图片走解码 + 可选模糊，纯色/渐变现画一张位图供预览用。 */
    static synchronized Bitmap load(Context context, SharedPreferences prefs) {
        if (!enabled(prefs)) return null;
        if (isColorStyle(prefs) && hasContent(prefs)) {
            return renderColorBitmap(context, 800, 800, prefs);
        }
        return loadImage(context, prefs);
    }

    /** 只加载图片背景；沿用原来的缓存与模糊。 */
    private static synchronized Bitmap loadImage(Context context, SharedPreferences prefs) {
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

    /**
     * 按当前背景类型渲染圆角卡片：图片走原逻辑，纯色/渐变直接按卡片尺寸画。
     * 纯色/渐变不参与高斯模糊，避免用户误以为模糊开关坏了。
     */
    static Bitmap renderCard(Context context, int widthPx, int heightPx,
                             SharedPreferences prefs, float cornerDp) {
        int w = Math.max(1, widthPx);
        int h = Math.max(1, heightPx);
        if (isColorStyle(prefs)) {
            if (!hasContent(prefs)) return null;
            return renderColorCard(context, w, h, prefs, cornerDp);
        }
        Bitmap source = loadImage(context, prefs);
        if (source == null) return null;
        // loadImage 已经按开关做过模糊，这里不再重复模糊
        return renderCard(context, w, h, source, false, 0, cornerDp);
    }

    private static Bitmap renderColorBitmap(Context context, int w, int h,
                                            SharedPreferences prefs) {
        Bitmap out = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        drawColorStyle(canvas, out.getWidth(), out.getHeight(), prefs);
        return out;
    }

    private static Bitmap renderColorCard(Context context, int w, int h,
                                          SharedPreferences prefs, float cornerDp) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        float density = context.getResources().getDisplayMetrics().density;
        float radius = Math.max(0f, Math.min(cornerDp * density, Math.min(w, h) / 2f));

        Path shape = new Path();
        shape.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(shape);
        drawColorStyle(canvas, w, h, prefs);
        canvas.restore();

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, density));
        border.setColor(isDarkStyle(prefs) ? 0x33FFFFFF : 0x22000000);
        canvas.drawPath(shape, border);
        return out;
    }

    private static void drawColorStyle(Canvas canvas, int w, int h, SharedPreferences prefs) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (TYPE_SOLID.equals(type(prefs))) {
            paint.setColor(solidColor(prefs));
        } else {
            paint.setShader(gradientShader(w, h, gradientStart(prefs), gradientEnd(prefs),
                    gradientAngle(prefs)));
        }
        canvas.drawRect(0, 0, w, h, paint);
    }

    private static Shader gradientShader(int w, int h, int start, int end, int angle) {
        double radians = Math.toRadians(angle);
        float cx = w / 2f;
        float cy = h / 2f;
        float length = (float) Math.hypot(w, h) / 2f;
        float dx = (float) (Math.cos(radians) * length);
        float dy = (float) (Math.sin(radians) * length);
        return new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy,
                start, end, Shader.TileMode.CLAMP);
    }

    static int averageColor(SharedPreferences prefs) {
        if (TYPE_SOLID.equals(type(prefs))) return solidColor(prefs);
        return average(gradientStart(prefs), gradientEnd(prefs));
    }

    private static int average(int start, int end) {
        return androidx.core.graphics.ColorUtils.blendARGB(start, end, 0.5f);
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
