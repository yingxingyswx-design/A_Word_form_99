package com.example.yiyan.xposed;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Vector / LSPosed 模块本体：跑在 launcher 进程里。
 *
 * 桌面进程里能拿到「小组件身后真正被画出来的像素」——整块 DragLayer 的内容
 * （壁纸 + 图标 + 别的组件）。我们给 DragLayer 挂一个 RuntimeShader 的 RenderEffect：
 * 小组件矩形之外原样输出，矩形之内做圆角 SDF 折射 / 色散 / 提饱和 / 边缘高光。
 * 因为效果作用在整棵视图树上，每帧都由 GPU 重算，这是真正的实时演算，
 * 而不是小组件里那张提前算好的壁纸位图。
 *
 * 只对 com.android.launcher3 生效；参数从主 App 的 SharedPreferences 读（root）。
 */
public final class LauncherGlass {

    private static final String TAG = "DailyLineGlass";
    private static final String PREFS = "/data/user/0/com.example.yiyan/shared_prefs/settings.xml";

    static final String ACTION_CONFIG = "com.example.yiyan.GLASS_CONFIG";
    private static final Map<String, String> config = new HashMap<>();
    private static String cachedPrefs = "";
    private static long cachedAt;
    private static RuntimeShader shader;
    private static RenderEffect effect;
    private static String lastSignature;

    private LauncherGlass() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            // 小组件刷新时一定会走这里：直接拿到宿主 View，再往上找 DragLayer。
            // 比 hook Activity#onResume 稳（各版本 Launcher 会重写 onResume）。
            XposedHelpers.findAndHookMethod(
                    "com.android.launcher3.widget.LauncherAppWidgetHostView", classLoader,
                    "updateAppWidget", android.widget.RemoteViews.class,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) return;
                            View host = (View) param.thisObject;
                            XposedBridge.log("DailyLine glass: widget host updated");
                            View layer = findDragLayerAncestor(host);
                            if (layer != null) {
                                attach(layer);
                            } else {
                                XposedBridge.log("DailyLine glass: no DragLayer ancestor");
                            }
                        }
                    });

            XposedBridge.log("DailyLine glass: hook installed");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static View findDragLayerAncestor(View view) {
        Object node = view;
        for (int i = 0; i < 12 && node instanceof View; i++) {
            View current = (View) node;
            if (current.getClass().getName().endsWith("DragLayer")) return current;
            Object parent = current.getParent();
            node = parent;
        }
        // 退而求其次：从根视图里找
        View root = view.getRootView();
        return findByClassName(root, "DragLayer");
    }

    private static View findDragLayer(Object activity) {
        try {
            Object layer = XposedHelpers.callMethod(activity, "getDragLayer");
            if (layer instanceof View) return (View) layer;
        } catch (Throwable ignored) {
        }
        try {
            Object window = XposedHelpers.callMethod(activity, "getWindow");
            Object decor = XposedHelpers.callMethod(window, "getDecorView");
            if (decor instanceof View) {
                View found = findByClassName((View) decor, "DragLayer");
                if (found != null) return found;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static View findByClassName(View view, String suffix) {
        if (view.getClass().getName().endsWith(suffix)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByClassName(group.getChildAt(i), suffix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean receiverRegistered;

    /** 配置从主 App 的广播来：launcher 进程没有 root，读不了对方的 prefs。 */
    private static void registerConfigReceiver(android.content.Context context) {
        if (receiverRegistered || context == null) return;
        receiverRegistered = true;
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_CONFIG);
            context.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent intent) {
                    if (intent == null) return;
                    config.clear();
                    android.os.Bundle extras = intent.getExtras();
                    if (extras != null) {
                        for (String key : extras.keySet()) {
                            Object value = extras.get(key);
                            if (value != null) config.put(key, String.valueOf(value));
                        }
                    }
                    lastSignature = null;
                }
            }, filter, android.content.Context.RECEIVER_EXPORTED);
            XposedBridge.log("DailyLineGlass: config receiver registered");
        } catch (Throwable t) {
            receiverRegistered = false;
            XposedBridge.log(t);
        }
    }

    private static void attach(final View root) {
        if (!(root instanceof ViewGroup)) return;
        registerConfigReceiver(root.getContext());
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    apply(root);
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
                handler.postDelayed(this, 1200);
            }
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        try {
                            apply(root);
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                    }
                });
        apply(root);
    }

    private static void apply(View root) {
        if (Build.VERSION.SDK_INT < 33) return;

        Map<String, String> prefs = config;
        if (!"true".equals(prefs.get("xposedGlass"))) {
            root.setRenderEffect(null);
            return;
        }

        View widget = findOurWidget(root);
        if (widget == null) {
            if (effect != null) XposedBridge.log("DailyLineGlass: no widget found");
            root.setRenderEffect(null);
            effect = null;
            return;
        }

        int[] rootLoc = new int[2];
        int[] widgetLoc = new int[2];
        root.getLocationOnScreen(rootLoc);
        widget.getLocationOnScreen(widgetLoc);

        float left = widgetLoc[0] - rootLoc[0];
        float top = widgetLoc[1] - rootLoc[1];
        float width = widget.getWidth();
        float height = widget.getHeight();
        if (width <= 0 || height <= 0) return;

        boolean frost = "frost".equals(prefs.get("glassMaterial"));
        float blur01 = number(prefs, "glassBlur", 62) / 100f;
        float density01 = number(prefs, "glassDensity", 45) / 100f;
        float highlight01 = number(prefs, "glassHighlight", 70) / 100f;
        float cornerDp = number(prefs, "glassCorner", 28);
        float density = root.getResources().getDisplayMetrics().density;
        boolean dark = "dark".equals(prefs.get("glassTone"));

        String signature = (int) left + "," + (int) top + "," + (int) width + "," + (int) height
                + "," + frost + "," + blur01 + "," + density01 + "," + highlight01 + "," + dark;
        if (signature.equals(lastSignature) && effect != null) return;
        lastSignature = signature;

        if (shader == null) {
            shader = new RuntimeShader(AGSL);
        }
        float minDim = Math.min(width, height);
        float corner = Math.min(cornerDp * density, minDim * 0.5f);
        float refraction = Math.min((0.10f + blur01 * 0.12f) * minDim, 90f);
        float curve = Math.min((0.36f + blur01 * 0.14f) * minDim * 0.35f, 40f);
        float dispersion = 0.05f + highlight01 * 0.09f;
        float saturation = 1.22f + density01 * 0.14f;
        float contrast = (dark ? 1.45f : 1.35f) + density01 * 0.25f;
        float tintAlpha = 0.03f + density01 * (dark ? 0.16f : 0.12f);
        float edge = 0.10f + highlight01 * 0.18f;

        shader.setFloatUniform("center", left + width / 2f, top + height / 2f);
        shader.setFloatUniform("halfSize", width / 2f, height / 2f);
        shader.setFloatUniform("corner", corner);
        shader.setFloatUniform("refraction", refraction);
        shader.setFloatUniform("curve", curve);
        shader.setFloatUniform("dispersion", dispersion);
        shader.setFloatUniform("saturation", saturation);
        shader.setFloatUniform("contrast", contrast);
        shader.setFloatUniform("edge", edge);
        shader.setFloatUniform("phase", (float) (android.os.SystemClock.uptimeMillis() / 2600.0));
        if (dark) {
            shader.setFloatUniform("tint", 0.05f, 0.04f, 0.08f);
        } else {
            shader.setFloatUniform("tint", 1f, 1f, 1f);
        }
        shader.setFloatUniform("tintMix", tintAlpha);

        float frostPx = frost ? (8f + blur01 * 18f) * density : (1f + blur01 * 3f) * density;
        RenderEffect liquid = RenderEffect.createRuntimeShaderEffect(shader, "content");
        if (frostPx < 1f) {
            effect = liquid;
        } else {
            effect = RenderEffect.createChainEffect(liquid,
                    RenderEffect.createBlurEffect(frostPx, frostPx, Shader.TileMode.CLAMP));
        }
        root.setRenderEffect(effect);
        XposedBridge.log("DailyLineGlass: glass on " + (int) left + "," + (int) top + " "
                + (int) width + "x" + (int) height + (frost ? " frost" : " liquid"));
    }

    private static View findOurWidget(View root) {
        if (root instanceof AppWidgetHostView) {
            AppWidgetHostView host = (AppWidgetHostView) root;
            try {
                AppWidgetProviderInfo info = host.getAppWidgetInfo();
                if (info != null && info.provider != null
                        && "com.example.yiyan".equals(info.provider.getPackageName())) {
                    return host;
                }
            } catch (Throwable ignored) {
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findOurWidget(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static float number(Map<String, String> prefs, String key, float fallback) {
        try {
            String value = prefs.get(key);
            return value == null ? fallback : Float.parseFloat(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 读主 App 的偏好：launcher 进程没有它的权限，用 root 读一次缓存几秒。 */
    private static Map<String, String> readPrefs() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!cachedPrefs.isEmpty() && now - cachedAt < 4000) {
            return parse(cachedPrefs);
        }
        StringBuilder sb = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + PREFS});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            process.waitFor();
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
        cachedPrefs = sb.toString();
        cachedAt = now;
        return parse(cachedPrefs);
    }

    private static Map<String, String> parse(String xml) {
        Map<String, String> out = new HashMap<>();
        int index = 0;
        while (true) {
            int nameAt = xml.indexOf("name=\"", index);
            if (nameAt < 0) break;
            int nameEnd = xml.indexOf('"', nameAt + 6);
            int valueAt = xml.indexOf("value=\"", nameEnd);
            if (nameEnd < 0 || valueAt < 0) break;
            int valueEnd = xml.indexOf('"', valueAt + 7);
            if (valueEnd < 0) break;
            out.put(xml.substring(nameAt + 6, nameEnd), xml.substring(valueAt + 7, valueEnd));
            index = valueEnd;
        }
        return out;
    }

    /**
     * 整窗效果：小组件矩形外原样返回，矩形内做透镜。
     * 采样的是 launcher 这一帧真正画出来的内容（壁纸 + 图标），所以图标压在小组件下面
     * 也能被正确地「折」进玻璃里。
     */
    private static final String AGSL = ""
            + "uniform shader content;\n"
            + "uniform float2 center;\n"
            + "uniform float2 halfSize;\n"
            + "uniform float corner;\n"
            + "uniform float refraction;\n"
            + "uniform float curve;\n"
            + "uniform float dispersion;\n"
            + "uniform float saturation;\n"
            + "uniform float contrast;\n"
            + "uniform float3 tint;\n"
            + "uniform float tintMix;\n"
            + "uniform float edge;\n"
            + "uniform float phase;\n"
            + "float sdRoundRect(float2 p, float2 b, float r) {\n"
            + "  float2 q = abs(p) - b + r;\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n"
            + "}\n"
            + "float2 sdNormal(float2 p, float2 b, float r) {\n"
            + "  float2 w = abs(p) - (b - r);\n"
            + "  float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);\n"
            + "  float g = max(w.x, w.y);\n"
            + "  float2 q = max(w, 0.0);\n"
            + "  float l = length(q);\n"
            + "  return s * ((g > 0.0) ? q / l : ((w.x > w.y) ? float2(1.0, 0.0) : float2(0.0, 1.0)));\n"
            + "}\n"
            + "half4 main(float2 fc) {\n"
            + "  float2 p = fc - center;\n"
            + "  float d = sdRoundRect(p, halfSize, corner);\n"
            + "  half4 base = content.eval(fc);\n"
            + "  if (d > 1.0) return base;\n"
            + "  float2 n = sdNormal(p, halfSize, corner);\n"
            + "  float lensW = max(6.0, corner * 1.8);\n"
            + "  float rim = 1.0 - saturate(-d / lensW);\n"
            + "  float bend = rim * rim;\n"
            + "  float2 uv = fc - n * bend * refraction;\n"
            + "  float2 dist = (fc - center) / max(halfSize * 2.0, float2(1.0));\n"
            + "  float2 aberration = dispersion * dist * dist * dist * min(halfSize.x, halfSize.y);\n"
            + "  half4 cR = content.eval(uv - aberration);\n"
            + "  half4 cG = content.eval(uv);\n"
            + "  half4 cB = content.eval(uv + aberration);\n"
            + "  float3 col = float3(cR.r, cG.g, cB.b);\n"
            + "  float lum = dot(col, float3(0.2126, 0.7152, 0.0722));\n"
            + "  col = mix(float3(lum), col, saturation);\n"
            + "  col = (col - 0.5) * contrast + 0.5;\n"
            + "  col = mix(col, tint, tintMix);\n"
            + "  float rimLine = 1.0 - smoothstep(0.0, max(1.5, 2.0), -d);\n"
            + "  float2 lightDir = normalize(float2(sin(phase), cos(phase)));\n"
            + "  float facing = saturate(dot(n, lightDir));\n"
            + "  float spec = pow(facing * bend, 3.0) * 0.6;\n"
            + "  col += float3(rimLine * 0.55 + spec * edge * 3.0);\n"
            + "  col = clamp(col, 0.0, 1.0);\n"
            + "  float aa = saturate(0.5 - d);\n"
            + "  return half4(mix(base.rgb, col, aa), 1.0);\n"
            + "}\n";
}
