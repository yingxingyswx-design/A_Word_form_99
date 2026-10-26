package com.example.yiyan;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 液态玻璃。
 *
 * Apple HIG / WWDC25《Meet Liquid Glass》里玻璃的几条硬特征：连续曲率圆角、
 * 边缘透镜（亮边 + 薄暗折痕 + 回光）、迎光侧的镜面高光、随内容变化的光源。
 *
 * Android 小组件的硬限制：RemoteViews 没有 backdrop blur，launcher 也不把身后的壁纸
 * 交给小组件，所以「把壁纸糊掉」在桌面上既做不准（对不齐），也做不出真模糊——之前那种
 * 「贴一块模糊壁纸」的做法在边界会露馅，看起来就是一张贴纸。
 *
 * 因此这里改成 Apple 的 .clear 思路：玻璃本身几乎完全透明，真正的壁纸就是背景（所以
 * 天然对齐、天然实时），质感全部由边缘光学承担——透镜亮边、内侧折痕、定向镜面高光、
 * 顶边内阴影。App 内预览会把真实壁纸垫在下面，看到的就是桌面上的样子。
 */
public final class GlassRenderer {

    public static final String KEY_ENABLED = "glassEnabled";
    public static final String KEY_BLUR = "glassBlur";
    public static final String KEY_DENSITY = "glassDensity";
    public static final String KEY_CORNER = "glassCorner";
    public static final String KEY_HIGHLIGHT = "glassHighlight";
    public static final String KEY_TONE = "glassTone";

    public static final String TONE_AUTO = "auto";
    public static final String TONE_LIGHT = "light";
    public static final String TONE_DARK = "dark";

    public static final int DEFAULT_BLUR = 45;
    public static final int DEFAULT_DENSITY = 40;
    public static final int DEFAULT_CORNER = 30;
    public static final int DEFAULT_HIGHLIGHT = 72;

    private static final long MAX_PIXELS = 2_600_000L;
    private static final double SQUIRCLE_N = 4.0;
    private static final int FALLBACK_PRIMARY = 0xFF6C5CE7;
    private static final int FALLBACK_SECONDARY = 0xFF8E7BEF;
    private static final int FALLBACK_TERTIARY = 0xFFB39DDB;

    private static int[] cachedColors;
    private static long cachedColorsAt;

    private GlassRenderer() {
    }

    public static final class Spec {
        public float blur = DEFAULT_BLUR / 100f;
        public float density = DEFAULT_DENSITY / 100f;
        public float cornerDp = DEFAULT_CORNER;
        public float highlight = DEFAULT_HIGHLIGHT / 100f;
        public boolean dark;
        public int tint = FALLBACK_PRIMARY;
        public int tint2 = FALLBACK_SECONDARY;
        public int tint3 = FALLBACK_TERTIARY;
        /** 光源方位 0–1：换句子时轻微变化，高光位置会「流动」。 */
        public float light = 0.35f;
    }

    public static boolean enabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public static Spec spec(Context context, SharedPreferences prefs) {
        Spec spec = new Spec();
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
        return spec.dark ? 0xFFFDFCFF : 0xFF111015;
    }

    public static int metaColor(Spec spec) {
        return spec.dark ? 0xFFEDE6FF : 0xFF3B3350;
    }

    public static int hintColor(Spec spec) {
        return spec.dark ? 0x99FDFCFF : 0x99413B50;
    }

    /** 透明 GIF 帧需要垫一层不透明底色。 */
    public static int solidFill(Spec spec) {
        return spec.dark ? 0xFF161320 : 0xFFF3F4FA;
    }

    public static int cornerPx(Context context, Spec spec, float density) {
        return Math.round(spec.cornerDp * density);
    }

    /** 壁纸三色（无需权限），用于给玻璃一点环境色。 */
    public static int[] wallpaperColors(Context context) {
        long now = System.currentTimeMillis();
        if (now - cachedColorsAt < 120_000L && cachedColors != null) return cachedColors;
        int[] colors = {FALLBACK_PRIMARY, FALLBACK_SECONDARY, FALLBACK_TERTIARY};
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            try {
                WallpaperManager manager = WallpaperManager.getInstance(context);
                android.app.WallpaperColors wallpaper =
                        manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
                if (wallpaper != null) {
                    if (wallpaper.getPrimaryColor() != null) {
                        colors[0] = wallpaper.getPrimaryColor().toArgb() | 0xFF000000;
                    }
                    if (wallpaper.getSecondaryColor() != null) {
                        colors[1] = wallpaper.getSecondaryColor().toArgb() | 0xFF000000;
                    }
                    if (wallpaper.getTertiaryColor() != null) {
                        colors[2] = wallpaper.getTertiaryColor().toArgb() | 0xFF000000;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        cachedColors = colors;
        cachedColorsAt = now;
        return colors;
    }

    public static Bitmap render(Context context, int widthPx, int heightPx, Spec spec) {
        return render(context, widthPx, heightPx, spec, null);
    }

    /**
     * @param backdrop 仅用于 App 内预览的合成（真实壁纸），不会画进玻璃本身；
     *                 小组件传 null，玻璃直接叠在桌面壁纸上。
     */
    public static Bitmap render(Context context, int widthPx, int heightPx, Spec spec,
                                Bitmap backdrop) {
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
        float edge = Math.max(1.5f, 1.2f * density);

        canvas.save();
        canvas.clipPath(shape);
        drawTint(canvas, rect, w, h, spec);
        drawDepth(canvas, rect, w, h, spec);
        drawSpecular(canvas, rect, w, h, spec);
        drawDither(canvas, w, h, spec);
        canvas.restore();

        drawEdge(canvas, shape, w, h, edge, spec);
        return out;
    }

    // ---- 极淡的环境色：玻璃会捡一点周围的颜色 ----

    private static void drawTint(Canvas canvas, RectF rect, int w, int h, Spec spec) {
        // 底色：白/黑里掺一点壁纸主色 —— 这就是 Apple `.clear` 里那层「压亮度」的染色，
        // 让文字在花哨壁纸上也能读，同时不破坏壁纸本身（没有假的模糊块，天然对齐）
        int toneColor = spec.dark ? 0xFF0B0912 : 0xFFFFFFFF;
        int scrimColor = blend(toneColor, spec.tint, spec.dark ? 0.30f : 0.26f);
        float scrimAlpha = (spec.dark ? 0.16f : 0.20f) + spec.blur * 0.26f
                + spec.density * 0.14f;

        Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        scrim.setShader(new LinearGradient(0, 0, w * 0.4f, h,
                withAlpha(scrimColor, scrimAlpha * 0.85f),
                withAlpha(scrimColor, scrimAlpha), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, scrim);

        // 一点点环境色流动，让玻璃不是死板的纯色板
        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setShader(new RadialGradient(w * (0.15f + 0.2f * spec.light), h * 0.1f,
                Math.max(w, h) * 0.7f, withAlpha(spec.tint2, 0.10f + spec.density * 0.10f),
                withAlpha(spec.tint2, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, tint);
    }

    // ---- 厚度：顶边内阴影 + 底边回光 ----

    private static void drawDepth(Canvas canvas, RectF rect, int w, int h, Spec spec) {
        float depth = Math.max(6f, Math.min(h * 0.28f, (10f + spec.blur * 26f) * density(spec, w, h)));

        int shade = spec.dark ? 0xFF000000 : 0xFF2A2438;
        Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        top.setShader(new LinearGradient(0, 0, 0, depth,
                withAlpha(shade, 0.16f + spec.density * 0.10f), withAlpha(shade, 0f),
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + depth, top);

        Paint left = new Paint(Paint.ANTI_ALIAS_FLAG);
        left.setShader(new LinearGradient(0, 0, depth * 0.8f, 0,
                withAlpha(shade, 0.09f), withAlpha(shade, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, rect.top, rect.left + depth * 0.8f, rect.bottom, left);

        Paint bottom = new Paint(Paint.ANTI_ALIAS_FLAG);
        bottom.setShader(new LinearGradient(0, h, 0, h - depth,
                withAlpha(0xFFFFFFFF, 0.10f + spec.highlight * 0.08f),
                withAlpha(0xFFFFFFFF, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, h - depth, rect.right, h, bottom);
    }

    /** 密度的像素换算：只是把滑杆映射成一个合理的厚度，不参与内存计算。 */
    private static float density(Spec spec, int w, int h) {
        return Math.max(1f, Math.min(w, h) / 300f);
    }

    // ---- 镜面高光 ----

    private static void drawSpecular(Canvas canvas, RectF rect, int w, int h, Spec spec) {
        if (spec.highlight <= 0.01f) return;
        float cx = w * (0.06f + 0.26f * spec.light);
        float cy = h * (0.0f + 0.12f * spec.light);
        float r = Math.max(w, h) * 0.52f;

        Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
        bloom.setShader(new RadialGradient(cx, cy, r,
                withAlpha(0xFFFFFFFF, 0.16f * spec.highlight),
                withAlpha(0xFFFFFFFF, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, bloom);

        // 顶边靠光的一段锐利反光
        Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(1.6f);
        arc.setShader(new LinearGradient(w * 0.02f, 0, w * 0.55f, 0,
                withAlpha(0xFFFFFFFF, 0.45f * spec.highlight),
                withAlpha(0xFFFFFFFF, 0f), Shader.TileMode.CLAMP));
        canvas.drawLine(w * 0.03f, 1.5f, w * 0.55f, 1.5f, arc);
    }

    // ---- 边缘：亮边 + 折痕 + 暗边 ----

    private static void drawEdge(Canvas canvas, Path shape, int w, int h, float edge, Spec spec) {
        // 迎光侧：一条细亮边（玻璃壁）
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(edge);
        rim.setShader(litShader(w, h, 0xFFFFFFFF, 0.40f + spec.highlight * 0.42f));
        canvas.drawPath(shape, rim);

        // 紧贴亮边内侧的一道折痕，折射感就来自这条暗线
        Paint crease = new Paint(Paint.ANTI_ALIAS_FLAG);
        crease.setStyle(Paint.Style.STROKE);
        crease.setStrokeWidth(edge * 2.6f);
        crease.setShader(litShader(w, h, 0xFF000000, 0.06f + spec.density * 0.05f));
        canvas.drawPath(shape, crease);

        // 背光侧压一道淡暗边，边界不至于糊掉
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setStyle(Paint.Style.STROKE);
        shade.setStrokeWidth(edge);
        shade.setShader(bottomShader(w, h, 0xFF000000, 0.20f));
        canvas.drawPath(shape, shade);
    }

    /** 指向左上的定向光：只照亮迎光的两条边。 */
    private static Shader litShader(int w, int h, int color, float peak) {
        return new LinearGradient(0, 0, w * 0.95f, h * 0.95f,
                new int[]{withAlpha(color, peak), withAlpha(color, peak * 0.12f),
                        withAlpha(color, 0f)},
                new float[]{0f, 0.30f, 0.72f}, Shader.TileMode.CLAMP);
    }

    private static Shader bottomShader(int w, int h, int color, float peak) {
        return new LinearGradient(w, h, 0, 0,
                new int[]{withAlpha(color, peak), withAlpha(color, 0f)},
                new float[]{0f, 0.60f}, Shader.TileMode.CLAMP);
    }

    /** 极轻的抖动，压掉大面积渐变的色带。 */
    private static void drawDither(Canvas canvas, int w, int h, Spec spec) {
        java.util.Random random = new java.util.Random(20251102L);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(withAlpha(spec.dark ? 0xFFFFFFFF : 0xFF000000, 0.02f));
        int count = Math.max(60, (w * h) / 9000);
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * w;
            float y = random.nextFloat() * h;
            canvas.drawCircle(x, y, 0.6f + random.nextFloat() * 0.9f, dot);
        }
    }

    // ---- 几何 ----

    /**
     * 连续曲率圆角：直边 + 四段超椭圆角。
     * n=4 的超椭圆在直边接入处曲率为 0，所以角与边之间没有可见折点。
     */
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
            float x = (float) (cx + sx * r * u);
            float y = (float) (cy + sy * r * v);
            path.lineTo(x, y);
        }
    }

    // ---- 工具 ----

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** 把 tint 按比例混进 color，让白/黑染层带上壁纸的环境色。 */
    private static int blend(int color, int tint, float ratio) {
        float r = Math.max(0f, Math.min(1f, ratio));
        int red = Math.round(Color.red(color) * (1 - r) + Color.red(tint) * r);
        int green = Math.round(Color.green(color) * (1 - r) + Color.green(tint) * r);
        int blue = Math.round(Color.blue(color) * (1 - r) + Color.blue(tint) * r);
        return Color.rgb(red, green, blue);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
