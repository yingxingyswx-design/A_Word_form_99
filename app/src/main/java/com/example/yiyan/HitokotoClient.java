package com.example.yiyan;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 在线随机一言：调用 Hitokoto 接口（https://v1.hitokoto.cn/）。
 *
 * 小组件每次换句时取一条，成功就缓存到 prefs；网络失败时退回缓存，
 * 缓存也没有就由调用方回退到本地语料，保证桌面不会因为断网变空白。
 */
final class HitokotoClient {

    private static final String ENDPOINT = "https://v1.hitokoto.cn/?encode=json&charset=utf-8";
    private static final String CACHE_KEY = "hitokoto_cache";
    private static final String TAG = "99YIYAN_LOG";

    static final String CATEGORY = "随机";

    private HitokotoClient() {
    }

    static Quote fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "DailyLine-Android");
        int code = connection.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);

        String body = readAll(connection.getInputStream());
        JSONObject json = new JSONObject(body);
        String text = json.optString("hitokoto", "").trim();
        if (text.isEmpty()) throw new IOException("返回内容为空");

        String work = json.optString("from", "").trim();
        String author = json.optString("from_who", "").trim();
        if (author.isEmpty()) author = work;
        if (author.equals(work)) work = "";
        return new Quote(text, author, work, CATEGORY, CATEGORY);
    }

    static void cache(SharedPreferences prefs, Quote quote) {
        prefs.edit().putString(CACHE_KEY, quote.toLine()).apply();
    }

    static Quote cached(SharedPreferences prefs) {
        Quote quote = Quote.parse(prefs.getString(CACHE_KEY, ""), CATEGORY, CATEGORY);
        if (quote != null) Log.i(TAG, "使用缓存的一言：" + quote.text);
        return quote;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return out.toString("UTF-8");
    }
}
