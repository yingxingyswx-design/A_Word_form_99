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
 * 画布上拖框体移动、拖右下角改大小、拖右上角圆点任意角度旋转；文字框与图片框可以任意重叠。
 * 图片支持多个（上限 4 个）、删除、水平/垂直镜像。
 * 小组件尺寸用「宽 +/− / 高 +/−」调整，编辑器会把画布按同样的宽高比显示。
 */
public class LayoutEditorActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_IMAGE = 4301;
    private static final int MAX_IMAGES = 4;
    /** 「旋转」按钮每次微调多少度；按住可以归零。 */
    private static final float ROTATE_STEP = 1f;

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
        canvas.setListener(this::updateHint);
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
        findViewById(R.id.btn_rotate).setOnClickListener(v -> rotateSelected(ROTATE_STEP));
        findViewById(R.id.btn_rotate).setOnLongClickListener(v -> {
            resetSelectedRotation();
            return true;
        });
        findViewById(R.id.btn_flip_h).setOnClickListener(v -> flipSelected(true));
        findViewById(R.id.btn_flip_v).setOnClickListener(v -> flipSelected(false));
        findViewById(R.id.btn_width_minus).setOnClickListener(v -> resizeWidget(-40, 0));
        findViewById(R.id.btn_width_plus).setOnClickListener(v -> resizeWidget(40, 0));
        findViewById(R.id.btn_height_minus).setOnClickListener(v -> resizeWidget(0, -20));
        findViewById(R.id.btn_height_plus).setOnClickListener(v -> resizeWidget(0, 20));
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            scene = CustomScene.defaults();
            canvas.setScene(scene);
            canvas.clearSelection();
            boxSwitch.setChecked(scene.textBox);
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
        // 走系统相册（Google Photos / 图库），不要文件管理器
        if (!ImagePicker.pick(this, REQUEST_PICK_IMAGE)) {
            Toast.makeText(this, "没有可用的相册选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGE || resultCode != RESULT_OK) return;
        Uri uri = ImagePicker.uriFrom(data);
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

        CustomScene.ImageBox box = new CustomScene.ImageBox(0f, 0f, 0.22f,
                Math.min(0.9f, 0.22f * scene.widthDp / Math.max(1, scene.heightDp)));
        // 依次错开叠放，允许压住文字或别的图片
        int count = scene.images.size();
        box.x = Math.max(0f, Math.min(1f - box.w, 0.55f + count * 0.04f));
        box.y = Math.max(0f, Math.min(1f - box.h, 0.14f + count * 0.06f));
        box.name = name;
        scene.images.add(box);
        canvas.setScene(scene);
        reloadBitmaps();
        updateHint();
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

    private void rotateSelected(float delta) {
        CustomScene.Box box = selectedBox();
        if (box == null) {
            Toast.makeText(this, "先选中文字框或图片", Toast.LENGTH_SHORT).show();
            return;
        }
        box.rotation = CustomScene.normalizeRotation(box.rotation + delta);
        canvas.invalidate();
        updateHint();
    }

    private void resetSelectedRotation() {
        CustomScene.Box box = selectedBox();
        if (box == null) {
            Toast.makeText(this, "先选中文字框或图片", Toast.LENGTH_SHORT).show();
            return;
        }
        box.rotation = 0f;
        canvas.invalidate();
        updateHint();
    }

    private void flipSelected(boolean horizontal) {
        if (canvas.getSelectedType() != LayoutCanvasView.TYPE_IMAGE) {
            Toast.makeText(this, "先选中一张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= scene.images.size()) return;
        CustomScene.ImageBox box = scene.images.get(index);
        if (horizontal) {
            box.flipH = !box.flipH;
        } else {
            box.flipV = !box.flipV;
        }
        reloadBitmaps();
        updateHint();
    }

    private CustomScene.Box selectedBox() {
        if (canvas.getSelectedType() == LayoutCanvasView.TYPE_TEXT) return scene.text;
        if (canvas.getSelectedType() == LayoutCanvasView.TYPE_IMAGE) {
            int index = canvas.getSelectedIndex();
            if (index >= 0 && index < scene.images.size()) return scene.images.get(index);
        }
        return null;
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
            // 旋转交给画布，这里只烘焙镜像
            Bitmap bitmap = ImageStore.decodeFlipped(this, source, 480,
                    box.flipH, box.flipV);
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
            selected = "已选中：文字框（旋转 " + Math.round(scene.text.rotation) + "°）";
        } else if (type == LayoutCanvasView.TYPE_IMAGE && canvas.getSelectedIndex() >= 0
                && canvas.getSelectedIndex() < scene.images.size()) {
            CustomScene.ImageBox box = scene.images.get(canvas.getSelectedIndex());
            selected = "已选中：图片 " + (canvas.getSelectedIndex() + 1)
                    + "（旋转 " + Math.round(box.rotation) + "°"
                    + (box.flipH ? "，水平镜像" : "")
                    + (box.flipV ? "，垂直镜像" : "") + "）";
        } else {
            selected = "未选中";
        }
        hint.setText("小组件 " + scene.widthDp + "×" + scene.heightDp + "dp · "
                + scene.images.size() + "/" + MAX_IMAGES + " 张图片 · " + selected
                + "\n拖动移动，拖右下角改大小，拖右上角圆点任意角度旋转；框之间可以重叠"
                + "\n「旋转」每按一次 +" + Math.round(ROTATE_STEP) + "°，长按归零");
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
