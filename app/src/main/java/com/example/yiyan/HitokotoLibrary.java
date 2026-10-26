package com.example.yiyan;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * App 内置的 Hitokoto 语料（assets/hitokoto_sentences.txt）。
 *
 * 数据来源：https://hitokoto.cn/ 的官方语句库
 * https://github.com/hitokoto-osc/sentences-bundle（AGPL-3.0，见仓库 NOTICE.md）。
 * 每行是 {@code 句子 \t 作者 \t 出处 \t 原分类}，统一以「随机」分类、kind=Hitokoto 参与桌面轮换。
 *
 * 解析只做一次并缓存在进程内——1.1 万条每次重解析会让小组件刷新明显变慢。
 */
final class HitokotoLibrary {

    private static final String ASSET = "hitokoto_sentences.txt";
    private static final String TAG = "99YIYAN_LOG";

    static final String KIND = "Hitokoto";
    static final String CATEGORY = "随机";

    private static List<Quote> cache;

    private HitokotoLibrary() {
    }

    static synchronized List<Quote> quotes(Context context) {
        if (cache != null) return cache;

        long start = System.currentTimeMillis();
        List<Quote> list = new ArrayList<>(12000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(ASSET), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length < 1) continue;
                String text = fields[0].trim();
                if (text.isEmpty()) continue;
                String author = fields.length > 1 ? fields[1].trim() : "";
                String work = fields.length > 2 ? fields[2].trim() : "";
                list.add(new Quote(text, author, work, KIND, CATEGORY));
            }
        } catch (Exception e) {
            Log.w(TAG, "读取内置语料失败", e);
        }
        cache = list;
        Log.i(TAG, "内置 Hitokoto 语料 " + list.size() + " 条，耗时 "
                + (System.currentTimeMillis() - start) + "ms");
        return cache;
    }
}
