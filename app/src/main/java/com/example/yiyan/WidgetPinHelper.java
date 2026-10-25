package com.example.yiyan;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.util.Locale;

/**
 * 把小组件放到桌面这一步的兼容层。
 *
 * 各 ROM 对 AppWidgetManager.requestPinAppWidget() 的实测行为（Android 8.0+）：
 *  - AOSP / Pixel / OPPO / realme / 三星：弹确认框，确认后添加；
 *  - 小米 / 红米（MIUI / HyperOS）：不弹框，直接添加；未授予「桌面快捷方式」权限时静默失败；
 *  - 华为 / 荣耀：弹确认框，但永不回调 PendingIntent；空间不足只提示、不自动换页；
 *  - vivo / iQOO（OriginOS）：接口无效，必须接入原子组件 SDK 并上架审核。
 *
 * 因此这里不信任 requestPinAppWidget() 的返回值（失败时它同样返回 true），
 * 一律用 AppWidgetManager.getAppWidgetIds() 的数量变化来判定是否真的加上了。
 */
final class WidgetPinHelper {

    private static final int REQUEST_PIN_CALLBACK = 4103;
    /** 等桌面完成添加后再校验，太短会在慢机型上误判。 */
    private static final long VERIFY_DELAY_MS = 2500L;

    private final Activity activity;

    WidgetPinHelper(Activity activity) {
        this.activity = activity;
    }

    void add() {
        final ComponentName provider = new ComponentName(activity, QuoteWidgetProvider.class);
        final AppWidgetManager manager = AppWidgetManager.getInstance(activity);
        int before = count(provider);

        // vivo / iQOO：官方接口对该品牌没有任何效果，直接给手动添加指引，避免用户空等。
        if (isVivo()) {
            showGuide(false);
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            fallback(provider, before);
            return;
        }
        if (!manager.isRequestPinAppWidgetSupported()) {
            fallback(provider, before);
            return;
        }

        PendingIntent callback = PendingIntent.getBroadcast(activity, REQUEST_PIN_CALLBACK,
                new Intent(activity, QuoteWidgetProvider.class)
                        .setAction(QuoteWidgetProvider.ACTION_PIN_RESULT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean requested = false;
        try {
            requested = manager.requestPinAppWidget(provider, null, callback);
        } catch (Throwable ignored) {
            // 个别 ROM 的 launcher 实现会抛异常，直接走兜底。
        }
        if (!requested) {
            fallback(provider, before);
            return;
        }

        toast("已向桌面发送添加请求，请按系统提示确认");
        scheduleVerify(provider, before, 0);
    }

    /**
     * 桌面完成添加是不可观测的（华为甚至永不回调），所以只能轮询 widget id 数量。
     * 用户还在系统确认框里操作时本页会失去焦点，此时不弹引导，等下一轮再判。
     */
    private void scheduleVerify(ComponentName provider, int before, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (count(provider) > before) {
                toast("小组件已添加到桌面");
                return;
            }
            if (!activity.hasWindowFocus() && attempt < 4) {
                scheduleVerify(provider, before, attempt + 1);
                return;
            }
            fallback(provider, before);
        }, VERIFY_DELAY_MS);
    }

    private int count(ComponentName provider) {
        return AppWidgetManager.getInstance(activity).getAppWidgetIds(provider).length;
    }

    /** 官方接口不可用、或调用后桌面没有真的加上时的兜底。 */
    private void fallback(ComponentName provider, int before) {
        if (count(provider) > before) {
            toast("小组件已添加到桌面");
            return;
        }
        if (isOppo() && openWidgetPanel()) {
            toast("已打开小组件面板，请选择 DailyLine 拖到桌面");
            return;
        }
        showGuide(true);
    }

    private void showGuide(boolean failed) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(failed ? "没能自动添加小组件" : "添加小组件")
                .setMessage(guideText())
                .setPositiveButton("回到桌面", (d, w) -> goHome())
                .setNegativeButton("知道了", null);
        if (isXiaomi()) {
            builder.setNeutralButton("桌面快捷方式权限", (d, w) -> openShortcutPermission());
        } else if (isOppo()) {
            builder.setNeutralButton("打开小组件面板", (d, w) -> openWidgetPanel());
        }
        builder.show();
    }

    private String guideText() {
        StringBuilder sb = new StringBuilder();
        if (isXiaomi()) {
            sb.append("HyperOS / MIUI 的「添加小组件」需要「桌面快捷方式」权限，未授权时不会报错，只会在桌面上悄悄失败。\n\n")
              .append("1. 点「桌面快捷方式权限」，允许 DailyLine 创建桌面快捷方式；\n")
              .append("2. 回到这里重新点「将小组件添加到桌面」。\n\n")
              .append("也可以手动添加：长按桌面空白处 → 添加小部件 → 找到 DailyLine → 拖到桌面。");
        } else if (isVivo()) {
            sb.append("OriginOS 不允许普通应用直接往桌面添加组件（官方接口对该品牌无效，需接入 vivo 原子组件 SDK 并上架审核）。\n\n")
              .append("手动添加：长按桌面空白处 → 「原子组件」→ 切到「安卓组件」页 → 找到 DailyLine → 拖到桌面。");
        } else if (isHuawei()) {
            sb.append("HarmonyOS / EMUI 会弹出添加确认框。若提示「当前页面空间不足」，请先往右滑新建一页桌面，再点一次添加。\n\n")
              .append("也可以手动添加：长按桌面空白处 → 「窗口小工具」→ 找到 DailyLine → 拖到桌面。");
        } else if (isOppo()) {
            sb.append("ColorOS 正常会弹出添加确认框。若没有反应，请用下方「打开小组件面板」，或长按桌面空白处 → 「小部件」→ 找到 DailyLine。");
        } else {
            sb.append("请回到桌面，长按空白处 → 选择「小组件 / 小部件」→ 找到 DailyLine → 拖到桌面。");
        }
        sb.append("\n\n当前桌面：").append(launcherPackage());
        sb.append("（").append(Build.MANUFACTURER).append(" ").append(Build.VERSION.RELEASE).append("）");
        return sb.toString();
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(home);
        } catch (Exception ignored) {
        }
    }

    /**
     * ColorOS 7-11 / realme UI 的桌面小组件面板入口（带 state=ToggleBar_widget 才会直接展开面板）。
     * 只在 OPPO 系机型上尝试，且必须先 resolve 确认目标 Activity 存在。
     */
    private boolean openWidgetPanel() {
        String[][] candidates = {
                {"com.android.launcher", "com.android.launcher.Launcher"},
                {"com.oppo.launcher", "com.oppo.launcher.Launcher"},
                {"com.coloros.launcher", "com.coloros.launcher.Launcher"},
                {"com.realme.launcher", "com.realme.launcher.Launcher"},
                {"net.oneplus.launcher", "net.oneplus.launcher.Launcher"},
        };
        for (String[] candidate : candidates) {
            Intent intent = new Intent()
                    .setComponent(new ComponentName(candidate[0], candidate[1]))
                    .putExtra("state", "ToggleBar_widget")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (activity.getPackageManager().resolveActivity(intent, 0) == null) continue;
            try {
                activity.startActivity(intent);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** MIUI / HyperOS 的权限编辑器，失败则退到系统应用详情页。 */
    private void openShortcutPermission() {
        Intent editor = new Intent("miui.intent.action.APP_PERM_EDITOR");
        editor.setClassName("com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity");
        editor.putExtra("extra_pkgname", activity.getPackageName());
        try {
            activity.startActivity(editor);
            return;
        } catch (Exception ignored) {
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            toast("请到系统设置中为 DailyLine 开启桌面快捷方式权限");
        }
    }

    private String launcherPackage() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo info = activity.getPackageManager()
                    .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null) return info.activityInfo.packageName;
        } catch (Exception ignored) {
        }
        return "未知";
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    // ---- 品牌判定：manufacturer 和 brand 都看，避免只认其中一个时漏判 ----

    private static String tag() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String brand = Build.BRAND == null ? "" : Build.BRAND;
        return (manufacturer + " " + brand).toLowerCase(Locale.ROOT);
    }

    static boolean isXiaomi() {
        String t = tag();
        return t.contains("xiaomi") || t.contains("redmi") || t.contains("poco");
    }

    static boolean isVivo() {
        String t = tag();
        return t.contains("vivo") || t.contains("iqoo") || t.contains("bbk");
    }

    static boolean isOppo() {
        String t = tag();
        return t.contains("oppo") || t.contains("realme") || t.contains("oneplus");
    }

    static boolean isHuawei() {
        String t = tag();
        return t.contains("huawei") || t.contains("honor");
    }
}
