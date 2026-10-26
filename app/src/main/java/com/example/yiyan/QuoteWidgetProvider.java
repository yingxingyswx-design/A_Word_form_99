package com.example.yiyan;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.util.Log;
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

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
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
            for (int id : manager.getAppWidgetIds(new ComponentName(context, QuoteWidgetProvider.class))) {
                update(context, manager, id);
            }
        }
    }

    private void update(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences("settings", 0);
        List<Quote> candidates = candidates(prefs);
        if (candidates.isEmpty()) return;

        boolean random = prefs.getBoolean("randomMode", true);
        int index = random
                ? new Random().nextInt(candidates.size())
                : (prefs.getInt("quoteIndex", -1) + 1) % candidates.size();
        Quote quote = candidates.get(index);

        prefs.edit()
                .putInt("quoteIndex", index)
                .putString("lastLog", "已切换：" + quote.text + "（" + System.currentTimeMillis() + "）")
                .apply();
        Log.i(TAG, (random ? "随机" : "顺序") + "切换到第" + index + "条：" + quote.text);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quote);
        views.setTextViewText(R.id.quote_text, quote.text);
        views.setTextViewText(R.id.quote_meta, meta(quote));
        views.setTextViewTextSize(R.id.quote_text, TypedValue.COMPLEX_UNIT_SP,
                prefs.getInt("fontSize", 16));
        applyImage(context, views, quote);

        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int accent = prefs.getInt("themeColor", 0xff6750a4);
        if (prefs.getBoolean("monet", false) && android.os.Build.VERSION.SDK_INT >= 31) {
            accent = context.getColor(android.R.color.system_accent1_600);
        }
        if (night) accent = 0xffd0bcff;

        views.setTextColor(R.id.quote_text, night ? 0xfff4eff7 : 0xff24212a);
        views.setTextColor(R.id.quote_meta, accent);
        views.setInt(R.id.widget_root, "setBackgroundColor",
                night ? 0xff211f24 : prefs.getInt("backgroundColor", 0xfffff9f0));

        Intent next = new Intent(ACTION).setPackage(context.getPackageName());
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(
                context, widgetId, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        manager.updateAppWidget(widgetId, views);
    }

    /**
     * 配图渲染在右侧。RemoteViews 不支持动图，GIF 只能取首帧；
     * 位图必须压到 {@link ImageStore#WIDGET_MAX_PX}，否则单次 RemoteViews 会超过 Binder 限制。
     */
    private void applyImage(Context context, RemoteViews views, Quote quote) {
        Bitmap bitmap = quote.hasImage()
                ? ImageStore.decode(context, quote.imagePath, ImageStore.WIDGET_MAX_PX)
                : null;
        if (bitmap == null) {
            views.setViewVisibility(R.id.quote_image, View.GONE);
            return;
        }
        views.setViewVisibility(R.id.quote_image, View.VISIBLE);
        views.setImageViewBitmap(R.id.quote_image, bitmap);
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
