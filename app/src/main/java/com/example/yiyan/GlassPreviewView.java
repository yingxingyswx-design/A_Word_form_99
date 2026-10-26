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
 * 「个性」页的玻璃实时预览。玻璃本身是透明底（桌面上壁纸是实时透出来的），
 * 这里在下面垫一块示意底，用来观察反光条、边框、内发光和磨砂的效果。
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
    }

    /** 示意底：桌面上这块位置是实时壁纸，这里用主题色铺一个柔和渐变来观察光学层。 */
    private void drawBackdrop(Canvas canvas, float w, float h) {
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

    private void ensureGlass(int w, int h) {
        if (!dirty && glass != null && glassWidth == w && glassHeight == h) return;
        Bitmap previous = glass;
        glass = GlassRenderer.render(getContext(), w, h, spec);
        glassWidth = w;
        glassHeight = h;
        dirty = false;
        if (previous != null && previous != glass && !previous.isRecycled()) previous.recycle();
    }
}
