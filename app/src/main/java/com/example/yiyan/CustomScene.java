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
 * 都能按比例还原。文字框与图片框都能绕自身中心任意旋转，图片框额外带水平/垂直镜像。
 * 框与框之间允许重叠：绘制顺序固定为文字在下、图片按列表顺序在上。
 *
 * 持久化用 org.json（Android 自带，不引第三方库）。
 */
final class CustomScene {

    static final String PREF_KEY = "custom_scene";

    /** 场景数据版本。2 起旋转角度为任意值；旧版是 0/90/180/270 且已烘焙进位图。 */
    private static final int VERSION = 2;

    /**
     * 小组件基准尺寸（dp）。各厂商桌面默认把组件锁成 2×2，所以新布局按接近实际的
     * 正方形画布预览；旧布局保留自己保存的宽高比，不会被强行改掉。
     */
    int widthDp = 180;
    int heightDp = 180;

    boolean textBox;
    // 2×2 默认没有配图，文字框先占满大部分卡片；加图片后会按列表顺序叠在文字上，
    // 用户仍可在编辑器里把文字框缩回原来的 64%×46%。
    final Box text = new Box(0.06f, 0.06f, 0.88f, 0.80f);

    final List<ImageBox> images = new ArrayList<>();

    static class Box {
        float x, y, w, h;
        /** 绕框中心的顺时针旋转角度，任意值，统一归一到 0–360。 */
        float rotation;

        Box(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        Box copy() {
            Box box = new Box(x, y, w, h);
            box.rotation = rotation;
            return box;
        }
    }

    static final class ImageBox extends Box {
        String name = "";
        boolean flipH;
        boolean flipV;

        ImageBox(float x, float y, float w, float h) {
            super(x, y, w, h);
        }

        ImageBox copy() {
            ImageBox box = new ImageBox(x, y, w, h);
            box.rotation = rotation;
            box.name = name;
            box.flipH = flipH;
            box.flipV = flipV;
            return box;
        }
    }

    static CustomScene defaults() {
        return new CustomScene();
    }

    /** 把任意角度归一到 [0, 360)。 */
    static float normalizeRotation(float degrees) {
        float value = degrees % 360f;
        if (value < 0) value += 360f;
        return value;
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
            root.put("v", VERSION);
            root.put("w", widthDp);
            root.put("h", heightDp);
            root.put("textBox", textBox);
            root.put("text", boxToJson(text));
            JSONArray array = new JSONArray();
            for (ImageBox image : images) {
                JSONObject item = boxToJson(image);
                item.put("n", image.name);
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
        json.put("r", Math.round(normalizeRotation(box.rotation) * 10d) / 10d);
        return json;
    }

    private static double round(float value) {
        return Math.round(value * 10000d) / 10000d;
    }

    static CustomScene fromJson(JSONObject root) throws JSONException {
        CustomScene scene = new CustomScene();
        int version = root.optInt("v", 1);
        scene.widthDp = Math.max(120, root.optInt("w", 180));
        scene.heightDp = Math.max(80, root.optInt("h", 180));
        scene.textBox = root.optBoolean("textBox", false);
        JSONObject text = root.optJSONObject("text");
        if (text != null) {
            scene.text.x = (float) text.optDouble("x", 0.06);
            scene.text.y = (float) text.optDouble("y", 0.06);
            scene.text.w = (float) text.optDouble("w", 0.88);
            scene.text.h = (float) text.optDouble("h", 0.80);
            scene.text.rotation = normalizeRotation((float) text.optDouble("r", 0));
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
                float rotation = (float) item.optDouble("r", 0);
                box.rotation = normalizeRotation(rotation);
                box.flipH = item.optBoolean("fh", false);
                box.flipV = item.optBoolean("fv", false);
                // 旧版把 90°/270° 烘焙进图片，并交换过框的宽高；转成自由角度后要换回来，
                // 否则同一份布局升级后会横竖颠倒。
                if (version < 2) {
                    int legacy = Math.round(rotation) % 360;
                    if (legacy == 90 || legacy == 270) {
                        float width = box.w;
                        box.w = box.h;
                        box.h = width;
                    }
                }
                scene.images.add(box);
            }
        }
        return scene;
    }
}
