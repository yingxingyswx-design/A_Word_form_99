package com.example.yiyan;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义布局编辑器。
 *
 * 画布上拖框体移动、拖右下角改大小、拖右上角圆点任意角度旋转；文字框与图片框可以任意重叠。
 * 图片支持多个（上限 4 个）、删除、水平/垂直镜像。
 * 小组件尺寸用「宽 +/− / 高 +/−」调整，编辑器会把画布按同样的宽高比显示。
 */
public class LayoutEditorActivity extends ThemedActivity {

    private static final int REQUEST_PICK_IMAGE = 4301;
    /** 上限跟小组件槽位数走（RemoteViews 布局是静态的），见 widget_quote_custom.xml。 */
    private static final int MAX_IMAGES = QuoteWidgetProvider.MAX_IMAGE_SLOTS;
    /** 「旋转」按钮每次微调多少度；按住可以归零。 */
    private static final float ROTATE_STEP = 1f;

    private LayoutCanvasView canvas;
    private TextView hint;
    private SharedPreferences prefs;
    private CustomScene scene;
    private boolean userResized;
    private boolean sizeDetected;
    private int detectedWidthDp;
    private int detectedHeightDp;

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
        // 进页面先尝试用无障碍缓存到的小组件矩形，把画布比例对齐桌面实际大小
        applyDetectedWidgetSize(false);
        updateHint();

        findViewById(R.id.btn_add_image).setOnClickListener(v -> pickImage());
        findViewById(R.id.btn_delete).setOnClickListener(v -> deleteSelected());
        // 右上角：文字框开关（只控制显示 / 隐藏，文字本身不能删除）
        findViewById(R.id.btn_text_toggle).setOnClickListener(v -> toggleTextBox());
        // 「更多」只保留读取桌面实际组件大小，其余次要操作已去掉
        findViewById(R.id.btn_more).setOnClickListener(v -> {
            if (!applyDetectedWidgetSize(true)) showAccessibilityHelp();
        });
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        refreshTextToggle();
    }

    private void toggleTextBox() {
        canvas.setTextBox(!canvas.isTextBoxOn());
        refreshTextToggle();
        updateHint();
    }

    private void refreshTextToggle() {
        View toggle = findViewById(R.id.btn_text_toggle);
        if (!(toggle instanceof TextView)) return;
        ((TextView) toggle).setText(canvas.isTextBoxOn() ? "隐藏文字框" : "显示文字框");
    }

    /** 删除当前选中的图片；未选中图片时提示。 */
    private void deleteSelected() {
        if (!canvas.isImageSelected()) {
            Toast.makeText(this, "先选中一张图片", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = canvas.getSelectedIndex();
        if (index < 0 || index >= scene.images.size()) return;
        CustomScene.ImageBox removed = scene.images.remove(index);
        ImageStore.deleteWithFrames(this, removed.name);
        canvas.setScene(scene);
        canvas.clearSelection();
        reloadBitmaps();
        updateHint();
    }

    // ---- 图片 ----

    private void pickImage() {
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
        // 槽位是有限的静态布局，超出的图片必须提示并回收，避免留下孤立文件
        if (scene.images.size() >= MAX_IMAGES) {
            ImageStore.deleteWithFrames(this, name);
            Toast.makeText(this, "最多放 " + MAX_IMAGES + " 张图片，请先删除一张",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (ImageStore.isGif(name)) {
            int frames = ImageStore.extractFrames(this, name);
            if (frames > 1) {
                Toast.makeText(this, "GIF 已拆成 " + frames + " 帧", Toast.LENGTH_SHORT).show();
            }
        }

        // 新图片框的宽高比跟随图片本身：fitCenter 下不会出现上下 / 左右空边
        int[] size = ImageStore.aspect(this, firstFrameName(name));
        float imageAspect = size == null ? 1f : (float) size[0] / Math.max(1, size[1]);
        float boxWidth = 0.22f;
        float boxHeight = boxWidth * (scene.widthDp / Math.max(1, scene.heightDp)) / imageAspect;
        if (boxHeight > 0.9f) {
            boxHeight = 0.9f;
            boxWidth = Math.min(0.9f, boxHeight * imageAspect * (scene.heightDp
                    / (float) Math.max(1, scene.widthDp)));
        }
        CustomScene.ImageBox box = new CustomScene.ImageBox(0f, 0f,
                Math.max(0.08f, boxWidth), Math.max(0.08f, boxHeight));
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

    /**
     * 无障碍服务会把桌面 AppWidgetHostView 的屏幕矩形存进 GlassAlign；
     * 第一个候选是脚本当时正在显示的那一句所命中的小组件。
     *
     * @return 是否读到了尺寸
     */
    private boolean applyDetectedWidgetSize(boolean toast) {
        if (!accessibilityEnabled()) {
            if (toast) {
                Toast.makeText(this, "请先开启 DailyLine 无障碍，再回桌面让服务扫描一次",
                        Toast.LENGTH_LONG).show();
            }
            return false;
        }
        int screenW = prefs.getInt("widgetScreenW", 0);
        int screenH = prefs.getInt("widgetScreenH", 0);
        long detectedAt = prefs.getLong("widgetSizeAt", 0);
        if (screenW <= 0 || screenH <= 0 || detectedAt <= 0
                || System.currentTimeMillis() - detectedAt > 30L * 60L * 1000L) {
            if (toast) {
                Toast.makeText(this,
                        "还没读到最新的 DailyLine 组件尺寸，请回到放着组件的那一页再回来",
                        Toast.LENGTH_LONG).show();
            }
            return false;
        }
        float density = getResources().getDisplayMetrics().density;
        int width = Math.max(120, Math.min(900, Math.round(screenW / density)));
        int height = Math.max(80, Math.min(520, Math.round(screenH / density)));
        sizeDetected = true;
        detectedWidthDp = width;
        detectedHeightDp = height;
        if (scene.widthDp != width || scene.heightDp != height) {
            scene.widthDp = width;
            scene.heightDp = height;
            canvas.invalidate();
        }
        updateHint();
        if (toast) {
            Toast.makeText(this, "已读取桌面组件：" + width + "×" + height + "dp",
                    Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean accessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName());
    }

    private void showAccessibilityHelp() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("读取桌面组件大小")
                .setMessage("需要开启 DailyLine 的无障碍服务：开启后回到桌面让服务扫描一次，"
                        + "再回到这里即可自动读取组件实际宽高。\n\n"
                        + "无障碍只读取 AppWidgetHostView 的屏幕坐标，不读取文字，也不监听输入。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去开启", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        Toast.makeText(this, "打不开无障碍设置", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private int bitmapToken;

    /** 每次调用都作废上一轮加载，避免快速增删图片时旧结果覆盖新布局。 */
    private void reloadBitmaps() {
        final int token = ++bitmapToken;
        final List<CustomScene.ImageBox> snapshot = new ArrayList<>(scene.images);
        new Thread(() -> {
            for (int i = 0; i < MAX_IMAGES; i++) {
                final int index = i;
                final String source = i < snapshot.size()
                        ? firstFrameName(snapshot.get(i).name) : null;
                final CustomScene.ImageBox box = i < snapshot.size() ? snapshot.get(i) : null;
                final Bitmap bitmap = box == null ? null
                        : ImageStore.decodeFlipped(getApplicationContext(), source, 480,
                        box.flipH, box.flipV);
                runOnUiThread(() -> {
                    if (token != bitmapToken) return;
                    canvas.setBitmap(index, bitmap);
                });
            }
        }, "layout-thumbs").start();
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
        String size = "画布 " + scene.widthDp + "×" + scene.heightDp + "dp";
        if (sizeDetected) {
            size += " · 桌面实测 " + detectedWidthDp + "×" + detectedHeightDp + "dp";
        }
        // 主界面不再堆按钮：选中后提示全屏拖动，其余操作在「更多」里
        hint.setText(size + " · " + scene.images.size() + "/" + MAX_IMAGES
                + " 张图片 · " + selected
                + "\n选中后按住画布任意位置即可拖动；右下角改大小，右上角圆点旋转"
                + "\n右上角可开关文字框描边（文字本身永远保留，不会被删除）");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (canvas != null) {
            if (!userResized) applyDetectedWidgetSize(false);
            updateHint();
        }
    }

    private void save() {
        scene.save(prefs);
        prefs.edit().putBoolean("customLayout", true).apply();
        sendBroadcast(new Intent(QuoteWidgetProvider.ACTION).setPackage(getPackageName()));
        Toast.makeText(this, "已保存，小组件按新布局重排", Toast.LENGTH_SHORT).show();
        finish();
    }
}
