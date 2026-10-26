package com.example.yiyan;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给小组件里的 AdapterViewFlipper 提供逐帧视图。
 *
 * 桌面自己按 flipInterval 翻页，不需要应用进程反复发更新广播。
 * 帧文件由 {@link ImageStore#extractFrames} 从 GIF 拆出来；这里只负责解码成位图。
 */
public class QuoteImageWidgetService extends RemoteViewsService {

    static final String EXTRA_FRAMES = "com.example.yiyan.EXTRA_FRAMES";
    static final String EXTRA_BACKGROUND = "com.example.yiyan.EXTRA_BACKGROUND";
    static final String EXTRA_ROTATION = "com.example.yiyan.EXTRA_ROTATION";
    static final String EXTRA_FLIP_H = "com.example.yiyan.EXTRA_FLIP_H";
    static final String EXTRA_FLIP_V = "com.example.yiyan.EXTRA_FLIP_V";

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        ArrayList<String> frames = intent == null
                ? null : intent.getStringArrayListExtra(EXTRA_FRAMES);
        int background = intent == null ? 0 : intent.getIntExtra(EXTRA_BACKGROUND, 0);
        int rotation = intent == null ? 0 : intent.getIntExtra(EXTRA_ROTATION, 0);
        boolean flipH = intent != null && intent.getBooleanExtra(EXTRA_FLIP_H, false);
        boolean flipV = intent != null && intent.getBooleanExtra(EXTRA_FLIP_V, false);
        return new FrameFactory(getApplicationContext(), frames, background, rotation, flipH, flipV);
    }

    private static final class FrameFactory implements RemoteViewsFactory {

        /** 帧位图很小（<=160px），整轮缓存下来避免每次翻页都重新解码。 */
        private static final int CACHE_SIZE = 12;

        private final Context context;
        private final ArrayList<String> frames = new ArrayList<>();
        private final int background;
        private final int rotation;
        private final boolean flipH;
        private final boolean flipV;
        private final LinkedHashMap<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(
                CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                return size() > CACHE_SIZE;
            }
        };

        FrameFactory(Context context, ArrayList<String> frames, int background,
                     int rotation, boolean flipH, boolean flipV) {
            this.context = context;
            this.background = background;
            this.rotation = rotation;
            this.flipH = flipH;
            this.flipV = flipV;
            if (frames != null) this.frames.addAll(frames);
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
            cache.clear();
        }

        @Override
        public void onDestroy() {
            cache.clear();
        }

        @Override
        public int getCount() {
            return frames.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= frames.size()) return null;
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_image_frame);
            String name = frames.get(position);
            Bitmap bitmap = cache.get(name);
            if (bitmap == null) {
                bitmap = ImageStore.decodeTransformed(context, name, ImageStore.WIDGET_MAX_PX,
                        rotation, flipH, flipV);
                if (bitmap != null) cache.put(name, bitmap);
            }
            if (bitmap != null) views.setImageViewBitmap(R.id.frame_image, bitmap);
            if (background != 0) {
                views.setInt(R.id.frame_root, "setBackgroundColor", background);
            }
            return views;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
