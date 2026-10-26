package com.example.yiyan;

import android.app.Application;
import android.content.SharedPreferences;

import com.google.android.material.color.DynamicColors;

/**
 * 全局启用 Material 3 动态取色（Android 12+ 跟随壁纸，低版本回退到品牌紫）。
 * 只有 AppCompatActivity 会被自动应用，所以三个界面都继承 AppCompatActivity。
 */
public class DailyApp extends Application {

    /** 液态玻璃参数版本：换一套默认值时 +1，老安装只会被重写一次。 */
    private static final int GLASS_DEFAULTS_VERSION = 8;

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        migrateGlassDefaults();
    }

    private void migrateGlassDefaults() {
        SharedPreferences prefs = getSharedPreferences("settings", 0);
        if (prefs.getInt("glassDefaults", 1) >= GLASS_DEFAULTS_VERSION) return;
        prefs.edit()
                .putInt(GlassRenderer.KEY_BLUR, GlassRenderer.DEFAULT_BLUR)
                .putInt(GlassRenderer.KEY_DENSITY, GlassRenderer.DEFAULT_DENSITY)
                .putInt(GlassRenderer.KEY_CORNER, GlassRenderer.DEFAULT_CORNER)
                .putInt(GlassRenderer.KEY_HIGHLIGHT, GlassRenderer.DEFAULT_HIGHLIGHT)
                .putString(GlassRenderer.KEY_TONE, GlassRenderer.TONE_AUTO)
                .putInt("glassDefaults", GLASS_DEFAULTS_VERSION)
                .apply();
    }
}
