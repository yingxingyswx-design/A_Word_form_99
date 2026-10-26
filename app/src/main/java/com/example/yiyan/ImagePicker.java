package com.example.yiyan;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

/**
 * 统一用系统相册选择器（Android Photo Picker）。
 *
 * 比 SAF 文件管理器好在：只显示图片 / GIF，界面是相册，不需要存储权限，
 * 也不需要长期持有 content:// 授权（选完立刻复制进私有目录）。
 * Android 13 以下退回系统文件选择器。
 */
final class ImagePicker {

    private ImagePicker() {
    }

    /**
     * 依次尝试：
     * 1. ACTION_PICK + 图片库 Uri —— 大多数机器上直接落到「相册」应用（这台是 Google Photos 的选择器）；
     * 2. ACTION_PICK_IMAGES —— Android 13+ 的系统照片选择器；
     * 3. ACTION_GET_CONTENT —— 让系统弹「相册 / 文件」选择；
     * 4. ACTION_OPEN_DOCUMENT —— 兜底。
     */
    static boolean pick(Activity activity, int requestCode) {
        // 注意：不能先用 resolveActivity 判断——Android 11+ 的包可见性会让它返回 null，
        // 于是永远落不到相册应用上（只能开系统照片选择器）。直接试、抛异常再换下一种。
        try {
            Intent pick = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            pick.setType("image/*");
            activity.startActivityForResult(pick, requestCode);
            return true;
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
                intent.setType("image/*");
                activity.startActivityForResult(intent, requestCode);
                return true;
            } catch (Exception ignored) {
            }
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (Exception ignored) {
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (Exception e) {
            return false;
        }
    }



    /** 选择结果里的图片 Uri；没有就返回 null。 */
    static Uri uriFrom(Intent data) {
        if (data == null) return null;
        Uri uri = data.getData();
        if (uri != null) return uri;
        // Photo Picker 在多选时走 ClipData，这里只取第一张
        if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
            return data.getClipData().getItemAt(0).getUri();
        }
        return null;
    }
}
