package com.example.yiyan;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.List;

/** 玻璃材质 → 高级设置：对齐方式、无障碍定位、扩展注入。正式版会删掉这里的调试项。 */
public class GlassAdvancedActivity extends ThemedActivity {

    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_glass_advanced);
        prefs = getSharedPreferences("settings", 0);
        ScreenInsets.applyTo(this);
        findViewById(R.id.advanced_back).setOnClickListener(v -> finish());

        MaterialButtonToggleGroup group = findViewById(R.id.glass_align_group);
        String mode = GlassAlign.mode(prefs);
        group.check(GlassAlign.MODE_AUTO.equals(mode) ? R.id.glass_align_auto
                : GlassAlign.MODE_MANUAL.equals(mode) ? R.id.glass_align_manual
                : R.id.glass_align_off);
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            String value = checkedId == R.id.glass_align_auto ? GlassAlign.MODE_AUTO
                    : checkedId == R.id.glass_align_manual ? GlassAlign.MODE_MANUAL
                    : GlassAlign.MODE_OFF;
            prefs.edit().putString(GlassAlign.KEY_MODE, value).apply();
            refreshStatus();
            refreshWidgets();
        });

        bindAlignSlider(R.id.glass_align_x, R.id.glass_align_x_label, "水平偏移",
                GlassAlign.KEY_OFFSET_X);
        bindAlignSlider(R.id.glass_align_y, R.id.glass_align_y_label, "垂直偏移",
                GlassAlign.KEY_OFFSET_Y);

        findViewById(R.id.glass_align_grant).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "打不开无障碍设置", Toast.LENGTH_SHORT).show();
            }
        });

        MaterialSwitch xposed = findViewById(R.id.glass_xposed);
        xposed.setChecked(prefs.getBoolean("xposedGlass", false));
        xposed.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("xposedGlass", checked).apply();
            QuoteWidgetProvider.sendGlassConfig(this, prefs);
            refreshWidgets();
            Toast.makeText(this, checked
                            ? "实验性：小组件会变透明，玻璃交给桌面进程画。需先在 Vector/LSPosed 里启用模块并加入「系统桌面」作用域，否则会只剩文字"
                            : "已关闭注入模式",
                    Toast.LENGTH_LONG).show();
        });

        refreshStatus();
    }

    private void bindAlignSlider(int sliderId, int labelId, String base, String key) {
        final Slider slider = findViewById(sliderId);
        final TextView label = findViewById(labelId);
        int value = prefs.getInt(key, 0);
        value = Math.round(Math.max(slider.getValueFrom(), Math.min(slider.getValueTo(), value)));
        slider.setValue(value);
        label.setText(base + " · " + value + "%");
        slider.addOnChangeListener((s, v, fromUser) -> {
            int rounded = Math.round(v);
            label.setText(base + " · " + rounded + "%");
            prefs.edit().putInt(key, rounded).apply();
            refreshWidgets();
        });
    }

    private void refreshStatus() {
        TextView status = findViewById(R.id.glass_align_status);
        String mode = GlassAlign.mode(prefs);
        String label = GlassAlign.MODE_AUTO.equals(mode) ? "自动（无障碍）"
                : GlassAlign.MODE_MANUAL.equals(mode) ? "手动偏移" : "关闭";
        List<Rect> rects = GlassAlign.candidates(prefs);
        String detail;
        if (rects.isEmpty()) {
            detail = "还没读到小组件位置";
        } else {
            Rect first = rects.get(0);
            detail = "读到 " + rects.size() + " 个候选，第一个 " + first.width() + "x"
                    + first.height() + " @ " + first.left + "," + first.top;
        }
        String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean serviceOn = enabled != null && enabled.contains(getPackageName());
        status.setText("无障碍服务：" + (serviceOn ? "已在系统里启用（若读不到位置，去系统设置里关一下再开）" : "未启用")
                + "\n对齐：" + label + " · " + detail);
    }

    private void refreshWidgets() {
        sendBroadcast(new android.content.Intent(QuoteWidgetProvider.ACTION)
                .setPackage(getPackageName()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }
}
