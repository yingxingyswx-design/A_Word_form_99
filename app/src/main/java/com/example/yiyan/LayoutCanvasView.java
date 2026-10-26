package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 自定义布局画布：文字框 + 多个图片框，可拖动、可拖右下角改大小，
 * 且框之间不允许重叠（移动/缩放时若相交就放弃这次位移）。
 *
 * 几何一律用「相对内容区的 0–1 分数」表达，保存后小组件按自己的实际尺寸还原。
 */
public class LayoutCanvasView extends View {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 2;

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_RESIZE = 2;
    private static final float MIN_SIZE = 0.08f;
    private static final float GAP = 0.004f;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF widgetRect = new RectF();
    private final RectF contentRect = new RectF();
    private final RectF tempRect = new RectF();

    private CustomScene scene = CustomScene.defaults();
    private final Bitmap[] bitmaps = new Bitmap[4];
    private String quoteText = "知者不惑。";
    private String metaText = "— 孔子 · 《论语》 · 原文";

    private int selectedType = TYPE_NONE;
    private int selectedIndex = -1;
    private int mode = MODE_NONE;

    private float downX, downY;
    private RectF dragOrigin;

    /** 玻璃背景缓存：尺寸或参数变了才重渲染。 */
    private Bitmap glassBitmap;
    private int glassWidth;
    private int glassHeight;
    private String glassSignature;

    public LayoutCanvasView(Context context) {
        this(context, null);
    }

    public LayoutCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float scaled = getResources().getDisplayMetrics().scaledDensity;

        textPaint.setColor(getResources().getColor(R.color.ink, context.getTheme()));
        textPaint.setTextSize(16 * scaled);
        metaPaint.setColor(getResources().getColor(R.color.accent, context.getTheme()));
        metaPaint.setTextSize(11 * scaled);

        backgroundPaint.setColor(getResources().getColor(R.color.paper, context.getTheme()));
        boxFillPaint.setColor(getResources().getColor(R.color.paper, context.getTheme()));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.5f));
        borderPaint.setColor(0x806750A4);
        dashedPaint.setStyle(Paint.Style.STROKE);
        dashedPaint.setStrokeWidth(dp(1f));
        dashedPaint.setColor(0x666750A4);
        dashedPaint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(4)}, 0));
        imagePaint.setFilterBitmap(true);
        handlePaint.setColor(0xFF6750A4);
        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(dp(2));
        handleStrokePaint.setColor(0xFFFFFFFF);

        setPadding((int) dp(12), (int) dp(12), (int) dp(12), (int) dp(12));
    }

    void setScene(CustomScene scene) {
        this.scene = scene;
        invalidate();
    }

    CustomScene getScene() {
        return scene;
    }

    void setContent(String text, String meta) {
        if (text != null && !text.isEmpty()) quoteText = text;
        if (meta != null && !meta.isEmpty()) metaText = meta;
        invalidate();
    }

    void setBitmap(int index, Bitmap bitmap) {
        if (index < 0 || index >= bitmaps.length) return;
        bitmaps[index] = bitmap;
        invalidate();
    }

    int getSelectedType() {
        return selectedType;
    }

    int getSelectedIndex() {
        return selectedIndex;
    }

    void clearSelection() {
        selectedType = TYPE_NONE;
        selectedIndex = -1;
        invalidate();
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        computeContentRect();
        applyGlassPalette();

        drawBackground(canvas);

        drawText(canvas);
        for (int i = 0; i < scene.images.size(); i++) {
            drawImage(canvas, i);
        }
        drawSelection(canvas);
    }

    /** 与桌面一致：开液态玻璃时画玻璃层，否则画纯色圆角底。 */
    private void drawBackground(Canvas canvas) {
        SharedPreferences prefs = prefs();
        if (!GlassRenderer.enabled(prefs)) {
            canvas.drawRoundRect(widgetRect, dp(18), dp(18), backgroundPaint);
            return;
        }
        int w = Math.max(1, Math.round(widgetRect.width()));
        int h = Math.max(1, Math.round(widgetRect.height()));
        GlassRenderer.Spec spec = GlassRenderer.spec(getContext(), prefs);
        String signature = spec.blur + "/" + spec.density + "/" + spec.highlight
                + "/" + spec.cornerDp + "/" + spec.dark;
        if (glassBitmap == null || glassWidth != w || glassHeight != h
                || !signature.equals(glassSignature)) {
            if (glassBitmap != null) glassBitmap.recycle();
            glassBitmap = GlassRenderer.render(getContext(), w, h, spec,
                    WallpaperSource.load(getContext(), 720));
            glassWidth = w;
            glassHeight = h;
            glassSignature = signature;
        }
        canvas.drawBitmap(glassBitmap, null, widgetRect, imagePaint);
    }

    private void applyGlassPalette() {
        SharedPreferences prefs = prefs();
        if (GlassRenderer.enabled(prefs)) {
            GlassRenderer.Spec spec = GlassRenderer.spec(getContext(), prefs);
            textPaint.setColor(GlassRenderer.textColor(spec));
            metaPaint.setColor(GlassRenderer.metaColor(spec));
        } else {
            textPaint.setColor(getResources().getColor(R.color.ink, getContext().getTheme()));
            metaPaint.setColor(getResources().getColor(R.color.accent, getContext().getTheme()));
        }
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("settings", 0);
    }

    private void computeContentRect() {
        float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float aspect = Math.max(0.4f, (float) scene.widthDp / Math.max(1, scene.heightDp));
        float width = availableWidth;
        float height = width / aspect;
        if (height > availableHeight) {
            height = availableHeight;
            width = height * aspect;
        }
        float left = getPaddingLeft() + (availableWidth - width) / 2f;
        float top = getPaddingTop() + (availableHeight - height) / 2f;
        widgetRect.set(left, top, left + width, top + height);
        contentRect.set(widgetRect);
    }

    private void drawText(Canvas canvas) {
        float left = contentRect.left + scene.text.x * contentRect.width();
        float top = contentRect.top + scene.text.y * contentRect.height();
        float width = Math.max(dp(24), scene.text.w * contentRect.width());
        float height = Math.max(dp(20), scene.text.h * contentRect.height());
        tempRect.set(left, top, left + width, top + height);

        if (scene.textBox) {
            canvas.drawRoundRect(tempRect, dp(10), dp(10), boxFillPaint);
            canvas.drawRoundRect(tempRect, dp(10), dp(10), borderPaint);
        }

        canvas.save();
        canvas.clipRect(tempRect);
        int available = Math.max(1, (int) width);
        StaticLayout body = buildLayout(quoteText, textPaint, available);
        float bodyHeight = Math.min(body.getHeight(), Math.max(0, height - dp(16)));
        canvas.translate(left, top);
        canvas.save();
        canvas.clipRect(0, 0, available, bodyHeight);
        body.draw(canvas);
        canvas.restore();

        StaticLayout meta = buildLayout(metaText, metaPaint, available);
        canvas.translate(0, Math.min(body.getHeight(), bodyHeight) + dp(4));
        canvas.save();
        canvas.clipRect(0, 0, available, Math.max(0, height - bodyHeight - dp(4)));
        meta.draw(canvas);
        canvas.restore();
        canvas.restore();

        canvas.drawRoundRect(tempRect, dp(6), dp(6), dashedPaint);
    }

    private void drawImage(Canvas canvas, int index) {
        CustomScene.ImageBox box = scene.images.get(index);
        float left = contentRect.left + box.x * contentRect.width();
        float top = contentRect.top + box.y * contentRect.height();
        float width = Math.max(dp(24), box.w * contentRect.width());
        float height = Math.max(dp(24), box.h * contentRect.height());
        tempRect.set(left, top, left + width, top + height);

        Bitmap bitmap = index < bitmaps.length ? bitmaps[index] : null;
        if (bitmap != null && !bitmap.isRecycled()) {
            drawCenterCrop(canvas, bitmap, tempRect);
        } else {
            canvas.drawRect(tempRect, boxFillPaint);
            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(0xAA6750A4);
            label.setTextSize(dp(12));
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("图片 " + (index + 1), tempRect.centerX(),
                    tempRect.centerY() + dp(4), label);
        }
        canvas.drawRect(tempRect, dashedPaint);
    }

    private void drawSelection(Canvas canvas) {
        RectF rect = selectionRect();
        if (rect == null) return;
        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(2));
        outline.setColor(0xFF6750A4);
        canvas.drawRect(rect, outline);

        float handle = dp(14);
        RectF corner = new RectF(rect.right - handle / 2, rect.bottom - handle / 2,
                rect.right + handle / 2, rect.bottom + handle / 2);
        canvas.drawRect(corner, handlePaint);
        canvas.drawRect(corner, handleStrokePaint);
    }

    private RectF selectionRect() {
        if (selectedType == TYPE_TEXT) {
            return boxRect(scene.text);
        }
        if (selectedType == TYPE_IMAGE
                && selectedIndex >= 0 && selectedIndex < scene.images.size()) {
            return boxRect(scene.images.get(selectedIndex));
        }
        return null;
    }

    private RectF boxRect(CustomScene.Box box) {
        float left = contentRect.left + box.x * contentRect.width();
        float top = contentRect.top + box.y * contentRect.height();
        return new RectF(left, top,
                left + box.w * contentRect.width(),
                top + box.h * contentRect.height());
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF destination) {
        float scale = Math.max(destination.width() / bitmap.getWidth(),
                destination.height() / bitmap.getHeight());
        float sourceWidth = destination.width() / scale;
        float sourceHeight = destination.height() / scale;
        float sourceLeft = (bitmap.getWidth() - sourceWidth) / 2f;
        float sourceTop = (bitmap.getHeight() - sourceHeight) / 2f;
        android.graphics.Rect source = new android.graphics.Rect(
                Math.round(sourceLeft), Math.round(sourceTop),
                Math.round(sourceLeft + sourceWidth), Math.round(sourceTop + sourceHeight));
        canvas.drawBitmap(bitmap, source, destination, imagePaint);
    }

    private StaticLayout buildLayout(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();
    }

    // ---- 交互 ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return onDown(event);
            case MotionEvent.ACTION_MOVE:
                return onMove(event);
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = MODE_NONE;
                dragOrigin = null;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean onDown(MotionEvent event) {
        downX = event.getX();
        downY = event.getY();
        computeContentRect();

        RectF handle = selectionRect();
        if (handle != null && withinHandle(handle, downX, downY)) {
            mode = MODE_RESIZE;
            dragOrigin = new RectF(handle);
            return true;
        }

        // 图片优先命中（后添加的在上层）
        for (int i = scene.images.size() - 1; i >= 0; i--) {
            if (boxRect(scene.images.get(i)).contains(downX, downY)) {
                selectedType = TYPE_IMAGE;
                selectedIndex = i;
                mode = MODE_MOVE;
                dragOrigin = new RectF(boxRect(scene.images.get(i)));
                invalidate();
                return true;
            }
        }
        if (boxRect(scene.text).contains(downX, downY)) {
            selectedType = TYPE_TEXT;
            selectedIndex = -1;
            mode = MODE_MOVE;
            dragOrigin = new RectF(boxRect(scene.text));
            invalidate();
            return true;
        }
        clearSelection();
        return true;
    }

    private boolean onMove(MotionEvent event) {
        if (mode == MODE_NONE || dragOrigin == null) return true;
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;

        CustomScene.Box target = selectedType == TYPE_TEXT
                ? scene.text
                : (selectedIndex >= 0 && selectedIndex < scene.images.size()
                ? scene.images.get(selectedIndex) : null);
        if (target == null) return true;

        float fx = dx / Math.max(1, contentRect.width());
        float fy = dy / Math.max(1, contentRect.height());

        CustomScene.Box candidate = target.copy();
        if (mode == MODE_MOVE) {
            candidate.x = (dragOrigin.left - contentRect.left) / contentRect.width() + fx;
            candidate.y = (dragOrigin.top - contentRect.top) / contentRect.height() + fy;
            clampInside(candidate);
        } else {
            candidate.w = dragOrigin.width() / contentRect.width() + fx;
            candidate.h = dragOrigin.height() / contentRect.height() + fy;
            candidate.w = Math.max(MIN_SIZE, Math.min(candidate.w, 1f - candidate.x));
            candidate.h = Math.max(MIN_SIZE, Math.min(candidate.h, 1f - candidate.y));
        }

        // 相交就整体放弃这次位移：文字框与图片框都是实体，不允许叠在一起
        if (!overlapsOthers(candidate, target)) {
            target.x = candidate.x;
            target.y = candidate.y;
            target.w = candidate.w;
            target.h = candidate.h;
            downX = event.getX();
            downY = event.getY();
            dragOrigin.set(boxRect(target));
            invalidate();
        }
        return true;
    }

    private boolean overlapsOthers(CustomScene.Box candidate, CustomScene.Box self) {
        for (CustomScene.ImageBox box : scene.images) {
            if (box != self && candidate.intersects(box, GAP)) return true;
        }
        return self != scene.text && candidate.intersects(scene.text, GAP);
    }

    private void clampInside(CustomScene.Box box) {
        box.x = Math.max(0f, Math.min(box.x, 1f - box.w));
        box.y = Math.max(0f, Math.min(box.y, 1f - box.h));
    }

    private boolean withinHandle(RectF rect, float x, float y) {
        float size = dp(22);
        return x >= rect.right - size && x <= rect.right + size
                && y >= rect.bottom - size && y <= rect.bottom + size;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
