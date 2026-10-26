package com.example.yiyan;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * 全局启用 Material 3 动态取色（Android 12+ 跟随壁纸，低版本回退到品牌紫）。
 * 只有 AppCompatActivity 会被自动应用，所以三个界面都继承 AppCompatActivity。
 */
public class DailyApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
