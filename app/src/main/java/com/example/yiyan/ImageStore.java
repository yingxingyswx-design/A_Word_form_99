package com.example.yiyan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * 一言配图的本地存储。
 *
 * 用户通过系统文件选择器（SAF）挑图后立刻把字节复制到应用私有目录，
 * 之后只记文件名。这样不依赖可以随时失效的 content:// 授权，
 * 桌面进程也能在任意时刻读到文件。
 */
final class ImageStore {

    private static final String DIR = "quote_images";
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    /** 小组件单次 RemoteViews 有 Binder 体积限制，解码后的位图必须压到这个尺寸以内。 */
    static final int WIDGET_MAX_PX = 240;

    private ImageStore() {
    }

    static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("99YIYAN_LOG", "无法创建配图目录：" + dir);
        }
        return dir;
    }

    static File resolve(Context context, String name) {
        if (name == null || name.trim().isEmpty()) return null;
        return new File(dir(context), name.trim());
    }

    static boolean exists(Context context, String name) {
        File file = resolve(context, name);
        return file != null && file.isFile();
    }

    /** 把选中的 Uri 复制进私有目录，返回文件名；失败或超限返回 null。 */
    static String importFrom(Context context, Uri uri) {
        if (uri == null) return null;
        InputStream in = null;
        OutputStream out = null;
        File target = null;
        try {
            String mime = null;
            try {
                mime = context.getContentResolver().getType(uri);
            } catch (Exception ignored) {
            }
            String name = UUID.randomUUID().toString().replace("-", "") + extensionFor(mime, uri);
            target = new File(dir(context), name);

            in = context.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            out = new FileOutputStream(target);

            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_BYTES) {
                    android.util.Log.w("99YIYAN_LOG", "配图超过 16MB，已放弃");
                    closeQuietly(out);
                    out = null;
                    target.delete();
                    return null;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
            return name;
        } catch (Exception e) {
            android.util.Log.w("99YIYAN_LOG", "导入配图失败", e);
            if (target != null) target.delete();
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    static void delete(Context context, String name) {
        File file = resolve(context, name);
        if (file != null && file.isFile() && !file.delete()) {
            android.util.Log.w("99YIYAN_LOG", "删除配图失败：" + file);
        }
    }

    static boolean isGif(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    /**
     * 解码成位图并限制最长边不超过 {@code maxPx}。GIF 只会得到首帧
     * —— RemoteViews 不支持动图，管理页里的动图预览另走 ImageDecoder。
     */
    static Bitmap decode(Context context, String name, int maxPx) {
        File file = resolve(context, name);
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) return null;

            int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longest > maxPx) {
                float scale = (float) maxPx / longest;
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * scale)),
                        Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }
            return bitmap;
        } catch (Exception e) {
            android.util.Log.w("99YIYAN_LOG", "解码配图失败：" + name, e);
            return null;
        } catch (OutOfMemoryError e) {
            android.util.Log.w("99YIYAN_LOG", "配图过大，内存不足：" + name);
            return null;
        }
    }

    private static String extensionFor(String mime, Uri uri) {
        String lower = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (lower.contains("gif")) return ".gif";
        if (lower.contains("png")) return ".png";
        if (lower.contains("webp")) return ".webp";
        if (lower.contains("bmp")) return ".bmp";
        if (lower.contains("heic") || lower.contains("heif")) return ".heic";
        if (lower.contains("jpeg") || lower.contains("jpg")) return ".jpg";

        // getType() 偶尔为 null，退而看路径后缀
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".gif", ".png", ".webp", ".jpg", ".jpeg", ".bmp", ".heic"}) {
            if (path.endsWith(ext)) return ".jpg".equals(ext) ? ".jpg" : ext;
        }
        return ".img";
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
