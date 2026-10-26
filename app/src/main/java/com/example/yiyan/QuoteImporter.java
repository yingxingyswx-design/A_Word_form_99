package com.example.yiyan;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 从 XML 导入一言。
 *
 * 支持两种写法（可混用，子标签优先于属性）：
 * {@code <quote text="…" author="…" work="…" category="…"/>}
 * {@code <quote><text>…</text><author>…</author><work>…</work><category>…</category></quote>}
 * 根节点任意（习惯用 quotes）。只有 text 必填，导入进「用户添加」，重复条目自动跳过。
 */
final class QuoteImporter {

    private static final int MAX_ENTRIES = 5000;

    static final class Result {
        int imported;
        int skipped;
        String error;
    }

    private QuoteImporter() {
    }

    static Result importXml(Context context, InputStream in) {
        Result result = new Result();
        SharedPreferences prefs = context.getSharedPreferences("settings", 0);
        Set<String> custom = new HashSet<>(prefs.getStringSet("custom_quotes", new HashSet<String>()));
        Set<String> keys = new HashSet<>();

        for (String line : custom) collectKey(line, keys);
        String editable = prefs.getString("editable_library", "");
        if (editable != null) {
            for (String line : editable.split("\\r?\\n")) collectKey(line, keys);
        }

        Set<String> merged = new HashSet<>(custom);
        String text = null, author = null, work = null, category = null;

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = parser.getName() == null ? "" : parser.getName().toLowerCase(Locale.ROOT);
                if (event == XmlPullParser.START_TAG) {
                    if (name.equals("quote") || name.equals("item")) {
                        text = parser.getAttributeValue(null, "text");
                        author = parser.getAttributeValue(null, "author");
                        work = parser.getAttributeValue(null, "work");
                        category = parser.getAttributeValue(null, "category");
                    } else if (name.equals("text") || name.equals("author")
                            || name.equals("work") || name.equals("category")) {
                        String value = parser.nextText().trim();
                        if (name.equals("text")) text = value;
                        else if (name.equals("author")) author = value;
                        else if (name.equals("work")) work = value;
                        else category = value;
                    }
                } else if (event == XmlPullParser.END_TAG
                        && (name.equals("quote") || name.equals("item"))) {
                    String t = safe(text);
                    if (!t.isEmpty() && merged.size() < MAX_ENTRIES) {
                        Quote quote = new Quote(t, safe(author), safe(work), "用户添加", safe(category));
                        if (keys.add(key(quote))) {
                            merged.add(quote.toLine());
                            result.imported++;
                        } else {
                            result.skipped++;
                        }
                    } else {
                        result.skipped++;
                    }
                    text = author = work = category = null;
                }
                event = parser.next();
            }
        } catch (Exception e) {
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        if (result.imported > 0) {
            prefs.edit().putStringSet("custom_quotes", merged).apply();
        }
        return result;
    }

    private static void collectKey(String line, Set<String> keys) {
        Quote quote = Quote.parse(line, "用户添加", "用户");
        if (quote != null) keys.add(key(quote));
    }

    private static String key(Quote quote) {
        return quote.text + "\u0001" + quote.author + "\u0001" + quote.work;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
