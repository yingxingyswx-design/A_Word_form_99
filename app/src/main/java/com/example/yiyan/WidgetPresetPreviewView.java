package com.example.yiyan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONObject;

import java.util.List;

/**
 * 保存组件的 2×2 外观预览。
 *
 * 按预设里的主题、背景、字号、自定义布局场景和图片重新绘制一张卡片，
 * 而不是简单显示一个色块。自定义布局里的文字框 / 图片框位置、旋转、镜像都会还原；
 * 背景图片和语录配图异步解码，避免打开弹窗时卡顿。
 */
public class WidgetPresetPreviewView extends View {

    private static final String SAMPLE = "知者不惑，仁者不忧，勇者不惧。";
    private static final String SAMPLE_META = "— 孔子 · 《论语》 · 原文";

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF card = new RectF();

    private WidgetPresetStore.Preset preset;
    private CustomScene scene;
    private Bitmap backgroundImage;
    private Bitmap quoteImage;
    private final Bitmap[] sceneImages = new Bitmap[4];
    private int imageToken;

    public WidgetPresetPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        borderPaint.setStyle(Paint.Style.STROKE);
    }

    public void setPreset(WidgetPresetStore.Preset preset) {
        this.preset = preset;
        this.scene = null;
        this.backgroundImage = null;
        this.quoteImage = null;
        for (int i = 0; i < sceneImages.length; i++) sceneImages[i] = null;
        final int token = ++imageToken;
        if (preset == null) {
            invalidate();
            return;
        }

        if (preset.values.optBoolean("customLayout", false)) {
            String json = preset.values.optString(CustomScene.PREF_KEY, "");
            if (!json.isEmpty()) {
                try {
                    scene = CustomScene.fromJson(new JSONObject(json));
                } catch (Exception ignored) {
                }
            }
        }

        final String backgroundName = preset.values.optString(CustomBackground.KEY_NAME, "");
        final boolean hasBackgroundImage = preset.values.optBoolean(
                CustomBackground.KEY_ENABLED, false)
                && CustomBackground.TYPE_IMAGE.equals(preset.values.optString(
                CustomBackground.KEY_TYPE, CustomBackground.TYPE_IMAGE));
        final String quoteName = preset.values.optString("lastQuoteImage", "");
        final CustomScene snapshot = scene;
        new Thread(() -> {
            Bitmap bg = hasBackgroundImage ? decodeFirst(backgroundName) : null;
            Bitmap quote = quoteName.isEmpty() ? null : decodeFirst(quoteName);
            Bitmap[] images = new Bitmap[4];
            if (snapshot != null) {
                for (int i = 0; i < Math.min(4, snapshot.images.size()); i++) {
                    CustomScene.ImageBox box = snapshot.images.get(i);
                    String name = firstFrame(box.name);
                    if (!name.isEmpty()) {
                        images[i] = ImageStore.decodeFlipped(getContext(), name, 640,
                                box.flipH, box.flipV);
                    }
                }
            }
            post(() -> {
                if (token != imageToken) return;
                backgroundImage = bg;
                quoteImage = quote;
                System.arraycopy(images, 0, sceneImages, 0, sceneImages.length);
                invalidate();
            });
        }, "preset-preview-images").start();
        invalidate();
    }

    private String firstFrame(String name) {
        if (name == null || name.isEmpty()) return "";
        List<String> frames = ImageStore.frameNames(getContext(), name);
        return frames.isEmpty() ? name : frames.get(0);
    }

    private Bitmap decodeFirst(String name) {
        String first = firstFrame(name);
        return first.isEmpty() ? null : ImageStore.decode(getContext(), first, 640);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (preset == null || getWidth() == 0 || getHeight() == 0) return;
        float density = getResources().getDisplayMetrics().density;
        float inset = dp(6);
        card.set(inset, inset, getWidth() - inset, getHeight() - inset);
        int corner = preset.values.optInt(GlassRenderer.KEY_CORNER,
                GlassRenderer.DEFAULT_CORNER);
        float radius = Math.min(dp(corner), Math.min(card.width(), card.height()) / 2f);

        clip.reset();
        clip.addRoundRect(card, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        boolean dark = drawBackground(canvas);
        canvas.restore();

        borderPaint.setStrokeWidth(Math.max(1f, density));
        borderPaint.setColor(dark ? 0x33FFFFFF : 0x22000000);
        canvas.drawPath(clip, borderPaint);

        boolean customLayout = preset.values.optBoolean("customLayout", false);
        if (customLayout && scene != null) {
            drawCustomText(canvas, dark);
            drawSceneImages(canvas);
        } else {
            drawDefaultWidget(canvas, dark);
        }
    }

    /** @return 背景是否为深色（决定文字黑白）。 */
    private boolean drawBackground(Canvas canvas) {
        boolean customBg = preset.values.optBoolean(CustomBackground.KEY_ENABLED, false);
        String type = preset.values.optString(CustomBackground.KEY_TYPE,
                CustomBackground.TYPE_IMAGE);
        if (customBg && CustomBackground.TYPE_SOLID.equals(type)) {
            int color = preset.values.optInt(CustomBackground.KEY_SOLID, 0xFFFDF8F3);
            backgroundPaint.setShader(null);
            backgroundPaint.setColor(color);
            canvas.drawRect(card, backgroundPaint);
            return isDark(color);
        }
        if (customBg && CustomBackground.TYPE_GRADIENT.equals(type)) {
            int start = preset.values.optInt(CustomBackground.KEY_GRADIENT_START, 0xFFF6D5F7);
            int end = preset.values.optInt(CustomBackground.KEY_GRADIENT_END, 0xFFD9E4FF);
            int angle = preset.values.optInt("customBgGradientAngle", 135);
            backgroundPaint.setShader(gradient(card, start, end, angle));
            canvas.drawRect(card, backgroundPaint);
            return isDark(average(start, end));
        }
        if (customBg && backgroundImage != null && !backgroundImage.isRecycled()) {
            drawCenterCrop(canvas, backgroundImage, card);
            return isDark(backgroundImage.getPixel(
                    backgroundImage.getWidth() / 2, backgroundImage.getHeight() / 2));
        }

        boolean glass = preset.values.optBoolean(GlassRenderer.KEY_ENABLED, false);
        String tone = preset.values.optString(GlassRenderer.KEY_TONE, GlassRenderer.TONE_AUTO);
        boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean dark = GlassRenderer.TONE_DARK.equals(tone)
                || (!GlassRenderer.TONE_LIGHT.equals(tone) && night);
        backgroundPaint.setShader(null);
        backgroundPaint.setColor(glass ? (dark ? 0xE6211F26 : 0xE6F7F8FC)
                : (dark ? 0xFF211F24 : 0xFFFFFFFF));
        canvas.drawRect(card, backgroundPaint);
        if (glass) {
            backgroundPaint.setShader(new LinearGradient(card.left, card.top,
                    card.right, card.bottom,
                    dark ? 0x332B2730 : 0x66FFFFFF, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(card, backgroundPaint);
        }
        return dark;
    }

    /** 默认布局：正文 + 右侧配图 + 作者行。 */
    private void drawDefaultWidget(Canvas canvas, boolean dark) {
        String text = preset.values.optString("lastQuoteText", SAMPLE);
        String meta = preset.values.optString("lastQuoteMeta", SAMPLE_META);
        if (text.isEmpty()) text = SAMPLE;
        if (meta.isEmpty()) meta = SAMPLE_META;
        float pad = dp(14);
        boolean hasImage = quoteImage != null && !quoteImage.isRecycled();
        float imageW = hasImage ? card.width() * 0.28f : 0;
        float textWidth = Math.max(dp(40), card.width() - pad * 2 - imageW
                - (hasImage ? dp(8) : 0));
        float textBottom = card.bottom - pad - dp(18);

        textPaint.setColor(dark ? 0xFFFDFCFF : 0xFF121016);
        textPaint.setTextSize(fontPx());
        Layout body = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint,
                        (int) textWidth)
                .setMaxLines(5)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        canvas.save();
        canvas.translate(card.left + pad, card.top + pad);
        canvas.clipRect(0, 0, textWidth, Math.max(1, textBottom - card.top - pad));
        body.draw(canvas);
        canvas.restore();

        metaPaint.setColor(ThemeColors.ensureReadable(themeColor(), dark));
        metaPaint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(meta, card.left + pad, card.bottom - pad, metaPaint);

        if (hasImage) {
            RectF target = new RectF(card.right - pad - imageW, card.top + pad,
                    card.right - pad, card.bottom - pad);
            canvas.save();
            Path imageClip = new Path();
            imageClip.addRoundRect(target, dp(12), dp(12), Path.Direction.CW);
            canvas.clipPath(imageClip);
            drawCenterCrop(canvas, quoteImage, target);
            canvas.restore();
        }
    }

    /** 自定义布局：按场景 0–1 坐标还原文字框，再按顺序叠图片。 */
    private void drawCustomText(Canvas canvas, boolean dark) {
        String text = preset.values.optString("lastQuoteText", SAMPLE);
        String meta = preset.values.optString("lastQuoteMeta", SAMPLE_META);
        if (text.isEmpty()) text = SAMPLE;
        if (meta.isEmpty()) meta = SAMPLE_META;
        CustomScene.Box box = scene.text;
        RectF rect = boxRect(box);
        float rotation = CustomScene.normalizeRotation(box.rotation);
        canvas.save();
        canvas.rotate(rotation, rect.centerX(), rect.centerY());
        if (scene.textBox) {
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1));
            border.setColor(dark ? 0x66FFFFFF : 0x667750A4);
            canvas.drawRoundRect(rect, dp(10), dp(10), border);
        }
        float pad = dp(8);
        float width = Math.max(1, rect.width() - pad * 2);
        textPaint.setColor(dark ? 0xFFFDFCFF : 0xFF121016);
        textPaint.setTextSize(fontPx());
        Layout body = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint,
                        (int) width)
                .setMaxLines(5)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        canvas.save();
        canvas.clipRect(rect);
        canvas.translate(rect.left + pad, rect.top + pad);
        body.draw(canvas);
        canvas.restore();

        metaPaint.setColor(ThemeColors.ensureReadable(themeColor(), dark));
        metaPaint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(meta, rect.left + pad, rect.bottom - pad, metaPaint);
        canvas.restore();
    }

    private void drawSceneImages(Canvas canvas) {
        for (int i = 0; i < Math.min(4, scene.images.size()); i++) {
            Bitmap bitmap = sceneImages[i];
            if (bitmap == null || bitmap.isRecycled()) continue;
            CustomScene.ImageBox box = scene.images.get(i);
            RectF rect = boxRect(box);
            canvas.save();
            canvas.rotate(CustomScene.normalizeRotation(box.rotation),
                    rect.centerX(), rect.centerY());
            canvas.scale(box.flipH ? -1f : 1f, box.flipV ? -1f : 1f,
                    rect.centerX(), rect.centerY());
            Path imageClip = new Path();
            imageClip.addRoundRect(rect, dp(10), dp(10), Path.Direction.CW);
            canvas.clipPath(imageClip);
            drawCenterCrop(canvas, bitmap, rect);
            canvas.restore();
        }
    }

    private RectF boxRect(CustomScene.Box box) {
        return new RectF(
                card.left + box.x * card.width(),
                card.top + box.y * card.height(),
                card.left + (box.x + box.w) * card.width(),
                card.top + (box.y + box.h) * card.height());
    }

    private float fontPx() {
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        return Math.max(10 * scaled, preset.values.optInt("fontSize", 16) * scaled);
    }

    private int themeColor() {
        if (preset.values.optBoolean("monet", false)) {
            return ThemeColors.systemAccent(getContext());
        }
        return preset.values.optInt(ThemeColors.KEY, ThemeColors.DEFAULT_COLOR);
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, RectF target) {
        float scale = Math.max(target.width() / bitmap.getWidth(),
                target.height() / bitmap.getHeight());
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        float left = target.left + (target.width() - w) / 2f;
        float top = target.top + (target.height() - h) / 2f;
        backgroundPaint.setShader(null);
        canvas.drawBitmap(bitmap, null,
                new RectF(left, top, left + w, top + h), backgroundPaint);
    }

    private Shader gradient(RectF rect, int start, int end, int angle) {
        double radians = Math.toRadians(angle);
        float cx = rect.centerX();
        float cy = rect.centerY();
        float length = (float) Math.hypot(rect.width(), rect.height()) / 2f;
        float dx = (float) (Math.cos(radians) * length);
        float dy = (float) (Math.sin(radians) * length);
        return new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy,
                start, end, Shader.TileMode.CLAMP);
    }

    private boolean isDark(int color) {
        return androidx.core.graphics.ColorUtils.calculateLuminance(color) < 0.45;
    }

    private int average(int start, int end) {
        return androidx.core.graphics.ColorUtils.blendARGB(start, end, 0.5f);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
