package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 所有页面的基类：在 setContentView 之前把用户主题色写进当前 Activity 的颜色资源，
 * 主题色变化后回到页面时自动 recreate，避免「选了颜色但 App 界面还是紫色」。
 */
public abstract class ThemedActivity extends AppCompatActivity {

    private int appliedThemeColor;
    private boolean appliedMonet;
    private boolean themeChangePending;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ThemeColors.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 冷启动深色模式时补一次资源注入，再 force 应用主题覆盖，
        // 把 colorPrimary 等拉回资源注入后的 @color/md_*。
        ThemeColors.applyToActivity(this);
        getTheme().applyStyle(R.style.ThemeOverlay_Yiyan_CustomColors, true);
        SharedPreferences prefs = getSharedPreferences("settings", 0);
        appliedThemeColor = ThemeColors.saved(prefs);
        appliedMonet = prefs.getBoolean("monet", false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        SharedPreferences prefs = getSharedPreferences("settings", 0);
        int color = ThemeColors.saved(prefs);
        boolean monet = prefs.getBoolean("monet", false);
        if (color != appliedThemeColor || monet != appliedMonet) {
            appliedThemeColor = color;
            appliedMonet = monet;
            themeChangePending = true;
            recreate();
        }
    }

    /** 子类在 onResume 里发现这个标记时不要再做一次 switch 同步，避免重复 recreate。 */
    protected final boolean isThemeChangePending() {
        return themeChangePending;
    }
}
