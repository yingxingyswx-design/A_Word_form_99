package com.example.yiyan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;

/**
 * 「实时玻璃」页的背景层：同一张会平移 / 推近的壁纸，玻璃板下面的那张。
 * 玻璃板的着色器采样的是它自己画出来的同一份内容，所以两边必须逐像素一致。
 */
public class LiquidGlassBackdrop extends View {

    private Bitmap wallpaper;
    private float time;

    public LiquidGlassBackdrop(Context context) {
        this(context, null);
    }

    public LiquidGlassBackdrop(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setWallpaper(Bitmap bitmap) {
        this.wallpaper = bitmap;
        invalidate();
    }

    void setTime(float seconds) {
        this.time = seconds;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        LiquidGlassScene.draw(canvas, wallpaper, getWidth(), getHeight(), time);
        LiquidGlassScene.drawScrim(canvas, getWidth(), getHeight());
    }
}
