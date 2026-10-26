package com.example.yiyan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * 一言管理：搜索、按分类筛选、点击条目编辑。
 * 作者与出处都是可选的，编辑时只有句子必填。
 */
public class ManageQuotesActivity extends Activity {

    private static final String ALL = "全部";
    private static final String DEFAULT_CATEGORY = "用户";

    private LinearLayout flow;
    private Spinner categoryFilter;
    private final ArrayList<Quote> items = new ArrayList<>();
    private final ArrayList<String> categories = new ArrayList<>();
    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_quotes);
        prefs = getSharedPreferences("settings", 0);
        flow = findViewById(R.id.quotes_flow);

        loadCategories();
        addHeader();
        loadItems();

        EditText search = findViewById(R.id.search_quotes);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                render(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    // ---- 头部：分类增删 + 分类筛选 + 标题 ----

    private void addHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        EditText input = new EditText(this);
        input.setHint("新分类");
        Button add = new Button(this);
        add.setText("添加");
        Button delete = new Button(this);
        delete.setText("删除当前分类");

        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(add);
        bar.addView(delete);
        flow.addView(bar);

        categoryFilter = new Spinner(this);
        flow.addView(categoryFilter);
        refreshCategoryFilter();

        add.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty() && !categories.contains(name)) {
                categories.add(name);
                saveCategories();
                input.setText("");
                refreshCategoryFilter();
            }
        });
        delete.setOnClickListener(v -> {
            String selected = String.valueOf(categoryFilter.getSelectedItem());
            if (ALL.equals(selected) || "哲学".equals(selected) || DEFAULT_CATEGORY.equals(selected)) {
                Toast.makeText(this, "内置分类不可删除", Toast.LENGTH_SHORT).show();
                return;
            }
            categories.remove(selected);
            saveCategories();
            refreshCategoryFilter();
        });

        TextView title = new TextView(this);
        title.setText("一言管理 · 点击条目编辑");
        title.setTextSize(24);
        title.setPadding(8, 8, 8, 20);
        title.setGravity(Gravity.START);
        flow.addView(title);
    }

    private void loadCategories() {
        categories.add("哲学");
        categories.add(DEFAULT_CATEGORY);
        for (String saved : prefs.getStringSet("categories", new HashSet<String>())) {
            if (!categories.contains(saved)) categories.add(saved);
        }
    }

    private void saveCategories() {
        prefs.edit().putStringSet("categories", new HashSet<>(categories)).apply();
    }

    private void refreshCategoryFilter() {
        ArrayList<String> values = new ArrayList<>();
        values.add(ALL);
        values.addAll(categories);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values);
        categoryFilter.setAdapter(adapter);
        categoryFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                render("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // ---- 数据 ----

    private void loadItems() {
        items.clear();
        String saved = prefs.getString("editable_library", "");
        if (!saved.trim().isEmpty()) {
            for (String line : saved.split("\\r?\\n")) {
                addUnique(Quote.parse(line, "用户编辑", "哲学"));
            }
        } else {
            for (Quote quote : QuoteLibrary.ALL) {
                if (quote.kind.equals("译写 / 意译")) addUnique(quote);
            }
        }
        for (String line : prefs.getStringSet("custom_quotes", new HashSet<String>())) {
            addUnique(Quote.parse(line, "用户添加", DEFAULT_CATEGORY));
        }
        android.util.Log.i("99YIYAN_LOG", "管理页加载一言 " + items.size() + " 条");
        render("");
    }

    private void addUnique(Quote quote) {
        if (quote == null) return;
        for (Quote old : items) {
            if (old.text.equals(quote.text)
                    && old.author.equals(quote.author)
                    && old.work.equals(quote.work)) {
                return;
            }
        }
        items.add(quote);
    }

    /** 只把句子非空的条目写回存储；作者/出处为空是允许的。 */
    private void persist() {
        StringBuilder out = new StringBuilder();
        for (Quote quote : items) {
            if (quote.text.trim().isEmpty()) continue;
            out.append(quote.toLine()).append('\n');
        }
        prefs.edit().putString("editable_library", out.toString()).apply();
        sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
        render("");
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    // ---- 列表 ----

    private void render(String query) {
        while (flow.getChildCount() > 3) flow.removeViewAt(3);

        String keyword = query.trim().toLowerCase(Locale.ROOT);
        String selected = categoryFilter == null ? ALL : String.valueOf(categoryFilter.getSelectedItem());
        int number = 1;

        for (Quote item : items) {
            String haystack = (item.text + " " + item.author + " " + item.work + " " + item.category)
                    .toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty() && !haystack.contains(keyword)) continue;
            if (!ALL.equals(selected) && !item.category.equals(selected)) continue;

            final int index = items.indexOf(item);
            TextView card = new TextView(this);
            StringBuilder body = new StringBuilder();
            body.append("第 ").append(number++).append(" 句 · ").append(item.category)
                    .append('\n').append(item.text);
            String source = item.source();
            if (!source.isEmpty()) body.append('\n').append(source);
            card.setText(body.toString());
            card.setTextSize(16);
            card.setPadding(20, 18, 20, 18);
            card.setOnClickListener(v -> edit(index));
            flow.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Space gap = new Space(this);
            flow.addView(gap, new LinearLayout.LayoutParams(1, 12));
        }
    }

    // ---- 编辑 ----

    private void edit(int index) {
        Quote old = items.get(index);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        EditText text = field(old.text, "句子（必填）");
        EditText author = field(old.author, "作者（选填）");
        EditText work = field(old.work, "出处 / 作品（选填）");
        EditText category = field(old.category, "分类");
        box.addView(text);
        box.addView(author);
        box.addView(work);
        box.addView(category);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑第 " + (index + 1) + " 句")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();
        // 自己接管保存按钮，句子为空时不关闭弹窗
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newText = text.getText().toString().trim();
            if (newText.isEmpty()) {
                Toast.makeText(this, "句子不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            String newCategory = category.getText().toString().trim();
            items.set(index, new Quote(newText,
                    author.getText().toString().trim(),
                    work.getText().toString().trim(),
                    "用户编辑",
                    newCategory.isEmpty() ? DEFAULT_CATEGORY : newCategory));
            dialog.dismiss();
            persist();
        });
    }

    private EditText field(String value, String hint) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hint);
        edit.setTextSize(16);
        return edit;
    }
}
