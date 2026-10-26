package com.example.yiyan;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 自定义布局编辑器。
 *
 * 画布上拖框体移动、拖右下角改大小；文字与图片框是实体，相交时这次位移会被丢弃。
 * 图片支持多个（上限 4 个）、删除、旋转 90°、水平/垂直镜像。
 * 小组件尺寸用「宽 +/− / 高 +/−」调整，编辑器会把画布按同样的宽高比显示。
 */
public class LayoutEditorActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_IMAGE = 4301;
    private static final int MAX_IMAGES = 4;

    private LayoutCanvasView canvas;
    private TextView hint;
    private SharedPreferences prefs;
    private CustomScene scene;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_layout_editor);
        ScreenInsets.applyTo(this);

        prefs = getSharedPreferences("settings", 0);
        scene = CustomScene.load(prefs);
        canvas = findViewById(R.id.layout_canvas);
        hint = findViewById(R.id.editor_hint);

        canvas.setScene(scene);
        canvas.setContent(prefs.getString("lastQuoteText", "知者不惑。"),
                prefs.getString("lastQuoteMeta", "— 孔子 · 《论语》 · 原文"));
        reloadBitmaps();
        updateHint();

        MaterialSwitch boxSwitch = findViewById(R.id.sw_text_box);
        boxSwitch.setChecked(scene.textBox);
        boxSwitch.setOnCheckedChangeListener((button, checked) -> {
            scene.textBox = checked;
            canvas.invalidate();
        });

        findViewById(R.id.btn_add_image).setOnClickListener(v -> pickImage());
        findViewById(R.id.btn_delete).setOnClickListener(v -> deleteSelected());
        findViewById(R.id.btn_rotate).setOnClickListener(v -> transformSelected(1, 0));
        findViewById(R.id.btn_flip_h).setOnClickListener(v -> transformSelected(0, 1));
        findViewById(R.id.btn_flip_v).setOnClickListener(v -> transformSelected(0, 2));
        findViewById(R.id.btn_width_minus).setOnClickListener(v -> resizeWidget(-40, 0));
        findViewById(R.id.btn_width_plus).setOnClickListener(v -> resizeWidget(40, 0));
        findViewById(R.id.btn_height_minus).setOnClickListener(v -> resizeWidget(0, -20));
        findViewById(R.id.btn_height_plus).setOnClickListener(v -> resizeWidget(0, 20));
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            scene = CustomScene.defaults();
            canvas.setScene(scene);
            canvas.clearSelection();
            reloadBitmaps();
            updateHint();
        });
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
    }

    // ---- 图片 ----

    private void pickImage() {
        if (scene.images.size() >= MAX_IMAGES) {
            Toast.makeText(this, "最多放 " + MAX_IMAGES + " 张图片", Toast.LENGTH_SHORT).show();
            return;
        }
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

        String name = ImageStore.importFrom(this, uri);
        if (name == null) {
            Toast.makeText(this, "无法读取这张图片（过大或格式不支持）", Toast.LENGTH_LONG).show();
            return;
        }
        if (ImageStore.isGif(name)) {
            int frames = ImageStore.extractFrames(this, name);
            if (frames > 1) {
                Toast.makeText(this, "GIF 已拆成 " + frames + " 帧", Toast.LENGTH_SHORT).show();
            }
        }

        CustomScene.ImageBox box = new CustomScene.ImageBox(0.55f, 0.15f, 0.22f,
                Math.min(0.9f, 0.22f * scene.widthDp / Math.max(1, scene.heightDp)));
        box.name = name;
        placeFree(box);
        scene.images.add(box);
        canvas.setScene(scene);
        reloadBitmaps();
        updateHint();
    }

    /** 网格扫描找一个不与现有框相交的位置；实在没空位就贴着右下角放。 */
    private void placeFree(CustomScene.ImageBox box) {
        for (float y = 0.02f; y < 0.95f; y += 0.06f) {
            for (float x = 0.02f; x < 0.95f; x += 0.05f) {
                if (x + box.w > 1f || y + box.h > 1f) continue;
                box.x = x;
                box.y = y;
                if (!overlapsAny(box)) return;
            }
        }
        box.x = Math.max(0f, 1f - box.w - 0.02f);
        box.y = Math.max(0f, 1f - box.h - 0.02f);
    }

    private boolean overlapsAny(CustomScene.Box candidate) {
        if (candidate.intersects(scene.text, 0.004f)) return true;
        for (CustomScene.ImageBox box : scene.images) {
            if (candidate.intersects(box, 0.004f)) return true;
        }
        return false;
    }

    private void deleteSelected() {
        if (canvas.getSelectedType() != LayoutCanvasView.TYPE_IMAGE) {
            Toast.makeText(this, "先选中一张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= scene.images.size()) return;
        CustomScene.ImageBox box = scene.images.remove(index);
        ImageStore.deleteWithFrames(this, box.name);
        canvas.setScene(scene);
        canvas.clearSelection();
        reloadBitmaps();
        updateHint();
    }

    /** kind: 1=旋转 90°，2=水平镜像，3=垂直镜像。 */
    private void transformSelected(int kind, int unused) {
        if (canvas.getSelectedType() != LayoutCanvasView.TYPE_IMAGE) {
            Toast.makeText(this, "先选中一张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= scene.images.size()) return;
        CustomScene.ImageBox box = scene.images.get(index);
        if (kind == 1) {
            box.rotation = (box.rotation + 90) % 360;
            // 转 90° 时把框的宽高对调，否则方图会被裁掉；与别的框冲突就保持原样
            CustomScene.Box candidate = new CustomScene.Box(box.x, box.y,
                    Math.min(box.h, 1f - box.x), Math.min(box.w, 1f - box.y));
            if (!overlapsAny(candidate)) {
                box.w = candidate.w;
                box.h = candidate.h;
            }
        } else if (kind == 2) {
            box.flipH = !box.flipH;
        } else if (kind == 3) {
            box.flipV = !box.flipV;
        }
        reloadBitmaps();
        updateHint();
    }

    private void resizeWidget(int deltaWidth, int deltaHeight) {
        scene.widthDp = Math.max(160, Math.min(900, scene.widthDp + deltaWidth));
        scene.heightDp = Math.max(100, Math.min(520, scene.heightDp + deltaHeight));
        canvas.invalidate();
        updateHint();
    }

    private void reloadBitmaps() {
        for (int i = 0; i < MAX_IMAGES; i++) {
            if (i >= scene.images.size()) {
                canvas.setBitmap(i, null);
                continue;
            }
            CustomScene.ImageBox box = scene.images.get(i);
            String source = firstFrameName(box.name);
            Bitmap bitmap = ImageStore.decodeTransformed(this, source, 480,
                    box.rotation, box.flipH, box.flipV);
            canvas.setBitmap(i, bitmap);
        }
    }

    private String firstFrameName(String name) {
        java.util.List<String> frames = ImageStore.frameNames(this, name);
        return frames.isEmpty() ? name : frames.get(0);
    }

    private void updateHint() {
        String selected;
        int type = canvas.getSelectedType();
        if (type == LayoutCanvasView.TYPE_TEXT) {
            selected = "已选中：文字框";
        } else if (type == LayoutCanvasView.TYPE_IMAGE && canvas.getSelectedIndex() >= 0) {
            CustomScene.ImageBox box = scene.images.get(canvas.getSelectedIndex());
            selected = "已选中：图片 " + (canvas.getSelectedIndex() + 1)
                    + "（旋转 " + box.rotation + "°"
                    + (box.flipH ? "，水平镜像" : "")
                    + (box.flipV ? "，垂直镜像" : "") + "）";
        } else {
            selected = "未选中";
        }
        hint.setText("小组件 " + scene.widthDp + "×" + scene.heightDp + "dp · "
                + scene.images.size() + "/" + MAX_IMAGES + " 张图片 · " + selected
                + "\n拖框移动，拖右下角改大小；相交时会放弃这次位移");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (canvas != null) updateHint();
    }

    private void save() {
        scene.save(prefs);
        prefs.edit().putBoolean("customLayout", true).apply();
        sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
        Toast.makeText(this, "已保存，小组件按新布局重排", Toast.LENGTH_SHORT).show();
        finish();
    }
}
