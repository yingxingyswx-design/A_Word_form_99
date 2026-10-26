package com.example.yiyan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 主题色取色器：上方是饱和度/明度面板，下方是色相条。
 * 当前选中的颜色会在面板上画一个带白圈的「触摸点」，拖动时跟手，
 * 色相条上也有一个白色游标，避免用户不知道现在选的是哪一块。
 */
public class ColorPickerView extends View {

    public interface OnColorChanged {
        void onColorChanged(int color);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF();
    private final RectF hueBar = new RectF();
    private final Path clip = new Path();

    private float hue = 270f;
    private float saturation = 0.62f;
    private float value = 0.64f;
    private int color = ThemeColors.DEFAULT_COLOR;
    private boolean pressed;
    private OnColorChanged listener;

    private float hueBarHeight;
    private float gap;

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        hueBarHeight = dp(30);
        gap = dp(12);
        setColor(ThemeColors.DEFAULT_COLOR);
        setContentDescription("颜色选择器：拖动圆点选择颜色，底部色相条切换色相");
    }

    public void setOnColorChangedListener(OnColorChanged listener) {
        this.listener = listener;
    }

    public int getColor() {
        return color;
    }

    /** 从外部同步颜色（不会回调 listener，避免和色卡选择互相触发）。 */
    public void setColor(int color) {
        this.color = color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] > 0.01f) hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        panel.set(0, 0, w, Math.max(1, h - hueBarHeight - gap));
        hueBar.set(0, panel.bottom + gap, w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (panel.isEmpty()) return;

        float radius = dp(20);
        clip.reset();
        clip.addRoundRect(panel, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(panel.left, 0, panel.right, 0,
                Color.WHITE, Color.HSVToColor(new float[]{hue, 1f, 1f}), Shader.TileMode.CLAMP));
        canvas.drawRect(panel, paint);
        paint.setShader(new LinearGradient(0, panel.top, 0, panel.bottom,
                0x00FFFFFF, 0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRect(panel, paint);
        canvas.restore();

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x33000000);
        canvas.drawRoundRect(panel, radius, radius, paint);

        // 色相条
        float hueRadius = hueBar.height() / 2f;
        clip.reset();
        clip.addRoundRect(hueBar, hueRadius, hueRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(hueBar.left, 0, hueBar.right, 0, new int[]{
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(hueBar, paint);
        canvas.restore();

        // 色相条游标
        float hx = hueBar.left + (hue / 360f) * hueBar.width();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55000000);
        canvas.drawRoundRect(hx - dp(2), hueBar.top - dp(2),
                hx + dp(2), hueBar.bottom + dp(2), dp(4), dp(4), paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawRoundRect(hx - dp(2.5f), hueBar.top - dp(3),
                hx + dp(2.5f), hueBar.bottom + dp(3), dp(3), dp(3), paint);
        paint.setColor(Color.HSVToColor(new float[]{hue, 1f, 1f}));
        canvas.drawRoundRect(hx - dp(1), hueBar.top + dp(4),
                hx + dp(1), hueBar.bottom - dp(4), dp(1), dp(1), paint);

        // 面板上的触摸点：阴影 + 白圈 + 当前色
        float x = panel.left + saturation * panel.width();
        float y = panel.top + (1f - value) * panel.height();
        float r = dp(11) + (pressed ? dp(2) : 0);
        paint.setColor(0x44000000);
        canvas.drawCircle(x, y + dp(1.5f), r + dp(2), paint);
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(x, y, r, paint);
        paint.setColor(color);
        canvas.drawCircle(x, y, Math.max(dp(1), r - dp(3)), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x33000000);
        canvas.drawCircle(x, y, r, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                handleTouch(event.getX(), event.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                handleTouch(event.getX(), event.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                pressed = false;
                handleTouch(event.getX(), event.getY());
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void handleTouch(float x, float y) {
        if (hueBar.contains(x, y) || y > panel.bottom) {
            hue = clamp((x - hueBar.left) / Math.max(1f, hueBar.width())) * 360f;
            // 纯白/纯黑时没有色相可言，第一次点色相条直接给出一个可看的颜色
            if (saturation < 0.05f) saturation = 1f;
            if (value < 0.05f) value = 1f;
        } else {
            saturation = clamp((x - panel.left) / Math.max(1f, panel.width()));
            value = clamp(1f - (y - panel.top) / Math.max(1f, panel.height()));
        }
        int next = Color.HSVToColor(new float[]{hue, saturation, value});
        if (next != color) {
            color = next;
            if (listener != null) listener.onColorChanged(color);
        }
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
