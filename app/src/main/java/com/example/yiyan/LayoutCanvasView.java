package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
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
 * 自定义布局画布：文字框 + 多个图片框，可拖动、可拖右下角改大小、可拖右上角圆点任意角度旋转。
 * 框与框之间允许重叠：文字画在最下面，图片按列表顺序往上叠。
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
    private static final int MODE_ROTATE = 3;

    private static final float MIN_SIZE = 0.08f;
    private static final float MAX_SIZE = 2.5f;

    /** 旋转手柄的视觉半径 / 触摸半径。手柄放在框的右上角。 */
    private static final float HANDLE_RADIUS_DP = 7f;
    private static final float HANDLE_TOUCH_DP = 24f;

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

    private CustomScene scene = CustomScene.defaults();
    private final Bitmap[] bitmaps = new Bitmap[4];
    private String quoteText = "知者不惑。";
    private String metaText = "— 孔子 · 《论语》 · 原文";

    private int selectedType = TYPE_NONE;
    private int selectedIndex = -1;
    private int mode = MODE_NONE;

    private float downX, downY;
    private RectF dragOrigin;
    private float startAngle;
    private float startRotation;

    private Listener listener;

    /** 玻璃背景缓存：尺寸或参数变了才重渲染。 */
    private Bitmap glassBitmap;
    private int glassWidth;
    private int glassHeight;
    private String glassSignature;

    public interface Listener {
        /** 选中项或旋转角度发生变化，供编辑器刷新提示文字。 */
        void onSceneChanged();
    }

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

        setPadding((int) dp(12), (int) dp(12), (int) dp(16), (int) dp(16));
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

    void setListener(Listener listener) {
        this.listener = listener;
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
        notifyChanged();
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
            glassBitmap = GlassRenderer.render(getContext(), w, h, spec);
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
        CustomScene.Box box = scene.text;
        RectF rect = boxRect(box);

        canvas.save();
        canvas.rotate(box.rotation, rect.centerX(), rect.centerY());

        if (scene.textBox) {
            // 文字框是透明底，只画描边，和桌面上的效果一致
            canvas.drawRoundRect(rect, dp(10), dp(10), borderPaint);
        }

        float width = Math.max(dp(24), rect.width());
        float height = Math.max(dp(20), rect.height());
        rect.set(rect.left, rect.top, rect.left + width, rect.top + height);

        canvas.save();
        canvas.clipRect(rect);
        int available = Math.max(1, (int) width);
        StaticLayout body = buildLayout(quoteText, textPaint, available);
        float bodyHeight = Math.min(body.getHeight(), Math.max(0, height - dp(16)));
        canvas.translate(rect.left, rect.top);
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

        canvas.drawRoundRect(rect, dp(6), dp(6), dashedPaint);
        canvas.restore();
    }

    private void drawImage(Canvas canvas, int index) {
        CustomScene.ImageBox box = scene.images.get(index);
        RectF rect = boxRect(box);

        canvas.save();
        canvas.rotate(box.rotation, rect.centerX(), rect.centerY());

        Bitmap bitmap = index < bitmaps.length ? bitmaps[index] : null;
        if (bitmap != null && !bitmap.isRecycled()) {
            drawCenterCrop(canvas, bitmap, rect);
        } else {
            canvas.drawRect(rect, boxFillPaint);
            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(0xAA6750A4);
            label.setTextSize(dp(12));
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("图片 " + (index + 1), rect.centerX(),
                    rect.centerY() + dp(4), label);
        }
        canvas.drawRect(rect, dashedPaint);
        canvas.restore();
    }

    private void drawSelection(Canvas canvas) {
        CustomScene.Box box = selectedBox();
        if (box == null) return;
        RectF rect = boxRect(box);

        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(dp(2));
        outline.setColor(0xFF6750A4);

        canvas.save();
        canvas.rotate(box.rotation, rect.centerX(), rect.centerY());
        canvas.drawRect(rect, outline);

        float radius = dp(HANDLE_RADIUS_DP);
        canvas.drawCircle(rect.right, rect.bottom, radius, handlePaint);
        canvas.drawCircle(rect.right, rect.bottom, radius, handleStrokePaint);
        canvas.restore();

        // 右上角是旋转手柄：位置跟着框一起转，任何角度都够得到
        float[] handle = rotateHandlePoint(rect, box.rotation);
        canvas.drawCircle(handle[0], handle[1], radius, handlePaint);
        canvas.drawCircle(handle[0], handle[1], radius, handleStrokePaint);
    }

    private CustomScene.Box selectedBox() {
        if (selectedType == TYPE_TEXT) return scene.text;
        if (selectedType == TYPE_IMAGE
                && selectedIndex >= 0 && selectedIndex < scene.images.size()) {
            return scene.images.get(selectedIndex);
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
        Rect source = new Rect(
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
                notifyChanged();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean onDown(MotionEvent event) {
        downX = event.getX();
        downY = event.getY();
        computeContentRect();

        // 已选中时，先给旋转 / 缩放手柄最高的命中优先级
        CustomScene.Box current = selectedBox();
        if (current != null) {
            RectF rect = boxRect(current);
            float[] handle = rotateHandlePoint(rect, current.rotation);
            if (distance(downX, downY, handle[0], handle[1]) <= dp(HANDLE_TOUCH_DP)) {
                mode = MODE_ROTATE;
                dragOrigin = new RectF(rect);
                startRotation = current.rotation;
                startAngle = angleFromCenter(rect, downX, downY);
                return true;
            }
            float[] corner = rotatedPoint(rect.right, rect.bottom, rect, current.rotation);
            if (distance(downX, downY, corner[0], corner[1]) <= dp(HANDLE_TOUCH_DP)) {
                mode = MODE_RESIZE;
                dragOrigin = new RectF(rect);
                return true;
            }
        }

        // 图片优先命中（后添加的在上层），文字在最底层
        for (int i = scene.images.size() - 1; i >= 0; i--) {
            CustomScene.ImageBox box = scene.images.get(i);
            if (insideBox(box, downX, downY)) {
                selectedType = TYPE_IMAGE;
                selectedIndex = i;
                mode = MODE_MOVE;
                dragOrigin = new RectF(boxRect(box));
                invalidate();
                notifyChanged();
                return true;
            }
        }
        if (insideBox(scene.text, downX, downY)) {
            selectedType = TYPE_TEXT;
            selectedIndex = -1;
            mode = MODE_MOVE;
            dragOrigin = new RectF(boxRect(scene.text));
            invalidate();
            notifyChanged();
            return true;
        }
        clearSelection();
        return true;
    }

    private boolean onMove(MotionEvent event) {
        if (mode == MODE_NONE || dragOrigin == null) return true;
        CustomScene.Box target = selectedBox();
        if (target == null) return true;

        float dx = event.getX() - downX;
        float dy = event.getY() - downY;

        if (mode == MODE_MOVE) {
            float fx = dx / Math.max(1f, contentRect.width());
            float fy = dy / Math.max(1f, contentRect.height());

            CustomScene.Box candidate = target.copy();
            candidate.x = (dragOrigin.left - contentRect.left) / contentRect.width() + fx;
            candidate.y = (dragOrigin.top - contentRect.top) / contentRect.height() + fy;
            clampInside(candidate);
            target.x = candidate.x;
            target.y = candidate.y;

            downX = event.getX();
            downY = event.getY();
            dragOrigin.set(boxRect(target));
            invalidate();
            return true;
        }

        if (mode == MODE_RESIZE) {
            // 把屏幕上的拖动量换算回框自身的坐标系，旋转后拖右下角仍然跟着手指走
            double rad = Math.toRadians(-target.rotation);
            float localX = (float) (dx * Math.cos(rad) - dy * Math.sin(rad));
            float localY = (float) (dx * Math.sin(rad) + dy * Math.cos(rad));
            float width = dragOrigin.width() / Math.max(1f, contentRect.width());
            float height = dragOrigin.height() / Math.max(1f, contentRect.height());
            target.w = clampSize(width + localX / Math.max(1f, contentRect.width()));
            target.h = clampSize(height + localY / Math.max(1f, contentRect.height()));
            invalidate();
            return true;
        }

        if (mode == MODE_ROTATE) {
            float angle = angleFromCenter(dragOrigin, event.getX(), event.getY());
            float delta = angle - startAngle;
            while (delta > 180f) delta -= 360f;
            while (delta < -180f) delta += 360f;
            target.rotation = CustomScene.normalizeRotation(startRotation + delta);
            invalidate();
            return true;
        }
        return true;
    }

    private void clampInside(CustomScene.Box box) {
        box.x = Math.max(0f, Math.min(box.x, 1f - box.w));
        box.y = Math.max(0f, Math.min(box.y, 1f - box.h));
    }

    private float clampSize(float value) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
    }

    private boolean insideBox(CustomScene.Box box, float x, float y) {
        RectF rect = boxRect(box);
        float[] local = toLocal(rect, box.rotation, x, y);
        return Math.abs(local[0]) <= rect.width() / 2f
                && Math.abs(local[1]) <= rect.height() / 2f;
    }

    /** 屏幕坐标 -> 以框中心为原点、随框旋转的局部坐标。 */
    private float[] toLocal(RectF rect, float rotation, float x, float y) {
        double rad = Math.toRadians(-rotation);
        float dx = x - rect.centerX();
        float dy = y - rect.centerY();
        return new float[]{
                (float) (dx * Math.cos(rad) - dy * Math.sin(rad)),
                (float) (dx * Math.sin(rad) + dy * Math.cos(rad))
        };
    }

    /** 局部坐标 -> 屏幕坐标。 */
    private float[] rotatedPoint(float px, float py, RectF rect, float rotation) {
        double rad = Math.toRadians(rotation);
        float dx = px - rect.centerX();
        float dy = py - rect.centerY();
        return new float[]{
                rect.centerX() + (float) (dx * Math.cos(rad) - dy * Math.sin(rad)),
                rect.centerY() + (float) (dx * Math.sin(rad) + dy * Math.cos(rad))
        };
    }

    /** 旋转手柄 = 框的右上角，随框一起旋转；只有整个点跑出画布时才拉回来。 */
    private float[] rotateHandlePoint(RectF rect, float rotation) {
        float[] point = rotatedPoint(rect.right, rect.top, rect, rotation);
        float margin = dp(10);
        point[0] = Math.max(margin, Math.min(getWidth() - margin, point[0]));
        point[1] = Math.max(margin, Math.min(getHeight() - margin, point[1]));
        return point;
    }

    private float angleFromCenter(RectF rect, float x, float y) {
        return (float) Math.toDegrees(Math.atan2(y - rect.centerY(), x - rect.centerX()));
    }

    private float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private void notifyChanged() {
        if (listener != null) listener.onSceneChanged();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
