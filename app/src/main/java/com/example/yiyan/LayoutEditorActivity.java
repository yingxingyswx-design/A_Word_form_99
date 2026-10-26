package com.example.yiyan;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.Toast;

/**
 * 自定义布局编辑器：在应用内拖动文字块与图片块。
 *
 * 桌面小组件本身收不到拖拽事件（RemoteViews 只有点击），所以拖拽只发生在
 * 这个真实 View 上，结果以 0–1 的分数写进 prefs，由小组件换算成 padding。
 */
public class LayoutEditorActivity extends Activity {

    private LayoutCanvasView canvas;
    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_layout_editor);
        ScreenInsets.applyTo(this);
        prefs = getSharedPreferences("settings", 0);

        canvas = findViewById(R.id.layout_canvas);
        canvas.setPositions(
                prefs.getFloat("textX", 0.06f),
                prefs.getFloat("textY", 0.06f),
                prefs.getFloat("imageX", 0.58f),
                prefs.getFloat("imageY", 0.18f));

        boolean box = prefs.getBoolean("textBox", false);
        canvas.setBoxEnabled(box);
        Switch boxSwitch = findViewById(R.id.text_box);
        boxSwitch.setChecked(box);
        boxSwitch.setOnCheckedChangeListener((button, checked) -> {
            canvas.setBoxEnabled(checked);
            canvas.invalidate();
        });

        String text = prefs.getString("lastQuoteText", "知者不惑。");
        String meta = prefs.getString("lastQuoteMeta", "— 孔子 · 《论语》 · 原文");
        String imageName = prefs.getString("lastQuoteImage", "");
        Bitmap bitmap = imageName.isEmpty() ? null : ImageStore.decode(this, imageName, 640);
        canvas.setContent(text, meta, bitmap);

        findViewById(R.id.reset_layout).setOnClickListener(v -> canvas.resetPositions());
        findViewById(R.id.save_layout).setOnClickListener(v -> save());
    }

    private void save() {
        prefs.edit()
                .putBoolean("customLayout", true)
                .putBoolean("textBox", canvas.isBoxEnabled())
                .putFloat("textX", canvas.getTextX())
                .putFloat("textY", canvas.getTextY())
                .putFloat("imageX", canvas.getImageX())
                .putFloat("imageY", canvas.getImageY())
                .apply();
        sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
        Toast.makeText(this, "已保存，小组件会按新位置重排", Toast.LENGTH_SHORT).show();
        finish();
    }
}
