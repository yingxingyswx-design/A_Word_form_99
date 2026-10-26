package com.example.yiyan;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义布局的「场景」：小组件尺寸、文字框、若干图片框。
 *
 * 位置与大小都存成 0–1 的分数（相对内容区），这样同一套布局在任意尺寸的小组件上
 * 都能按比例还原。图片框额外带旋转角度与水平/垂直镜像。
 *
 * 持久化用 org.json（Android 自带，不引第三方库）。
 */
final class CustomScene {

    static final String PREF_KEY = "custom_scene";

    /** 小组件基准尺寸（dp），编辑器的画布比例和小组件都按它换算。 */
    int widthDp = 409;
    int heightDp = 236;

    boolean textBox;
    final Box text = new Box(0.06f, 0.06f, 0.64f, 0.46f);

    final List<ImageBox> images = new ArrayList<>();

    static class Box {
        float x, y, w, h;

        Box(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        Box copy() {
            return new Box(x, y, w, h);
        }

        boolean intersects(Box other, float gap) {
            return x < other.x + other.w + gap
                    && x + w + gap > other.x
                    && y < other.y + other.h + gap
                    && y + h + gap > other.y;
        }
    }

    static final class ImageBox extends Box {
        String name = "";
        int rotation;      // 0/90/180/270
        boolean flipH;
        boolean flipV;

        ImageBox(float x, float y, float w, float h) {
            super(x, y, w, h);
        }

        ImageBox copy() {
            ImageBox box = new ImageBox(x, y, w, h);
            box.name = name;
            box.rotation = rotation;
            box.flipH = flipH;
            box.flipV = flipV;
            return box;
        }
    }

    static CustomScene defaults() {
        return new CustomScene();
    }

    static CustomScene load(SharedPreferences prefs) {
        String json = prefs.getString(PREF_KEY, "");
        if (json == null || json.trim().isEmpty()) return defaults();
        try {
            return fromJson(new JSONObject(json));
        } catch (Exception e) {
            return defaults();
        }
    }

    void save(SharedPreferences prefs) {
        prefs.edit().putString(PREF_KEY, toJson().toString()).apply();
    }

    JSONObject toJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("w", widthDp);
            root.put("h", heightDp);
            root.put("textBox", textBox);
            root.put("text", boxToJson(text));
            JSONArray array = new JSONArray();
            for (ImageBox image : images) {
                JSONObject item = boxToJson(image);
                item.put("n", image.name);
                item.put("r", image.rotation);
                item.put("fh", image.flipH);
                item.put("fv", image.flipV);
                array.put(item);
            }
            root.put("images", array);
        } catch (JSONException ignored) {
        }
        return root;
    }

    private static JSONObject boxToJson(Box box) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("x", round(box.x));
        json.put("y", round(box.y));
        json.put("w", round(box.w));
        json.put("h", round(box.h));
        return json;
    }

    private static double round(float value) {
        return Math.round(value * 10000d) / 10000d;
    }

    static CustomScene fromJson(JSONObject root) throws JSONException {
        CustomScene scene = new CustomScene();
        scene.widthDp = Math.max(120, root.optInt("w", 409));
        scene.heightDp = Math.max(80, root.optInt("h", 236));
        scene.textBox = root.optBoolean("textBox", false);
        JSONObject text = root.optJSONObject("text");
        if (text != null) {
            scene.text.x = (float) text.optDouble("x", 0.06);
            scene.text.y = (float) text.optDouble("y", 0.06);
            scene.text.w = (float) text.optDouble("w", 0.62);
            scene.text.h = (float) text.optDouble("h", 0.36);
        }
        JSONArray array = root.optJSONArray("images");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                ImageBox box = new ImageBox(
                        (float) item.optDouble("x", 0.6),
                        (float) item.optDouble("y", 0.1),
                        (float) item.optDouble("w", 0.25),
                        (float) item.optDouble("h", 0.3));
                box.name = item.optString("n", "");
                box.rotation = item.optInt("r", 0);
                box.flipH = item.optBoolean("fh", false);
                box.flipV = item.optBoolean("fv", false);
                scene.images.add(box);
            }
        }
        return scene;
    }
}
