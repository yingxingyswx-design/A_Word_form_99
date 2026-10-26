package com.example.yiyan;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_IMPORT_XML = 4401;
    private static final String KEY_UPDATE_CHANNEL = "updateChannel";


    private SharedPreferences prefs;
    private boolean autoChecked;
    private UpdateChecker.Release latestRelease;

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
        bindOnlineRandom();
        bindShowHitokoto();
        bindParaphraseSwitch();
        bindFontSize();
        bindMonet();
        bindThemeColor();
        bindGlass();
        bindRootHomeWatch();
        bindAddQuote();
        bindCustomLayout();
        bindImport();
        bindUpdate();
        bindRefreshAll();
        bindBottomNav();
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
            previewSpec();
            scheduleWidgetRefresh();
            Toast.makeText(this, checked
                            ? "已开启玻璃材质，回桌面看效果"
                            : "已关闭玻璃材质，恢复纯色底",
                    Toast.LENGTH_SHORT).show();
        });

        com.google.android.material.button.MaterialButtonToggleGroup material =
                findViewById(R.id.glass_material_group);
        String savedMaterial = prefs.getString(GlassRenderer.KEY_MATERIAL,
                GlassRenderer.MATERIAL_FROST);
        material.check(GlassRenderer.MATERIAL_LIQUID.equals(savedMaterial)
                ? R.id.glass_material_liquid : R.id.glass_material_frost);
        material.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String value = checkedId == R.id.glass_material_liquid
                    ? GlassRenderer.MATERIAL_LIQUID : GlassRenderer.MATERIAL_FROST;
            prefs.edit().putString(GlassRenderer.KEY_MATERIAL, value).apply();
            previewSpec();
            scheduleWidgetRefresh();
            Toast.makeText(this, GlassRenderer.MATERIAL_LIQUID.equals(value)
                            ? "已切换：液态玻璃（透明，桌面壁纸实时透出）"
                            : "已切换：毛玻璃（真实壁纸模糊）",
                    Toast.LENGTH_SHORT).show();
        });

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
                    boolean shizuku = RootHomeWatcher.hasShizuku();
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
            final String label = RootHomeWatcher.BACKEND_SHIZUKU.equals(backend)
                    ? "Shizuku" : "root";
            runOnUiThread(() -> status.setText(running
                    ? "状态：运行中（" + label + "）· 正在监听回到桌面"
                    : "状态：未运行"));
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

    /** 在线随机一言开关。 */
    private void bindOnlineRandom() {
        MaterialSwitch online = findViewById(R.id.online_random);
        online.setChecked(prefs.getBoolean("onlineRandom", false));
        online.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("onlineRandom", checked).apply();
            sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
            Toast.makeText(this, checked ? "已切换到在线随机一言" : "已回到本地语料",
                    Toast.LENGTH_SHORT).show();
        });
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
        monet.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean("monet", checked).apply());
    }

    private void bindThemeColor() {
        ColorPickerView picker = findViewById(R.id.color_picker);
        picker.setOnColorChangedListener(color ->
                prefs.edit().putInt("themeColor", color).apply());
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
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            boolean theme = id == R.id.nav_theme;
            boolean settings = id == R.id.nav_settings;
            findViewById(R.id.quotes_panel).setVisibility(
                    theme || settings ? View.GONE : View.VISIBLE);
            findViewById(R.id.theme_panel).setVisibility(theme ? View.VISIBLE : View.GONE);
            findViewById(R.id.settings_panel).setVisibility(settings ? View.VISIBLE : View.GONE);
            if (settings) maybeAutoCheckUpdate();
            return true;
        });
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
