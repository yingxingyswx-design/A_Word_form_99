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
 * 保存组件的真实小组件视图预览。
 *
 * 不是色块：按预设里的背景 / 字号 / 颜色 / 自定义布局（文字框、图片框的位置、旋转、
 * 翻转、图片比例）把小组件重新画一遍。2×2 与 4×2 走与桌面相同的分支：
 * 2×2 = 紧凑版（无配图、正文满宽），4×2 = 常规版（正文 + 右侧配图、完整作者行）。
 */
public class WidgetPresetPreviewView extends View {

    private static final String SAMPLE = "知者不惑，仁者不忧，勇者不惧。";
    private static final String SAMPLE_META = "— 孔子 · 《论语》 · 原文";
    private static final int SAMPLE_META_MAX_WIDTH = 12;

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
    private final Bitmap[] sceneImages = new Bitmap[QuoteWidgetProvider.MAX_IMAGE_SLOTS];
    private int imageToken;
    private int cellsWide = 2;

    public WidgetPresetPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        borderPaint.setStyle(Paint.Style.STROKE);
        textPaint.setAntiAlias(true);
        metaPaint.setAntiAlias(true);
    }

    /** 预览的桌面尺寸：2×2 = 2 格宽；4×2 = 4 格宽（AppWidget 的 70dp 规则）。 */
    public void setPreviewCells(int cellsWide) {
        this.cellsWide = cellsWide >= 4 ? 4 : 2;
        requestLayout();
        invalidate();
    }

    public int getPreviewCells() {
        return cellsWide;
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

        if (WidgetPresetStore.usesCustomLayout(preset.values)) {
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
        final String quoteName = preset.values.optString("snapshotImage", "");
        final CustomScene snapshot = scene;
        new Thread(() -> {
            Bitmap bg = hasBackgroundImage ? decodeFirst(backgroundName) : null;
            Bitmap quote = quoteName.isEmpty() ? null : decodeFirst(quoteName);
            Bitmap[] images = new Bitmap[QuoteWidgetProvider.MAX_IMAGE_SLOTS];
            if (snapshot != null) {
                for (int i = 0; i < Math.min(QuoteWidgetProvider.MAX_IMAGE_SLOTS,
                        snapshot.images.size()); i++) {
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

    // ---- 测量与版面 ----

    /** 2×2 按 1:1，4×2 按 2:1；与桌面 AppWidget 的实际比例一致。 */
    private float targetAspect() {
        return cellsWide >= 4 ? 2f : 1f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = Math.round(dp(320));
        }
        int availWidth = Math.max(1, width - getPaddingLeft() - getPaddingRight());
        int height = Math.round(availWidth / targetAspect())
                + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    private void layoutCard() {
        float inset = dp(6);
        float availW = Math.max(1f, getWidth() - inset * 2);
        float availH = Math.max(1f, getHeight() - inset * 2);
        float width = availW;
        float height = width / targetAspect();
        if (height > availH) {
            height = availH;
            width = height * targetAspect();
        }
        float left = getWidth() / 2f - width / 2f;
        float top = getHeight() / 2f - height / 2f;
        card.set(left, top, left + width, top + height);
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (preset == null || getWidth() == 0 || getHeight() == 0) return;
        layoutCard();
        float density = getResources().getDisplayMetrics().density;
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

        boolean customLayout = WidgetPresetStore.usesCustomLayout(preset.values);
        if (customLayout && scene != null) {
            drawCustomText(canvas, dark);
            drawSceneImages(canvas);
            drawHint(canvas, dark);
        } else if (cellsWide >= 4) {
            // 4×2 常规版：正文 + 右侧配图 + 完整作者行
            drawDefaultWidget(canvas, dark);
        } else {
            // 2×2 紧凑版：无配图、正文满宽、作者行一行
            drawCompactWidget(canvas, dark);
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
        // 和桌面一致：没开玻璃、也没开自定义背景时用主题纸张底色
        int paper = preset.values.optInt("backgroundColor", 0);
        if (paper == 0) paper = dark ? 0xFF211F24 : 0xFFFDF8F3;
        backgroundPaint.setShader(null);
        backgroundPaint.setColor(glass ? (dark ? 0xE6211F26 : 0xE6F7F8FC) : paper);
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

    /** 2×2 紧凑版：与 widget_quote_compact.xml 一致，无配图、作者行一行。 */
    private void drawCompactWidget(Canvas canvas, boolean dark) {
        String text = text();
        String meta = compactMeta();
        float pad = dp(10);
        float width = Math.max(dp(40), card.width() - pad * 2);
        float metaHeight = dp(14);

        textPaint.setColor(dark ? 0xFFFDFCFF : 0xFF121016);
        textPaint.setTextSize(fontPx());
        Layout body = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint,
                        (int) width)
                .setMaxLines(6)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(card.left + pad, card.top + pad);
        canvas.clipRect(0, 0, width,
                Math.max(1, card.height() - pad * 2 - metaHeight));
        body.draw(canvas);
        canvas.restore();

        metaPaint.setColor(ThemeColors.ensureReadable(themeColor(), dark));
        metaPaint.setTextSize(sp(9));
        canvas.drawText(meta, card.left + pad, card.bottom - pad, metaPaint);
    }

    /** 4×2 常规版：与 widget_quote.xml 一致，正文 + 右侧配图 + 完整作者行。 */
    private void drawDefaultWidget(Canvas canvas, boolean dark) {
        String text = text();
        String meta = fullMeta();
        float pad = dp(14);
        float imageW = dp(72);
        float gap = dp(8);
        boolean hasImage = quoteImage != null && !quoteImage.isRecycled();
        float textWidth = Math.max(dp(40), card.width() - pad * 2
                - (hasImage ? imageW + gap : 0));
        float textBottom = card.bottom - pad - dp(18);

        textPaint.setColor(dark ? 0xFFFDFCFF : 0xFF121016);
        textPaint.setTextSize(fontPx());
        Layout body = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint,
                        (int) textWidth)
                .setMaxLines(8)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(card.left + pad, card.top + pad);
        canvas.clipRect(0, 0, textWidth, Math.max(1, textBottom - card.top - pad));
        body.draw(canvas);
        canvas.restore();

        // 作者行最多两行，和桌面 widget_quote.xml 一致
        metaPaint.setColor(ThemeColors.ensureReadable(themeColor(), dark));
        metaPaint.setTextSize(sp(10));
        Layout metaLayout = StaticLayout.Builder.obtain(meta, 0, meta.length(), metaPaint,
                        (int) textWidth)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        canvas.save();
        canvas.translate(card.left + pad, card.bottom - pad - metaLayout.getHeight());
        metaLayout.draw(canvas);
        canvas.restore();

        if (hasImage) {
            RectF target = new RectF(card.right - pad - imageW, card.top + pad,
                    card.right - pad, card.bottom - pad);
            canvas.save();
            Path imageClip = new Path();
            imageClip.addRoundRect(target, dp(12), dp(12), Path.Direction.CW);
            canvas.clipPath(imageClip);
            drawFitCenter(canvas, quoteImage, target);
            canvas.restore();
        }
    }

    /** 自定义布局：按场景 0–1 坐标还原文字框，再按顺序叠图片。 */
    private void drawCustomText(Canvas canvas, boolean dark) {
        String text = text();
        String meta = fullMeta();
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
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.clipRect(rect);
        canvas.translate(rect.left + pad, rect.top + pad);
        body.draw(canvas);
        canvas.restore();

        metaPaint.setColor(ThemeColors.ensureReadable(themeColor(), dark));
        metaPaint.setTextSize(sp(10));
        canvas.drawText(meta, rect.left + pad, rect.bottom - pad, metaPaint);
        canvas.restore();
    }

    private void drawSceneImages(Canvas canvas) {
        for (int i = 0; i < Math.min(QuoteWidgetProvider.MAX_IMAGE_SLOTS,
                scene.images.size()); i++) {
            Bitmap bitmap = sceneImages[i];
            if (bitmap == null || bitmap.isRecycled()) continue;
            CustomScene.ImageBox box = scene.images.get(i);
            RectF rect = boxRect(box);
            canvas.save();
            canvas.rotate(CustomScene.normalizeRotation(box.rotation),
                    rect.centerX(), rect.centerY());
            canvas.scale(box.flipH ? -1f : 1f, box.flipV ? -1f : 1f,
                    rect.centerX(), rect.centerY());
            drawFitCenter(canvas, bitmap, rect);
            canvas.restore();
        }
    }

    /** 「点按换一句」只在 4×2 且高度够时才出现（与桌面判断一致）。 */
    private void drawHint(Canvas canvas, boolean dark) {
        if (card.height() < dp(140)) return;
        Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hint.setColor(dark ? 0x66FDFCFF : 0x776F80);
        hint.setTextSize(sp(8));
        hint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("点按换一句", card.right - dp(10), card.bottom - dp(6), hint);
    }

    // ---- 工具 ----

    private String text() {
        String text = preset.values.optString("snapshotText", "").trim();
        if (text.isEmpty()) text = SAMPLE;
        return text;
    }

    /** 2×2 和桌面一样只保留作者与作品。 */
    private String compactMeta() {
        String meta = preset.values.optString("snapshotMeta", "").trim();
        if (meta.isEmpty()) return SAMPLE_META;
        // 形如「— 作者 · 《作品》 · 类型 · 分类」，去掉后两段
        String[] parts = meta.split(" · ");
        if (parts.length <= 2) return meta;
        return parts[0] + " · " + parts[1];
    }

    private String fullMeta() {
        String meta = preset.values.optString("snapshotMeta", "").trim();
        return meta.isEmpty() ? SAMPLE_META : meta;
    }

    private float fontPx() {
        return sp(preset.values.optInt("fontSize", 16));
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private int themeColor() {
        if (preset.values.optBoolean("monet", false)) {
            return ThemeColors.systemAccent(getContext());
        }
        return preset.values.optInt(ThemeColors.KEY, ThemeColors.DEFAULT_COLOR);
    }

    private void drawFitCenter(Canvas canvas, Bitmap bitmap, RectF target) {
        float scale = Math.min(target.width() / bitmap.getWidth(),
                target.height() / bitmap.getHeight());
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        float left = target.left + (target.width() - w) / 2f;
        float top = target.top + (target.height() - h) / 2f;
        backgroundPaint.setShader(null);
        canvas.drawBitmap(bitmap, null,
                new RectF(left, top, left + w, top + h), backgroundPaint);
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

    private RectF boxRect(CustomScene.Box box) {
        return new RectF(
                card.left + box.x * card.width(),
                card.top + box.y * card.height(),
                card.left + (box.x + box.w) * card.width(),
                card.top + (box.y + box.h) * card.height());
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
