package com.example.yiyan;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义背景二级页。
 *
 * 纯色和渐变都按小组件实际尺寸重新绘制，不是把预览位图拉伸；
 * 色卡选中后有勾选、描边和弹性回弹。图片入口保留原来的相册选择器。
 */
public class BackgroundStyleActivity extends ThemedActivity {

    private static final long WIDGET_REFRESH_DELAY_MS = 160L;

    private final List<StyleChoice> solidChoices = new ArrayList<>();
    private final List<StyleChoice> gradientChoices = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshWidgets = () -> sendBroadcast(
            new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) applyImage(uri);
            });

    private SharedPreferences prefs;
    private FrameLayout preview;
    private TextView previewType;
    private TextView previewName;
    private TextView customHex;
    private TextView imageStatus;
    private ColorPickerView picker;
    private int previewToken;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_background_style);
        ScreenInsets.applyTo(this);

        prefs = getSharedPreferences("settings", 0);
        preview = findViewById(R.id.bg_preview);
        previewType = findViewById(R.id.bg_preview_type);
        previewName = findViewById(R.id.bg_preview_name);
        customHex = findViewById(R.id.bg_custom_hex);
        imageStatus = findViewById(R.id.bg_image_status);
        picker = findViewById(R.id.bg_color_picker);

        findViewById(R.id.bg_back).setOnClickListener(v -> finish());
        findViewById(R.id.bg_pick_image).setOnClickListener(v -> {
            try {
                photoPicker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
            } catch (Exception e) {
                Toast.makeText(this, "没有可用的相册选择器", Toast.LENGTH_LONG).show();
            }
        });

        buildSolidChoices();
        buildGradientChoices();

        int initial = CustomBackground.TYPE_SOLID.equals(CustomBackground.type(prefs))
                ? CustomBackground.solidColor(prefs)
                : CustomBackground.TYPE_GRADIENT.equals(CustomBackground.type(prefs))
                ? CustomBackground.gradientStart(prefs)
                : ThemeColors.saved(prefs);
        picker.setColor(initial);
        customHex.setText(ThemeColors.hex(initial));
        picker.setOnColorChangedListener(color -> {
            customHex.setText(ThemeColors.hex(color));
            applySolid(color);
        });

        renderPreview();
        updateChoices(false);
        refreshImageStatus();
    }

    // ---- 纯色色卡 ----

    private void buildSolidChoices() {
        LinearLayout container = findViewById(R.id.solid_container);
        container.removeAllViews();
        solidChoices.clear();

        for (int start = 0; start < CustomBackground.SOLID_COLORS.length; start += 4) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (start > 0) rowLp.topMargin = dp(8);
            row.setLayoutParams(rowLp);
            container.addView(row);

            for (int i = start; i < Math.min(start + 4, CustomBackground.SOLID_COLORS.length); i++) {
                row.addView(createSolidCell(i));
            }
        }
    }

    private View createSolidCell(int index) {
        int color = CustomBackground.SOLID_COLORS[index];
        String name = CustomBackground.SOLID_NAMES[index];
        int onColor = ThemeColors.contrastOn(color);

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cellLp.setMargins(dp(4), 0, dp(4), 0);
        cell.setLayoutParams(cellLp);

        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(0f);
        card.setRadius(dp(20));
        card.setCardBackgroundColor(color);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0x33000000);
        card.setClickable(true);
        card.setFocusable(true);
        card.setRippleColor(ColorStateList.valueOf((onColor & 0x00FFFFFF) | 0x33000000));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        card.setContentDescription(name);

        FrameLayout content = new FrameLayout(this);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));

        ImageView check = new ImageView(this);
        check.setLayoutParams(new FrameLayout.LayoutParams(
                dp(20), dp(20), Gravity.TOP | Gravity.END));
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(onColor);
        check.setVisibility(View.INVISIBLE);
        content.addView(check);

        TextView aa = new TextView(this);
        aa.setText("Aa");
        aa.setTextSize(17);
        aa.setTextColor(onColor);
        aa.setAlpha(0.72f);
        aa.setTypeface(Typeface.DEFAULT_BOLD);
        aa.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        content.addView(aa);
        card.addView(content);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(5);
        label.setLayoutParams(labelLp);

        cell.addView(card);
        cell.addView(label);
        card.setOnClickListener(v -> applySolid(color));

        solidChoices.add(new StyleChoice(card, check, label, color, -1));
        return cell;
    }

    // ---- 渐变色卡 ----

    private void buildGradientChoices() {
        LinearLayout container = findViewById(R.id.gradient_container);
        container.removeAllViews();
        gradientChoices.clear();

        for (int start = 0; start < CustomBackground.GRADIENTS.length; start += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (start > 0) rowLp.topMargin = dp(8);
            row.setLayoutParams(rowLp);
            container.addView(row);

            for (int i = start; i < Math.min(start + 2, CustomBackground.GRADIENTS.length); i++) {
                row.addView(createGradientCell(i));
            }
        }
    }

    private View createGradientCell(int index) {
        int start = CustomBackground.GRADIENTS[index][0];
        int end = CustomBackground.GRADIENTS[index][1];
        String name = CustomBackground.GRADIENT_NAMES[index];
        int onColor = ThemeColors.contrastOn(average(start, end));

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cellLp.setMargins(dp(4), 0, dp(4), 0);
        cell.setLayoutParams(cellLp);

        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(0f);
        card.setRadius(dp(20));
        card.setCardBackgroundColor(Color.TRANSPARENT);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0x33000000);
        card.setClickable(true);
        card.setFocusable(true);
        card.setClipToOutline(true);
        card.setRippleColor(ColorStateList.valueOf((onColor & 0x00FFFFFF) | 0x33000000));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));
        card.setContentDescription(name);

        FrameLayout content = new FrameLayout(this);
        View fill = new View(this);
        fill.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        gradient.setCornerRadius(dp(20));
        fill.setBackground(gradient);
        content.addView(fill);

        ImageView check = new ImageView(this);
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(
                dp(20), dp(20), Gravity.TOP | Gravity.END);
        checkLp.setMargins(dp(8), dp(8), dp(8), dp(8));
        check.setLayoutParams(checkLp);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(onColor);
        check.setVisibility(View.INVISIBLE);
        content.addView(check);

        TextView aa = new TextView(this);
        aa.setText("Aa");
        aa.setTextSize(17);
        aa.setTextColor(onColor);
        aa.setAlpha(0.72f);
        aa.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams aaLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        aaLp.setMargins(dp(10), dp(8), dp(8), dp(8));
        aa.setLayoutParams(aaLp);
        content.addView(aa);
        card.addView(content);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(5);
        label.setLayoutParams(labelLp);

        cell.addView(card);
        cell.addView(label);
        card.setOnClickListener(v -> applyGradient(index));

        gradientChoices.add(new StyleChoice(card, check, label, -1, index));
        return cell;
    }

    // ---- 应用 ----

    private void applySolid(int color) {
        prefs.edit()
                .putString(CustomBackground.KEY_TYPE, CustomBackground.TYPE_SOLID)
                .putInt(CustomBackground.KEY_SOLID, color)
                .putBoolean(CustomBackground.KEY_ENABLED, true)
                .putBoolean(GlassRenderer.KEY_ENABLED, false)
                .apply();
        CustomBackground.invalidate();
        if (picker.getColor() != color) picker.setColor(color);
        customHex.setText(ThemeColors.hex(color));
        renderPreview();
        updateChoices(true);
        refreshImageStatus();
        scheduleWidgetRefresh();
    }

    private void applyGradient(int index) {
        int start = CustomBackground.GRADIENTS[index][0];
        int end = CustomBackground.GRADIENTS[index][1];
        int angle = CustomBackground.GRADIENTS[index][2];
        prefs.edit()
                .putString(CustomBackground.KEY_TYPE, CustomBackground.TYPE_GRADIENT)
                .putInt(CustomBackground.KEY_GRADIENT_START, start)
                .putInt(CustomBackground.KEY_GRADIENT_END, end)
                .putInt("customBgGradientAngle", angle)
                .putString(CustomBackground.KEY_GRADIENT_NAME, CustomBackground.GRADIENT_NAMES[index])
                .putBoolean(CustomBackground.KEY_ENABLED, true)
                .putBoolean(GlassRenderer.KEY_ENABLED, false)
                .apply();
        CustomBackground.invalidate();
        picker.setColor(start);
        customHex.setText(ThemeColors.hex(start));
        renderPreview();
        updateChoices(true);
        refreshImageStatus();
        scheduleWidgetRefresh();
    }

    private void applyImage(android.net.Uri uri) {
        String name = ImageStore.importFrom(this, uri);
        if (name == null) {
            Toast.makeText(this, "这张图读不了（或超过 16MB）", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit()
                .putString(CustomBackground.KEY_TYPE, CustomBackground.TYPE_IMAGE)
                .putString(CustomBackground.KEY_NAME, name)
                .putBoolean(CustomBackground.KEY_ENABLED, true)
                .putBoolean(GlassRenderer.KEY_ENABLED, false)
                .apply();
        CustomBackground.invalidate();
        renderPreview();
        updateChoices(true);
        refreshImageStatus();
        scheduleWidgetRefresh();
        Toast.makeText(this, "已设为图片背景", Toast.LENGTH_SHORT).show();
    }

    // ---- 预览与选中态 ----

    private void renderPreview() {
        final int token = ++previewToken;
        String type = CustomBackground.type(prefs);
        if (CustomBackground.TYPE_SOLID.equals(type)) {
            int color = CustomBackground.solidColor(prefs);
            setPreviewBackground(solidDrawable(color));
            applyPreviewText(ThemeColors.contrastOn(color));
            previewType.setText("纯色背景");
            previewName.setText(ThemeColors.hex(color));
        } else if (CustomBackground.TYPE_GRADIENT.equals(type)) {
            int start = CustomBackground.gradientStart(prefs);
            int end = CustomBackground.gradientEnd(prefs);
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, new int[]{start, end});
            gradient.setCornerRadius(dp(32));
            gradient.setStroke(dp(1), 0x22000000);
            setPreviewBackground(gradient);
            applyPreviewText(ThemeColors.contrastOn(average(start, end)));
            previewType.setText("渐变背景");
            previewName.setText(CustomBackground.gradientName(prefs));
        } else {
            String name = CustomBackground.name(prefs);
            previewType.setText("图片背景");
            previewName.setText(name.isEmpty() ? "未设置" : name);
            if (name.isEmpty()) {
                applyPreviewText(0xFF1C1B1F);
                setPreviewBackground(solidDrawable(0xFFF6F6F8));
            } else {
                applyPreviewText(0xFFFFFFFF);
                setPreviewBackground(solidDrawable(0xFFF6F6F8));
                new Thread(() -> {
                    android.graphics.Bitmap bitmap = ImageStore.decode(
                            getApplicationContext(), name, 800);
                    if (bitmap == null) return;
                    RoundedBitmapDrawable rounded = RoundedBitmapDrawableFactory.create(
                            getResources(), bitmap);
                    rounded.setCornerRadius(dp(32));
                    rounded.setAntiAlias(true);
                    runOnUiThread(() -> {
                        if (token != previewToken) return;
                        preview.setBackground(rounded);
                    });
                }, "bg-preview-image").start();
            }
        }
    }

    private void setPreviewBackground(Drawable drawable) {
        preview.setBackground(drawable);
    }

    private void applyPreviewText(int color) {
        previewName.setTextColor(color);
        previewType.setTextColor((color & 0x00FFFFFF) | 0xB3000000);
    }

    private GradientDrawable solidDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(32));
        drawable.setStroke(dp(1), 0x22000000);
        return drawable;
    }

    private void updateChoices(boolean animate) {
        String type = CustomBackground.type(prefs);
        int solid = CustomBackground.solidColor(prefs);
        int gradientStart = CustomBackground.gradientStart(prefs);
        int gradientEnd = CustomBackground.gradientEnd(prefs);

        for (StyleChoice choice : solidChoices) {
            boolean selected = CustomBackground.TYPE_SOLID.equals(type)
                    && choice.solidColor == solid;
            bindChoice(choice, selected, animate, ThemeColors.contrastOn(choice.solidColor));
        }
        for (StyleChoice choice : gradientChoices) {
            int index = choice.gradientIndex;
            boolean selected = CustomBackground.TYPE_GRADIENT.equals(type)
                    && CustomBackground.GRADIENTS[index][0] == gradientStart
                    && CustomBackground.GRADIENTS[index][1] == gradientEnd;
            int color = average(CustomBackground.GRADIENTS[index][0],
                    CustomBackground.GRADIENTS[index][1]);
            bindChoice(choice, selected, animate, ThemeColors.contrastOn(color));
        }
    }

    private void bindChoice(StyleChoice choice, boolean selected, boolean animate, int onColor) {
        if (selected == choice.selected && animate) return;
        choice.selected = selected;

        choice.check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        choice.check.setColorFilter(onColor);
        choice.card.setStrokeWidth(dp(selected ? 3 : 1));
        choice.card.setStrokeColor(selected ? onColor : 0x33000000);
        choice.label.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        choice.label.setTextColor(selected
                ? MaterialColors.getColor(this,
                        com.google.android.material.R.attr.colorPrimary, ThemeColors.DEFAULT_COLOR)
                : MaterialColors.getColor(this,
                        com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F));

        float scale = selected ? 1.04f : 1f;
        if (animate) {
            choice.card.animate()
                    .scaleX(scale).scaleY(scale)
                    .setDuration(selected ? 220 : 160)
                    .setInterpolator(selected
                            ? new OvershootInterpolator(1.35f)
                            : new DecelerateInterpolator())
                    .start();
        } else {
            choice.card.setScaleX(scale);
            choice.card.setScaleY(scale);
        }
    }

    private void refreshImageStatus() {
        String name = CustomBackground.name(prefs);
        if (name.isEmpty()) {
            imageStatus.setText("图片背景：未设置");
        } else if (CustomBackground.TYPE_IMAGE.equals(CustomBackground.type(prefs))) {
            imageStatus.setText("图片背景：" + name + " · 使用中");
        } else {
            imageStatus.setText("相册图片：" + name + " · 点下方切换为图片背景");
        }
    }

    private void scheduleWidgetRefresh() {
        handler.removeCallbacks(refreshWidgets);
        handler.postDelayed(refreshWidgets, WIDGET_REFRESH_DELAY_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refreshWidgets);
        refreshWidgets.run();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshWidgets);
        super.onDestroy();
    }

    private int average(int start, int end) {
        return androidx.core.graphics.ColorUtils.blendARGB(start, end, 0.5f);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class StyleChoice {
        final MaterialCardView card;
        final ImageView check;
        final TextView label;
        final int solidColor;
        final int gradientIndex;
        boolean selected;

        StyleChoice(MaterialCardView card, ImageView check, TextView label,
                    int solidColor, int gradientIndex) {
            this.card = card;
            this.check = check;
            this.label = label;
            this.solidColor = solidColor;
            this.gradientIndex = gradientIndex;
        }
    }
}
