package com.example.yiyan;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SettingsActivity extends ThemedActivity {

    private static final int REQUEST_IMPORT_XML = 4401;
    private static final int REQUEST_PICK_BACKGROUND = 4402;
    private static final String KEY_UPDATE_CHANNEL = "updateChannel";


    /** 官方推荐的相册选择器：避免 startActivityForResult 在部分 ROM 上拿不到结果。 */
    private final androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> photoPicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri == null) return;
                        onBackgroundPicked(uri);
                    });

    private SharedPreferences prefs;
    private boolean autoChecked;
    private boolean wallpaperGranted;
    private UpdateChecker.Release latestRelease;

    /** 底部 Tab 的三个面板，切换时做同方向的平滑滑动。 */
    private static final int[] PANEL_IDS = {
            R.id.quotes_panel, R.id.theme_panel, R.id.settings_panel};
    private int currentPanelIndex = 0;

    private final android.os.Handler refreshHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshWidgets = () -> sendBroadcast(
            new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ScreenInsets.applyTo(this);
        prefs = getSharedPreferences("settings", 0);

        findViewById(R.id.manage_quotes).setOnClickListener(v -> startActivity(new Intent(this, ManageQuotesActivity.class)));
        findViewById(R.id.add_widget).setOnClickListener(v -> new WidgetPinHelper(this).add());

        bindOrderMode();
        bindCategory();
        bindShowHitokoto();
        bindParaphraseSwitch();
        bindFontSize();
        bindMonet();
        bindThemeColor();
        bindGlass();
        bindGlassPermission();
        bindCustomBackground();
        bindWidgetPresets();
        bindRootHomeWatch();
        bindAddQuote();
        bindCustomLayout();
        bindImport();
        bindUpdate();
        bindRefreshAll();
        bindBottomNav();
        restorePanel(savedInstanceState);
        requestPrivileges();
    }

    /**
     * 进入 App 时主动要权限：先问 root 管理器（Magisk 弹授权框），
     * 再问 Shizuku（已运行且未授权时弹 Shizuku 的授权框）。
     */
    private void requestPrivileges() {
        new Thread(() -> {
            final boolean root = RootHomeWatcher.requestRoot();
            final boolean shizukuInstalled = RootHomeWatcher.shizukuInstalled(this);
            // 没授权时 Shizuku 不会把 binder 给我们，所以「拿不到 binder」不能当成「没运行」
            final boolean shizukuRunning = RootHomeWatcher.awaitShizuku(1500);
            final boolean shizukuGranted = shizukuRunning && RootHomeWatcher.shizukuGranted();
            runOnUiThread(() -> {
                if (shizukuInstalled && !shizukuGranted) {
                    RootHomeWatcher.requestShizukuPermission();
                }
                refreshRootStatus(findViewById(R.id.root_status));
                if (!root && !shizukuInstalled) {
                    Toast.makeText(this, "没有 root，也没安装 Shizuku", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /**
     * 液态玻璃：开关 + 模糊 / 浓度 / 高光 / 圆角 + 色调。
     * 拖动滑块时只更新预览，落稳 350ms 后再广播刷新桌面，避免每个 step 都重建位图。
     */
    private void bindGlass() {
        final GlassPreviewView preview = findViewById(R.id.glass_preview);
        MaterialSwitch enabled = findViewById(R.id.glass_enabled);
        enabled.setChecked(GlassRenderer.enabled(prefs));
        enabled.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(GlassRenderer.KEY_ENABLED, checked).apply();
            if (checked) {
                // 互斥：开玻璃就关掉自定义背景
                prefs.edit().putBoolean(CustomBackground.KEY_ENABLED, false).apply();
                syncCustomBackgroundSwitch();
                refreshCustomBackgroundStatus();
                refreshPreviewBackdrop();
            }
            previewSpec();
            scheduleWidgetRefresh();
            Toast.makeText(this, checked
                            ? "已开启玻璃材质，回桌面看效果"
                            : "已关闭玻璃材质，恢复纯色底",
                    Toast.LENGTH_SHORT).show();
        });

        com.google.android.material.button.MaterialButtonToggleGroup material =
                findViewById(R.id.glass_material_group);
        material.setVisibility(android.view.View.GONE);
        prefs.edit().putString(GlassRenderer.KEY_MATERIAL, GlassRenderer.MATERIAL_FROST).apply();

        findViewById(R.id.open_live_glass).setOnClickListener(v ->
                startActivity(new Intent(this, LiquidGlassActivity.class)));
        findViewById(R.id.open_glass_advanced).setOnClickListener(v ->
                startActivity(new Intent(this, GlassAdvancedActivity.class)));

        bindGlassSlider(R.id.glass_blur, R.id.glass_blur_label, "雾面程度",
                GlassRenderer.KEY_BLUR, GlassRenderer.DEFAULT_BLUR, "%");
        bindGlassSlider(R.id.glass_density, R.id.glass_density_label, "玻璃浓度",
                GlassRenderer.KEY_DENSITY, GlassRenderer.DEFAULT_DENSITY, "%");
        bindGlassSlider(R.id.glass_highlight, R.id.glass_highlight_label, "高光 / 折射",
                GlassRenderer.KEY_HIGHLIGHT, GlassRenderer.DEFAULT_HIGHLIGHT, "%");
        bindGlassSlider(R.id.glass_corner, R.id.glass_corner_label, "圆角",
                GlassRenderer.KEY_CORNER, GlassRenderer.DEFAULT_CORNER, "dp");

        com.google.android.material.button.MaterialButtonToggleGroup tone =
                findViewById(R.id.glass_tone_group);
        String savedTone = prefs.getString(GlassRenderer.KEY_TONE, GlassRenderer.TONE_AUTO);
        tone.check(GlassRenderer.TONE_DARK.equals(savedTone) ? R.id.glass_tone_dark
                : GlassRenderer.TONE_LIGHT.equals(savedTone) ? R.id.glass_tone_light
                : R.id.glass_tone_auto);
        tone.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String value = checkedId == R.id.glass_tone_dark ? GlassRenderer.TONE_DARK
                    : checkedId == R.id.glass_tone_light ? GlassRenderer.TONE_LIGHT
                    : GlassRenderer.TONE_AUTO;
            prefs.edit().putString(GlassRenderer.KEY_TONE, value).apply();
            previewSpec();
            scheduleWidgetRefresh();
        });
        previewSpec();
    }

    private void bindGlassSlider(int sliderId, int labelId, final String base,
                                 final String key, int defaultValue, final String unit) {
        final Slider slider = findViewById(sliderId);
        final TextView label = findViewById(labelId);
        int value = prefs.getInt(key, defaultValue);
        value = Math.round(Math.max(slider.getValueFrom(), Math.min(slider.getValueTo(), value)));
        slider.setValue(value);
        label.setText(base + " · " + value + unit);
        slider.addOnChangeListener((s, v, fromUser) -> {
            int rounded = Math.round(v);
            label.setText(base + " · " + rounded + unit);
            prefs.edit().putInt(key, rounded).apply();
            previewSpec();
            scheduleWidgetRefresh();
        });
    }

    private void previewSpec() {
        GlassPreviewView preview = findViewById(R.id.glass_preview);
        GlassRenderer.Spec spec = GlassRenderer.spec(this, prefs);
        preview.setSpec(spec, GlassRenderer.enabled(prefs));
    }

    /**
     * 毛玻璃要读壁纸位图：Android 14 起只有「所有文件访问」拿得到壁纸像素。
     * 这里只做一件事——把授权入口摆出来、把状态说清楚，不授权也能用取色兜底。
     */
    private void bindGlassPermission() {
        wallpaperGranted = WallpaperSource.canRead(this);
        findViewById(R.id.glass_wallpaper_grant).setOnClickListener(v -> {
            try {
                startActivity(WallpaperSource.permissionIntent(this));
            } catch (Exception e) {
                Toast.makeText(this, "打不开授权页，请到系统设置里手动打开「所有文件访问」",
                        Toast.LENGTH_LONG).show();
            }
        });
        refreshWallpaperPermission();
    }

    private void refreshWallpaperPermission() {
        boolean granted = WallpaperSource.canRead(this);
        TextView status = findViewById(R.id.glass_wallpaper_status);
        status.setText(granted
                ? "壁纸读取：已授权 · 玻璃会真的模糊桌面壁纸"
                : "壁纸读取：未授权 · 玻璃只会取壁纸颜色，不会模糊");
        findViewById(R.id.glass_wallpaper_grant).setEnabled(!granted);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs == null || isThemeChangePending()) return;
        refreshThemeColorRow();
        MaterialSwitch monet = findViewById(R.id.monet);
        if (monet != null && monet.isChecked() != prefs.getBoolean("monet", false)) {
            monet.setChecked(prefs.getBoolean("monet", false));
        }
        refreshCustomBackgroundUi();
        syncGlassSwitch();
        boolean granted = WallpaperSource.canRead(this);
        refreshWallpaperPermission();
        if (granted && !wallpaperGranted) {
            wallpaperGranted = true;
            WallpaperSource.invalidate();
            GlassPreviewView preview = findViewById(R.id.glass_preview);
            if (preview != null) preview.invalidateWallpaper();
            scheduleWidgetRefresh();
            Toast.makeText(this, "已授权：毛玻璃现在会用真实壁纸做模糊", Toast.LENGTH_SHORT).show();
        }
    }

    /** 自定义背景：图片 / GIF（首帧），可选高斯模糊与强度；和玻璃材质互斥。 */
    private void bindCustomBackground() {
        final MaterialSwitch enabled = findViewById(R.id.custom_bg_enabled);
        final MaterialSwitch blur = findViewById(R.id.custom_bg_blur);
        final Slider amount = findViewById(R.id.custom_bg_blur_amount);
        final TextView label = findViewById(R.id.custom_bg_blur_label);

        // 必须先选图 / 选色，才能打开「使用自定义背景」
        boolean hasContent = CustomBackground.hasContent(prefs);
        enabled.setEnabled(hasContent);
        enabled.setChecked(hasContent && CustomBackground.enabled(prefs));
        blur.setChecked(CustomBackground.blurred(prefs));
        amount.setValue(CustomBackground.blurAmount(prefs));
        label.setText("模糊程度 · " + Math.round(amount.getValue()) + "%");
        refreshCustomBackgroundStatus();

        findViewById(R.id.custom_bg_style).setOnClickListener(v ->
                startActivity(new Intent(this, BackgroundStyleActivity.class)));

        findViewById(R.id.custom_bg_pick).setOnClickListener(v -> {
            try {
                photoPicker.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                        .setMediaType(androidx.activity.result.contract.ActivityResultContracts
                                .PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
            } catch (Exception e) {
                if (!ImagePicker.pick(this, REQUEST_PICK_BACKGROUND)) {
                    Toast.makeText(this, "没有可用的相册选择器", Toast.LENGTH_SHORT).show();
                }
            }
        });

        enabled.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(CustomBackground.KEY_ENABLED, checked).apply();
            if (checked) {
                // 互斥：开自定义背景就关掉玻璃材质
                prefs.edit().putBoolean(GlassRenderer.KEY_ENABLED, false).apply();
                syncGlassSwitch();
                previewSpec();
            }
            CustomBackground.invalidate();
            refreshPreviewBackdrop();
            refreshCustomBackgroundStatus();
            scheduleWidgetRefresh();
        });
        blur.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(CustomBackground.KEY_BLUR, checked).apply();
            CustomBackground.invalidate();
            refreshPreviewBackdrop();
            refreshCustomBackgroundStatus();
            scheduleWidgetRefresh();
        });
        amount.addOnChangeListener((slider, value, fromUser) -> {
            int rounded = Math.round(value);
            label.setText("模糊程度 · " + rounded + "%");
            prefs.edit().putInt(CustomBackground.KEY_BLUR_AMOUNT, rounded).apply();
            if (CustomBackground.blurred(prefs)) {
                CustomBackground.invalidate();
                refreshPreviewBackdrop();
                refreshCustomBackgroundStatus();
                scheduleWidgetRefresh();
            }
        });
        refreshCustomBackgroundUi();
    }

    /** 选择器回调统一走这里：复制到私有目录 → 写 prefs → 打开开关 → 刷新。 */
    private void onBackgroundPicked(android.net.Uri picked) {
        String name = ImageStore.importFrom(this, picked);
        boolean exists = name != null && ImageStore.exists(this, name);
        android.util.Log.i("99YIYAN_LOG", "背景 pick: uri=" + picked + " name=" + name
                + " exists=" + exists);
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
        syncGlassSwitch();
        refreshCustomBackgroundUi();
        CustomBackground.invalidate();
        refreshPreviewBackdrop();
        scheduleWidgetRefresh();
        Toast.makeText(this, "已设为玻璃背景" + (CustomBackground.blurred(prefs) ? "（高斯模糊）" : ""),
                Toast.LENGTH_SHORT).show();
    }

    private void syncGlassSwitch() {
        MaterialSwitch glass = findViewById(R.id.glass_enabled);
        if (glass != null) glass.setChecked(GlassRenderer.enabled(prefs));
    }

    /** 只要选过图片 / 纯色 / 渐变就允许打开开关；内容真丢了渲染时会回退。 */
    private boolean hasCustomBackground() {
        return CustomBackground.hasContent(prefs);
    }

    private void syncCustomBackgroundSwitch() {
        MaterialSwitch bg = findViewById(R.id.custom_bg_enabled);
        if (bg != null) bg.setChecked(CustomBackground.enabled(prefs));
    }

    private void refreshPreviewBackdrop() {
        GlassPreviewView preview = findViewById(R.id.glass_preview);
        if (preview != null) preview.invalidateWallpaper();
    }

    private void refreshCustomBackgroundUi() {
        boolean has = hasCustomBackground();
        MaterialSwitch enabled = findViewById(R.id.custom_bg_enabled);
        if (enabled != null) {
            enabled.setEnabled(has);
            enabled.setChecked(has && CustomBackground.enabled(prefs));
        }
        boolean image = CustomBackground.TYPE_IMAGE.equals(CustomBackground.type(prefs));
        MaterialSwitch blur = findViewById(R.id.custom_bg_blur);
        Slider amount = findViewById(R.id.custom_bg_blur_amount);
        if (blur != null) {
            blur.setEnabled(image);
            blur.setAlpha(image ? 1f : 0.45f);
        }
        if (amount != null) {
            amount.setEnabled(image);
            amount.setAlpha(image ? 1f : 0.45f);
        }
        refreshCustomBackgroundStatus();
    }

    private void refreshCustomBackgroundStatus() {
        TextView status = findViewById(R.id.custom_bg_status);
        if (status == null) return;
        if (!hasCustomBackground()) {
            status.setText("自定义背景：未设置（图片 / 纯色 / 渐变均可）");
            return;
        }
        String type = CustomBackground.type(prefs);
        StringBuilder text = new StringBuilder("自定义背景：")
                .append(CustomBackground.styleLabel(prefs))
                .append(" · ")
                .append(CustomBackground.enabled(prefs) ? "使用中" : "未启用");
        if (CustomBackground.TYPE_IMAGE.equals(type)) {
            text.append(" · ").append(CustomBackground.blurred(prefs)
                    ? "高斯模糊 " + CustomBackground.blurAmount(prefs) + "%" : "不模糊");
        } else {
            text.append(" · 不参与模糊");
        }
        status.setText(text.toString());
    }
    // ---- 保存组件（外观预设） ----

    private void bindWidgetPresets() {
        findViewById(R.id.save_widget_preset).setOnClickListener(v -> {
            WidgetPresetStore.Preset preset = WidgetPresetStore.saveCurrent(prefs);
            if (preset == null) {
                Toast.makeText(this, "最多保存 " + WidgetPresetStore.MAX + " 个组件，请先删除一个",
                        Toast.LENGTH_LONG).show();
                return;
            }
            refreshSavedWidgets();
            Toast.makeText(this, "已保存「" + preset.name + "」", Toast.LENGTH_SHORT).show();
        });
        refreshSavedWidgets();
    }

    private void refreshSavedWidgets() {
        LinearLayout list = findViewById(R.id.saved_widget_list);
        TextView empty = findViewById(R.id.saved_widget_empty);
        if (list == null || empty == null) return;
        List<WidgetPresetStore.Preset> presets = WidgetPresetStore.load(prefs);
        empty.setVisibility(presets.isEmpty() ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        for (WidgetPresetStore.Preset preset : presets) {
            list.addView(buildWidgetPresetRow(preset));
        }
    }

    private View buildWidgetPresetRow(WidgetPresetStore.Preset preset) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(18));
        card.setCardElevation(0f);
        card.setCardBackgroundColor(getColor(R.color.md_surface_container_high));
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0x22000000);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(8);
        card.setLayoutParams(cardLp);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("应用 " + preset.name);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(8), dp(10));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(preset.name);
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColor(R.color.md_on_surface));
        labels.addView(name);

        TextView summary = new TextView(this);
        summary.setText(preset.summary());
        summary.setTextSize(11);
        summary.setMaxLines(2);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setTextColor(getColor(R.color.md_on_surface_variant));
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryLp.topMargin = dp(2);
        summary.setLayoutParams(summaryLp);
        labels.addView(summary);

        TextView delete = new TextView(this);
        delete.setText("删除");
        delete.setTextSize(12);
        delete.setTypeface(Typeface.DEFAULT_BOLD);
        delete.setTextColor(getColor(R.color.md_primary));
        delete.setGravity(Gravity.CENTER);
        delete.setPadding(dp(12), dp(6), dp(12), dp(6));
        delete.setClickable(true);
        delete.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        if (ripple.resourceId != 0) delete.setBackgroundResource(ripple.resourceId);
        delete.setOnClickListener(v -> {
            WidgetPresetStore.delete(prefs, preset.id);
            refreshSavedWidgets();
            Toast.makeText(this, "已删除「" + preset.name + "」", Toast.LENGTH_SHORT).show();
        });

        row.addView(labels);
        row.addView(delete);
        card.addView(row);
        // MaterialCardView 里再包 LinearLayout 时，部分 ROM 的触摸会被子层吃掉；
        // 卡片和行都挂上监听，点文字区域也能稳定触发。
        card.setOnClickListener(v -> confirmUseWidgetPreset(preset));
        card.setOnLongClickListener(v -> {
            showWidgetPresetEditMenu(preset);
            return true;
        });
        row.setClickable(true);
        row.setOnClickListener(v -> confirmUseWidgetPreset(preset));
        row.setOnLongClickListener(v -> {
            showWidgetPresetEditMenu(preset);
            return true;
        });
        return card;
    }

    /** 点击条目先二次确认，避免误触直接覆盖当前组件。 */
    private void confirmUseWidgetPreset(WidgetPresetStore.Preset preset) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("使用「" + preset.name + "」？")
                .setMessage(preset.summary())
                .setNegativeButton("取消", null)
                .setPositiveButton("使用", (dialog, which) -> applyWidgetPreset(preset))
                .show();
    }

    /** 长按编辑：查看 2×2 视图预览 / 重命名；删除仍走条目右侧按钮。 */
    private void showWidgetPresetEditMenu(WidgetPresetStore.Preset preset) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(preset.name)
                .setItems(new String[]{"查看小组件视图", "重命名"}, (dialog, which) -> {
                    if (which == 0) showWidgetPresetPreview(preset);
                    else showRenameWidgetPresetDialog(preset);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showWidgetPresetPreview(WidgetPresetStore.Preset preset) {
        View content = getLayoutInflater().inflate(R.layout.dialog_widget_preview, null);
        WidgetPresetPreviewView preview = content.findViewById(R.id.preset_preview_view);
        preview.setPreset(preset);
        ((TextView) content.findViewById(R.id.preset_preview_summary)).setText(preset.summary());
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("小组件视图 · " + preset.name)
                .setView(content)
                .setNegativeButton("关闭", null)
                .setPositiveButton("使用这套", (dialog, which) -> applyWidgetPreset(preset))
                .show();
    }

    private void showRenameWidgetPresetDialog(WidgetPresetStore.Preset preset) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(preset.name);
        input.setSelection(0, input.length());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("重命名组件")
                        .setView(box)
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", null)
                        .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.length() > 24) name = name.substring(0, 24);
                    WidgetPresetStore.rename(prefs, preset.id, name);
                    refreshSavedWidgets();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void applyWidgetPreset(WidgetPresetStore.Preset preset) {
        WidgetPresetStore.apply(prefs, preset);
        CustomBackground.invalidate();
        sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
        Toast.makeText(this, "已切换为「" + preset.name + "」", Toast.LENGTH_SHORT).show();
        recreate();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 回桌面自动换一句：root 常驻脚本监听焦点窗口，见 {@link RootHomeWatcher}。 */
    private void bindRootHomeWatch() {
        final MaterialSwitch watch = findViewById(R.id.root_home_watch);
        final TextView status = findViewById(R.id.root_status);
        watch.setChecked(RootHomeWatcher.isEnabled(this));
        refreshRootStatus(status);

        watch.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                button.setEnabled(false);
                new Thread(() -> {
                    boolean root = RootHomeWatcher.hasRoot();
                    boolean shizuku = RootHomeWatcher.awaitShizuku(2500);
                    if (!root && shizuku && !RootHomeWatcher.shizukuGranted()) {
                        runOnUiThread(() -> {
                            button.setEnabled(true);
                            RootHomeWatcher.requestShizukuPermission();
                            Toast.makeText(this, "请在 Shizuku 里允许「每日一言」",
                                    Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                    if (!root && !shizuku) {
                        runOnUiThread(() -> {
                            button.setEnabled(true);
                            button.setChecked(false);
                            refreshRootStatus(status);
                            Toast.makeText(this, "没有 root，也没检测到运行中的 Shizuku",
                                    Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                    final boolean ok = RootHomeWatcher.start(this);
                    prefs.edit().putBoolean(RootHomeWatcher.KEY_ENABLED, ok).apply();
                    runOnUiThread(() -> {
                        button.setEnabled(true);
                        button.setChecked(ok);
                        refreshRootStatus(status);
                        Toast.makeText(this, ok ? "已开启：回到桌面自动换一句"
                                        : "脚本没能启动，换个后端再试",
                                Toast.LENGTH_SHORT).show();
                    });
                }).start();
            } else {
                new Thread(() -> {
                    RootHomeWatcher.stop(this);
                    runOnUiThread(() -> refreshRootStatus(status));
                }).start();
                prefs.edit().putBoolean(RootHomeWatcher.KEY_ENABLED, false).apply();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != RootHomeWatcher.REQUEST_SHIZUKU) return;
        if (grantResults.length == 0 || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Shizuku 未授权", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            final boolean ok = RootHomeWatcher.start(this);
            prefs.edit().putBoolean(RootHomeWatcher.KEY_ENABLED, ok).apply();
            runOnUiThread(() -> {
                MaterialSwitch watch = findViewById(R.id.root_home_watch);
                watch.setChecked(ok);
                refreshRootStatus(findViewById(R.id.root_status));
                Toast.makeText(this, ok ? "已通过 Shizuku 开启" : "Shizuku 脚本启动失败",
                        Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    private void refreshRootStatus(TextView status) {
        new Thread(() -> {
            final boolean running = RootHomeWatcher.isRunning(this);
            final String backend = RootHomeWatcher.backend(this);
            final boolean root = RootHomeWatcher.rootGranted();
            final boolean installed = RootHomeWatcher.shizukuInstalled(this);
            final boolean shizukuRunning = RootHomeWatcher.awaitShizuku(1200);
            final boolean shizukuGranted = shizukuRunning && RootHomeWatcher.shizukuGranted();
            final String shizukuText = !installed ? "未安装"
                    : (shizukuGranted ? "已授权" : "未授权");
            final String label = RootHomeWatcher.BACKEND_SHIZUKU.equals(backend)
                    ? "Shizuku" : "root";
            runOnUiThread(() -> status.setText(
                    "Root：" + (root ? "已授权" : "未授权")
                            + " · Shizuku：" + shizukuText
                            + (running ? " · 监听中（" + label + "）" : " · 未监听")));
        }).start();
    }

    private void scheduleWidgetRefresh() {
        refreshHandler.removeCallbacks(refreshWidgets);
        refreshHandler.postDelayed(refreshWidgets, 350);
    }

    private void bindCustomLayout() {
        final MaterialSwitch custom = findViewById(R.id.custom_layout);
        custom.setChecked(prefs.getBoolean("customLayout", false));
        custom.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("customLayout", checked).apply();
            sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
            Toast.makeText(this, checked ? "已切换到自定义布局" : "已恢复默认排版",
                    Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.edit_layout).setOnClickListener(v -> {
            if (!prefs.getBoolean("customLayout", false)) {
                prefs.edit().putBoolean("customLayout", true).apply();
                custom.setChecked(true);
            }
            startActivity(new Intent(this, LayoutEditorActivity.class));
        });
    }

    private void bindOrderMode() {
        Spinner mode = findViewById(R.id.order_mode);
        mode.setAdapter(ArrayAdapter.createFromResource(this, R.array.order_modes,
                android.R.layout.simple_spinner_dropdown_item));
        mode.setSelection(prefs.getBoolean("randomMode", true) ? 0 : 1);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putBoolean("randomMode", position == 0).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void bindCategory() {
        Spinner category = findViewById(R.id.custom_category);
        ArrayList<String> cats = new ArrayList<>();
        cats.add("用户");

        cats.add("哲学");
        for (String saved : prefs.getStringSet("categories", new HashSet<String>())) {
            if (!cats.contains(saved)) cats.add(saved);
        }
        category.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, cats));
    }

    /** 内置 Hitokoto 语料（随机分类）是否参与桌面轮换。 */
    private void bindShowHitokoto() {
        MaterialSwitch show = findViewById(R.id.show_hitokoto);
        show.setChecked(prefs.getBoolean("showHitokoto", true));
        show.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("showHitokoto", checked).apply();
            sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
        });
    }

    private void bindParaphraseSwitch() {
        MaterialSwitch show = findViewById(R.id.show_paraphrase);
        show.setChecked(prefs.getBoolean("paraphrase", true));
        show.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean("paraphrase", checked).apply());
    }

    private void bindFontSize() {
        Slider size = findViewById(R.id.font_size);
        size.setValue(clampFontSize(prefs.getInt("fontSize", 16)));
        size.addOnChangeListener((slider, value, fromUser) ->
                prefs.edit().putInt("fontSize", Math.round(value)).apply());
    }

    private float clampFontSize(int value) {
        return Math.max(12f, Math.min(28f, value));
    }

    private void bindMonet() {
        MaterialSwitch monet = findViewById(R.id.monet);
        monet.setChecked(prefs.getBoolean("monet", false));
        monet.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("monet", checked).apply();
            refreshThemeColorRow();
            scheduleWidgetRefresh();
            // 开关自己占着前台，onResume 不会触发，必须主动重建才能让全 App 换色
            if (!isThemeChangePending()) button.post(() -> {
                if (!isFinishing()) recreate();
            });
        });
    }

    /** 主题色改为二级页；这里只保留入口、当前色圆点和摘要。 */
    private void bindThemeColor() {
        findViewById(R.id.open_theme_color).setOnClickListener(v ->
                startActivity(new Intent(this, ThemeColorActivity.class)));
        refreshThemeColorRow();
    }

    private void refreshThemeColorRow() {
        if (prefs == null) return;
        View dot = findViewById(R.id.theme_color_dot);
        TextView summary = findViewById(R.id.theme_color_summary);
        if (dot == null || summary == null) return;

        boolean monet = prefs.getBoolean("monet", false);
        int color = monet ? ThemeColors.systemAccent(this) : ThemeColors.saved(prefs);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        circle.setStroke(Math.max(1, Math.round(getResources().getDisplayMetrics().density)),
                0x33000000);
        dot.setBackground(circle);

        summary.setText(monet
                ? "跟随系统壁纸 · Monet"
                : ThemeColors.nameOf(ThemeColors.saved(prefs)) + " · "
                + ThemeColors.hex(ThemeColors.saved(prefs)));
    }

    private void bindAddQuote() {
        findViewById(R.id.add_quote).setOnClickListener(v -> {
            EditText textField = findViewById(R.id.custom_text);
            EditText authorField = findViewById(R.id.custom_author);
            EditText workField = findViewById(R.id.custom_work);
            Spinner category = findViewById(R.id.custom_category);

            String text = sanitize(textField.getText().toString());
            if (text.isEmpty()) {
                Toast.makeText(this, "句子是必填的", Toast.LENGTH_SHORT).show();
                textField.requestFocus();
                return;
            }
            // 作者与出处都是选填，留空时条目照样有效，小组件那一行会整段省略
            String author = sanitize(authorField.getText().toString());
            String work = sanitize(workField.getText().toString());
            String cat = String.valueOf(category.getSelectedItem());

            Set<String> quotes = new HashSet<>(prefs.getStringSet("custom_quotes", new HashSet<String>()));
            String line = new Quote(text, author, work, "用户添加", cat).toLine();
            if (!quotes.add(line)) {
                Toast.makeText(this, "这条一言已经在库里了", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putStringSet("custom_quotes", quotes).apply();

            textField.setText("");
            authorField.setText("");
            workField.setText("");
            sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
            Toast.makeText(this,
                    author.isEmpty() && work.isEmpty()
                            ? "已添加（未填写作者与出处）"
                            : "已加入“" + cat + "”分类",
                    Toast.LENGTH_SHORT).show();
        });
    }

    /** 存储用 "|" 分字段、换行分条目，所以用户输入里的这两个字符要替换掉。 */
    private String sanitize(String raw) {
        return raw.replace('|', '／').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void bindRefreshAll() {
        findViewById(R.id.refresh_all).setOnClickListener(v -> {
            sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
            Toast.makeText(this, "已应用并刷新小组件", Toast.LENGTH_SHORT).show();
        });
    }

    private void bindBottomNav() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        // 自己保存当前 Tab；BottomNav 的自动恢复会和面板可见性不同步
        nav.setSaveEnabled(false);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            int index = id == R.id.nav_theme ? 1 : id == R.id.nav_settings ? 2 : 0;
            switchPanel(index);
            if (index == 2) maybeAutoCheckUpdate();
            return true;
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("panelIndex", currentPanelIndex);
        super.onSaveInstanceState(outState);
    }

    /** recreate（例如应用保存的组件）后恢复原 Tab，避免图标选中但内容回到「一言」。 */
    private void restorePanel(Bundle savedInstanceState) {
        int index = savedInstanceState == null ? 0 : savedInstanceState.getInt("panelIndex", 0);
        if (index < 0 || index >= PANEL_IDS.length) index = 0;
        currentPanelIndex = index;
        for (int i = 0; i < PANEL_IDS.length; i++) {
            View panel = findViewById(PANEL_IDS[i]);
            panel.setVisibility(i == index ? View.VISIBLE : View.GONE);
            panel.setAlpha(1f);
            panel.setTranslationX(0f);
        }
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        int itemId = index == 1 ? R.id.nav_theme : index == 2 ? R.id.nav_settings : R.id.nav_quotes;
        nav.getMenu().findItem(itemId).setChecked(true);
        if (index == 2) maybeAutoCheckUpdate();
    }

    /** 面板切换：新页面从滑动方向进入，旧页面反向退场，避免生硬地 GONE/VISIBLE 跳变。 */
    private void switchPanel(int index) {
        if (index == currentPanelIndex) return;
        final int direction = index > currentPanelIndex ? 1 : -1;
        final int offset = Math.round(42 * getResources().getDisplayMetrics().density);
        final int smallOffset = Math.round(24 * getResources().getDisplayMetrics().density);

        for (int id : PANEL_IDS) findViewById(id).animate().cancel();

        final View incoming = findViewById(PANEL_IDS[index]);
        final View outgoing = findViewById(PANEL_IDS[currentPanelIndex]);
        for (int i = 0; i < PANEL_IDS.length; i++) {
            if (i == index || i == currentPanelIndex) continue;
            View hidden = findViewById(PANEL_IDS[i]);
            hidden.setVisibility(View.GONE);
            hidden.setAlpha(1f);
            hidden.setTranslationX(0f);
        }

        incoming.setVisibility(View.VISIBLE);
        incoming.setAlpha(0f);
        incoming.setTranslationX(direction * offset);
        incoming.bringToFront();
        incoming.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(280)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        outgoing.animate()
                .alpha(0f)
                .translationX(-direction * smallOffset)
                .setDuration(190)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    outgoing.setVisibility(View.GONE);
                    outgoing.setAlpha(1f);
                    outgoing.setTranslationX(0f);
                })
                .start();

        currentPanelIndex = index;
    }

    // ---- 导入 XML ----

    private void bindImport() {
        findViewById(R.id.import_xml).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "text/xml", "application/xml", "text/plain", "application/octet-stream"});
            try {
                startActivityForResult(intent, REQUEST_IMPORT_XML);
            } catch (Exception e) {
                Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.import_format_help).setOnClickListener(v -> showImportFormatDialog());
    }

    private void showImportFormatDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_import_format, null);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("导入格式和示例")
                .setView(content)
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_XML || resultCode != RESULT_OK || data == null) return;
        if (data.getData() == null) return;
        TextView status = findViewById(R.id.import_status);
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
            if (in == null) throw new java.io.IOException("无法打开文件");
            QuoteImporter.Result result = QuoteImporter.importXml(this, in);
            String text;
            if (result.error != null) {
                text = "导入失败：" + result.error;
            } else {
                text = "导入 " + result.imported + " 条，跳过重复/无效 " + result.skipped + " 条";
                if (result.imported > 0) {
                    sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
                }
            }
            status.setText(text);
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            status.setText("导入失败：" + e.getMessage());
            Toast.makeText(this, "导入失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- 检查更新 ----

    private void bindUpdate() {
        TextView current = findViewById(R.id.update_current);
        current.setText("当前版本 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        refreshChannelUi();

        findViewById(R.id.btn_check_update).setOnClickListener(v -> checkUpdate(false));
        findViewById(R.id.btn_download_update).setOnClickListener(v -> downloadUpdate());
        findViewById(R.id.open_help).setOnClickListener(v ->
                startActivity(new Intent(this, HelpActivity.class)));
        findViewById(R.id.btn_toggle_channel).setOnClickListener(v -> {
            boolean prerelease = UpdateChecker.CHANNEL_PRERELEASE.equals(updateChannel());
            prefs.edit().putString(KEY_UPDATE_CHANNEL,
                    prerelease ? UpdateChecker.CHANNEL_STABLE : UpdateChecker.CHANNEL_PRERELEASE).apply();
            refreshChannelUi();
            checkUpdate(false);
        });
    }

    private String updateChannel() {
        return prefs.getString(KEY_UPDATE_CHANNEL, UpdateChecker.CHANNEL_STABLE);
    }

    private void refreshChannelUi() {
        boolean prerelease = UpdateChecker.CHANNEL_PRERELEASE.equals(updateChannel());
        TextView channel = findViewById(R.id.update_channel);
        channel.setText("更新频道：GitHub Release"
                + (prerelease ? "（含预发布）" : "（稳定版）"));
        MaterialButton toggle = findViewById(R.id.btn_toggle_channel);
        toggle.setText(prerelease ? "切换到「稳定版」频道" : "切换到「含预发布」频道");
    }

    private void maybeAutoCheckUpdate() {
        if (autoChecked) return;
        autoChecked = true;
        checkUpdate(true);
    }

    private void checkUpdate(boolean silent) {
        TextView latest = findViewById(R.id.update_latest);
        TextView status = findViewById(R.id.update_status);
        status.setText("正在检查…");
        new Thread(() -> {
            try {
                UpdateChecker.Release release = UpdateChecker.fetchLatest(updateChannel());
                runOnUiThread(() -> {
                    this.latestRelease = release;
                    boolean newer = UpdateChecker.isNewer(release.tag, BuildConfig.VERSION_NAME);
                    latest.setText("最新版本 " + release.tag + (newer ? "（可更新）" : "（已是最新）"));
                    status.setText(newer
                            ? "发现新版本，点击下方按钮下载安装"
                            : "你已经在使用最新版本");
                    findViewById(R.id.btn_download_update).setEnabled(newer);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    latest.setText("检查失败");
                    status.setText("检查失败：" + e.getMessage()
                            + (silent ? "" : "\n请检查网络后重试"));
                });
            }
        }).start();
    }

    private void downloadUpdate() {
        if (latestRelease == null || latestRelease.apkUrl.isEmpty()) return;
        final UpdateChecker.Release release = latestRelease;
        TextView status = findViewById(R.id.update_status);
        MaterialButton button = findViewById(R.id.btn_download_update);
        button.setEnabled(false);
        status.setText("开始下载…");
        new Thread(() -> {
            try {
                java.io.File apk = UpdateChecker.download(this, release, (percent, done, total) ->
                        runOnUiThread(() -> status.setText("正在下载 " + percent + "%")));
                runOnUiThread(() -> {
                    status.setText("下载完成，准备安装");
                    installApk(apk);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("下载失败：" + e.getMessage());
                    button.setEnabled(true);
                });
            }
        }).start();
    }

    private void installApk(java.io.File apk) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "请先允许安装未知来源应用", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".files", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
