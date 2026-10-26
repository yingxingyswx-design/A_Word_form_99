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
 * 小组件的玻璃材质，两种可切换：
 *
 * 毛玻璃（FROST）——把真实壁纸裁到小组件比例、缩到很小、做三遍盒式模糊（≈高斯）再放大，
 * 同时降饱和、按浅/深色调压亮度，最后叠磨砂颗粒与边缘光学。桌面上就是一块真正的磨砂玻璃板；
 * 因为模糊半径极大，壁纸对不齐也基本看不出来。
 *
 * 液态玻璃（LIQUID）——Apple `.clear` 那种透明玻璃：面板几乎全透明，桌面壁纸直接透出来
 * （天然对齐、无需取壁纸），质感全部由 squircle 圆角、透镜亮边、内侧折痕、镜面高光承担。
 *
 * 两者共用的边缘光学：连续曲率圆角、迎光侧亮边、折痕、暗侧收边、顶边内阴影、定向高光。
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
        public String material = MATERIAL_FROST;
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

        public boolean isFrost() {
            return !MATERIAL_LIQUID.equals(material);
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
        if (spec.isFrost()) return spec.dark ? 0xFFFDFCFF : 0xFF121016;
        return spec.dark ? 0xFFFDFCFF : 0xFF111015;
    }

    public static int metaColor(Spec spec) {
        return spec.dark ? 0xFFEDE6FF : 0xFF3B3350;
    }

    public static int hintColor(Spec spec) {
        return spec.dark ? 0x99FDFCFF : 0x99413B50;
    }

    /** 透明 GIF 帧需要垫一层不透明底色，取毛玻璃主体色的近似值。 */
    public static int solidFill(Spec spec) {
        int tone = spec.dark ? 0xFF0B0912 : 0xFFF7F8FC;
        return blend(tone, spec.tint, 0.32f);
    }

    public static int cornerPx(Context context, Spec spec, float density) {
        return Math.round(spec.cornerDp * density);
    }

    /** 壁纸三色（无需权限）。 */
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
     * @param wallpaper 真实壁纸；毛玻璃需要它做模糊底，液态玻璃不需要（传 null 即可）。
     */
    public static Bitmap render(Context context, int widthPx, int heightPx, Spec spec,
                                Bitmap wallpaper) {
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.max(1, widthPx);
        int h = Math.max(1, heightPx);
        long pixels = (long) w * h;
        if (pixels > MAX_PIXELS) {
            double scale = Math.sqrt(MAX_PIXELS / (double) pixels);
            w = Math.max(1, (int) Math.round(w * scale));
            h = Math.max(1, (int) Math.round(h * scale));
        }

        float radius = Math.min(Math.min(w, h) / 2f, spec.cornerDp * density);
        // 毛玻璃走高分辨率逐像素透镜管线（自己算折射/色散/rim/highlight），
        // 液态玻璃仍是透明层，交给桌面壁纸透出来。
        if (spec.isFrost()) {
            return renderFrostCpu(wallpaper, w, h, spec, radius);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        RectF rect = new RectF(0, 0, w, h);
        Path shape = squircleRect(rect, radius);
        float edge = Math.max(1.5f, 1.2f * density);

        canvas.save();
        canvas.clipPath(shape);
        drawClear(canvas, rect, w, h, spec);
        drawDepth(canvas, rect, w, h, spec);
        drawSpecular(canvas, rect, w, h, spec);
        drawDither(canvas, w, h, spec);
        canvas.restore();

        drawEdge(canvas, shape, w, h, edge, spec);
        return out;
    }

    // ---- 毛玻璃：逐像素透镜（CPU 版 Liquid Glass 管线） ----

    /**
     * 一块「透镜玻璃」位图，按 iOS 合成的顺序来：
     *
     * 1. 背景：真实壁纸中心裁剪 → 缩样 → 盒式模糊（对应 RenderEffect 的 blur）；
     * 2. 逐像素：圆角矩形 SDF 给出到边的距离与法线，沿法线向内采样做折射，
     *    边缘把背后的内容「折」进来，产生透镜感，而不是一层糊；
     * 3. 边缘 RGB 微分离做色散；提饱和（vibrancy）；只把暗部往白/黑提（自适应薄雾），
     *    所以玻璃是通透的，不是乳白填充；
     * 4. rim line（干净的亮边）+ 定向高光 + 背光侧内阴影；
     * 5. SDF 覆盖率当 alpha，边缘自带抗锯齿。
     *
     * 参考实现（Kotlin + AGSL）：himanshu-lal4/react-native-liquid-glassmorphism。
     */
    private static Bitmap renderFrostCpu(Bitmap wallpaper, int w, int h, Spec spec, float radius) {
        float targetScale = Math.min(1f, 860f / Math.max(w, h));
        int cw = Math.max(64, Math.round(w * targetScale));
        int ch = Math.max(36, Math.round(h * targetScale));
        float scale = cw / (float) w;

        int[] content = new int[cw * ch];
        if (wallpaper != null && !wallpaper.isRecycled()) {
            Bitmap crop = centerCrop(wallpaper, w, h);
            Bitmap small = Bitmap.createScaledBitmap(crop, cw, ch, true);
            if (small != crop) crop.recycle();
            small.getPixels(content, 0, cw, 0, 0, cw, ch);
            small.recycle();
        } else {
            fillColorField(content, cw, ch, spec);
        }
        int blurRadius = Math.max(1, Math.round((2.5f + spec.blur * 11f) * scale * 2f));
        boxBlur(content, cw, ch, blurRadius);
        boxBlur(content, cw, ch, Math.max(1, blurRadius / 2));

        int[] pixels = new int[cw * ch];
        float cx = cw / 2f;
        float cy = ch / 2f;
        float halfW = cw / 2f - 0.5f;
        float halfH = ch / 2f - 0.5f;
        float corner = Math.max(0f, Math.min(radius * scale, Math.min(halfW, halfH)));
        float lensW = Math.max(3f, corner * 1.35f);
        float reflW = Math.max(2f, Math.min(halfW, halfH) * 0.55f);
        float lens = lensW * 0.85f;
        float guardW = Math.max(2f, reflW * 0.18f);
        float epsilon = Math.max(0.75f, lensW * 0.3f);

        boolean dark = spec.dark;
        float toneR = dark ? 10f : 255f;
        float toneG = dark ? 8f : 255f;
        float toneB = dark ? 16f : 255f;
        float tintR = Color.red(spec.tint);
        float tintG = Color.green(spec.tint);
        float tintB = Color.blue(spec.tint);
        float tintMix = 0.05f + spec.density * 0.09f;
        float lift = dark ? 0.06f : 0.05f;
        float adaptK = (dark ? 0.28f : 0.40f) + spec.density * 0.26f;
        float sat = 1.20f + spec.density * 0.18f;
        float sheenAmount = 0.05f + spec.highlight * 0.05f;
        float specAmount = spec.highlight * 0.6f;
        float lightX = -0.55f + 0.5f * spec.light;
        float lightY = -0.72f;

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

                float ca = bend * lens * 0.02f;
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

                float adapt = Math.min(0.5f, clamp01(lift + (1f - lum) * adaptK));
                rr = rr + (toneR - rr) * adapt;
                gg = gg + (toneG - gg) * adapt;
                bb = bb + (toneB - bb) * adapt;

                rr = rr + (tintR - rr) * tintMix;
                gg = gg + (tintG - gg) * tintMix;
                bb = bb + (tintB - bb) * tintMix;

                float rimLine = 1f - smoothstep(0f, Math.max(1.5f, 2.2f * scale), -d);
                float diag = (x / (float) cw + y / (float) ch) * 0.5f;
                float sheen = (float) Math.exp(-Math.pow((diag - 0.32f) * 3f, 2)) * sheenAmount;
                float facing = clamp01(nx * lightX + ny * lightY);
                float specular = (float) Math.pow(facing * bend, 3f) * specAmount;

                float shade = bend * (1f - facing) * 0.16f;
                float add = (rimLine * 0.55f + sheen + specular) * 255f;
                float sub = shade * 255f * 0.45f;
                rr = clamp255(rr + add - sub);
                gg = clamp255(gg + add - sub);
                bb = clamp255(bb + add - sub);

                pixels[y * cw + x] = Color.argb((int) (aa * 255f), (int) rr, (int) gg, (int) bb);
            }
        }

        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, cw, 0, 0, cw, ch);
        return out;
    }

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

    /** 取壁纸中心区域，比例对齐小组件。 */
    private static Bitmap centerCrop(Bitmap source, int dstWidth, int dstHeight) {
        float scale = Math.max(dstWidth / (float) source.getWidth(),
                dstHeight / (float) source.getHeight());
        int cropWidth = Math.min(source.getWidth(), Math.max(1, Math.round(dstWidth / scale)));
        int cropHeight = Math.min(source.getHeight(), Math.max(1, Math.round(dstHeight / scale)));
        int left = Math.max(0, (source.getWidth() - cropWidth) / 2);
        int top = Math.max(0, (source.getHeight() - cropHeight) / 2);
        return Bitmap.createBitmap(source, left, top, cropWidth, cropHeight);
    }

    /** 读不到壁纸时的低频色图：壁纸主色/次色/第三色的柔和分布。 */
    private static void fillColorField(int[] pixels, int sw, int sh, Spec spec) {
        int primary = spec.tint;
        int secondary = spec.tint2;
        int tertiary = spec.tint3;
        for (int y = 0; y < sh; y++) {
            float fy = y / (float) Math.max(1, sh - 1);
            for (int x = 0; x < sw; x++) {
                float fx = x / (float) Math.max(1, sw - 1);
                int base = blend(primary, secondary, clamp01(fx * 0.8f + fy * 0.2f));
                float blobA = gauss(fx, fy, 0.15f, 0.05f, 0.35f);
                float blobB = gauss(fx, fy, 0.9f, 0.85f, 0.4f);
                int color = blend(base, secondary, blobA * 0.6f);
                color = blend(color, tertiary, blobB * 0.6f);
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
                int outIndex = row + clampIndex(x - radius, w);
                int inIndex = row + clampIndex(x + radius + 1, w);
                int out = pixels[outIndex];
                int in = pixels[inIndex];
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


    // ---- 液态玻璃：几乎全透明，只做染色与明度微调 ----

    private static void drawClear(Canvas canvas, RectF rect, int w, int h, Spec spec) {
        int tone = spec.dark ? 0xFF0B0912 : 0xFFFFFFFF;
        int scrimColor = blend(tone, spec.tint, spec.dark ? 0.30f : 0.26f);
        float scrimAlpha = (spec.dark ? 0.16f : 0.20f) + spec.blur * 0.26f
                + spec.density * 0.14f;

        Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        scrim.setShader(new LinearGradient(0, 0, w * 0.4f, h,
                withAlpha(scrimColor, scrimAlpha * 0.85f),
                withAlpha(scrimColor, scrimAlpha), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, scrim);

        Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tint.setShader(new RadialGradient(w * (0.15f + 0.2f * spec.light), h * 0.1f,
                Math.max(w, h) * 0.7f, withAlpha(spec.tint2, 0.10f + spec.density * 0.10f),
                withAlpha(spec.tint2, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect, tint);
    }

    // ---- 厚度与高光（两种材质共用） ----

    private static void drawDepth(Canvas canvas, RectF rect, int w, int h, Spec spec) {
        float depth = Math.max(6f, Math.min(h * 0.28f, 9f + spec.blur * 26f));
        int shade = spec.dark ? 0xFF000000 : 0xFF241E33;

        Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        top.setShader(new LinearGradient(0, 0, 0, depth,
                withAlpha(shade, (spec.isFrost() ? 0.18f : 0.12f) + spec.density * 0.08f),
                withAlpha(shade, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + depth, top);

        Paint bottom = new Paint(Paint.ANTI_ALIAS_FLAG);
        bottom.setShader(new LinearGradient(0, h, 0, h - depth,
                withAlpha(0xFFFFFFFF, 0.10f + spec.highlight * 0.10f),
                withAlpha(0xFFFFFFFF, 0f), Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, h - depth, rect.right, h, bottom);
    }

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

        Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(1.6f);
        arc.setShader(new LinearGradient(w * 0.02f, 0, w * 0.55f, 0,
                withAlpha(0xFFFFFFFF, 0.45f * spec.highlight),
                withAlpha(0xFFFFFFFF, 0f), Shader.TileMode.CLAMP));
        canvas.drawLine(w * 0.03f, 1.5f, w * 0.55f, 1.5f, arc);
    }

    private static void drawEdge(Canvas canvas, Path shape, int w, int h, float edge, Spec spec) {
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(edge);
        rim.setShader(litShader(w, h, 0xFFFFFFFF, 0.38f + spec.highlight * 0.42f));
        canvas.drawPath(shape, rim);

        Paint crease = new Paint(Paint.ANTI_ALIAS_FLAG);
        crease.setStyle(Paint.Style.STROKE);
        crease.setStrokeWidth(edge * 2.4f);
        crease.setShader(litShader(w, h, 0xFF000000, 0.05f + spec.density * 0.05f));
        canvas.drawPath(shape, crease);

        Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        shade.setStyle(Paint.Style.STROKE);
        shade.setStrokeWidth(edge);
        shade.setShader(bottomShader(w, h, 0xFF000000, 0.20f));
        canvas.drawPath(shape, shade);
    }

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

    /** 磨砂颗粒 + 抖动：压掉色带，也让玻璃更「磨」。 */
    private static void drawDither(Canvas canvas, int w, int h, Spec spec) {
        java.util.Random random = new java.util.Random(20251102L);
        float alpha = spec.isFrost() ? 0.05f : 0.02f;
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(withAlpha(spec.dark ? 0xFFFFFFFF : 0xFF000000, alpha));
        int count = Math.max(80, (w * h) / 6000);
        for (int i = 0; i < count; i++) {
            float x = random.nextFloat() * w;
            float y = random.nextFloat() * h;
            canvas.drawCircle(x, y, 0.6f + random.nextFloat() * 0.9f, dot);
        }
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
