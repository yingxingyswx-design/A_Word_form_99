package com.example.yiyan;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/**
 * 桌面小组件。每次刷新从 SharedPreferences 重新组装候选集、挑一条、渲染 RemoteViews。
 * 无状态、无后台服务，进程随时可被回收。
 */
public class QuoteWidgetProvider extends AppWidgetProvider {

    public static final String ACTION = "com.example.yiyan.NEXT_QUOTE";
    public static final String ACTION_PIN_RESULT = "com.example.yiyan.PIN_RESULT";

    private static final String TAG = "99YIYAN_LOG";
    /** launcher 会在上报的小组件尺寸之外再扣一圈宿主内边距，这里按 dp 估一个值。 */
    private static final int HOST_INSET_DP = 8;

    /** 自定义布局的图片槽位上限：RemoteViews 布局是静态的，只能预先摆好固定数量的槽。 */
    private static final int MAX_IMAGE_SLOTS = 4;
    private static final int[] SLOT_WRAP = {
            R.id.image_wrap1, R.id.image_wrap2, R.id.image_wrap3, R.id.image_wrap4};
    private static final int[] SLOT_IMAGE = {
            R.id.quote_image1, R.id.quote_image2, R.id.quote_image3, R.id.quote_image4};
    private static final int[] SLOT_FLIPPER = {
            R.id.quote_image_flipper1, R.id.quote_image_flipper2,
            R.id.quote_image_flipper3, R.id.quote_image_flipper4};

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAsync(context, ids);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_PIN_RESULT.equals(action)) {
            Log.i(TAG, "桌面已固定小组件 id="
                    + intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
            return;
        }
        if (ACTION.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            updateAsync(context, manager.getAppWidgetIds(
                    new ComponentName(context, QuoteWidgetProvider.class)));
        }
    }

    /**
     * 在线随机一言要联网，不能在主线程做；用 goAsync 把广播生命周期延长到更新完成。
     */
    private void updateAsync(Context context, int[] ids) {
        final BroadcastReceiver.PendingResult pending = goAsync();
        final int[] widgetIds = ids == null ? new int[0] : ids.clone();
        new Thread(() -> {
            try {
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                for (int id : widgetIds) update(context, manager, id);
            } catch (Exception e) {
                Log.w(TAG, "更新小组件失败", e);
            } finally {
                pending.finish();
            }
        }, "widget-update").start();
    }

    /**
     * 拖动改变小组件大小时必须重绘：位置是按尺寸算成 padding 的，
     * 不重算就会沿用旧尺寸，出现「按最大尺寸排版、缩小后被裁掉」。
     */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int widgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, manager, widgetId, newOptions);
        update(context, manager, widgetId);
    }

    private void update(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences("settings", 0);
        List<Quote> candidates = candidates(prefs);

        boolean onlineRandom = prefs.getBoolean("onlineRandom", false);
        Quote quote = null;
        int index = -1;

        if (onlineRandom) {
            // 「随机」是 App 自带的在线分类：取 Hitokoto，失败退缓存，再失败回本地语料
            try {
                quote = HitokotoClient.fetch();
                HitokotoClient.cache(prefs, quote);
                Log.i(TAG, "在线随机一言：" + quote.text);
            } catch (Exception e) {
                Log.w(TAG, "在线一言失败，用缓存：" + e.getMessage());
                quote = HitokotoClient.cached(prefs);
            }
        }

        if (quote == null) {
            if (candidates.isEmpty()) return;
            boolean random = prefs.getBoolean("randomMode", true);
            index = random
                    ? new Random().nextInt(candidates.size())
                    : (prefs.getInt("quoteIndex", -1) + 1) % candidates.size();
            quote = candidates.get(index);
        }

        String meta = meta(quote);

        // lastQuote* 供自定义布局编辑器预览用
        SharedPreferences.Editor editor = prefs.edit()
                .putString("lastLog", "已切换：" + quote.text + "（" + System.currentTimeMillis() + "）")
                .putString("lastQuoteText", quote.text)
                .putString("lastQuoteMeta", meta)
                .putString("lastQuoteImage", quote.imagePath);
        if (index >= 0) editor.putInt("quoteIndex", index);
        editor.apply();
        if (index >= 0) {
            Log.i(TAG, (prefs.getBoolean("randomMode", true) ? "随机" : "顺序")
                    + "切换到第" + index + "条：" + quote.text);
        }

        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int accent = prefs.getInt("themeColor", 0xff6750a4);
        if (prefs.getBoolean("monet", false) && android.os.Build.VERSION.SDK_INT >= 31) {
            accent = context.getColor(android.R.color.system_accent1_600);
        }
        if (night) accent = 0xffd0bcff;
        int background = night ? 0xff211f24 : prefs.getInt("backgroundColor", 0xfffff9f0);

        boolean custom = prefs.getBoolean("customLayout", false);
        RemoteViews views = new RemoteViews(context.getPackageName(),
                custom ? R.layout.widget_quote_custom : R.layout.widget_quote);
        views.setTextViewText(R.id.quote_text, quote.text);
        views.setTextViewText(R.id.quote_meta, meta);
        views.setTextViewTextSize(R.id.quote_text, TypedValue.COMPLEX_UNIT_SP,
                prefs.getInt("fontSize", 16));
        int animatedMask = 0;
        boolean animated = false;
        if (custom) {
            animatedMask = applyCustomLayout(context, manager, widgetId, views, prefs, background, quote);
        } else {
            animated = applyImage(context, widgetId, views, quote, background);
        }

        views.setTextColor(R.id.quote_text, night ? 0xfff4eff7 : 0xff24212a);
        views.setTextColor(R.id.quote_meta, accent);
        views.setInt(R.id.widget_root, "setBackgroundColor", background);

        Intent next = new Intent(ACTION).setPackage(context.getPackageName());
        PendingIntent click = PendingIntent.getBroadcast(
                context, widgetId, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, click);
        // 静态图也挂上点击；AdapterViewFlipper 绝对不能 setOnClickListener，
        // AdapterView 会直接抛 "Don't call setOnClickListener for an AdapterView"
        if (!custom) {
            views.setOnClickPendingIntent(R.id.quote_image, click);
        }

        manager.updateAppWidget(widgetId, views);
        if (custom) {
            for (int i = 0; i < MAX_IMAGE_SLOTS; i++) {
                if ((animatedMask & (1 << i)) != 0) {
                    manager.notifyAppWidgetViewDataChanged(widgetId, SLOT_FLIPPER[i]);
                }
            }
        } else if (animated) {
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.quote_image_flipper);
        }
    }

    /**
     * 右侧配图。GIF 会拆成帧文件交给 AdapterViewFlipper，由桌面自己翻页；
     * 同时把首帧设成静态图垫底，遇到不支持集合控件的桌面也还能看到图。
     *
     * @return true 表示这次用的是逐帧轮播，调用方需要通知集合数据刷新
     */
    private boolean applyImage(Context context, int widgetId, RemoteViews views, Quote quote, int background) {
        if (!quote.hasImage()) {
            views.setViewVisibility(R.id.quote_image, View.GONE);
            views.setViewVisibility(R.id.quote_image_flipper, View.GONE);
            return false;
        }

        List<String> frames = ImageStore.frameNames(context, quote.imagePath);
        if (frames.size() > 1) {
            Bitmap firstFrame = ImageStore.decode(context, frames.get(0), ImageStore.WIDGET_MAX_PX);
            if (firstFrame != null) {
                views.setImageViewBitmap(R.id.quote_image, firstFrame);
                views.setViewVisibility(R.id.quote_image, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.quote_image, View.GONE);
            }

            Intent adapter = new Intent(context, QuoteImageWidgetService.class);
            // data 参与 RemoteViewsService 的工厂缓存 key，必须带上，否则不同条目会复用同一组帧
            adapter.setData(Uri.parse("frames://" + quote.imagePath));
            adapter.putStringArrayListExtra(QuoteImageWidgetService.EXTRA_FRAMES,
                    new ArrayList<>(frames));
            // 逐帧图的透明区域要垫成小组件背景色，否则会透出下面的静态首帧
            adapter.putExtra(QuoteImageWidgetService.EXTRA_BACKGROUND, background);
            views.setRemoteAdapter(widgetId, R.id.quote_image_flipper, adapter);
            // 翻页间隔固定在布局里（android:flipInterval），不用 setInt 反射调用，
            // 免得某些 launcher 因方法不可远程调用而丢弃整份 RemoteViews
            views.setViewVisibility(R.id.quote_image_flipper, View.VISIBLE);
            return true;
        }

        views.setViewVisibility(R.id.quote_image_flipper, View.GONE);
        Bitmap bitmap = ImageStore.decode(context, quote.imagePath, ImageStore.WIDGET_MAX_PX);
        if (bitmap == null) {
            views.setViewVisibility(R.id.quote_image, View.GONE);
            return false;
        }
        views.setViewVisibility(R.id.quote_image, View.VISIBLE);
        views.setImageViewBitmap(R.id.quote_image, bitmap);
        return false;
    }

    /**
     * 自定义布局：文字框 + 最多 4 个图片框，按场景里的 0–1 分数换算成 padding。
     * RemoteViews 改不了 margin，padding 会把 match_parent 子视图精确挤成目标矩形。
     *
     * @return 逐位标记哪些图片槽用了 GIF 轮播（调用方需要在 updateAppWidget 之后 notify）
     */
    private int applyCustomLayout(Context context, AppWidgetManager manager, int widgetId,
                                  RemoteViews views, SharedPreferences prefs, int background,
                                  Quote quote) {
        CustomScene scene = CustomScene.load(prefs);
        float density = context.getResources().getDisplayMetrics().density;
        int[] sizeDp = widgetSizeDp(manager, widgetId);

        int inset = Math.round(14 * density);
        int hostInset = Math.round(HOST_INSET_DP * density);
        int contentWidth = Math.max(1, Math.round(sizeDp[0] * density) - inset * 2 - hostInset);
        int contentHeight = Math.max(1, Math.round(sizeDp[1] * density) - inset * 2 - hostInset);

        int textLeft = clampPx(scene.text.x * contentWidth, 0, contentWidth - 1);
        int textTop = clampPx(scene.text.y * contentHeight, 0, contentHeight - 1);
        int textWidth = clampPx(scene.text.w * contentWidth, Math.round(40 * density),
                contentWidth - textLeft);
        int textHeight = clampPx(scene.text.h * contentHeight, Math.round(24 * density),
                contentHeight - textTop);
        views.setViewPadding(R.id.text_wrap, textLeft, textTop,
                Math.max(0, contentWidth - textLeft - textWidth),
                Math.max(0, contentHeight - textTop - textHeight));
        views.setViewVisibility(R.id.text_box, scene.textBox ? View.VISIBLE : View.GONE);
        // 框高固定时，先按可容纳的行数把句子截断，避免出现半行被硬裁的画面
        views.setTextViewText(R.id.quote_text, fitText(context, quote.text,
                textWidth, Math.max(0, textHeight - Math.round(30 * density)),
                prefs.getInt("fontSize", 16)));

        int animatedMask = 0;
        for (int i = 0; i < MAX_IMAGE_SLOTS; i++) {
            if (i >= scene.images.size()) {
                views.setViewVisibility(SLOT_WRAP[i], View.GONE);
                continue;
            }
            CustomScene.ImageBox box = scene.images.get(i);
            if (!ImageStore.exists(context, box.name)) {
                views.setViewVisibility(SLOT_WRAP[i], View.GONE);
                continue;
            }

            int left = clampPx(box.x * contentWidth, 0, contentWidth - 1);
            int top = clampPx(box.y * contentHeight, 0, contentHeight - 1);
            int width = clampPx(box.w * contentWidth, Math.round(24 * density), contentWidth - left);
            int height = clampPx(box.h * contentHeight, Math.round(24 * density), contentHeight - top);
            views.setViewVisibility(SLOT_WRAP[i], View.VISIBLE);
            views.setViewPadding(SLOT_WRAP[i], left, top,
                    Math.max(0, contentWidth - left - width),
                    Math.max(0, contentHeight - top - height));

            List<String> frames = ImageStore.frameNames(context, box.name);
            if (frames.size() > 1) {
                Bitmap first = ImageStore.decodeTransformed(context, frames.get(0),
                        ImageStore.WIDGET_MAX_PX, box.rotation, box.flipH, box.flipV);
                if (first != null) {
                    views.setImageViewBitmap(SLOT_IMAGE[i], first);
                    views.setViewVisibility(SLOT_IMAGE[i], View.VISIBLE);
                } else {
                    views.setViewVisibility(SLOT_IMAGE[i], View.GONE);
                }

                Intent adapter = new Intent(context, QuoteImageWidgetService.class);
                // data 参与 RemoteViewsService 的工厂缓存 key，槽位与变换都要带进去
                adapter.setData(Uri.parse("slot://" + i + "/" + box.name + "/"
                        + box.rotation + "/" + box.flipH + "/" + box.flipV));
                adapter.putStringArrayListExtra(QuoteImageWidgetService.EXTRA_FRAMES,
                        new ArrayList<>(frames));
                adapter.putExtra(QuoteImageWidgetService.EXTRA_BACKGROUND, background);
                adapter.putExtra(QuoteImageWidgetService.EXTRA_ROTATION, box.rotation);
                adapter.putExtra(QuoteImageWidgetService.EXTRA_FLIP_H, box.flipH);
                adapter.putExtra(QuoteImageWidgetService.EXTRA_FLIP_V, box.flipV);
                views.setRemoteAdapter(widgetId, SLOT_FLIPPER[i], adapter);
                views.setViewVisibility(SLOT_FLIPPER[i], View.VISIBLE);
                animatedMask |= 1 << i;
            } else {
                views.setViewVisibility(SLOT_FLIPPER[i], View.GONE);
                Bitmap bitmap = ImageStore.decodeTransformed(context, box.name,
                        ImageStore.WIDGET_MAX_PX, box.rotation, box.flipH, box.flipV);
                if (bitmap != null) {
                    views.setImageViewBitmap(SLOT_IMAGE[i], bitmap);
                    views.setViewVisibility(SLOT_IMAGE[i], View.VISIBLE);
                } else {
                    views.setViewVisibility(SLOT_IMAGE[i], View.GONE);
                }
            }
        }
        return animatedMask;
    }

    /** 按框宽与可用高度把句子截到整数行，避免半行被裁。 */
    private String fitText(Context context, String text, int widthPx, int maxHeightPx, int fontSizeSp) {
        if (text == null || widthPx <= 0 || maxHeightPx <= 0) return text;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTextSize(fontSizeSp * context.getResources().getDisplayMetrics().scaledDensity);
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, widthPx)
                .setMaxLines(8)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        if (layout.getLineCount() == 0) return text;
        int lineHeight = layout.getLineBottom(0) - layout.getLineTop(0);
        if (lineHeight <= 0) return text;
        int maxLines = Math.max(1, maxHeightPx / lineHeight);
        if (layout.getLineCount() <= maxLines) return text;
        int end = layout.getLineEnd(Math.min(maxLines, layout.getLineCount()) - 1);
        return text.substring(0, Math.max(0, end - 1)).trim() + "…";
    }

    /** 优先用 API 31+ 的 OPTION_APPWIDGET_SIZES（真实尺寸），否则退回 min 尺寸。 */
    private int[] widgetSizeDp(AppWidgetManager manager, int widgetId) {
        Bundle options = manager.getAppWidgetOptions(widgetId);
        int width = Math.max(120, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180));
        int height = Math.max(80, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty()) {
                SizeF first = sizes.get(0);
                width = Math.max(120, Math.round(first.getWidth()));
                height = Math.max(80, Math.round(first.getHeight()));
            }
        }
        Log.i(TAG, "widget " + widgetId + " 尺寸=" + width + "x" + height + "dp options=" + options);
        return new int[]{width, height};
    }

    private int clampPx(float value, int min, int max) {
        int upper = Math.max(min, max);
        return Math.max(min, Math.min(upper, Math.round(value)));
    }

    /**
     * 候选集：编辑过的库优先（整体替换内置语料），否则用内置意译条目，最后追加用户添加的。
     * 作者/出处为空的自定义条目同样有效，只有句子必填。
     */
    private List<Quote> candidates(SharedPreferences prefs) {
        List<Quote> list = new ArrayList<>();

        String saved = prefs.getString("editable_library", "");
        if (!saved.trim().isEmpty()) {
            for (String line : saved.split("\\r?\\n")) {
                Quote quote = Quote.parse(line, "用户编辑", "哲学");
                if (quote != null) list.add(quote);
            }
        } else {
            for (Quote quote : QuoteLibrary.ALL) {
                if (quote.kind.equals("译写 / 意译")) list.add(quote);
            }
        }

        for (String line : prefs.getStringSet("custom_quotes", new HashSet<String>())) {
            Quote quote = Quote.parse(line, "用户添加", "用户");
            if (quote != null) list.add(quote);
        }
        return list;
    }

    /** 元信息行：作者与出处在缺失时整段省略，不会出现 “—  · 《》”。 */
    private String meta(Quote quote) {
        StringBuilder head = new StringBuilder();
        String author = zhAuthor(quote.author);
        String work = zhWork(quote.work);
        if (!author.isEmpty()) head.append(author);
        if (!work.isEmpty()) {
            if (head.length() > 0) head.append(" · ");
            head.append("《").append(work).append("》");
        }
        StringBuilder meta = new StringBuilder();
        if (head.length() > 0) meta.append("— ").append(head).append(" · ");
        return meta.append(quote.kind).append(" · ").append(quote.category).toString();
    }

    private String zhAuthor(String source) {
        if (source == null) return "";
        String[][] table = {
                {"Socrates", "苏格拉底"}, {"Plato", "柏拉图"}, {"Aristotle", "亚里士多德"},
                {"Nietzsche", "尼采"}, {"Descartes", "笛卡尔"}, {"Kant", "康德"},
                {"Rousseau", "卢梭"}, {"Camus", "加缪"}, {"Sartre", "萨特"},
                {"Laozi", "老子"}, {"Confucius", "孔子"},
                {"Marcus Aurelius", "马可·奥勒留"}, {"Epictetus", "爱比克泰德"},
                {"Wittgenstein", "维特根斯坦"}, {"Hannah Arendt", "汉娜·阿伦特"},
                {"Heidegger", "海德格尔"}, {"Foucault", "福柯"}, {"Rawls", "罗尔斯"},
        };
        for (String[] pair : table) {
            if (source.equals(pair[0])) return pair[1];
        }
        return source;
    }

    private String zhWork(String source) {
        if (source == null) return "";
        String[][] table = {
                {"Republic", "理想国"}, {"Apology", "申辩篇"},
                {"Nicomachean Ethics", "尼各马可伦理学"}, {"The Social Contract", "社会契约论"},
                {"Meditations", "沉思录"}, {"Tao Te Ching", "道德经"},
                {"The Myth of Sisyphus", "西西弗斯神话"}, {"Being and Nothingness", "存在与虚无"},
                {"Tractatus Logico-Philosophicus", "逻辑哲学论"},
                {"Philosophical Investigations", "哲学研究"}, {"On Liberty", "论自由"},
                {"Utilitarianism", "功利主义"},
                {"Groundwork of the Metaphysics of Morals", "道德形而上学奠基"},
                {"What Is Enlightenment?", "什么是启蒙？"}, {"The Second Sex", "第二性"},
                {"The Human Condition", "人的境况"}, {"Discipline and Punish", "规训与惩罚"},
                {"A Theory of Justice", "正义论"},
        };
        for (String[] pair : table) {
            if (source.equals(pair[0])) return pair[1];
        }
        return source;
    }
}
