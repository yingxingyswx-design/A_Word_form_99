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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;

/**
 * 小组件的玻璃材质。桌面小组件拿不到身后的像素（RemoteViews 没有 RenderEffect /
 * RuntimeShader），所以只能「自己把壁纸算一遍」，再按 FolkPatch 的光学层画出来：
 *
 * 毛玻璃（默认）——读真实壁纸 → 按小组件比例中心裁剪 → 缩样 → 两遍盒式模糊（≈高斯）
 * → 逐像素走透镜管线：圆角矩形 SDF 求到边距离与法线，边缘沿法线向内采样做折射，
 * 边缘 RGB 微分离做色散，提饱和，只把暗部往色调提（自适应薄雾），再叠 rim line、
 * 定向高光与背光侧内阴影，SDF 覆盖率当 alpha。读数就是一块真的磨砂透镜，不是白纱。
 *
 * 液态玻璃——Apple `.clear` 那种几乎全透明的玻璃：面板本身不上壁纸，桌面壁纸直接
 * 透出来（天然对齐、无需权限），质感全部由 squircle 圆角、双层亮边、镜面高光、
 * 内侧折痕和底部内发光承担。因为面板是透明的，它不依赖壁纸位图。
 *
 * 亮度 / 透明度映射参照 FolkPatch 的 navBarGlassEffect：浅色更亮、深色更收敛，
 * 圆角用连续曲率（超椭圆 n=4）而不是普通 roundRect。
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

    public static final int DEFAULT_BLUR = 62;
    public static final int DEFAULT_DENSITY = 45;
    public static final int DEFAULT_CORNER = 28;
    public static final int DEFAULT_HIGHLIGHT = 70;

    private static final String TAG = "99YIYAN_LOG";
    private static final long MAX_PIXELS = 2_600_000L;
    /** 毛玻璃固定雾化程度（不跟滑块）。 */
    private static final float FIXED_BLUR = 0.62f;
    private static final float FROST_MAX_WIDTH = 760f;
    private static final float LIQUID_MAX_WIDTH = 900f;
    private static final double SQUIRCLE_N = 4.0;

    private static final int FALLBACK_PRIMARY = 0xFF6C5CE7;
    private static final int FALLBACK_SECONDARY = 0xFF8E7BEF;
    private static final int FALLBACK_TERTIARY = 0xFFB39DDB;

    private static int[] cachedColors;
    private static long cachedColorsAt;

    private GlassRenderer() {
    }

    public static final class Spec {
        public String material = MATERIAL_FROST;
        public float blur = DEFAULT_BLUR / 100f;
        public float density = DEFAULT_DENSITY / 100f;
        public float cornerDp = DEFAULT_CORNER;
        public float highlight = DEFAULT_HIGHLIGHT / 100f;
        public boolean dark;
        public int tint = FALLBACK_PRIMARY;
        public int tint2 = FALLBACK_SECONDARY;
        public int tint3 = FALLBACK_TERTIARY;
        /** 光源方位 0–1，换句子时轻微变化，高光会「流动」。 */
        public float light = 0.35f;

        public boolean isFrost() {
            return MATERIAL_FROST.equals(material);
        }

        /** 渲染在后台线程做，参数要先拷一份，免得拖滑块时被改到一半。 */
        public Spec copy() {
            Spec s = new Spec();
            s.material = material;
            s.blur = blur;
            s.density = density;
            s.cornerDp = cornerDp;
            s.highlight = highlight;
            s.dark = dark;
            s.tint = tint;
            s.tint2 = tint2;
            s.tint3 = tint3;
            s.light = light;
            return s;
        }
    }

    public static boolean enabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public static Spec spec(Context context, SharedPreferences prefs) {
        Spec spec = new Spec();
        spec.material = prefs.getString(KEY_MATERIAL, MATERIAL_FROST);
        spec.blur = clamp01(prefs.getInt(KEY_BLUR, DEFAULT_BLUR) / 100f);
        spec.density = clamp01(prefs.getInt(KEY_DENSITY, DEFAULT_DENSITY) / 100f);
        spec.highlight = clamp01(prefs.getInt(KEY_HIGHLIGHT, DEFAULT_HIGHLIGHT) / 100f);
        spec.cornerDp = Math.max(0, Math.min(48, prefs.getInt(KEY_CORNER, DEFAULT_CORNER)));
        spec.dark = isDark(context, prefs);
        int[] colors = wallpaperColors(context);
        spec.tint = colors[0];
        spec.tint2 = colors[1];
        spec.tint3 = colors[2];
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

    /** 透明 GIF 帧需要垫一层不透明底色时用：取毛玻璃主体的近似色。 */
    public static int solidFill(Spec spec) {
        int tone = spec.dark ? 0xFF0B0912 : 0xFFF7F8FC;
        return blend(tone, spec.tint, 0.30f);
    }

    public static int cornerPx(Context context, Spec spec, float density) {
        return Math.round(spec.cornerDp * density);
    }

    /** 壁纸三色（无需权限，Android 8.1+）。读不到壁纸时用它兜底。 */
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
     * @param wallpaper 真实壁纸；毛玻璃用它做模糊底，液态玻璃不需要（传 null 即可）。
     */
    public static Bitmap render(Context context, int widthPx, int heightPx, Spec spec,
                                Bitmap wallpaper) {
        return render(context, widthPx, heightPx, spec, wallpaper, null);
    }

    /**
     * @param crop 壁纸里要采样的区域（对齐到小组件在屏幕上的位置）；null = 居中裁剪。
     */
    public static Bitmap render(Context context, int widthPx, int heightPx, Spec spec,
                                Bitmap wallpaper, Rect crop) {
        float density = context.getResources().getDisplayMetrics().density;
        if (spec == null) spec = new Spec();
        // 只有一种材质：液态玻璃（透镜管线），雾化程度跟滑块
        if (wallpaper != null && !wallpaper.isRecycled()) {
            return renderLens(wallpaper, widthPx, heightPx, spec, density, false, crop);
        }
        return renderLiquidFallback(widthPx, heightPx, spec, density);
    }

    // ---- 透镜管线：中心裁剪 → 模糊 → 逐像素折射 ----

    /**
     * @param liquid true = 液态玻璃：模糊更轻、透明度更高、边缘折射更长，壁纸结构仍看得见；
     *               false = 毛玻璃：重模糊 + 乳白薄雾 + 磨砂颗粒，面板不透明。
     */
    private static Bitmap renderLens(Bitmap wallpaper, int widthPx, int heightPx, Spec spec,
                                     float density, boolean liquid, Rect crop) {
        int w = Math.max(1, widthPx);
        int h = Math.max(1, heightPx);
        long area = (long) w * h;
        if (area > MAX_PIXELS) {
            double scale = Math.sqrt(MAX_PIXELS / (double) area);
            w = Math.max(1, (int) Math.round(w * scale));
            h = Math.max(1, (int) Math.round(h * scale));
        }

        // 模糊在小分辨率上做，再交给 ImageView 放大：快，而且放大本身就再糊一层
        // 这台 SD835 上逐像素透镜大约 10µs/px：全分辨率（1000x600）要十几秒，
        // 所以按 820px 上限算，再交给 ImageView 放大——模糊本身会吃掉放大的痕迹。
        float targetScale = Math.min(1f, 820f / Math.max(w, h));
        int cw = Math.max(64, Math.round(w * targetScale));
        int ch = Math.max(36, Math.round(h * targetScale));
        float scale = cw / (float) w;

        int[] content = new int[cw * ch];
        if (wallpaper != null && !wallpaper.isRecycled()) {
            Bitmap sampled = sampleCenter(wallpaper, cw, ch, crop);
            sampled.getPixels(content, 0, cw, 0, 0, cw, ch);
            sampled.recycle();
        } else {
            Log.w(TAG, "没有壁纸位图，退回壁纸取色（不会模糊）");
            fillColorField(content, cw, ch, spec);
        }

        // 液态玻璃只轻轻糊一层，保留壁纸的结构；毛玻璃要糊到看不出细节
        float blurBase = liquid ? (1.0f + spec.blur * 4.5f) : (2.5f + spec.blur * 11f);
        int blurRadius = Math.max(1, Math.round(blurBase * scale * 2f));
        boxBlur(content, cw, ch, blurRadius);
        if (!liquid) boxBlur(content, cw, ch, Math.max(1, blurRadius / 2));

        float radius = Math.min(Math.min(w, h) / 2f, spec.cornerDp * density) * scale;
        int[] pixels = lens(content, cw, ch, radius, spec, scale, liquid);
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, cw, 0, 0, cw, ch);
        return out;
    }

    /**
     * 逐像素透镜。顺序照 iOS 合成：
     * SDF → 折射位移 → 色散 → 提饱和 → 自适应薄雾 → 染色 → rim line / 高光 / 内阴影。
     */
    private static int[] lens(int[] content, int cw, int ch, float radius, Spec spec,
                              float scale, boolean liquid) {
        int[] pixels = new int[cw * ch];
        float cx = cw / 2f;
        float cy = ch / 2f;
        float halfW = cw / 2f - 0.5f;
        float halfH = ch / 2f - 0.5f;
        float corner = Math.max(0f, Math.min(radius, Math.min(halfW, halfH)));
        float lensW = Math.max(3f, corner * (liquid ? 1.9f : 1.35f));
        float reflW = Math.max(2f, Math.min(halfW, halfH) * 0.55f);
        float lens = lensW * (liquid ? 1.05f : 0.85f);
        float guardW = Math.max(2f, reflW * 0.18f);
        float epsilon = Math.max(0.75f, lensW * 0.3f);

        boolean dark = spec.dark;
        float toneR = dark ? 12f : 255f;
        float toneG = dark ? 10f : 255f;
        float toneB = dark ? 20f : 255f;
        float tintR = Color.red(spec.tint);
        float tintG = Color.green(spec.tint);
        float tintB = Color.blue(spec.tint);

        // 毛玻璃要「乳白」，液态玻璃要「通透」：薄雾、饱和度、高光强度都分两套
        float milk = liquid
                ? 0.01f + spec.density * 0.02f
                : (dark ? 0.02f : 0.02f) + spec.density * 0.07f;
        float tintMix = (liquid ? 0.02f : 0.04f) + spec.density * (liquid ? 0.05f : 0.10f);
        float adaptK = liquid
                ? (dark ? 0.05f : 0.06f) + spec.density * 0.08f
                : (dark ? 0.08f : 0.10f) + spec.density * 0.18f;
        float sat = liquid ? 1.28f + spec.density * 0.12f : 1.22f + spec.density * 0.12f;
        float sheenAmount = liquid ? 0.10f + spec.highlight * 0.12f
                : 0.05f + spec.highlight * 0.05f;
        float specAmount = spec.highlight * (liquid ? 0.95f : 0.65f);
        // 液态玻璃是半透明的：桌面上真实壁纸仍从下面透上来，边缘折射因此更像「玻璃板」
        float alphaScale = liquid ? (dark ? 0.80f : 0.68f) : 1f;
        float lightX = -0.55f + 0.5f * spec.light;
        float lightY = -0.72f;
        float rimWidth = Math.max(1.5f, 2.0f * scale);

        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                float px = x + 0.5f - cx;
                float py = y + 0.5f - cy;
                float d = sdRoundRect(px, py, halfW, halfH, corner);
                float aa = clamp01(0.5f - d);
                if (aa <= 0f) {
                    pixels[y * cw + x] = 0;
                    continue;
                }

                float rim = 1f - clamp01(-d / lensW);
                float bend = rim * rim;

                float gx = sdRoundRect(px + epsilon, py, halfW, halfH, corner)
                        - sdRoundRect(px - epsilon, py, halfW, halfH, corner);
                float gy = sdRoundRect(px, py + epsilon, halfW, halfH, corner)
                        - sdRoundRect(px, py - epsilon, halfW, halfH, corner);
                float gl = (float) Math.sqrt(gx * gx + gy * gy) + 1e-5f;
                float nx = gx / gl;
                float ny = gy / gl;

                float guard = smoothstep(0f, guardW, -d);
                float disp = bend * lens * guard;
                float ux = cx + px - nx * disp;
                float uy = cy + py - ny * disp;

                float ca = bend * lens * (liquid ? 0.035f : 0.02f);
                int cRed = sampleBilinear(content, cw, ch, ux + nx * ca, uy + ny * ca);
                int cGreen = sampleBilinear(content, cw, ch, ux, uy);
                int cBlue = sampleBilinear(content, cw, ch, ux - nx * ca, uy - ny * ca);
                float rr = Color.red(cRed);
                float gg = Color.green(cGreen);
                float bb = Color.blue(cBlue);

                float lum = (0.2126f * rr + 0.7152f * gg + 0.0722f * bb) / 255f;
                rr = 255f * (lum + (rr / 255f - lum) * sat);
                gg = 255f * (lum + (gg / 255f - lum) * sat);
                bb = 255f * (lum + (bb / 255f - lum) * sat);

                // 自适应薄雾：只把暗部往色调提，亮部保留，玻璃才是通透的
                float adapt = Math.min(0.55f, clamp01((1f - lum) * adaptK));
                rr = rr + (toneR - rr) * adapt;
                gg = gg + (toneG - gg) * adapt;
                bb = bb + (toneB - bb) * adapt;

                rr = rr + (toneR - rr) * milk;
                gg = gg + (toneG - gg) * milk;
                bb = bb + (toneB - bb) * milk;

                rr = rr + (tintR - rr) * tintMix;
                gg = gg + (tintG - gg) * tintMix;
                bb = bb + (tintB - bb) * tintMix;

                float rimLine = 1f - smoothstep(0f, rimWidth, -d);
                float diag = (x / (float) cw + y / (float) ch) * 0.5f;
                float sheen = (float) Math.exp(-Math.pow((diag - 0.32f) * 3f, 2)) * sheenAmount;
                float facing = clamp01(nx * lightX + ny * lightY);
                float specular = (float) Math.pow(facing * bend, 3f) * specAmount;

                float shade = bend * (1f - facing) * (liquid ? 0.22f : 0.16f);
                float rimBoost = liquid ? 1.15f : 0.85f;
                float add = (rimLine * rimBoost + sheen + specular) * 255f;
                if (liquid) {
                    // 顶部那一道迎光更亮，玻璃才有「厚度方向」
                    float top = clamp01(1f - (y / (float) ch) * 2.4f);
                    add += top * top * (0.05f + spec.highlight * 0.08f) * 255f;
                }
                float sub = shade * 255f * 0.45f;
                rr = clamp255(rr + add - sub);
                gg = clamp255(gg + add - sub);
                bb = clamp255(bb + add - sub);

                pixels[y * cw + x] = Color.argb((int) (aa * alphaScale * 255f),
                        (int) rr, (int) gg, (int) bb);
            }
        }
        return pixels;
    }

    // ---- 液态玻璃兜底：读不到壁纸时只画透明光学层 ----

    private static Bitmap renderLiquidFallback(int widthPx, int heightPx, Spec spec, float density) {
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
        float edge = Math.max(1.2f, 1.2f * density);

        canvas.save();
        canvas.clipPath(shape);
        drawLiquidBody(canvas, rect, w, h, spec, density);
        canvas.restore();

        drawLiquidEdge(canvas, shape, rect, w, h, edge, spec, density);
        return out;
    }

    /** 几乎看不见的染色 + 顶部反光 + 底部内发光；全透明是重点。 */
    private static void drawLiquidBody(Canvas canvas, RectF rect, int w, int h, Spec spec,
                                       float density) {
        int tone = spec.dark ? 0xFF0B0912 : 0xFFFFFFFF;
        int wash = blend(tone, spec.tint2, spec.dark ? 0.18f : 0.10f);
        float alpha = (spec.dark ? 0.10f : 0.06f) + spec.density * (spec.dark ? 0.14f : 0.10f);

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setShader(new LinearGradient(0, 0, w * 0.55f, h,
                withAlpha(wash, alpha), withAlpha(wash, alpha * 0.35f),
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect, body);


        // 斜上方的高光晕：玻璃「迎光」的那一面
        Paint bloom = new Paint(Paint.ANTI_ALIAS_FLAG);
        bloom.setShader(new RadialGradient(w * (0.10f + 0.20f * spec.light), -h * 0.05f,
                Math.max(w, h) * 0.75f,
                withAlpha(0xFFFFFFFF, 0.05f + spec.highlight * 0.10f),
                withAlpha(0xFFFFFFFF, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, bloom);

        // 底部内发光：给透明玻璃一点厚度
        float glow = (spec.dark ? 0.05f : 0.08f) + spec.highlight * 0.10f;
        float glowH = Math.min(h * 0.35f, 10f * density);
        Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        inner.setShader(new LinearGradient(0, h - glowH, 0, h,
                withAlpha(0xFFFFFFFF, 0f), withAlpha(0xFFFFFFFF, glow),
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, h - glowH, rect.right, h, inner);

        // 顶部竖向薄渐变（FolkPatch：顶部更亮，往下收）
        Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        top.setShader(new LinearGradient(0, 0, 0, Math.min(h * 0.5f, 26f * density),
                withAlpha(0xFFFFFFFF, 0.04f + spec.highlight * 0.07f),
                withAlpha(0xFFFFFFFF, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + Math.min(h * 0.5f, 26f * density), top);

        // 顶部横向镜面高光条
        if (spec.highlight > 0.02f) {
            float thickness = Math.max(1.2f, 1.6f * density);
            float left = w * 0.15f;
            float right = w * 0.85f;
            Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
            specular.setShader(new LinearGradient(left, 0, right, 0,
                    new int[]{0x00FFFFFF,
                            withAlpha(0xFFFFFFFF, 0.30f + 0.40f * spec.highlight),
                            0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(left, 2f * density, right, 2f * density + thickness),
                    thickness / 2f, thickness / 2f, specular);
        }
    }

    /** 双层亮边：外圈迎光、内圈折痕 + 背光侧收边。 */
    private static void drawLiquidEdge(Canvas canvas, Path shape, RectF rect, int w, int h,
                                       float edge, Spec spec, float density) {
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(edge);
        rim.setShader(new LinearGradient(0, 0, w * 0.95f, h * 0.95f,
                new int[]{withAlpha(0xFFFFFFFF, 0.58f + spec.highlight * 0.34f),
                        withAlpha(0xFFFFFFFF, 0.22f + spec.highlight * 0.12f),
                        withAlpha(0xFFFFFFFF, 0.12f)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, rim);

        // 内侧折痕：同一轮廓往里收 1dp，暗色，只在背光侧可见
        canvas.save();
        canvas.translate(0f, edge * 0.8f);
        Paint crease = new Paint(Paint.ANTI_ALIAS_FLAG);
        crease.setStyle(Paint.Style.STROKE);
        crease.setStrokeWidth(edge * 2.2f);
        crease.setShader(new LinearGradient(w, h, 0, 0,
                new int[]{withAlpha(0xFF000000, 0.10f + spec.density * 0.08f),
                        withAlpha(0xFF000000, 0f)},
                new float[]{0f, 0.65f}, Shader.TileMode.CLAMP));
        canvas.drawPath(shape, crease);
        canvas.restore();

        // 背光侧内阴影：让面板看起来是「嵌」在桌面里
        canvas.save();
        canvas.clipPath(shape);
        float shadeH = Math.min(h * 0.30f, 8f * density);
        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setShader(new LinearGradient(0, h, 0, h - shadeH,
                withAlpha(0xFF000000, spec.dark ? 0.16f : 0.10f),
                withAlpha(0xFF000000, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, h - shadeH, rect.right, h, shade);
        canvas.restore();
    }

    // ---- 几何 / 采样 ----

    private static float sdRoundRect(float px, float py, float halfW, float halfH, float r) {
        float qx = Math.abs(px) - halfW + r;
        float qy = Math.abs(py) - halfH + r;
        float mx = Math.max(qx, 0f);
        float my = Math.max(qy, 0f);
        return Math.min(Math.max(qx, qy), 0f) + (float) Math.sqrt(mx * mx + my * my) - r;
    }

    private static int sampleBilinear(int[] src, int w, int h, float x, float y) {
        float fx = Math.max(0f, Math.min(w - 1.001f, x));
        float fy = Math.max(0f, Math.min(h - 1.001f, y));
        int x0 = (int) fx;
        int y0 = (int) fy;
        int x1 = Math.min(w - 1, x0 + 1);
        int y1 = Math.min(h - 1, y0 + 1);
        float tx = fx - x0;
        float ty = fy - y0;
        int c00 = src[y0 * w + x0];
        int c10 = src[y0 * w + x1];
        int c01 = src[y1 * w + x0];
        int c11 = src[y1 * w + x1];
        float r = lerp(lerp(Color.red(c00), Color.red(c10), tx),
                lerp(Color.red(c01), Color.red(c11), tx), ty);
        float g = lerp(lerp(Color.green(c00), Color.green(c10), tx),
                lerp(Color.green(c01), Color.green(c11), tx), ty);
        float b = lerp(lerp(Color.blue(c00), Color.blue(c10), tx),
                lerp(Color.blue(c01), Color.blue(c11), tx), ty);
        return Color.rgb((int) r, (int) g, (int) b);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x <= edge0 ? 0f : 1f;
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp255(float value) {
        return Math.max(0f, Math.min(255f, value));
    }

    /** 取壁纸中心区域，比例对齐小组件；不会返回 / 回收传入的壁纸本身。 */
    private static Bitmap sampleCenter(Bitmap source, int dstWidth, int dstHeight) {
        return sampleCenter(source, dstWidth, dstHeight, null);
    }

    /**
     * @param crop 指定的源区域（壁纸坐标）；null = 按目标比例居中裁剪
     */
    private static Bitmap sampleCenter(Bitmap source, int dstWidth, int dstHeight, Rect crop) {
        int cropWidth;
        int cropHeight;
        int left;
        int top;
        if (crop != null && crop.width() > 0 && crop.height() > 0) {
            cropWidth = Math.min(source.getWidth(), crop.width());
            cropHeight = Math.min(source.getHeight(), crop.height());
            left = Math.max(0, Math.min(source.getWidth() - cropWidth, crop.left));
            top = Math.max(0, Math.min(source.getHeight() - cropHeight, crop.top));
        } else {
            float scale = Math.max(dstWidth / (float) source.getWidth(),
                    dstHeight / (float) source.getHeight());
            cropWidth = Math.min(source.getWidth(), Math.max(1, Math.round(dstWidth / scale)));
            cropHeight = Math.min(source.getHeight(), Math.max(1, Math.round(dstHeight / scale)));
            left = Math.max(0, (source.getWidth() - cropWidth) / 2);
            top = Math.max(0, (source.getHeight() - cropHeight) / 2);
        }

        Bitmap out = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, new Rect(left, top, left + cropWidth, top + cropHeight),
                new Rect(0, 0, dstWidth, dstHeight), paint);
        return out;
    }
    /** 读不到壁纸时的低频色图：壁纸主色 / 次色 / 第三色的柔和分布。 */
    private static void fillColorField(int[] pixels, int sw, int sh, Spec spec) {
        for (int y = 0; y < sh; y++) {
            float fy = y / (float) Math.max(1, sh - 1);
            for (int x = 0; x < sw; x++) {
                float fx = x / (float) Math.max(1, sw - 1);
                int base = blend(spec.tint, spec.tint2, clamp01(fx * 0.8f + fy * 0.2f));
                float blobA = gauss(fx, fy, 0.15f, 0.05f, 0.35f);
                float blobB = gauss(fx, fy, 0.9f, 0.85f, 0.4f);
                int color = blend(base, spec.tint2, blobA * 0.6f);
                color = blend(color, spec.tint3, blobB * 0.6f);
                pixels[y * sw + x] = color;
            }
        }
    }

    private static float gauss(float x, float y, float cx, float cy, float sigma) {
        float dx = x - cx;
        float dy = y - cy;
        return (float) Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma));
    }

    /** 可分离盒式模糊，横竖各一遍；调用两次接近高斯。 */
    private static void boxBlur(int[] pixels, int w, int h, int radius) {
        if (radius <= 0) return;
        int[] temp = new int[pixels.length];
        int window = radius * 2 + 1;

        for (int y = 0; y < h; y++) {
            int row = y * w;
            int r = 0, g = 0, b = 0, a = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = pixels[row + clampIndex(i, w)];
                a += Color.alpha(color);
                r += Color.red(color);
                g += Color.green(color);
                b += Color.blue(color);
            }
            for (int x = 0; x < w; x++) {
                temp[row + x] = Color.argb(a / window, r / window, g / window, b / window);
                int out = pixels[row + clampIndex(x - radius, w)];
                int in = pixels[row + clampIndex(x + radius + 1, w)];
                a += Color.alpha(in) - Color.alpha(out);
                r += Color.red(in) - Color.red(out);
                g += Color.green(in) - Color.green(out);
                b += Color.blue(in) - Color.blue(out);
            }
        }

        for (int x = 0; x < w; x++) {
            int r = 0, g = 0, b = 0, a = 0;
            for (int i = -radius; i <= radius; i++) {
                int color = temp[clampIndex(i, h) * w + x];
                a += Color.alpha(color);
                r += Color.red(color);
                g += Color.green(color);
                b += Color.blue(color);
            }
            for (int y = 0; y < h; y++) {
                pixels[y * w + x] = Color.argb(a / window, r / window, g / window, b / window);
                int out = temp[clampIndex(y - radius, h) * w + x];
                int in = temp[clampIndex(y + radius + 1, h) * w + x];
                a += Color.alpha(in) - Color.alpha(out);
                r += Color.red(in) - Color.red(out);
                g += Color.green(in) - Color.green(out);
                b += Color.blue(in) - Color.blue(out);
            }
        }
    }

    private static int clampIndex(int index, int size) {
        if (index < 0) return 0;
        if (index >= size) return size - 1;
        return index;
    }

    // ---- 圆角 ----

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
