package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 「个性」页的玻璃实时预览。
 *
 * 桌面上的小组件是「透明层 + 真实壁纸」，所以预览也照这个来：先铺一张真实壁纸当桌面，
 * 再把 {@link GlassRenderer} 算出来的同一张玻璃位图叠上去——毛玻璃是模糊过的壁纸透镜，
 * 液态玻璃是几乎全透明的光学层，壁纸从下面透出来。所见即桌面。
 *
 * 读不到壁纸时（没给「所有文件访问」）退回主题色渐变，并直接在预览里提示。
 */
public class GlassPreviewView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint warnPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF rect = new RectF();

    private GlassRenderer.Spec spec = new GlassRenderer.Spec();
    private boolean enabled;
    private Bitmap glass;
    private int glassWidth;
    private int glassHeight;
    private boolean dirty = true;
    private boolean rendering;
    private int pendingWidth;
    private int pendingHeight;

    private Bitmap wallpaper;
    private boolean wallpaperLoaded;
    private boolean loadingWallpaper;

    public GlassPreviewView(Context context) {
        this(context, null);
    }

    public GlassPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        textPaint.setTextSize(17 * scaled);
        metaPaint.setTextSize(11 * scaled);
        warnPaint.setTextSize(10 * scaled);
        cropPaint.setFilterBitmap(true);
    }

    public void setSpec(GlassRenderer.Spec spec, boolean enabled) {
        this.spec = spec == null ? new GlassRenderer.Spec() : spec;
        this.enabled = enabled;
        this.dirty = true;
        if (enabled) loadWallpaper();
        invalidate();
    }

    /** 权限刚授予 / 壁纸刚换过时调一下，强制重读。 */
    public void invalidateWallpaper() {
        WallpaperSource.invalidate();
        wallpaperLoaded = false;
        loadWallpaper();
        dirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dirty = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        rect.set(0, 0, w, h);

        drawBackdrop(canvas, w, h);

        if (enabled) {
            ensureGlass((int) w, (int) h);
            if (glass != null) canvas.drawBitmap(glass, null, rect, paint);
        } else {
            float radius = GlassRenderer.cornerPx(getContext(), spec,
                    getResources().getDisplayMetrics().density);
            Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
            card.setColor(0xFFFDF8F3);
            canvas.drawRoundRect(rect, radius, radius, card);
            card.setStyle(Paint.Style.STROKE);
            card.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
            card.setColor(0xFFE8DCCB);
            canvas.drawRoundRect(rect, radius, radius, card);
        }

        float pad = 16 * getResources().getDisplayMetrics().density;
        boolean dark = spec.dark;
        textPaint.setColor(dark ? 0xFFFDFCFF : 0xFF121016);
        metaPaint.setColor(dark ? 0xFFEDE6FF : 0xFF3B3350);
        canvas.drawText("知者不惑。", pad, pad + textPaint.getTextSize(), textPaint);
        canvas.drawText("— 孔子 · 《论语》 · 玻璃预览", pad,
                pad + textPaint.getTextSize() + metaPaint.getTextSize() * 2.1f, metaPaint);

        if (enabled && spec.isFrost() && !hasWallpaper()) {
            warnPaint.setColor(dark ? 0xCCFFD8A8 : 0xCC7A4B00);
            canvas.drawText("未授权读取壁纸：毛玻璃只剩取色（不会模糊）", pad,
                    h - pad, warnPaint);
        }
    }

    /** 桌面模拟：真实壁纸（居中裁剪），读不到时用主题色渐变。 */
    private void drawBackdrop(Canvas canvas, float w, float h) {
        Bitmap source = wallpaper;
        if (source != null && !source.isRecycled()) {
            float scale = Math.max(w / source.getWidth(), h / source.getHeight());
            int cropW = Math.min(source.getWidth(), Math.max(1, Math.round(w / scale)));
            int cropH = Math.min(source.getHeight(), Math.max(1, Math.round(h / scale)));
            int left = Math.max(0, (source.getWidth() - cropW) / 2);
            int top = Math.max(0, (source.getHeight() - cropH) / 2);
            src.set(left, top, left + cropW, top + cropH);
            canvas.drawBitmap(source, src, rect, cropPaint);
            return;
        }

        int primary = getResources().getColor(R.color.md_primary, getContext().getTheme());
        int secondary = getResources().getColor(R.color.md_secondary, getContext().getTheme());

        backdropPaint.setShader(new LinearGradient(0, 0, w, h,
                primary, secondary, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, backdropPaint);

        backdropPaint.setShader(new RadialGradient(w * 0.15f, h * 0.9f, Math.max(w, h) * 0.6f,
                Color.argb(150, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, backdropPaint);

        backdropPaint.setShader(new RadialGradient(w * 0.9f, h * 0.1f, Math.max(w, h) * 0.65f,
                Color.argb(110, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, backdropPaint);
    }

    private boolean hasWallpaper() {
        return wallpaper != null && !wallpaper.isRecycled();
    }

    private void loadWallpaper() {
        if (wallpaperLoaded || loadingWallpaper) return;
        loadingWallpaper = true;
        final Context context = getContext().getApplicationContext();
        new Thread(() -> {
            SharedPreferences prefs = context.getSharedPreferences("settings", 0);
            Bitmap custom = CustomBackground.load(context, prefs);
            Bitmap loaded = custom != null ? custom : WallpaperSource.load(context, 800);
            post(() -> {
                wallpaper = loaded;
                wallpaperLoaded = true;
                loadingWallpaper = false;
                dirty = true;
                invalidate();
            });
        }, "glass-preview-wallpaper").start();
    }

    /**
     * 玻璃位图放到后台线程算：模糊 + 逐像素折射在这台机器上要几百毫秒，
     * 放主线程会把设置页拖到 ANR。算完再换上去，期间继续显示上一张。
     */
    private void ensureGlass(final int w, final int h) {
        if (!dirty && glass != null && glassWidth == w && glassHeight == h) return;
        if (rendering) {
            pendingWidth = w;
            pendingHeight = h;
            return;
        }
        rendering = true;
        final GlassRenderer.Spec snapshot = spec.copy();
        final Bitmap backdrop = snapshot.isFrost() ? wallpaper : null;
        new Thread(() -> {
            final Bitmap bitmap = GlassRenderer.render(getContext(), w, h, snapshot, backdrop);
            post(() -> {
                Bitmap previous = glass;
                glass = bitmap;
                glassWidth = w;
                glassHeight = h;
                dirty = false;
                rendering = false;
                if (previous != null && previous != glass && !previous.isRecycled()) previous.recycle();
                if (pendingWidth > 0) {
                    pendingWidth = 0;
                    dirty = true;
                }
                invalidate();
            });
        }, "glass-preview-render").start();
    }
}
