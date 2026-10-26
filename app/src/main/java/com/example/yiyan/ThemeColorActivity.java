package com.example.yiyan;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
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

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * 主题色二级页。
 *
 * 色卡用 Material 3 Expressive 的大圆角色块；选中有勾选、描边和弹性回弹。
 * 自定义取色器带「触摸点」：面板上是白圈标记，色相条上有游标，拖动即时反馈。
 */
public class ThemeColorActivity extends ThemedActivity {

    private static final long WIDGET_REFRESH_DELAY_MS = 160L;

    private final List<Swatch> swatches = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshWidgets = () -> sendBroadcast(
            new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));

    private SharedPreferences prefs;
    private MaterialCardView heroCard;
    private TextView heroCaption;
    private TextView heroName;
    private TextView heroHex;
    private TextView heroAa;
    private TextView customHex;
    private ColorPickerView picker;
    private ValueAnimator heroAnimator;
    private int selectedColor;
    private int shownColor = -1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_color);
        ScreenInsets.applyTo(this);

        prefs = getSharedPreferences("settings", 0);
        selectedColor = ThemeColors.saved(prefs);

        heroCard = findViewById(R.id.hero_card);
        heroCaption = findViewById(R.id.hero_caption);
        heroName = findViewById(R.id.hero_name);
        heroHex = findViewById(R.id.hero_hex);
        heroAa = findViewById(R.id.hero_aa);
        customHex = findViewById(R.id.custom_hex);
        picker = findViewById(R.id.theme_color_picker);

        findViewById(R.id.theme_back).setOnClickListener(v -> finish());
        findViewById(R.id.reset_color).setOnClickListener(v -> {
            picker.setColor(ThemeColors.DEFAULT_COLOR);
            applyColor(ThemeColors.DEFAULT_COLOR, false);
        });

        boolean monet = prefs.getBoolean("monet", false);
        findViewById(R.id.monet_note).setVisibility(monet ? View.VISIBLE : View.GONE);

        buildSwatches();
        picker.setColor(selectedColor);
        picker.setOnColorChangedListener(color -> applyColor(color, true));

        render(selectedColor, false);
        updateSwatches(false);
        customHex.setText(ThemeColors.hex(selectedColor));
    }

    /** 色卡网格：4 列，Material 3 Expressive 的大圆角色块 + 底部名称。 */
    private void buildSwatches() {
        LinearLayout container = findViewById(R.id.swatch_container);
        container.removeAllViews();
        swatches.clear();

        for (int start = 0; start < ThemeColors.PRESET_COLORS.length; start += 4) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (start > 0) rowLp.topMargin = dp(8);
            row.setLayoutParams(rowLp);
            container.addView(row);

            for (int i = start; i < Math.min(start + 4, ThemeColors.PRESET_COLORS.length); i++) {
                row.addView(createSwatch(i));
            }
        }
    }

    private View createSwatch(int index) {
        int color = ThemeColors.PRESET_COLORS[index];
        String name = ThemeColors.PRESET_NAMES[index];
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));
        card.setContentDescription(name);

        FrameLayout content = new FrameLayout(this);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));

        ImageView check = new ImageView(this);
        FrameLayout.LayoutParams checkLp = new FrameLayout.LayoutParams(
                dp(20), dp(20), Gravity.TOP | Gravity.END);
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
        card.setOnClickListener(v -> applyColor(color, false));

        Swatch swatch = new Swatch(card, check, label, color, name);
        swatches.add(swatch);
        return cell;
    }

    private void applyColor(int color, boolean fromPicker) {
        selectedColor = color;
        // 选自定义色时自动关掉 Monet，否则用户会看到「选了色但没有生效」
        prefs.edit().putInt(ThemeColors.KEY, color).putBoolean("monet", false).apply();
        if (!fromPicker) picker.setColor(color);
        findViewById(R.id.monet_note).setVisibility(View.GONE);
        render(color, true);
        updateSwatches(true);
        customHex.setText(ThemeColors.hex(color));
        scheduleWidgetRefresh();
    }

    private void render(int color, boolean animate) {
        heroName.setText(ThemeColors.nameOf(color));
        heroHex.setText(ThemeColors.hex(color));

        int onColor = ThemeColors.contrastOn(color);
        heroName.setTextColor(onColor);
        heroAa.setTextColor(onColor);
        heroHex.setTextColor((onColor & 0x00FFFFFF) | 0xB3000000);
        heroCaption.setTextColor((onColor & 0x00FFFFFF) | 0xD9000000);

        if (heroAnimator != null) heroAnimator.cancel();
        if (animate && shownColor != -1 && shownColor != color) {
            heroAnimator = ValueAnimator.ofObject(
                    new ArgbEvaluator(), shownColor, color);
            heroAnimator.setDuration(240);
            heroAnimator.setInterpolator(new DecelerateInterpolator());
            heroAnimator.addUpdateListener(a ->
                    heroCard.setCardBackgroundColor((int) a.getAnimatedValue()));
            heroAnimator.start();
        } else {
            heroCard.setCardBackgroundColor(color);
        }
        shownColor = color;
    }

    private void updateSwatches(boolean animate) {
        for (Swatch swatch : swatches) {
            boolean selected = swatch.color == selectedColor;
            if (selected == swatch.selected && animate) continue;
            swatch.selected = selected;

            int onColor = ThemeColors.contrastOn(swatch.color);
            swatch.check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            swatch.check.setColorFilter(onColor);
            swatch.card.setStrokeWidth(dp(selected ? 3 : 1));
            swatch.card.setStrokeColor(selected ? onColor : 0x33000000);
            swatch.label.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            swatch.label.setTextColor(selected
                    ? MaterialColors.getColor(this,
                            com.google.android.material.R.attr.colorPrimary, ThemeColors.DEFAULT_COLOR)
                    : MaterialColors.getColor(this,
                            com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F));

            float scale = selected ? 1.045f : 1f;
            if (animate) {
                swatch.card.animate()
                        .scaleX(scale).scaleY(scale)
                        .setDuration(selected ? 220 : 160)
                        .setInterpolator(selected
                                ? new OvershootInterpolator(1.35f)
                                : new DecelerateInterpolator())
                        .start();
            } else {
                swatch.card.setScaleX(scale);
                swatch.card.setScaleY(scale);
            }
        }
    }

    private void scheduleWidgetRefresh() {
        handler.removeCallbacks(refreshWidgets);
        handler.postDelayed(refreshWidgets, WIDGET_REFRESH_DELAY_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 拖完取色器马上返回桌面时，别把最后一次改动留在 debounce 里
        handler.removeCallbacks(refreshWidgets);
        refreshWidgets.run();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshWidgets);
        if (heroAnimator != null) heroAnimator.cancel();
        super.onDestroy();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Swatch {
        final MaterialCardView card;
        final ImageView check;
        final TextView label;
        final int color;
        final String name;
        boolean selected;

        Swatch(MaterialCardView card, ImageView check, TextView label, int color, String name) {
            this.card = card;
            this.check = check;
            this.label = label;
            this.color = color;
            this.name = name;
        }
    }
}
