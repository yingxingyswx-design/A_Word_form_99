package com.example.yiyan;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 只做一件事：从 launcher 的窗口树里，把桌面小组件那一块的屏幕坐标读出来。
 *
 * 桌面小组件身后只有壁纸，知道它占屏幕哪一块，就能从壁纸里裁出它背后那条，
 * 玻璃的模糊 / 折射才和桌面严丝合缝。无障碍是普通 App 唯一能拿到这个坐标的办法。
 *
 * 注意：这里只读取 `AppWidgetHostView` 的 bounds，不读任何文字、不监听输入。
 */
public class WidgetLocatorService extends AccessibilityService {

    private static final String TAG = "99YIYAN_LOG";

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable periodicScan = new Runnable() {
        @Override
        public void run() {
            scanNow();
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "无障碍已连接，开始定位小组件");
        handler.removeCallbacks(periodicScan);
        handler.post(periodicScan);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(periodicScan);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }
        if (event.getPackageName() != null) {
            getSharedPreferences("settings", 0).edit()
                    .putString("lastForeground", event.getPackageName().toString())
                    .apply();
        }
        scanNow();
    }

    /** 真正读一次桌面视图树：找到所有 AppWidgetHostView，再用小组件里那句话认领我们那个。 */
    private void scanNow() {
        List<Rect> hosts = new ArrayList<>();
        List<Rect> ours = new ArrayList<>();
        List<Rect> texts = new ArrayList<>();
        String quote = getSharedPreferences("settings", 0).getString("lastQuoteText", "");
        String prefix = quote == null ? "" : quote.trim();
        if (prefix.length() > 10) prefix = prefix.substring(0, 10);

        // 先看活动窗口；拿不到再遍历所有可交互窗口（桌面窗口不一定算「活动窗口」）
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            try {
                collect(active, hosts, ours, texts, prefix, null, 0);
            } catch (Exception e) {
                Log.w(TAG, "读取小组件位置失败：" + e);
            }
        }
        if (hosts.isEmpty()) {
            try {
                for (android.view.accessibility.AccessibilityWindowInfo window : getWindows()) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    collect(root, hosts, ours, texts, prefix, null, 0);
                    if (!hosts.isEmpty()) break;
                }
            } catch (Exception e) {
                Log.w(TAG, "遍历窗口失败：" + e);
            }
        }
        if (!hosts.isEmpty()) {
            // 桌面常有多个 AppWidgetHostView（屏幕时间、日历……）。优先用 RemoteViews 子节点
            // 的 package 认出自己：我们的 child package 是 com.example.yiyan，尺寸不会因为
            // 当前句子变了、文字被裁切或旋转而失配。
            List<Rect> ordered = new ArrayList<>();
            for (Rect rect : ours) {
                if (!ordered.contains(rect)) ordered.add(rect);
            }
            // 老 launcher 不一定暴露 child package，再退回到“当前句子命中的 host”
            if (ordered.isEmpty()) {
                for (Rect text : texts) {
                    int cx = (text.left + text.right) / 2;
                    int cy = (text.top + text.bottom) / 2;
                    for (Rect host : hosts) {
                        if (host.contains(cx, cy) && !ordered.contains(host)) {
                            ordered.add(host);
                            break;
                        }
                    }
                }
            }
            for (Rect host : hosts) {
                if (!ordered.contains(host)) ordered.add(host);
            }
            GlassAlign.storeCandidates(this, ordered);
            // 单独记录「确认属于 DailyLine」的尺寸，供自定义布局编辑器使用。
            // 如果当前桌面页只有别的组件，ours 为空，绝不能拿屏幕时间 / 日历的尺寸顶替。
            Rect own = !ours.isEmpty() ? ours.get(0) : confidentByText(texts, hosts);
            if (own != null) {
                getSharedPreferences("settings", 0).edit()
                        .putInt("widgetScreenW", own.width())
                        .putInt("widgetScreenH", own.height())
                        .putLong("widgetSizeAt", System.currentTimeMillis())
                        .apply();
            }
            Log.i(TAG, "无障碍扫描：找到 " + hosts.size() + " 个小组件，自身 package 命中 "
                    + ours.size() + "，文字命中 " + texts.size() + "，选中 " + ordered.get(0)
                    + (own == null ? "，未确认到自身尺寸" : "，自身尺寸 " + own.width() + "x" + own.height()));
        } else {
            Log.i(TAG, "无障碍扫描：当前窗口没有 AppWidgetHostView");
        }
    }

    private Rect confidentByText(List<Rect> texts, List<Rect> hosts) {
        for (Rect text : texts) {
            int cx = (text.left + text.right) / 2;
            int cy = (text.top + text.bottom) / 2;
            for (Rect host : hosts) {
                if (host.contains(cx, cy)) return host;
            }
        }
        return null;
    }

    private void collect(AccessibilityNodeInfo node, List<Rect> out, List<Rect> ours,
                         List<Rect> texts, String quotePrefix, Rect currentHost, int depth) {
        if (node == null || depth > 40 || out.size() >= 12) return;

        CharSequence text = node.getText();
        if (quotePrefix.length() >= 4 && text != null
                && text.toString().trim().startsWith(quotePrefix)) {
            Rect textBounds = new Rect();
            node.getBoundsInScreen(textBounds);
            if (textBounds.width() > 0 && textBounds.height() > 0) texts.add(textBounds);
        }

        CharSequence className = node.getClassName();
        String name = className == null ? "" : className.toString();
        boolean isHost = name.contains("AppWidgetHostView");
        if (!isHost) {
            String id = node.getViewIdResourceName();
            isHost = id != null && id.contains("app_widget");
        }

        Rect host = currentHost;
        if (isHost) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 120 && bounds.height() > 80
                    && bounds.right <= 2000 && bounds.bottom <= 4000) {
                out.add(bounds);
                host = bounds;
            }
        }

        CharSequence packageName = node.getPackageName();
        if (host != null && packageName != null
                && getPackageName().contentEquals(packageName)) {
            if (!ours.contains(host)) ours.add(host);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), out, ours, texts, quotePrefix, host, depth + 1);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
