package com.example.yiyan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 自定义布局的预览画布：模拟小组件区域，文字块与图片块都可以拖动。
 *
 * 位置以「相对内容区的分数」保存（0–1），小组件按自己的实际尺寸换算成 padding。
 * 这样同一套坐标在 2x2 / 4x2 等不同尺寸的小组件上都能按比例还原。
 */
public class LayoutCanvasView extends View {

    private static final int TARGET_NONE = 0;
    private static final int TARGET_TEXT = 1;
    private static final int TARGET_IMAGE = 2;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF widgetRect = new RectF();
    private final RectF contentRect = new RectF();
    private final RectF imageRect = new RectF();
    private final RectF textRect = new RectF();

    private String quoteText = "知者不惑。";
    private String metaText = "— 孔子 · 《论语》 · 原文";
    private Bitmap image;
    private boolean boxEnabled;

    private float textX = 0.06f, textY = 0.06f;
    private float imageX = 0.58f, imageY = 0.18f;

    private int dragTarget = TARGET_NONE;
    private float dragOffsetX, dragOffsetY;

    public LayoutCanvasView(Context context) {
        this(context, null);
    }

    public LayoutCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;

        textPaint.setColor(getResources().getColor(R.color.ink, context.getTheme()));
        textPaint.setTextSize(16 * scaled);
        metaPaint.setColor(getResources().getColor(R.color.accent, context.getTheme()));
        metaPaint.setTextSize(11 * scaled);

        backgroundPaint.setColor(getResources().getColor(R.color.paper, context.getTheme()));
        boxFillPaint.setColor(0x1FFFFFFF);
        boxStrokePaint.setStyle(Paint.Style.STROKE);
        boxStrokePaint.setStrokeWidth(dp(2));
        boxStrokePaint.setColor(0x806750A4);
        dashedPaint.setStyle(Paint.Style.STROKE);
        dashedPaint.setStrokeWidth(dp(1.5f));
        dashedPaint.setColor(0x996750A4);
        dashedPaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(5)}, 0));
        imagePaint.setFilterBitmap(true);

        setPadding((int) dp(16), (int) dp(16), (int) dp(16), (int) dp(16));
    }

    public void setContent(String text, String meta, Bitmap bitmap) {
        if (text != null && !text.isEmpty()) quoteText = text;
        if (meta != null && !meta.isEmpty()) metaText = meta;
        image = bitmap;
        invalidate();
    }

    public void setBoxEnabled(boolean enabled) {
        boxEnabled = enabled;
        invalidate();
    }

    public boolean isBoxEnabled() {
        return boxEnabled;
    }

    public void setPositions(float tx, float ty, float ix, float iy) {
        textX = clamp01(tx);
        textY = clamp01(ty);
        imageX = clamp01(ix);
        imageY = clamp01(iy);
        invalidate();
    }

    public float getTextX() {
        return textX;
    }

    public float getTextY() {
        return textY;
    }

    public float getImageX() {
        return imageX;
    }

    public float getImageY() {
        return imageY;
    }

    public void resetPositions() {
        setPositions(0.06f, 0.06f, 0.58f, 0.18f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        widgetRect.set(getPaddingLeft(), getPaddingTop(),
                getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
        float radius = dp(18);
        canvas.drawRoundRect(widgetRect, radius, radius, backgroundPaint);

        float inset = dp(14);
        contentRect.set(widgetRect.left + inset, widgetRect.top + inset,
                widgetRect.right - inset, widgetRect.bottom - inset);

        drawTextBlock(canvas);
        drawImageBlock(canvas);
    }

    private void drawTextBlock(Canvas canvas) {
        float left = contentRect.left + textX * contentRect.width();
        float top = contentRect.top + textY * contentRect.height();
        int available = Math.max((int) dp(40),
                (int) (contentRect.right - left - dp(6)));

        StaticLayout layout = buildLayout(quoteText, textPaint, available);
        StaticLayout metaLayout = buildLayout(metaText, metaPaint, available);

        float blockHeight = layout.getHeight() + dp(6) + metaLayout.getHeight();
        textRect.set(left, top, left + available, top + blockHeight);

        if (boxEnabled) {
            RectF box = new RectF(textRect);
            box.inset(-dp(6), -dp(6));
            canvas.drawRoundRect(box, dp(10), dp(10), boxFillPaint);
            canvas.drawRoundRect(box, dp(10), dp(10), boxStrokePaint);
        }

        canvas.save();
        canvas.translate(left, top);
        layout.draw(canvas);
        canvas.translate(0, layout.getHeight() + dp(6));
        metaLayout.draw(canvas);
        canvas.restore();

        canvas.drawRoundRect(textRect, dp(8), dp(8), dashedPaint);
    }

    private void drawImageBlock(Canvas canvas) {
        float size = dp(78);
        float left = contentRect.left + imageX * contentRect.width();
        float top = contentRect.top + imageY * contentRect.height();
        left = Math.min(left, contentRect.right - size);
        top = Math.min(top, contentRect.bottom - size);
        imageRect.set(left, top, left + size, top + size);

        canvas.drawRect(imageRect, dashedPaint);
        if (image != null && !image.isRecycled()) {
            drawCenterCrop(canvas, image, imageRect);
        } else {
            Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
            placeholder.setColor(0x226750A4);
            canvas.drawRect(imageRect, placeholder);
            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(0xAA6750A4);
            label.setTextSize(dp(12));
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("图片", imageRect.centerX(),
                    imageRect.centerY() + dp(4), label);
        }
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF destination) {
        float scale = Math.max(destination.width() / bitmap.getWidth(),
                destination.height() / bitmap.getHeight());
        float sourceWidth = destination.width() / scale;
        float sourceHeight = destination.height() / scale;
        float sourceLeft = (bitmap.getWidth() - sourceWidth) / 2f;
        float sourceTop = (bitmap.getHeight() - sourceHeight) / 2f;
        Rect source = new Rect(Math.round(sourceLeft), Math.round(sourceTop),
                Math.round(sourceLeft + sourceWidth), Math.round(sourceTop + sourceHeight));
        canvas.drawBitmap(bitmap, source, destination, imagePaint);
    }

    private StaticLayout buildLayout(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragTarget = hitTest(event.getX(), event.getY());
                if (dragTarget == TARGET_TEXT) {
                    dragOffsetX = event.getX() - textRect.left;
                    dragOffsetY = event.getY() - textRect.top;
                } else if (dragTarget == TARGET_IMAGE) {
                    dragOffsetX = event.getX() - imageRect.left;
                    dragOffsetY = event.getY() - imageRect.top;
                }
                return dragTarget != TARGET_NONE || super.onTouchEvent(event);
            case MotionEvent.ACTION_MOVE:
                if (dragTarget == TARGET_NONE) return super.onTouchEvent(event);
                moveTarget(event.getX(), event.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragTarget = TARGET_NONE;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private int hitTest(float x, float y) {
        if (imageRect.contains(x, y)) return TARGET_IMAGE;
        if (textRect.contains(x, y)) return TARGET_TEXT;
        return TARGET_NONE;
    }

    private void moveTarget(float x, float y) {
        float left = x - dragOffsetX;
        float top = y - dragOffsetY;
        float fx = contentRect.width() <= 0 ? 0
                : (left - contentRect.left) / contentRect.width();
        float fy = contentRect.height() <= 0 ? 0
                : (top - contentRect.top) / contentRect.height();
        if (dragTarget == TARGET_TEXT) {
            textX = clamp01(fx);
            textY = clamp01(fy);
        } else if (dragTarget == TARGET_IMAGE) {
            imageX = clamp01(fx);
            imageY = clamp01(fy);
        }
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
