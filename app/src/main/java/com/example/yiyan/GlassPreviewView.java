package com.example.yiyan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

/**
 * 「个性」页里的液态玻璃实时预览：底下铺一层假壁纸，上面盖 GlassRenderer 渲染的玻璃层，
 * 参数一动就重画，省得每次都要回桌面看效果。
 */
public class GlassPreviewView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private GlassRenderer.Spec spec = new GlassRenderer.Spec();
    private boolean enabled;
    private Bitmap glass;
    private Bitmap wallpaper;
    private int glassWidth;
    private int glassHeight;
    private boolean dirty = true;

    public GlassPreviewView(Context context) {
        this(context, null);
    }

    public GlassPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        textPaint.setTextSize(17 * scaled);
        metaPaint.setTextSize(11 * scaled);
    }

    public void setSpec(GlassRenderer.Spec spec, boolean enabled) {
        this.spec = spec == null ? new GlassRenderer.Spec() : spec;
        this.enabled = enabled;
        this.dirty = true;
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
            float radius = GlassRenderer.cornerPx(getContext(), spec, getResources()
                    .getDisplayMetrics().density);
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
        textPaint.setColor(dark ? 0xFFF7F4FF : 0xFF1B1A20);
        metaPaint.setColor(dark ? 0xFFD7C8FF : 0xFF4F378B);
        canvas.drawText("知者不惑。", pad, pad + textPaint.getTextSize(), textPaint);
        canvas.drawText("— 孔子 · 《论语》 · 液态玻璃", pad,
                pad + textPaint.getTextSize() + metaPaint.getTextSize() * 2.1f, metaPaint);
    }

    private void drawBackdrop(Canvas canvas, float w, float h) {
        if (wallpaper != null && !wallpaper.isRecycled()) {
            // 用真实壁纸做底：居中裁剪，和桌面上的观感一致
            float scale = Math.max(w / wallpaper.getWidth(), h / wallpaper.getHeight());
            float dw = wallpaper.getWidth() * scale;
            float dh = wallpaper.getHeight() * scale;
            RectF dst = new RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            canvas.drawBitmap(wallpaper, null, dst, backdropPaint);
            return;
        }
        // 读不到壁纸时，用壁纸取色搭一个同色系的底，不写死颜色
        backdropPaint.setShader(new LinearGradient(0, 0, w, h,
                spec.tint2, spec.tint3, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, backdropPaint);
        backdropPaint.setShader(new RadialGradient(w * 0.18f, h * 0.85f, Math.max(w, h) * 0.55f,
                spec.tint, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, backdropPaint);
        backdropPaint.setShader(new RadialGradient(w * 0.9f, h * 0.1f, Math.max(w, h) * 0.6f,
                spec.tint2, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, backdropPaint);
    }

    /** 设置真实壁纸（可能为 null：系统不允许读）。 */
    public void setWallpaper(Bitmap bitmap) {
        if (bitmap == wallpaper) return;
        wallpaper = bitmap;
        dirty = true;
        invalidate();
    }

    private void ensureGlass(int w, int h) {
        if (!dirty && glass != null && glassWidth == w && glassHeight == h) return;
        Bitmap previous = glass;
        glass = GlassRenderer.render(getContext(), w, h, spec, wallpaper);
        glassWidth = w;
        glassHeight = h;
        dirty = false;
        if (previous != null && previous != glass && !previous.isRecycled()) previous.recycle();
    }
}
