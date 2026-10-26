package com.example.yiyan;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.ColorResourcesOverride;
import com.google.android.material.color.utilities.Scheme;

import java.util.HashMap;
import java.util.Map;

/**
 * 主题色的唯一来源：色卡、自定义取色器、桌面小组件都从这里取值。
 *
 * 保存的是用户选中的原始色；真正画到桌面上时再按浅色/深色模式校正明度，
 * 避免深色模式下作者行变成深色字、浅色模式下浅色字看不见。
 */
final class ThemeColors {

    static final String KEY = "themeColor";
    static final int DEFAULT_COLOR = 0xFF6750A4;

    /** Material 3 Expressive 风格的深色色卡：保证白字或深字至少有一边可读。 */
    static final int[] PRESET_COLORS = {
            0xFF6750A4, // 紫罗兰
            0xFF4355B9, // 靛蓝
            0xFF00639B, // 天青
            0xFF006A6A, // 青碧
            0xFF2E6B3E, // 松绿
            0xFF4F6A00, // 橄榄
            0xFF7D5700, // 琥珀
            0xFF9A4600, // 赭橙
            0xFFA33A32, // 珊瑚
            0xFFA8385B, // 玫红
            0xFF7D5260, // 藕荷
            0xFF4A4458  // 石墨
    };

    static final String[] PRESET_NAMES = {
            "紫罗兰", "靛蓝", "天青", "青碧", "松绿", "橄榄",
            "琥珀", "赭橙", "珊瑚", "玫红", "藕荷", "石墨"
    };

    private ThemeColors() {
    }

    static int saved(SharedPreferences prefs) {
        return prefs.getInt(KEY, DEFAULT_COLOR);
    }

    static String nameOf(int color) {
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            if (PRESET_COLORS[i] == color) return PRESET_NAMES[i];
        }
        return "自定义颜色";
    }

    static String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    /** 色卡上文字/勾选图标的颜色：浅色卡用深字，深色卡用白字。 */
    static int contrastOn(int color) {
        return ColorUtils.calculateLuminance(color) > 0.46 ? 0xFF1C1B1F : 0xFFFFFFFF;
    }

    /**
     * 桌面小组件实际使用的强调色：尊重 Monet，再按当前深浅模式校正明度。
     * 之前深色模式会把主题色强制改成固定的 D0BCFF，等于用户的自定义色失效；
     * 这里只提高过暗颜色的明度，色相和用户选择都保留。
     */
    static int forWidget(Context context, SharedPreferences prefs) {
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return forBackground(context, prefs, night);
    }

    /** 自定义背景是深色还是浅色与系统深色模式无关，所以单独接收 darkBackground。 */
    static int forBackground(Context context, SharedPreferences prefs, boolean darkBackground) {
        int color = saved(prefs);
        if (prefs.getBoolean("monet", false) && Build.VERSION.SDK_INT >= 31) {
            color = context.getColor(android.R.color.system_accent1_600);
        }
        return ensureReadable(color, darkBackground);
    }

    static int ensureReadable(int color, boolean night) {
        float luminance = (float) ColorUtils.calculateLuminance(color);
        if (night && luminance < 0.45f) {
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(color, hsl);
            hsl[2] = Math.max(hsl[2], 0.74f);
            return ColorUtils.HSLToColor(hsl);
        }
        if (!night && luminance > 0.55f) {
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(color, hsl);
            hsl[2] = Math.min(hsl[2], 0.42f);
            return ColorUtils.HSLToColor(hsl);
        }
        return color;
    }

    /** Monet 开启时系统动态色，仅用于设置页的小圆点预览。 */
    static int systemAccent(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            return context.getColor(android.R.color.system_accent1_600);
        }
        return DEFAULT_COLOR;
    }

    /**
     * 把用户主题色真正应用到 App 界面，而不只是小组件作者行。
     *
     * AppTheme 里 colorPrimary / colorPrimaryContainer 等直接引用 R.color.md_*，
     * 这里用 Material 的 ColorResourcesOverride 在 attachBaseContext 阶段把这些颜色资源
     * 替换成由种子色生成的 M3 方案。这样按钮、滑块、开关、底部导航等都会一起变色，
     * 同时保留现有的纸张色 surface，不会把整个界面刷成深色。
     *
     * 必须包在 attachBaseContext 里：Activity 的 theme 在 onCreate 时已经建好，
     * 之后再 applyIfPossible 会出现窗口空白 / 首帧不绘制。
     */
    static Context wrapContext(Context base) {
        if (base == null) return null;
        Map<Integer, Integer> colors = colorMap(base);
        try {
            ColorResourcesOverride override = ColorResourcesOverride.getInstance();
            if (override != null) {
                Context wrapped = override.wrapContextIfPossible(base, colors);
                if (wrapped != null) return wrapped;
            }
        } catch (Throwable ignored) {
            // API 30 以下或个别 ROM 不支持 ResourcesLoader 时退回默认紫色，不影响功能
        }
        return base;
    }

    /**
     * 再对 Activity 自身的 Resources 应用一次。冷启动进入深色模式时，
     * attachBaseContext 拿到的配置可能还是浅色；系统随后切到 night 会重建 Resources，
     * 丢掉 ResourcesLoader。onCreate 阶段再补一次，确保 values-night 也吃到主题色。
     */
    static void applyToActivity(Activity activity) {
        if (activity == null) return;
        try {
            ColorResourcesOverride override = ColorResourcesOverride.getInstance();
            if (override != null) override.applyIfPossible(activity, colorMap(activity));
        } catch (Throwable ignored) {
        }
    }

    private static Map<Integer, Integer> colorMap(Context base) {
        SharedPreferences prefs = base.getSharedPreferences("settings", 0);
        int seed = saved(prefs);
        if (prefs.getBoolean("monet", false) && Build.VERSION.SDK_INT >= 31) {
            // attachBaseContext 阶段还没有完整 Theme，用 Resources 直接取系统色，
            // 否则 Context.getColor() 会走 getTheme() 并在部分 ROM 上 NPE。
            seed = base.getResources().getColor(android.R.color.system_accent1_600);
        }
        boolean night = (base.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Scheme scheme = night ? Scheme.dark(seed) : Scheme.light(seed);

        Map<Integer, Integer> colors = new HashMap<>();
        colors.put(R.color.md_primary, scheme.getPrimary());
        colors.put(R.color.md_on_primary, scheme.getOnPrimary());
        colors.put(R.color.md_primary_container, scheme.getPrimaryContainer());
        colors.put(R.color.md_on_primary_container, scheme.getOnPrimaryContainer());
        colors.put(R.color.md_secondary, scheme.getSecondary());
        colors.put(R.color.md_on_secondary, scheme.getOnSecondary());
        colors.put(R.color.md_secondary_container, scheme.getSecondaryContainer());
        colors.put(R.color.md_on_secondary_container, scheme.getOnSecondaryContainer());
        // 自定义布局编辑器、色卡上的 meta 文字等仍直接引用 @color/accent
        colors.put(R.color.accent, scheme.getPrimary());
        return colors;
    }
}
