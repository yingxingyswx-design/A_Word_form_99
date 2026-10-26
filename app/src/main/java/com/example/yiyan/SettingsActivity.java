package com.example.yiyan;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ScreenInsets.applyTo(this);
        prefs = getSharedPreferences("settings", 0);

        findViewById(R.id.manage_quotes).setOnClickListener(v -> startActivity(new Intent(this, ManageQuotesActivity.class)));
        findViewById(R.id.add_widget).setOnClickListener(v -> new WidgetPinHelper(this).add());

        bindOrderMode();
        bindCategory();
        bindParaphraseSwitch();
        bindFontSize();
        bindMonet();
        bindThemeColor();
        bindAddQuote();
        bindCustomLayout();
        bindRefreshAll();
        bindBottomNav();
    }

    private void bindCustomLayout() {
        final MaterialSwitch custom = findViewById(R.id.custom_layout);
        custom.setChecked(prefs.getBoolean("customLayout", false));
        custom.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("customLayout", checked).apply();
            sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
            Toast.makeText(this, checked ? "已切换到自定义布局" : "已恢复默认排版",
                    Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.edit_layout).setOnClickListener(v -> {
            if (!prefs.getBoolean("customLayout", false)) {
                prefs.edit().putBoolean("customLayout", true).apply();
                custom.setChecked(true);
            }
            startActivity(new Intent(this, LayoutEditorActivity.class));
        });
    }

    private void bindOrderMode() {
        Spinner mode = findViewById(R.id.order_mode);
        mode.setAdapter(ArrayAdapter.createFromResource(this, R.array.order_modes,
                android.R.layout.simple_spinner_dropdown_item));
        mode.setSelection(prefs.getBoolean("randomMode", true) ? 0 : 1);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putBoolean("randomMode", position == 0).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void bindCategory() {
        Spinner category = findViewById(R.id.custom_category);
        ArrayList<String> cats = new ArrayList<>();
        cats.add("用户");
        cats.add("哲学");
        for (String saved : prefs.getStringSet("categories", new HashSet<String>())) {
            if (!cats.contains(saved)) cats.add(saved);
        }
        category.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, cats));
    }

    private void bindParaphraseSwitch() {
        MaterialSwitch show = findViewById(R.id.show_paraphrase);
        show.setChecked(prefs.getBoolean("paraphrase", true));
        show.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean("paraphrase", checked).apply());
    }

    private void bindFontSize() {
        Slider size = findViewById(R.id.font_size);
        size.setValue(clampFontSize(prefs.getInt("fontSize", 16)));
        size.addOnChangeListener((slider, value, fromUser) ->
                prefs.edit().putInt("fontSize", Math.round(value)).apply());
    }

    private float clampFontSize(int value) {
        return Math.max(12f, Math.min(28f, value));
    }

    private void bindMonet() {
        MaterialSwitch monet = findViewById(R.id.monet);
        monet.setChecked(prefs.getBoolean("monet", false));
        monet.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean("monet", checked).apply());
    }

    private void bindThemeColor() {
        ColorPickerView picker = findViewById(R.id.color_picker);
        picker.setOnColorChangedListener(color ->
                prefs.edit().putInt("themeColor", color).apply());
    }

    private void bindAddQuote() {
        findViewById(R.id.add_quote).setOnClickListener(v -> {
            EditText textField = findViewById(R.id.custom_text);
            EditText authorField = findViewById(R.id.custom_author);
            EditText workField = findViewById(R.id.custom_work);
            Spinner category = findViewById(R.id.custom_category);

            String text = sanitize(textField.getText().toString());
            if (text.isEmpty()) {
                Toast.makeText(this, "句子是必填的", Toast.LENGTH_SHORT).show();
                textField.requestFocus();
                return;
            }
            // 作者与出处都是选填，留空时条目照样有效，小组件那一行会整段省略
            String author = sanitize(authorField.getText().toString());
            String work = sanitize(workField.getText().toString());
            String cat = String.valueOf(category.getSelectedItem());

            Set<String> quotes = new HashSet<>(prefs.getStringSet("custom_quotes", new HashSet<String>()));
            String line = new Quote(text, author, work, "用户添加", cat).toLine();
            if (!quotes.add(line)) {
                Toast.makeText(this, "这条一言已经在库里了", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putStringSet("custom_quotes", quotes).apply();

            textField.setText("");
            authorField.setText("");
            workField.setText("");
            sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
            Toast.makeText(this,
                    author.isEmpty() && work.isEmpty()
                            ? "已添加（未填写作者与出处）"
                            : "已加入“" + cat + "”分类",
                    Toast.LENGTH_SHORT).show();
        });
    }

    /** 存储用 "|" 分字段、换行分条目，所以用户输入里的这两个字符要替换掉。 */
    private String sanitize(String raw) {
        return raw.replace('|', '／').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private void bindRefreshAll() {
        findViewById(R.id.refresh_all).setOnClickListener(v -> {
            sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
            Toast.makeText(this, "已应用并刷新小组件", Toast.LENGTH_SHORT).show();
        });
    }

    private void bindBottomNav() {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            boolean theme = item.getItemId() == R.id.nav_theme;
            findViewById(R.id.quotes_panel).setVisibility(theme ? View.GONE : View.VISIBLE);
            findViewById(R.id.theme_panel).setVisibility(theme ? View.VISIBLE : View.GONE);
            return true;
        });
    }
}
