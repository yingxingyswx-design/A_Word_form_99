package com.example.yiyan;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

/**
 * 读取当前桌面壁纸。
 *
 * Android 14 起普通应用默认读不到壁纸像素（targetSdk ≥ 34 时 getDrawable() 会返回系统默认图
 * 或直接抛 SecurityException），所以这里按顺序尝试：
 * getWallpaperFile()（有权限时可用）→ getDrawable()（旧系统）→ 失败返回 null，
 * 调用方退回「壁纸取色」方案。
 */
public final class WallpaperSource {

    private static final String TAG = "99YIYAN_LOG";
    private static final long TTL_MS = 60_000L;

    private static Bitmap cached;
    private static int cachedId = Integer.MIN_VALUE;
    private static long cachedAt;

    private WallpaperSource() {
    }

    /** 有没有读壁纸的权限：Android 11+ 看「所有文件访问」，更早看存储权限。 */
    public static boolean canRead(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** 当前壁纸，可能为 null（读不到时）。返回值已缩到 maxWidth 以内，可直接模糊使用。 */
    public static Bitmap load(Context context, int maxWidth) {
        if (!canRead(context)) return null;
        WallpaperManager manager = WallpaperManager.getInstance(context);
        int id = -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                id = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
            } catch (Exception ignored) {
            }
        }
        if (cached != null && cachedId == id
                && System.currentTimeMillis() - cachedAt < TTL_MS) {
            return cached;
        }

        Bitmap bitmap = null;
        try {
            bitmap = readFromFile(manager, maxWidth);
        } catch (Throwable t) {
            Log.w(TAG, "壁纸文件不可读：" + t);
        }
        if (bitmap == null) {
            try {
                bitmap = readFromDrawable(manager, maxWidth);
            } catch (Throwable t) {
                Log.w(TAG, "壁纸 drawable 不可读：" + t);
            }
        }
        if (bitmap == null) {
            Log.w(TAG, "壁纸不可读，退回壁纸取色");
            return null;
        }
        cached = bitmap;
        cachedId = id;
        cachedAt = System.currentTimeMillis();
        Log.i(TAG, "壁纸已读取 " + bitmap.getWidth() + "x" + bitmap.getHeight() + " id=" + id);
        return bitmap;
    }

    private static Bitmap readFromFile(WallpaperManager manager, int maxWidth) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        ParcelFileDescriptor descriptor = manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
        if (descriptor == null) return null;
        try (android.os.ParcelFileDescriptor fd = descriptor) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, options);
            options.inSampleSize = sampleSize(options.outWidth, maxWidth);
            options.inJustDecodeBounds = false;
            Bitmap raw = android.graphics.BitmapFactory.decodeFileDescriptor(
                    fd.getFileDescriptor(), null, options);
            return scaleDown(raw, maxWidth);
        }
    }

    private static Bitmap readFromDrawable(WallpaperManager manager, int maxWidth) {
        Drawable drawable = manager.getDrawable();
        if (drawable == null) return null;
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            width = maxWidth;
            height = Math.round(maxWidth * 2.2f);
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return scaleDown(bitmap, maxWidth);
    }

    private static int sampleSize(int width, int maxWidth) {
        int sample = 1;
        while (width / sample > maxWidth * 2) sample *= 2;
        return sample;
    }

    private static Bitmap scaleDown(Bitmap raw, int maxWidth) {
        if (raw == null) return null;
        if (raw.getWidth() <= maxWidth) return raw;
        float ratio = maxWidth / (float) raw.getWidth();
        Bitmap scaled = Bitmap.createScaledBitmap(raw, maxWidth,
                Math.max(1, Math.round(raw.getHeight() * ratio)), true);
        if (scaled != raw) raw.recycle();
        return scaled;
    }
}
