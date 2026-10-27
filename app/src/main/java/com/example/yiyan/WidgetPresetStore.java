package com.example.yiyan;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「保存组件」：把当前小组件的外观参数存成一个预设，最多 10 个。
 *
 * 这样用户可以准备「紫罗兰纯色」「夜色紫渐变」「图片玻璃」几套组件，
 * 在「个性」页一键切换，而不必每次重新调主题、背景、布局和玻璃参数。
 * 只保存外观相关键，不动语料、收藏和小组件实例本身。
 */
final class WidgetPresetStore {

    static final int MAX = 10;
    private static final String KEY = "savedWidgetPresets";

    private static final String LAYOUT_FLAG = "customLayout";
    private static final String LEGACY_LAYOUT_FLAG = "customlayout";

    /**
     * 保存和还原的外观键；顺序不影响 JSON。
     * 注意：lastQuote* 是「当前正在显示的那一句」，不属于外观，
     * 不能写进预设，否则查看旧预设时显示的是之后换过的句子（之前就踩过这个坑）。
     */
    private static final String[] SNAPSHOT_KEYS = {
            "fontSize", "themeColor", "monet", "backgroundColor",
            LAYOUT_FLAG, LEGACY_LAYOUT_FLAG, CustomScene.PREF_KEY,
            GlassRenderer.KEY_ENABLED, GlassRenderer.KEY_MATERIAL,
            GlassRenderer.KEY_BLUR, GlassRenderer.KEY_DENSITY,
            GlassRenderer.KEY_HIGHLIGHT, GlassRenderer.KEY_CORNER,
            GlassRenderer.KEY_TONE,
            CustomBackground.KEY_ENABLED, CustomBackground.KEY_TYPE,
            CustomBackground.KEY_NAME, CustomBackground.KEY_SOLID,
            CustomBackground.KEY_GRADIENT_START, CustomBackground.KEY_GRADIENT_END,
            CustomBackground.KEY_GRADIENT_NAME, "customBgGradientAngle",
            CustomBackground.KEY_BLUR, CustomBackground.KEY_BLUR_AMOUNT,
            GlassAlign.KEY_MODE, GlassAlign.KEY_OFFSET_X, GlassAlign.KEY_OFFSET_Y,
            "xposedGlass"
    };

    private WidgetPresetStore() {
    }

    static List<Preset> load(SharedPreferences prefs) {
        List<Preset> result = new ArrayList<>();
        String raw = prefs.getString(KEY, "");
        if (raw == null || raw.trim().isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Preset preset = Preset.fromJson(item);
                if (preset != null) result.add(preset);
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    /** 保存当前外观；超过 10 个返回 null（调用方提示用户先删除）。 */
    static Preset saveCurrent(SharedPreferences prefs) {
        List<Preset> presets = load(prefs);
        if (presets.size() >= MAX) return null;

        Set<String> usedNames = new HashSet<>();
        for (Preset preset : presets) usedNames.add(preset.name);
        String name = null;
        for (int i = 1; i <= MAX; i++) {
            String candidate = "组件 " + i;
            if (!usedNames.contains(candidate)) {
                name = candidate;
                break;
            }
        }
        if (name == null) return null;

        JSONObject values = new JSONObject();
        Map<String, ?> all = prefs.getAll();
        try {
            for (String key : SNAPSHOT_KEYS) {
                if (all.containsKey(key)) values.put(key, all.get(key));
                else values.put(key, JSONObject.NULL);
            }
            // 例外：保存时的句子 / 配图，只用于「查看小组件视图」，还原外观时不会覆盖
            values.put("snapshotText", prefs.getString("lastQuoteText",
                    "知者不惑，仁者不忧，勇者不惧。"));
            values.put("snapshotMeta", prefs.getString("lastQuoteMeta",
                    "— 孔子 · 《论语》 · 原文"));
            values.put("snapshotImage", prefs.getString("lastQuoteImage", ""));
            Preset preset = new Preset(System.currentTimeMillis(), name,
                    System.currentTimeMillis(), values);
            presets.add(preset);
            save(prefs, presets);
            return preset;
        } catch (JSONException e) {
            return null;
        }
    }

    static void delete(SharedPreferences prefs, long id) {
        List<Preset> presets = load(prefs);
        for (int i = presets.size() - 1; i >= 0; i--) {
            if (presets.get(i).id == id) presets.remove(i);
        }
        save(prefs, presets);
    }

    static void rename(SharedPreferences prefs, long id, String newName) {
        List<Preset> presets = load(prefs);
        boolean changed = false;
        for (Preset preset : presets) {
            if (preset.id == id) {
                preset.name = newName;
                changed = true;
            }
        }
        if (changed) save(prefs, presets);
    }

    static void apply(SharedPreferences prefs, Preset preset) {
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : SNAPSHOT_KEYS) {
            Object value = preset.values.opt(key);
            if (value == null || value == JSONObject.NULL) {
                editor.remove(key);
            } else if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof Number) {
                editor.putInt(key, ((Number) value).intValue());
            } else {
                editor.putString(key, String.valueOf(value));
            }
        }
        // 兼容老预设：只有小写键时也把开关补上，否则还原后还是默认布局
        if (!preset.values.has(LAYOUT_FLAG) && preset.values.has(LEGACY_LAYOUT_FLAG)) {
            Object legacy = preset.values.opt(LEGACY_LAYOUT_FLAG);
            if (legacy instanceof Boolean) editor.putBoolean(LAYOUT_FLAG, (Boolean) legacy);
        }
        editor.apply();
        CustomBackground.invalidate();
    }

    /**
     * 是否使用自定义布局。
     * 老版本保存时写的是小写键 {@code customlayout}，读的是 {@code customLayout}，
     * 结果预设里明明有 layout 场景却一直按默认布局显示。
     */
    static boolean usesCustomLayout(JSONObject values) {
        if (values.optBoolean(LAYOUT_FLAG, false)) return true;
        if (values.optBoolean(LEGACY_LAYOUT_FLAG, false)) return true;
        // 兜底：有可解析的场景数据就视为自定义布局
        String json = values.optString(CustomScene.PREF_KEY, "");
        if (json == null || json.trim().isEmpty()) return false;
        try {
            CustomScene scene = CustomScene.fromJson(new JSONObject(json));
            return scene != null && (!scene.images.isEmpty() || scene.textBox
                    || Math.abs(scene.text.w - 0.88f) > 0.001f
                    || Math.abs(scene.text.h - 0.80f) > 0.001f);
        } catch (Exception e) {
            return false;
        }
    }

    private static void save(SharedPreferences prefs, List<Preset> presets) {
        JSONArray array = new JSONArray();
        for (Preset preset : presets) array.put(preset.toJson());
        prefs.edit().putString(KEY, array.toString()).apply();
    }

    static final class Preset {
        final long id;
        String name;
        final long createdAt;
        final JSONObject values;

        Preset(long id, String name, long createdAt, JSONObject values) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.values = values;
        }

        static Preset fromJson(JSONObject item) {
            JSONObject values = item.optJSONObject("values");
            if (values == null) return null;
            return new Preset(item.optLong("id", 0),
                    item.optString("name", "组件"),
                    item.optLong("createdAt", 0), values);
        }

        JSONObject toJson() {
            JSONObject item = new JSONObject();
            try {
                item.put("id", id);
                item.put("name", name);
                item.put("createdAt", createdAt);
                item.put("values", values);
            } catch (JSONException ignored) {
            }
            return item;
        }

        String summary() {
            boolean monet = values.optBoolean("monet", false);
            String theme = monet ? "Monet"
                    : ThemeColors.nameOf(values.optInt("themeColor", ThemeColors.DEFAULT_COLOR));

            boolean customBg = values.optBoolean(CustomBackground.KEY_ENABLED, false);
            String type = values.optString(CustomBackground.KEY_TYPE, CustomBackground.TYPE_IMAGE);
            String background;
            if (customBg) {
                if (CustomBackground.TYPE_SOLID.equals(type)) {
                    background = "纯色";
                } else if (CustomBackground.TYPE_GRADIENT.equals(type)) {
                    String gradient = values.optString(CustomBackground.KEY_GRADIENT_NAME, "渐变");
                    background = "渐变 · " + gradient;
                } else {
                    background = "图片";
                }
            } else if (values.optBoolean(GlassRenderer.KEY_ENABLED, false)) {
                background = "玻璃";
            } else {
                background = "纯色底";
            }

            String layout = usesCustomLayout(values) ? "自定义布局" : "默认布局";
            String fontSize = values.optInt("fontSize", 16) + "sp";
            String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(createdAt));
            return theme + " · " + background + " · " + layout + " · " + fontSize
                    + " · " + time;
        }
    }
}
