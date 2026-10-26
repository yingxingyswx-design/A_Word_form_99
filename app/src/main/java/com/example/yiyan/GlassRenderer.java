package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * 小组件玻璃材质。参照 FolkPatch 的做法（它用 io.github.fletchmckee.liquid 做实时模糊，
 * 再用 Canvas 画边框 / 顶部反光条 / 内发光 / 竖向渐变），这里不读壁纸、不贴图，
 * 只画「透明底 + 光学层」，桌面壁纸永远是实时透出来的那一张。
 *
 * 两种材质：
 * - 液态玻璃（默认）：几乎全透明的染色 + 1px 边框 + 顶部反光条 + 底部内发光 + 细渐变；
 * - 毛玻璃：同一套光学层，但底色更厚，再加磨砂颗粒与顶边内阴影。
 *
 * 亮度/透明度映射按 FolkPatch 的取值（浅色更亮、深色更收敛），
 * 圆角用连续曲率（超椭圆）而不是普通 roundRect。
 */
public final class GlassRenderer {

    public static final String KEY_ENABLED = "glassEnabled";
    public static final String KEY_MATERIAL = "glassMaterial";
    public static final String KEY_BLUR = "glassBlur";
    public static final String KEY_DENSITY = "glassDensity";
    public static final String KEY_CORNER = "glassCorner";
    public static final String KEY_HIGHLIGHT = "glassHighlight";
    public static final String KEY_TONE = "glassTone";

    public static final String MATERIAL_FROST = "frost";
    public static final String MATERIAL_LIQUID = "liquid";

    public static final String TONE_AUTO = "auto";
    public static final String TONE_LIGHT = "light";
    public static final String TONE_DARK = "dark";

    public static final int DEFAULT_BLUR = 55;
    public static final int DEFAULT_DENSITY = 45;
    public static final int DEFAULT_CORNER = 28;
    public static final int DEFAULT_HIGHLIGHT = 75;

    private static final long MAX_PIXELS = 2_600_000L;
    private static final double SQUIRCLE_N = 4.0;

    private GlassRenderer() {
    }

    public static final class Spec {
        public String material = MATERIAL_LIQUID;
        public float blur = DEFAULT_BLUR / 100f;
        public float density = DEFAULT_DENSITY / 100f;
        public float cornerDp = DEFAULT_CORNER;
        public float highlight = DEFAULT_HIGHLIGHT / 100f;
        public boolean dark;

        public boolean isFrost() {
            return MATERIAL_FROST.equals(material);
        }
    }

    public static boolean enabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public static Spec spec(Context context, SharedPreferences prefs) {
        Spec spec = new Spec();
        spec.material = prefs.getString(KEY_MATERIAL, MATERIAL_LIQUID);
        spec.blur = clamp01(prefs.getInt(KEY_BLUR, DEFAULT_BLUR) / 100f);
        spec.density = clamp01(prefs.getInt(KEY_DENSITY, DEFAULT_DENSITY) / 100f);
        spec.highlight = clamp01(prefs.getInt(KEY_HIGHLIGHT, DEFAULT_HIGHLIGHT) / 100f);
        spec.cornerDp = Math.max(0, Math.min(48, prefs.getInt(KEY_CORNER, DEFAULT_CORNER)));
        spec.dark = isDark(context, prefs);
        return spec;
    }

    public static boolean isDark(Context context, SharedPreferences prefs) {
        String tone = prefs.getString(KEY_TONE, TONE_AUTO);
        if (TONE_DARK.equals(tone)) return true;
        if (TONE_LIGHT.equals(tone)) return false;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int textColor(Spec spec) {
        return spec.dark ? 0xFFFDFCFF : 0xFF121016;
    }

    public static int metaColor(Spec spec) {
        return spec.dark ? 0xFFEDE6FF : 0xFF3B3350;
    }

    public static int hintColor(Spec spec) {
        return spec.dark ? 0x99FDFCFF : 0x99413B50;
    }

    /** 透明 GIF 帧需要垫一层不透明底色时用。 */
    public static int solidFill(Spec spec) {
        return spec.dark ? 0xFF1A1723 : 0xFFF4F5FA;
    }

    public static int cornerPx(Context context, Spec spec, float density) {
        return Math.round(spec.cornerDp * density);
    }

    public static Bitmap render(Context context, int widthPx, int heightPx, Spec spec) {
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.max(1, widthPx);
        int h = Math.max(1, heightPx);
        long pixels = (long) w * h;
        if (pixels > MAX_PIXELS) {
            double scale = Math.sqrt(MAX_PIXELS / (double) pixels);
            w = Math.max(1, (int) Math.round(w * scale));
            h = Math.max(1, (int) Math.round(h * scale));
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        RectF rect = new RectF(0, 0, w, h);
        float radius = Math.min(Math.min(w, h) / 2f, spec.cornerDp * density);
        Path shape = squircleRect(rect, radius);

        canvas.save();
        canvas.clipPath(shape);
        drawBase(canvas, rect, w, h, spec);
        drawVerticalGradient(canvas, rect, w, h, spec);
        drawSpecular(canvas, w, h, spec, density);
        drawInnerGlow(canvas, w, h, spec, density);
        if (spec.isFrost()) {
            drawFrost(canvas, rect, w, h, spec, density);
        }
        canvas.restore();

        drawBorder(canvas, shape, w, h, spec, density);
        return out;
    }

    /** 底色：一层很淡的染色；液态玻璃几乎看不见，毛玻璃更厚。 */
    private static void drawBase(Canvas canvas, RectF rect, int w, int h, Spec spec) {
        float alpha;
        if (spec.isFrost()) {
            alpha = (spec.dark ? 0.30f : 0.36f) + spec.density * 0.28f + spec.blur * 0.10f;
        } else {
            alpha = (spec.dark ? 0.10f : 0.12f) + spec.density * 0.16f;
        }
        int color = spec.dark ? 0xFF101018 : 0xFFFFFFFF;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(withAlpha(color, Math.min(0.72f, alpha)));
        canvas.drawRect(rect, paint);
    }

    /** FolkPatch 的竖向渐变：顶部更亮，往下收。 */
    private static void drawVerticalGradient(Canvas canvas, RectF rect, int w, int h, Spec spec) {
        float high;
        float low;
        if (spec.dark) {
            high = 0.05f + 0.10f * spec.highlight;
            low = 0.01f + 0.04f * spec.highlight;
        } else {
            high = 0.08f + 0.18f * spec.highlight;
            low = 0.03f + 0.08f * spec.highlight;
        }
        float base = 0.45f + (1f - spec.density) * 0.35f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                withAlpha(0xFFFFFFFF, high * base),
                withAlpha(0xFFFFFFFF, low * base), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);
    }

    /** 顶部横向反光条：中间亮、两端透明，距顶约 2dp。 */
    private static void drawSpecular(Canvas canvas, int w, int h, Spec spec, float density) {
        if (spec.highlight <= 0.02f) return;
        float alpha = spec.dark
                ? 0.10f + 0.12f * spec.highlight
                : 0.25f + 0.25f * spec.highlight;
        float top = 2f * density;
        float thickness = Math.max(1.2f, 1.5f * density);
        float left = w * 0.15f;
        float right = w * 0.85f;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(left, 0, right, 0,
                new int[]{0x00FFFFFF, withAlpha(0xFFFFFFFF, alpha), 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(left, top, right, top + thickness),
                thickness / 2f, thickness / 2f, paint);
    }

    /** 底部内发光：给玻璃一点厚度。 */
    private static void drawInnerGlow(Canvas canvas, int w, int h, Spec spec, float density) {
        float alpha = spec.dark
                ? 0.02f + 0.04f * spec.highlight
                : 0.04f + 0.08f * spec.highlight;
        float inset = 4f * density;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, h - 4f * density, 0, h - 8f * density,
                withAlpha(0xFFFFFFFF, 0f),
                withAlpha(0xFFFFFFFF, alpha), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(inset, h - 8f * density, w - inset, h - 4f * density),
                2f * density, 2f * density, paint);
    }

    /** 毛玻璃额外加的：磨砂颗粒 + 顶边内阴影。 */
    private static void drawFrost(Canvas canvas, RectF rect, int w, int h, Spec spec, float density) {
        int shade = spec.dark ? 0xFF000000 : 0xFF241E33;
        float depth = Math.max(6f, Math.min(h * 0.3f, (8f + spec.blur * 22f) * density));
        Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        top.setShader(new LinearGradient(0, 0, 0, depth,
                withAlpha(shade, 0.12f + spec.density * 0.10f), withAlpha(shade, 0f),
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + depth, top);

        Random random = new Random(20251102L);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(withAlpha(0xFFFFFFFF, 0.04f + spec.blur * 0.04f));
        int count = Math.max(80, (w * h) / 5000);
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * w;
            float y = random.nextFloat() * h;
            canvas.drawCircle(x, y, 0.6f + random.nextFloat() * 1.1f, dot);
        }
    }

    /** 1px 边框：迎光侧更亮，背光侧几乎消失。 */
    private static void drawBorder(Canvas canvas, Path shape, int w, int h, Spec spec, float density) {
        float alpha = spec.dark ? 0.08f : 0.25f;
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1f, density));
        border.setShader(new LinearGradient(0, 0, w, h,
                new int[]{withAlpha(0xFFFFFFFF, alpha * spec.highlight * 1.6f),
                        withAlpha(0xFFFFFFFF, alpha * 0.35f),
                        withAlpha(0xFFFFFFFF, 0f)},
                new float[]{0f, 0.45f, 0.95f}, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, border);
    }

    // ---- 几何 ----

    /** 连续曲率圆角：直边 + 四段超椭圆角（n=4，接边处曲率为 0）。 */
    private static Path squircleRect(RectF rect, float radius) {
        Path path = new Path();
        float left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom;
        float r = Math.min(radius, Math.min(right - left, bottom - top) / 2f);
        if (r <= 0.5f) {
            path.addRect(rect, Path.Direction.CW);
            return path;
        }
        final double exp = 2.0 / SQUIRCLE_N;
        final int steps = 18;

        path.moveTo(left + r, top);
        path.lineTo(right - r, top);
        corner(path, right - r, top + r, r, exp, steps, +1, -1, true);

        path.lineTo(right, bottom - r);
        corner(path, right - r, bottom - r, r, exp, steps, +1, +1, false);

        path.lineTo(left + r, bottom);
        corner(path, left + r, bottom - r, r, exp, steps, -1, +1, true);

        path.lineTo(left, top + r);
        corner(path, left + r, top + r, r, exp, steps, -1, -1, false);

        path.close();
        return path;
    }

    private static void corner(Path path, float cx, float cy, float r, double exp, int steps,
                               int sx, int sy, boolean reverse) {
        for (int i = 0; i <= steps; i++) {
            int step = reverse ? steps - i : i;
            double t = Math.PI / 2 * step / steps;
            double u = Math.pow(Math.cos(t), exp);
            double v = Math.pow(Math.sin(t), exp);
            path.lineTo((float) (cx + sx * r * u), (float) (cy + sy * r * v));
        }
    }

    // ---- 工具 ----

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
