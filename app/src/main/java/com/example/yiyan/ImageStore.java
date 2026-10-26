package com.example.yiyan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 一言配图的本地存储。
 *
 * 用户通过系统文件选择器（SAF）挑图后立刻把字节复制到应用私有目录，
 * 之后只记文件名。这样不依赖可以随时失效的 content:// 授权，
 * 桌面进程也能在任意时刻读到文件。
 *
 * GIF 额外拆成逐帧 PNG（{@code <base>_f00.png} …），并写一份 {@code <base>.meta}
 * 记录帧数与间隔——RemoteViews 不能播放动图，桌面小组件靠 AdapterViewFlipper
 * 在这些帧文件之间自动翻页。
 */
final class ImageStore {

    private static final String TAG = "99YIYAN_LOG";
    private static final String DIR = "quote_images";
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    /** 小组件单次 RemoteViews 有 Binder 体积限制，解码后的位图必须压到这个尺寸以内。 */
    static final int WIDGET_MAX_PX = 240;
    /**
     * 帧文件按最长边存这个尺寸。小组件右侧图片按 78dp 显示，3x 屏也才 234px，
     * 但每次翻页都要把位图跨 Binder 送一次，所以取 160px —— 肉眼几乎看不出差别，
     * 单帧体积却从 230KB 降到约 100KB。
     */
    static final int FRAME_MAX_PX = 160;
    /** 桌面翻页间隔，必须和 widget_quote.xml 里的 android:flipInterval 保持一致。 */
    static final int FRAME_INTERVAL_MS = 200;
    /** 帧数上限：20 帧 × 100KB 意味着 5fps 下每秒搬运 500KB，压到 12 帧更稳。 */
    static final int MAX_FRAMES = 12;

    private ImageStore() {
    }

    static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建配图目录：" + dir);
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
                    Log.w(TAG, "配图超过 16MB，已放弃");
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
            Log.w(TAG, "导入配图失败", e);
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
            Log.w(TAG, "删除配图失败：" + file);
        }
    }

    /** 删除配图本身、它的 meta 与所有帧文件。 */
    static void deleteWithFrames(Context context, String name) {
        if (name == null || name.trim().isEmpty()) return;
        delete(context, name);
        clearFrames(context, name);
    }

    /** 只删 meta 与帧文件，保留原图（重新拆帧前清场用）。 */
    static void clearFrames(Context context, String name) {
        if (name == null || name.trim().isEmpty()) return;
        File dir = dir(context);
        String base = baseName(name);
        File meta = new File(dir, base + ".meta");
        if (meta.isFile()) meta.delete();
        File[] files = dir.listFiles();
        if (files == null) return;
        String prefix = base + "_f";
        for (File file : files) {
            String fileName = file.getName();
            if (fileName.startsWith(prefix) && fileName.endsWith(".png")) file.delete();
        }
    }

    static boolean isGif(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    /**
     * 把 GIF 拆成逐帧 PNG，并写 meta（帧数 / 帧间隔）。已经是静态图或拆不出两帧时返回 0。
     * 用已废弃的 {@link Movie} 解码 GIF —— 它是系统里唯一能按时间取帧的公开 API。
     */
    static int extractFrames(Context context, String imageName) {
        if (!isGif(imageName)) return 0;
        File gif = resolve(context, imageName);
        if (gif == null || !gif.isFile()) return 0;

        Movie movie;
        try {
            movie = Movie.decodeFile(gif.getPath());
        } catch (Throwable t) {
            Log.w(TAG, "GIF 解码失败：" + imageName, t);
            return 0;
        }
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) return 0;

        int duration = movie.duration();
        if (duration <= 0) duration = 900;
        // 采样帧数与时长成比例，配合固定翻页间隔让播放速度尽量接近原 GIF
        int samples = Math.min(MAX_FRAMES,
                Math.max(2, Math.round((float) duration / FRAME_INTERVAL_MS)));

        String base = baseName(imageName);
        File dir = dir(context);
        clearFrames(context, imageName);

        float scale = Math.min(1f, (float) FRAME_MAX_PX / Math.max(movie.width(), movie.height()));
        int width = Math.max(1, Math.round(movie.width() * scale));
        int height = Math.max(1, Math.round(movie.height() * scale));

        int saved = 0;
        Bitmap previous = null;
        try {
            for (int i = 0; i < samples; i++) {
                movie.setTime((int) ((long) duration * i / samples));
                Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(frame);
                canvas.scale(scale, scale);
                movie.draw(canvas, 0, 0);

                // GIF 里常有连续重复帧（表情包尤其多），去重后既不丢画面也少搬一份位图
                if (previous != null && previous.sameAs(frame)) {
                    frame.recycle();
                    continue;
                }
                if (previous != null) previous.recycle();
                previous = frame;

                OutputStream out = null;
                try {
                    out = new FileOutputStream(new File(dir, frameName(base, saved)));
                    frame.compress(Bitmap.CompressFormat.PNG, 100, out);
                    out.flush();
                    saved++;
                } finally {
                    closeQuietly(out);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "拆帧失败：" + imageName, e);
            if (previous != null) {
                previous.recycle();
                previous = null;
            }
            clearFrames(context, imageName);
            return 0;
        } finally {
            if (previous != null) previous.recycle();
        }

        if (saved < 2) {
            clearFrames(context, imageName);
            return 0;
        }

        FileWriter writer = null;
        try {
            writer = new FileWriter(new File(dir, base + ".meta"));
            writer.write(saved + "\n" + FRAME_INTERVAL_MS + "\n");
        } catch (Exception e) {
            Log.w(TAG, "写 GIF 帧信息失败", e);
            clearFrames(context, imageName);
            return 0;
        } finally {
            closeQuietly(writer);
        }
        return saved;
    }

    /** @return {@code [帧数, 帧间隔毫秒]}；没有拆过帧时返回 {@code [0, 150]}。 */
    static int[] readFrameInfo(Context context, String imageName) {
        if (imageName == null || imageName.trim().isEmpty()) return new int[]{0, 150};
        File meta = new File(dir(context), baseName(imageName) + ".meta");
        if (!meta.isFile()) return new int[]{0, 150};
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(meta), "UTF-8"));
            int count = Integer.parseInt(reader.readLine().trim());
            int interval = Integer.parseInt(reader.readLine().trim());
            return new int[]{count, Math.max(60, Math.min(600, interval))};
        } catch (Exception e) {
            return new int[]{0, 150};
        } finally {
            closeQuietly(reader);
        }
    }

    /** 该配图可用于轮播的帧文件名；不足两帧或文件缺失时返回空列表。 */
    static List<String> frameNames(Context context, String imageName) {
        if (imageName == null || imageName.trim().isEmpty()) return Collections.emptyList();
        int count = readFrameInfo(context, imageName)[0];
        if (count < 2) return Collections.emptyList();

        String base = baseName(imageName);
        File dir = dir(context);
        ArrayList<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String frame = frameName(base, i);
            if (!new File(dir, frame).isFile()) return Collections.emptyList();
            names.add(frame);
        }
        return names;
    }

    /**
     * 解码成位图并限制最长边不超过 {@code maxPx}。GIF 得到首帧（帧文件请直接用文件名解码）。
     */
    static Bitmap decode(Context context, String name, int maxPx) {        File file = resolve(context, name);
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
            Log.w(TAG, "解码配图失败：" + name, e);
            return null;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "配图过大，内存不足：" + name);
            return null;
        }
    }

    /** 解码并按旋转 / 镜像变换（旋转 90/180/270 时宽高对调）。 */
    static Bitmap decodeTransformed(Context context, String name, int maxPx,
                                    int rotation, boolean flipH, boolean flipV) {
        Bitmap source = decode(context, name, maxPx);
        if (source == null) return null;
        int normalized = ((rotation % 360) + 360) % 360;
        if (normalized == 0 && !flipH && !flipV) return source;
        try {
            Matrix matrix = new Matrix();
            matrix.postScale(flipH ? -1f : 1f, flipV ? -1f : 1f);
            matrix.postRotate(normalized);
            Bitmap transformed = Bitmap.createBitmap(source, 0, 0,
                    source.getWidth(), source.getHeight(), matrix, true);
            if (transformed != source) source.recycle();
            return transformed;
        } catch (Exception e) {
            Log.w(TAG, "变换配图失败：" + name, e);
            return source;
        }
    }

    private static String frameName(String base, int index) {
        return String.format(Locale.ROOT, "%s_f%02d.png", base, index);
    }

    private static List<String> frameFileNames(String base, int max) {
        ArrayList<String> names = new ArrayList<>(max);
        for (int i = 0; i < max; i++) names.add(frameName(base, i));
        return names;
    }

    static String baseName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
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
            if (path.endsWith(ext)) return ext;
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
