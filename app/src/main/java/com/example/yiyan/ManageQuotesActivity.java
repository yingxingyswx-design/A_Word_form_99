package com.example.yiyan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * 一言管理：搜索、按分类筛选、点击条目编辑。
 * 作者、出处、配图都是可选的，编辑时只有句子必填。
 * 配图通过系统文件选择器（SAF）选中后立刻复制到私有目录，支持 GIF。
 */
public class ManageQuotesActivity extends Activity {

    private static final String ALL = "全部";
    private static final String DEFAULT_CATEGORY = "用户";
    private static final int REQUEST_PICK_IMAGE = 4201;

    private LinearLayout flow;
    private Spinner categoryFilter;
    private final ArrayList<Quote> items = new ArrayList<>();
    private final ArrayList<String> categories = new ArrayList<>();
    private SharedPreferences prefs;

    // 编辑弹窗状态
    private AlertDialog editDialog;
    private ImageView editPreview;
    private TextView editStatus;
    private int editIndex = -1;
    private String editImagePath = "";
    private String editOldImagePath = "";
    private boolean editSaved = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_quotes);
        ScreenInsets.applyTo(this);
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

    /** 只把句子非空的条目写回存储；作者/出处/配图为空都是允许的。 */
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
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setPadding(dp(20), dp(18), dp(20), dp(18));
            card.setOnClickListener(v -> edit(index));

            TextView body = new TextView(this);
            StringBuilder text = new StringBuilder();
            text.append("第 ").append(number++).append(" 句 · ").append(item.category)
                    .append('\n').append(item.text);
            String source = item.source();
            if (!source.isEmpty()) text.append('\n').append(source);
            body.setText(text.toString());
            body.setTextSize(16);
            card.addView(body, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Bitmap thumb = item.hasImage() ? ImageStore.decode(this, item.imagePath, 160) : null;
            if (thumb != null) {
                ImageView preview = new ImageView(this);
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.setImageBitmap(thumb);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
                lp.setMargins(dp(12), 0, 0, 0);
                card.addView(preview, lp);
            }

            flow.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Space gap = new Space(this);
            flow.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
        }
    }

    // ---- 编辑 ----

    private void edit(int index) {
        final Quote old = items.get(index);
        editIndex = index;
        editOldImagePath = old.imagePath;
        editImagePath = old.imagePath;
        editSaved = false;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        EditText text = field(old.text, "句子（必填）");
        EditText author = field(old.author, "作者（选填）");
        EditText work = field(old.work, "出处 / 作品（选填）");
        EditText category = field(old.category, "分类");
        box.addView(text);
        box.addView(author);
        box.addView(work);
        box.addView(category);
        box.addView(buildImageRow());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        editDialog = new AlertDialog.Builder(this)
                .setTitle("编辑第 " + (index + 1) + " 句")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        editDialog.setOnDismissListener(d -> {
            stopPreviewAnimation();
            // 只是挑了图没保存：把刚拷贝进来的文件删掉，避免垃圾文件
            if (!editSaved && !editImagePath.isEmpty() && !editImagePath.equals(editOldImagePath)) {
                ImageStore.deleteWithFrames(this, editImagePath);
            }
            editDialog = null;
            editPreview = null;
            editStatus = null;
        });
        editDialog.show();
        editDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newText = text.getText().toString().trim();
            if (newText.isEmpty()) {
                Toast.makeText(this, "句子不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!editImagePath.equals(editOldImagePath) && !editOldImagePath.isEmpty()) {
                ImageStore.deleteWithFrames(this, editOldImagePath);
            }
            String newCategory = category.getText().toString().trim();
            items.set(editIndex, new Quote(newText,
                    author.getText().toString().trim(),
                    work.getText().toString().trim(),
                    "用户编辑",
                    newCategory.isEmpty() ? DEFAULT_CATEGORY : newCategory,
                    editImagePath));
            editSaved = true;
            editDialog.dismiss();
            persist();
        });
        refreshPreview();
    }

    private View buildImageRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        editPreview = new ImageView(this);
        editPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(editPreview, new LinearLayout.LayoutParams(0, dp(120), 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        Button pick = new Button(this);
        pick.setText("选择图片");
        pick.setOnClickListener(v -> pickImage());
        Button remove = new Button(this);
        remove.setText("移除图片");
        remove.setOnClickListener(v -> {
            String previous = editImagePath;
            editImagePath = "";
            if (!previous.isEmpty() && !previous.equals(editOldImagePath)) {
                ImageStore.deleteWithFrames(this, previous);
            }
            refreshPreview();
        });
        buttons.addView(pick);
        buttons.addView(remove);
        row.addView(buttons);

        editStatus = new TextView(this);
        editStatus.setTextSize(12);
        editStatus.setPadding(0, dp(6), 0, 0);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(row);
        column.addView(editStatus);
        return column;
    }

    private void pickImage() {
        // SAF：不需要存储权限，也不依赖返回的临时授权（字节会被立刻复制到私有目录）
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        String imported = ImageStore.importFrom(this, uri);
        if (imported == null) {
            Toast.makeText(this, "无法读取这张图片（可能过大或格式不支持）", Toast.LENGTH_LONG).show();
            return;
        }
        String previous = editImagePath;
        editImagePath = imported;
        if (!previous.isEmpty() && !previous.equals(editOldImagePath)) {
            ImageStore.deleteWithFrames(this, previous);
        }
        if (ImageStore.isGif(imported)) {
            int frames = ImageStore.extractFrames(this, imported);
            Toast.makeText(this,
                    frames > 1
                            ? "GIF 已拆成 " + frames + " 帧，桌面小组件会循环播放"
                            : "这个 GIF 拆不出多帧，桌面只显示首帧",
                    frames > 1 ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        }
        refreshPreview();
    }

    private void refreshPreview() {
        if (editPreview == null) return;
        if (editImagePath.isEmpty()) {
            editPreview.setImageDrawable(null);
            editPreview.setVisibility(View.GONE);
            editStatus.setText("未选择图片");
            return;
        }
        editPreview.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ImageStore.isGif(editImagePath)) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(
                        ImageStore.resolve(this, editImagePath));
                Drawable drawable = ImageDecoder.decodeDrawable(source);
                editPreview.setImageDrawable(drawable);
                if (drawable instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) drawable).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    ((AnimatedImageDrawable) drawable).start();
                }
                int[] info = ImageStore.readFrameInfo(this, editImagePath);
                editStatus.setText(info[0] > 1
                        ? "GIF 已拆成 " + info[0] + " 帧，桌面每 " + info[1] + " ms 轮播（此处预览动画）"
                        : "GIF 未能拆帧，桌面只显示首帧（此处可预览动画）");
                return;
            } catch (Exception ignored) {
                // 解码失败就退回静态首帧
            }
        }
        Bitmap bitmap = ImageStore.decode(this, editImagePath, 640);
        if (bitmap != null) {
            editPreview.setImageBitmap(bitmap);
            int[] info = ImageStore.readFrameInfo(this, editImagePath);
            if (ImageStore.isGif(editImagePath)) {
                editStatus.setText(info[0] > 1
                        ? "GIF 已拆成 " + info[0] + " 帧，桌面每 " + info[1] + " ms 轮播"
                        : "GIF 未能拆帧，桌面只显示首帧");
            } else {
                editStatus.setText("已选择图片");
            }
        } else {
            editPreview.setImageDrawable(null);
            editStatus.setText("图片读取失败");
        }
    }

    private void stopPreviewAnimation() {
        if (editPreview == null) return;
        Drawable drawable = editPreview.getDrawable();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && drawable instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) drawable).stop();
        }
    }

    private EditText field(String value, String hint) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hint);
        edit.setTextSize(16);
        return edit;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
