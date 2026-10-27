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
import android.graphics.Color;
import android.graphics.Rect;
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
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 桌面小组件。每次刷新从 SharedPreferences 重新组装候选集、挑一条、渲染 RemoteViews。
 * 无状态、无后台服务，进程随时可被回收。
 */
public class QuoteWidgetProvider extends AppWidgetProvider {

    public static final String ACTION = "com.example.yiyan.NEXT_QUOTE";
    public static final String ACTION_PIN_RESULT = "com.example.yiyan.PIN_RESULT";

    private static final String TAG = "99YIYAN_LOG";

    /** 全分辨率渲染一次要一两秒，缓存住：参数没变就直接复用，点一下换句不会卡。 */
    private static String cachedGlassKey;
    private static Bitmap cachedGlass;

    /** launcher 会在上报的小组件尺寸之外再扣一圈宿主内边距，这里按 dp 估一个值。 */
    private static final int HOST_INSET_DP = 8;

    /**
     * 自定义布局的图片槽位上限。RemoteViews 布局是静态的，只能预先摆好固定数量的槽，
     * 所以这个数必须和 {@code widget_image_slots.xml} 里的槽位数量一致。
     */
    static final int MAX_IMAGE_SLOTS = 40;
    private static final int[] SLOT_WRAP = layoutIds("image_wrap%d");
    private static final int[] SLOT_IMAGE = layoutIds("quote_image%d");
    private static final int[] SLOT_FLIPPER = layoutIds("quote_image_flipper%d");

    /** 按 slot_image_N / slot_image_flipper_N 的命名规律取 id：1..MAX_IMAGE_SLOTS。 */
    private static int[] layoutIds(String format) {
        int[] ids = new int[MAX_IMAGE_SLOTS];
        for (int i = 0; i < MAX_IMAGE_SLOTS; i++) {
            ids[i] = context().getResources().getIdentifier(
                    String.format(Locale.ROOT, format, i + 1), "id", context().getPackageName());
            if (ids[i] == 0) {
                throw new IllegalStateException("缺少图片槽位资源：" + String.format(format, i + 1));
            }
        }
        return ids;
    }

    private static android.content.Context context() {
        // getResources() 需要 Context；这里只用来查 id，借助 App 全局实例
        return com.example.yiyan.DailyApp.getInstance();
    }

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
     * 玻璃位图 / 图片解码可能在后台做，仍用 goAsync 把广播生命周期延长到更新完成。
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

    /** 把玻璃参数广播给 launcher 里的 Vector/LSPosed 模块（它没有 root，读不了我们的 prefs）。 */
    static void sendGlassConfig(Context context, SharedPreferences prefs) {
        Intent intent = new Intent("com.example.yiyan.GLASS_CONFIG");
        intent.putExtra("xposedGlass", prefs.getBoolean("xposedGlass", false));
        intent.putExtra("glassMaterial", prefs.getString(GlassRenderer.KEY_MATERIAL,
                GlassRenderer.MATERIAL_FROST));
        intent.putExtra("glassBlur", prefs.getInt(GlassRenderer.KEY_BLUR, GlassRenderer.DEFAULT_BLUR));
        intent.putExtra("glassDensity", prefs.getInt(GlassRenderer.KEY_DENSITY,
                GlassRenderer.DEFAULT_DENSITY));
        intent.putExtra("glassHighlight", prefs.getInt(GlassRenderer.KEY_HIGHLIGHT,
                GlassRenderer.DEFAULT_HIGHLIGHT));
        intent.putExtra("glassCorner", prefs.getInt(GlassRenderer.KEY_CORNER,
                GlassRenderer.DEFAULT_CORNER));
        intent.putExtra("glassTone", prefs.getString(GlassRenderer.KEY_TONE, GlassRenderer.TONE_AUTO));
        try {
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "广播玻璃配置失败：" + e);
        }
    }

    private void update(Context context, AppWidgetManager manager, int widgetId) {
        SharedPreferences prefs = context.getSharedPreferences("settings", 0);
        sendGlassConfig(context, prefs);
        List<Quote> candidates = candidates(context, prefs);
        if (candidates.isEmpty()) return;
        // 随机一言 / Hitokoto 全部使用 App 内置的 assets 语料，不再联网取词。
        boolean random = prefs.getBoolean("randomMode", true);
        int index = random
                ? new Random().nextInt(candidates.size())
                : (prefs.getInt("quoteIndex", -1) + 1) % candidates.size();
        Quote quote = candidates.get(index);

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
        // 主题色统一交给 ThemeColors：Monet 优先，深色模式只提高过暗颜色的明度，
        // 不再把用户选的颜色直接覆盖成固定紫色。
        int accent = ThemeColors.forWidget(context, prefs);
        int background = night ? 0xff211f24 : prefs.getInt("backgroundColor", 0xfffff9f0);

        float density = context.getResources().getDisplayMetrics().density;
        int[] sizeDp = widgetSizeDp(context, manager, widgetId);
        // 各厂商桌面默认把组件锁成 2×2，可用内容区通常只有 110–170dp。
        // 这个尺寸下继续用「固定配图 + 三行作者信息」会把文字挤成两三字一列，
        // 所以按实际尺寸切换紧凑版：不显示配图、文字占满宽度、字号与内边距一起缩。
        boolean compact = isCompact(sizeDp);
        boolean custom = prefs.getBoolean("customLayout", false);
        boolean showMeta = !compact || (sizeDp[0] >= 96 && sizeDp[1] >= 96);
        boolean showHint = !compact && sizeDp[1] >= 150;
        int userFontSp = clampFontSp(prefs.getInt("fontSize", 16));
        int fontSp = custom ? userFontSp : widgetFontSize(context, sizeDp, userFontSp,
                quote.hasImage(), compact, showMeta, showHint);

        RemoteViews views = new RemoteViews(context.getPackageName(),
                custom ? R.layout.widget_quote_custom
                        : (compact ? R.layout.widget_quote_compact : R.layout.widget_quote));
        views.setTextViewText(R.id.quote_text, quote.text);
        // 2×2 上完整元信息（作者 · 作品 · 类型 · 分类）一行放不下，只保留作者与作品，
        // 避免出现 “— 刘慈欣 · 《三体I：地球往事》 · Hito…” 这种被截断的尾巴。
        views.setTextViewText(R.id.quote_meta, compact ? metaCompact(quote) : meta);
        views.setTextViewTextSize(R.id.quote_text, TypedValue.COMPLEX_UNIT_SP, fontSp);
        views.setViewVisibility(R.id.quote_meta, showMeta ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.next_hint, showHint ? View.VISIBLE : View.GONE);
        if (!custom) {
            int padPx = Math.round(contentPaddingDp(sizeDp, compact) * density);
            views.setViewPadding(R.id.widget_content, padPx, padPx, padPx, padPx);
        }

        // 液态玻璃：根背景转透明，让壁纸从玻璃层下面透出来；玻璃位图按小组件实际尺寸现渲染
        boolean hasCustomBg = CustomBackground.enabled(prefs) && CustomBackground.hasContent(prefs);
        boolean colorBg = hasCustomBg && CustomBackground.isColorStyle(prefs);
        // 互斥：启用自定义背景时不再走玻璃；启用玻璃时忽略自定义背景
        GlassRenderer.Spec glassSpec = (!hasCustomBg && GlassRenderer.enabled(prefs))
                ? GlassRenderer.spec(context, prefs) : null;
        int frameBackground = colorBg ? CustomBackground.averageColor(prefs) : background;
        if (hasCustomBg) {
            int cardW = Math.round(sizeDp[0] * density);
            int cardH = Math.round(sizeDp[1] * density);
            Bitmap card = null;
            try {
                card = CustomBackground.renderCard(context, cardW, cardH, prefs,
                        prefs.getInt(GlassRenderer.KEY_CORNER, GlassRenderer.DEFAULT_CORNER));
            } catch (Throwable t) {
                Log.w(TAG, "自定义背景渲染失败", t);
            }
            if (card == null) {
                views.setViewVisibility(R.id.glass_bg, View.GONE);
                hasCustomBg = false;
                colorBg = false;
            } else {
                views.setImageViewBitmap(R.id.glass_bg, card);
                views.setViewVisibility(R.id.glass_bg, View.VISIBLE);
                Log.i(TAG, "背景=自定义卡片 " + cardW + "x" + cardH + " "
                        + CustomBackground.styleLabel(prefs)
                        + (CustomBackground.isColorStyle(prefs) ? "" :
                        CustomBackground.blurred(prefs)
                                ? " 高斯模糊 " + CustomBackground.blurAmount(prefs) + "%" : " 原图"));
            }
        } else if (glassSpec != null) {
            Bitmap wallpaper = WallpaperSource.load(context, 800);
            // 把壁纸裁到小组件在桌面上的位置：玻璃里的折射才和桌面严丝合缝
            Rect crop = null;
            if (wallpaper != null) {
                Rect widgetRect = GlassAlign.widgetScreenRect(context, prefs, sizeDp);
                if (widgetRect != null) {
                    crop = GlassAlign.wallpaperCrop(wallpaper,
                            context.getResources().getDisplayMetrics().widthPixels,
                            context.getResources().getDisplayMetrics().heightPixels,
                            widgetRect);
                    Log.i(TAG, "壁纸采样 " + crop + " ← 小组件 " + widgetRect);
                }
            }
            Log.i(TAG, "玻璃材质=" + glassSpec.material + " " + sizeDp[0] + "x" + sizeDp[1] + "dp tone="
                    + (glassSpec.dark ? "dark" : "light")
                    + " blur=" + Math.round(glassSpec.blur * 100)
                    + " density=" + Math.round(glassSpec.density * 100)
                    + " 壁纸=" + (wallpaper == null ? "不可用" :
                    wallpaper.getWidth() + "x" + wallpaper.getHeight()));
            int glassW = Math.round(sizeDp[0] * density);
            int glassH = Math.round(sizeDp[1] * density);
            String glassKey = glassW + "x" + glassH + "|" + glassSpec.material + "|"
                    + glassSpec.blur + "|" + glassSpec.density + "|" + glassSpec.highlight
                    + "|" + glassSpec.cornerDp + "|" + glassSpec.dark
                    + "|" + System.identityHashCode(wallpaper)
                    + "|" + (crop == null ? "-" : crop.toShortString());
            long startedAt = System.currentTimeMillis();
            Bitmap glassBitmap;
            if (glassKey.equals(cachedGlassKey) && cachedGlass != null && !cachedGlass.isRecycled()) {
                glassBitmap = cachedGlass;
            } else {
                glassBitmap = GlassRenderer.render(context, glassW, glassH, glassSpec, wallpaper, crop);
                cachedGlassKey = glassKey;
                cachedGlass = glassBitmap;
                Log.i(TAG, "玻璃重渲染 " + glassW + "x" + glassH + " 用时 "
                        + (System.currentTimeMillis() - startedAt) + "ms");
            }
            Log.i(TAG, "glass bitmap " + glassBitmap.getWidth() + "x" + glassBitmap.getHeight()
                    + " centerAlpha=" + android.graphics.Color.alpha(glassBitmap.getPixel(
                    glassBitmap.getWidth() / 2, glassBitmap.getHeight() / 2)));
            views.setImageViewBitmap(R.id.glass_bg, glassBitmap);
            views.setViewVisibility(R.id.glass_bg, View.VISIBLE);
            frameBackground = GlassRenderer.solidFill(glassSpec);


        } else {
            views.setViewVisibility(R.id.glass_bg, View.GONE);
        }

        int animatedMask = 0;
        boolean animated = false;
        if (custom) {
            animatedMask = applyCustomLayout(context, widgetId, views, prefs, frameBackground,
                    quote, sizeDp, compact);
        } else if (!compact) {
            // 2×2 紧凑版没有配图容器，图片让位给文字；放大后可恢复右侧配图
            animated = applyImage(context, widgetId, views, quote, frameBackground);
        }

        if (colorBg) {
            // 纯色/渐变的深浅与系统深色模式无关，直接按背景亮度选文字色；
            // 作者行仍使用用户主题色，只按背景明度校正到可读。
            boolean darkBg = CustomBackground.isDarkStyle(prefs);
            views.setTextColor(R.id.quote_text, darkBg ? 0xFFFDFCFF : 0xFF121016);
            views.setTextColor(R.id.quote_meta,
                    ThemeColors.forBackground(context, prefs, darkBg));
            views.setTextColor(R.id.next_hint, darkBg ? 0x99FDFCFF : 0x99413B50);
            views.setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT);
        } else if (glassSpec != null || hasCustomBg) {
            boolean darkTone = glassSpec != null ? glassSpec.dark
                    : GlassRenderer.isDark(context, prefs);
            GlassRenderer.Spec toneSpec = glassSpec != null ? glassSpec
                    : GlassRenderer.spec(context, prefs);
            toneSpec.dark = darkTone;
            views.setTextColor(R.id.quote_text, GlassRenderer.textColor(toneSpec));
            views.setTextColor(R.id.quote_meta, GlassRenderer.metaColor(toneSpec));
            views.setTextColor(R.id.next_hint, GlassRenderer.hintColor(toneSpec));
            views.setInt(R.id.widget_root, "setBackgroundColor", Color.TRANSPARENT);
        } else {
            views.setTextColor(R.id.quote_text, night ? 0xfff4eff7 : 0xff24212a);
            views.setTextColor(R.id.quote_meta, accent);
            views.setTextColor(R.id.next_hint, night ? 0x99FFFFFF : 0x776F80);
            views.setInt(R.id.widget_root, "setBackgroundColor", background);
        }

        Intent next = new Intent(ACTION).setPackage(context.getPackageName());
        PendingIntent click = PendingIntent.getBroadcast(
                context, widgetId, next,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, click);
        // 静态图也挂上点击；AdapterViewFlipper 绝对不能 setOnClickListener，
        // AdapterView 会直接抛 "Don't call setOnClickListener for an AdapterView"。
        // 紧凑版没有 quote_image，不能对不存在的 id 设置点击，否则整份 RemoteViews 会应用失败。
        if (!custom && !compact) {
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
    private boolean applyImage(Context context, int widgetId, RemoteViews views, Quote quote,
                               int frameBackground) {
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
            // 逐帧图的透明区域要垫成小组件底色，否则会透出下面的静态首帧
            adapter.putExtra(QuoteImageWidgetService.EXTRA_BACKGROUND, frameBackground);
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
    private int applyCustomLayout(Context context, int widgetId, RemoteViews views,
                                  SharedPreferences prefs, int frameBackground, Quote quote,
                                  int[] sizeDp, boolean compact) {
        CustomScene scene = CustomScene.load(prefs);
        float density = context.getResources().getDisplayMetrics().density;

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
        // 只控制文字框的那圈描边；文字本身永远显示，不会被删除
        views.setViewVisibility(R.id.text_box, scene.textBox ? View.VISIBLE : View.GONE);
        // 文字框和文字一起绕框中心旋转。旋转必须落在真正的框视图上：
        // text_wrap 本身是整块小组件，padding 只是把子视图挤成框的大小。
        float textRotation = Math.round(CustomScene.normalizeRotation(scene.text.rotation) * 10f) / 10f;
        views.setFloat(R.id.text_box, "setRotation", textRotation);
        views.setFloat(R.id.text_content, "setRotation", textRotation);

        // 2×2 下用户画的文字框可能只有 60–90dp 宽；先按框的实际像素缩字号，
        // 再按可用高度截断，避免沿用 16sp 后一行只剩两三个字、作者行被挤到框外。
        boolean showMeta = textHeight >= Math.round((compact ? 42 : 34) * density);
        int metaReserve = showMeta ? Math.round((compact ? 18 : 30) * density) : 0;
        int customFontSp = customFontSize(context, textWidth,
                Math.max(1, textHeight - metaReserve), compact,
                clampFontSp(prefs.getInt("fontSize", 16)));
        views.setTextViewTextSize(R.id.quote_text, TypedValue.COMPLEX_UNIT_SP, customFontSp);
        views.setTextViewTextSize(R.id.quote_meta, TypedValue.COMPLEX_UNIT_SP,
                compact ? 9 : 10);
        views.setViewVisibility(R.id.quote_meta, showMeta ? View.VISIBLE : View.GONE);
        // 框高固定时，先按可容纳的行数把句子截断，避免出现半行被硬裁的画面
        views.setTextViewText(R.id.quote_text, fitText(context, quote.text,
                textWidth, Math.max(1, textHeight - metaReserve), customFontSp));

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
            // 图片框按图片本身比例收窄 / 压扁：fitCenter 下不会出现上下或左右空边。
            // 桌面端 RemoteViews 改不了框尺寸，所以这里直接换算 padding。
            List<String> frames = ImageStore.frameNames(context, box.name);
            String aspectSource = frames.isEmpty() ? box.name : frames.get(0);
            int[] intrinsic = ImageStore.aspect(context, aspectSource);
            if (intrinsic != null && intrinsic[0] > 0 && intrinsic[1] > 0
                    && width > 0 && height > 0) {
                float aspect = (float) intrinsic[0] / intrinsic[1];
                float current = (float) width / height;
                if (Math.abs(current - aspect) / aspect > 0.02f) {
                    float fitW = (float) Math.sqrt((double) width * height * aspect);
                    float fitH = (float) Math.sqrt((double) width * height / aspect);
                    int newW = clampInt(Math.round(fitW), Math.round(12 * density), contentWidth);
                    int newH = clampInt(Math.round(fitH), Math.round(12 * density), contentHeight);
                    if (Math.abs(((float) newW / newH) - aspect) / aspect > 0.02f) {
                        newW = Math.max(1, Math.round(newH * aspect));
                        newH = Math.max(1, Math.round(newW / aspect));
                    }
                    width = clampInt(newW, Math.round(12 * density), contentWidth - left);
                    height = clampInt(newH, Math.round(12 * density), contentHeight - top);
                }
            }
            views.setViewVisibility(SLOT_WRAP[i], View.VISIBLE);
            views.setViewPadding(SLOT_WRAP[i], left, top,
                    Math.max(0, contentWidth - left - width),
                    Math.max(0, contentHeight - top - height));
            // 任意角度旋转：ImageView / AdapterViewFlipper 的尺寸正好是框，绕自身中心转即可
            float rotation = Math.round(CustomScene.normalizeRotation(box.rotation) * 10f) / 10f;
            views.setFloat(SLOT_IMAGE[i], "setRotation", rotation);
            views.setFloat(SLOT_FLIPPER[i], "setRotation", rotation);

            if (frames.size() > 1) {
                Bitmap first = ImageStore.decodeFlipped(context, frames.get(0),
                        ImageStore.WIDGET_MAX_PX, box.flipH, box.flipV);
                if (first != null) {
                    views.setImageViewBitmap(SLOT_IMAGE[i], first);
                    views.setViewVisibility(SLOT_IMAGE[i], View.VISIBLE);
                } else {
                    views.setViewVisibility(SLOT_IMAGE[i], View.GONE);
                }

                Intent adapter = new Intent(context, QuoteImageWidgetService.class);
                // data 参与 RemoteViewsService 的工厂缓存 key，槽位与镜像都要带进去
                adapter.setData(Uri.parse("slot://" + i + "/" + box.name + "/"
                        + box.flipH + "/" + box.flipV));
                adapter.putStringArrayListExtra(QuoteImageWidgetService.EXTRA_FRAMES,
                        new ArrayList<>(frames));
                adapter.putExtra(QuoteImageWidgetService.EXTRA_BACKGROUND, frameBackground);
                adapter.putExtra(QuoteImageWidgetService.EXTRA_FLIP_H, box.flipH);
                adapter.putExtra(QuoteImageWidgetService.EXTRA_FLIP_V, box.flipV);
                views.setRemoteAdapter(widgetId, SLOT_FLIPPER[i], adapter);
                views.setViewVisibility(SLOT_FLIPPER[i], View.VISIBLE);
                animatedMask |= 1 << i;
            } else {
                views.setViewVisibility(SLOT_FLIPPER[i], View.GONE);
                Bitmap bitmap = ImageStore.decodeFlipped(context, box.name,
                        ImageStore.WIDGET_MAX_PX, box.flipH, box.flipV);
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
        if (text == null || text.isEmpty() || widthPx <= 0) return text;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTextSize(fontSizeSp * context.getResources().getDisplayMetrics().scaledDensity);
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, widthPx)
                .setMaxLines(20)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        if (layout.getLineCount() == 0) return text;
        int lineHeight = layout.getLineBottom(0) - layout.getLineTop(0);
        if (lineHeight <= 0) return text;
        // 哪怕框只有一行高也至少放一行，再截断；返回原文会导致文字溢出框外。
        int maxLines = Math.max(1, maxHeightPx / lineHeight);
        if (layout.getLineCount() <= maxLines) return text;
        int end = layout.getLineEnd(Math.min(maxLines, layout.getLineCount()) - 1);
        int cut = Math.max(1, Math.min(text.length(), end));
        return text.substring(0, cut).trim() + "…";
    }

    /**
     * 2×2 锁定桌面下，组件可用宽度只有 110–170dp；固定配图会让正文只剩 20–40dp。
     * 这里按尺寸给出紧凑版/常规版的分界，宽度或高度任一项偏小都走紧凑版。
     */
    private boolean isCompact(int[] sizeDp) {
        return sizeDp[0] < 200 || sizeDp[1] < 150;
    }

    private int contentPaddingDp(int[] sizeDp, boolean compact) {
        if (!compact) return 14;
        int min = Math.min(sizeDp[0], sizeDp[1]);
        if (min < 125) return 8;
        if (min < 160) return 10;
        return 12;
    }

    /**
     * 按实际可用宽高推算字号，而不是直接把设置里的 16sp 塞进 2×2。
     * 目标：每行约 8–9 个汉字、正文至少 4–5 行；同时尊重系统字体缩放（fontScale），
     * 老用户把系统字号调大时不会把组件撑爆。
     */
    private int widgetFontSize(Context context, int[] sizeDp, int userSp, boolean hasImage,
                               boolean compact, boolean showMeta, boolean showHint) {
        float density = context.getResources().getDisplayMetrics().density;
        float scaled = context.getResources().getDisplayMetrics().scaledDensity;
        float fontScale = Math.max(1f, scaled / Math.max(1f, density));

        int pad = contentPaddingDp(sizeDp, compact);
        int imageReserve = (!compact && hasImage) ? 80 : 0;
        int textWidthDp = Math.max(40, sizeDp[0] - pad * 2 - imageReserve);
        int reserveDp = (showMeta ? (compact ? 16 : 36) : 0) + (showHint ? 14 : 0);
        int textHeightDp = Math.max(24, sizeDp[1] - pad * 2 - reserveDp);
        int targetLines = compact ? (sizeDp[0] >= 220 ? 4 : 5) : 4;

        float byWidth = textWidthDp / (8.5f * fontScale);
        float byHeight = textHeightDp / (targetLines * 1.4f * fontScale);
        int cap = (int) Math.floor(Math.min(byWidth, byHeight));
        int max = compact ? 15 : 28;
        int min = compact ? 11 : 12;
        int font = Math.min(userSp, Math.min(cap, max));
        return Math.max(min, Math.min(28, font));
    }

    /** 自定义布局的文字框常常比默认版更窄，按框宽高单独缩字号。 */
    private int customFontSize(Context context, int textWidthPx, int textHeightPx,
                               boolean compact, int userSp) {
        float scaled = context.getResources().getDisplayMetrics().scaledDensity;
        // 每行至少放 6 个字，框高至少容纳 3 行；不满足时继续缩小。
        float byWidth = textWidthPx / (6f * Math.max(1f, scaled));
        float byHeight = Math.max(1, textHeightPx) / (3f * 1.4f * Math.max(1f, scaled));
        int cap = (int) Math.floor(Math.min(byWidth, byHeight));
        int max = compact ? 15 : 28;
        int min = compact ? 10 : 12;
        return Math.max(min, Math.min(max, Math.min(userSp, cap)));
    }

    private int clampFontSp(int value) {
        return Math.max(12, Math.min(28, value));
    }

    /**
     * 读取小组件当前尺寸。各 ROM 的 launcher 对 options 的填写差异很大：
     *  - MIN/MAX 表示「可调整范围」，并不总是当前尺寸。Pixel 上 2×2 的
     *    minHeight=108、maxHeight=210，但屏幕上实际是 179×210；
     *  - API 31 的 SIZES 才带着成对的当前候选尺寸（179×210、419×109……）；
     *  - 部分 ROM 只写 MIN，或小尺寸下直接给 0。
     * 所以先用当前方向的宽度去 SIZES 里找同宽的那一对，再退回 MIN/MAX 与 2×2 安全值。
     */
    private int[] widgetSizeDp(Context context, AppWidgetManager manager, int widgetId) {
        Bundle options = manager.getAppWidgetOptions(widgetId);
        boolean portrait = context.getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_LANDSCAPE;

        int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
        int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        int width = firstPositive(portrait ? minW : maxW, portrait ? maxW : minW);
        int height = firstPositive(portrait ? minH : maxH, portrait ? maxH : minH);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ArrayList<SizeF> sizes = options.getParcelableArrayList(
                    AppWidgetManager.OPTION_APPWIDGET_SIZES);
            if (sizes != null && !sizes.isEmpty()) {
                SizeF match = null;
                if (width > 0) {
                    for (SizeF size : sizes) {
                        // 同一宽度可能出现多次（竖屏/横屏各一份），取第一份即可
                        if (Math.abs(size.getWidth() - width) <= 6) {
                            match = size;
                            break;
                        }
                    }
                }
                if (match == null) match = sizes.get(0);
                width = Math.round(match.getWidth());
                height = Math.round(match.getHeight());
            }
        }
        if (width <= 0) width = 160;
        if (height <= 0) height = 150;
        width = clampInt(width, 80, 1200);
        height = clampInt(height, 60, 1200);

        Log.i(TAG, "widget " + widgetId + " 尺寸=" + width + "x" + height + "dp compact="
                + isCompact(new int[]{width, height}) + " options=" + options);
        return new int[]{width, height};
    }

    private int firstPositive(int first, int second) {
        return first > 0 ? first : second;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampPx(float value, int min, int max) {
        int upper = Math.max(min, max);
        return Math.max(min, Math.min(upper, Math.round(value)));
    }

    /**
     * 候选集：编辑过的库优先（整体替换内置语料），否则用内置意译条目，最后追加用户添加的。
     * 作者/出处为空的自定义条目同样有效，只有句子必填。
     */
    private List<Quote> candidates(Context context, SharedPreferences prefs) {
        List<Quote> list = new ArrayList<>();
        Set<String> disabled = prefs.getStringSet("disabledCategories", new HashSet<String>());

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

        // 内置 Hitokoto 语料（12 个原始分类），可在设置里整体关掉
        if (prefs.getBoolean("showHitokoto", true)) {
            list.addAll(HitokotoLibrary.quotes(context));
        }

        List<Quote> filtered = list;
        if (!disabled.isEmpty()) {
            filtered = new ArrayList<>(list.size());
            for (Quote quote : list) {
                if (!disabled.contains(quote.category)) filtered.add(quote);
            }
            Log.i(TAG, "候选 " + list.size() + " 条，禁用 " + disabled.size()
                    + " 个分类后剩 " + filtered.size() + " 条");
            // 全被禁掉时不做静默失败：退回完整列表，避免桌面卡在旧的一句
            if (filtered.isEmpty()) {
                Log.w(TAG, "所有分类都被禁用，本次忽略过滤");
                return list;
            }
        }
        return filtered;
    }

    /** 2×2 用的一行元信息：只留作者与作品，两者都没有时才退到类型/分类。 */
    private String metaCompact(Quote quote) {
        String author = zhAuthor(quote.author);
        String work = zhWork(quote.work);
        StringBuilder line = new StringBuilder();
        if (!author.isEmpty()) line.append("— ").append(author);
        if (!work.isEmpty()) {
            if (line.length() > 0) line.append(" · ");
            else line.append("— ");
            line.append("《").append(work).append("》");
        }
        if (line.length() == 0) return quote.kind + " · " + quote.category;
        return line.toString();
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
