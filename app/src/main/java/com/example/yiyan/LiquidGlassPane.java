package com.example.yiyan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.RequiresApi;

/**
 * 真正「实时演算」的玻璃板：把背景画进自己这一层，再用 RuntimeShader + RenderEffect
 * 在 GPU 上逐像素折射 / 模糊 / 色散。
 *
 * 着色器直接取自 FolkPatch 用的那个库（FletchMcKee/liquid，Apache-2.0）；
 * 它的做法是：
 *   1. 把玻璃身后的内容录进一个 GraphicsLayer；
 *   2. RenderEffect.createChainEffect(RuntimeShader 效果, createBlurEffect(...))，
 *      先按 frost 半径模糊，再让着色器采样这张模糊图；
 *   3. 着色器内部用圆角矩形 SDF 求到边距离与法线，在边缘把采样坐标往内推（折射），
 *      再按距离做 RGB 色散、提饱和 / 提对比、混入 tint、叠边缘高光。
 * 因为跑在 GPU 上、每帧都算，背景一动折射就跟着动——这就是「实时」。
 *
 * 小组件（RemoteViews）没有 RenderEffect / RuntimeShader，这条路只能走在 App 窗口里。
 */
public class LiquidGlassPane extends View {

    private static final String TAG = "99YIYAN_LOG";

    /**
     * AGSL 着色器，来自 FletchMcKee/liquid 的 LiquidShader.kt（Apache-2.0）。
     * 只改了注释；uniform 名与计算与上游一致。
     */
    static final String AGSL = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refraction;\n"
            + "uniform float curve;\n"
            + "uniform float edge;\n"
            + "layout(color) uniform half4 tint;\n"
            + "uniform float saturation;\n"
            + "uniform float dispersion;\n"
            + "uniform float contrast;\n"
            + "uniform float phase;\n"
            + "const float AA_WIDTH_PX = 1.5;\n"
            + "float computeSdf(in float2 p, in float2 b, in half4 r) {\n"
            + "  r.xy = (p.x > 0.0) ? r.xy : r.zw;\n"
            + "  r.x = (p.y > 0.0) ? r.x : r.y;\n"
            + "  float2 q = abs(p) - b + r.x;\n"
            + "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r.x;\n"
            + "}\n"
            + "float2 computeSdfNormal(float2 pos, float2 shapeSize, half4 vr) {\n"
            + "  vr.xy = (pos.x > 0.0) ? vr.xy : vr.zw;\n"
            + "  vr.x = (pos.y > 0.0) ? vr.x : vr.y;\n"
            + "  float r = vr.x;\n"
            + "  float2 w = abs(pos) - (shapeSize - r);\n"
            + "  float2 s = float2(pos.x < 0.0 ? -1.0 : 1.0, pos.y < 0.0 ? -1.0 : 1.0);\n"
            + "  float g = max(w.x, w.y);\n"
            + "  float2 q = max(w, 0.0);\n"
            + "  float l = length(q);\n"
            + "  return s * ((g > 0.0) ? q / l : ((w.x > w.y) ? float2(1.0, 0.0) : float2(0.0, 1.0)));\n"
            + "}\n"
            + "half3 applyColorAdjustments(half3 color) {\n"
            + "  float lum = dot(color, half3(0.2126, 0.7152, 0.0722));\n"
            + "  color = saturate(mix(half3(lum), color, saturation));\n"
            + "  return saturate((color - 0.5) * contrast + 0.5);\n"
            + "}\n"
            + "half4 main(float2 fragCoord) {\n"
            + "  float minDimension = min(size.x, size.y);\n"
            + "  float2 center = size * 0.5;\n"
            + "  float2 shapeCoord = (fragCoord - center) / minDimension;\n"
            + "  float2 shapeSize = size / minDimension;\n"
            + "  float2 halfShapeSize = shapeSize * 0.5;\n"
            + "  half4 shapeVr = half4(cornerRadii);\n"
            + "  float shapeSdf = computeSdf(shapeCoord, halfShapeSize, shapeVr);\n"
            + "  float aaWidth = AA_WIDTH_PX / minDimension;\n"
            + "  if (shapeSdf > aaWidth) {\n"
            + "    return half4(0.0);\n"
            + "  }\n"
            + "  half4 lensVr = min(shapeVr * 1.5, half4(min(shapeSize.x, shapeSize.y) * 0.5));\n"
            + "  float2 sdfNormal = computeSdfNormal(shapeCoord, halfShapeSize, lensVr);\n"
            + "  half4 fragColor;\n"
            + "  float2 baseCoord = fragCoord;\n"
            + "  if (refraction > 0.0 && curve > 0.0) {\n"
            + "    float lensDepth = 1.0 - saturate(-shapeSdf / refraction);\n"
            + "    float distortion = 1.0 - sqrt(1.0 - lensDepth * lensDepth);\n"
            + "    float normalDisplacement = distortion * -curve * minDimension;\n"
            + "    baseCoord = fragCoord + normalDisplacement * sdfNormal;\n"
            + "  }\n"
            + "  if (dispersion > 0.0) {\n"
            + "    float2 distFromCenter = (fragCoord - center) / size;\n"
            + "    float2 aberration = dispersion * distFromCenter * distFromCenter * distFromCenter * minDimension;\n"
            + "    float2 coordR = baseCoord - aberration;\n"
            + "    float2 coordG = baseCoord;\n"
            + "    float2 coordB = baseCoord + aberration;\n"
            + "    float2 shapeCoordR = (coordR - center) / minDimension;\n"
            + "    float2 shapeCoordB = (coordB - center) / minDimension;\n"
            + "    bool validR = computeSdf(shapeCoordR, halfShapeSize, shapeVr) <= 0.0;\n"
            + "    bool validB = computeSdf(shapeCoordB, halfShapeSize, shapeVr) <= 0.0;\n"
            + "    half4 colorG = content.eval(coordG);\n"
            + "    half4 colorR = validR ? content.eval(coordR) : colorG;\n"
            + "    half4 colorB = validB ? content.eval(coordB) : colorG;\n"
            + "    fragColor = half4(colorR.r, colorG.g, colorB.b, colorG.a);\n"
            + "  } else {\n"
            + "    fragColor = content.eval(baseCoord);\n"
            + "  }\n"
            + "  if (fragColor.a <= 0.0) {\n"
            + "    fragColor = content.eval(fragCoord);\n"
            + "  }\n"
            + "  fragColor.rgb = applyColorAdjustments(fragColor.rgb);\n"
            + "  fragColor.rgb = mix(fragColor.rgb, tint.rgb, tint.a);\n"
            + "  float edgeSmooth = smoothstep(-edge, 0.0, shapeSdf);\n"
            + "  float2 lightDirection = normalize(float2(sin(phase), cos(phase))) * 0.21;\n"
            + "  float nDotL = abs(dot(sdfNormal, lightDirection));\n"
            + "  float edgeLighting = edgeSmooth * nDotL;\n"
            + "  fragColor.rgb += edgeLighting;\n"
            + "  float aaAlpha = 1.0 - smoothstep(-aaWidth * 0.5, aaWidth * 0.5, shapeSdf);\n"
            + "  return fragColor * aaAlpha;\n"
            + "}\n";

    private final float[] radii = new float[4];

    private Bitmap wallpaper;
    private int screenWidth;
    private int screenHeight;
    private float time;

    private float cornerPx = 36f;
    private float frostPx = 8f;
    private float refraction = 0.14f;
    private float curve = 0.42f;
    private float edge = 0.14f;
    private float saturation = 0.58f;
    private float dispersion = 0.22f;
    private float contrast = 1.6f;
    private int tint = 0x14FFFFFF;

    private RuntimeShader shader;
    private RenderEffect blurEffect;

    private float phase;
    private boolean overlayMode;

    /** 悬浮窗模式：按屏幕坐标采样壁纸，不压缩放 / 漂移，玻璃和桌面严丝合缝。 */
    void setOverlayMode(boolean overlay) {
        this.overlayMode = overlay;
        invalidate();
    }

    void setPhase(float value) {
        this.phase = value;
        if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            shader.setFloatUniform("phase", phase);
        }
        invalidate();
    }

    public LiquidGlassPane(Context context) {
        this(context, null);
    }

    public LiquidGlassPane(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    void setWallpaper(Bitmap bitmap) {
        this.wallpaper = bitmap;
        invalidate();
    }

    void setScreenSize(int width, int height) {
        this.screenWidth = width;
        this.screenHeight = height;
        invalidate();
    }

    void setTime(float seconds) {
        this.time = seconds;
        invalidate();
    }

    /**
     * @param cornerDp 圆角
     * @param frostDp  模糊半径；液态玻璃几 dp，毛玻璃十几 dp
     * @param blur01   0–1，控制折射 / 色散 / 饱和
     * @param density01 0–1，控制 tint 与对比
     * @param highlight01 0–1，控制边缘高光
     */
    void setGlass(float cornerDp, float frostDp, float blur01, float density01,
                  float highlight01, boolean dark) {
        float d = getResources().getDisplayMetrics().density;
        this.cornerPx = cornerDp * d;
        this.frostPx = frostDp * d;
        this.refraction = 0.08f + blur01 * 0.10f;
        this.curve = 0.36f + blur01 * 0.14f;
        this.edge = 0.05f + highlight01 * 0.12f;
        this.saturation = 0.50f + density01 * 0.25f;
        this.dispersion = 0.05f + highlight01 * 0.09f;
        this.contrast = (dark ? 1.55f : 1.45f) + density01 * 0.35f;
        float tintAlpha = 0.03f + density01 * (dark ? 0.14f : 0.10f);
        this.tint = dark
                ? android.graphics.Color.argb(Math.round(tintAlpha * 255f), 12, 10, 20)
                : android.graphics.Color.argb(Math.round(tintAlpha * 255f), 255, 255, 255);
        // 模糊半径进了 RenderEffect，参数一换就要重建
        blurEffect = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) invalidateEffect();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) invalidateEffect();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 窗口坐标 → 本视图坐标。背景层画什么这里就画什么，着色器才能采样到「身后」。
        int[] loc = new int[2];
        if (overlayMode) {
            getLocationOnScreen(loc);
        } else {
            getLocationInWindow(loc);
        }
        View root = getRootView();
        int w = screenWidth > 0 ? screenWidth : root.getWidth();
        int h = screenHeight > 0 ? screenHeight : root.getHeight();
        canvas.save();
        canvas.translate(-loc[0], -loc[1]);
        LiquidGlassScene.draw(canvas, wallpaper, w, h, time, !overlayMode);
        if (!overlayMode) {
            // 背景层压了一层暗角，玻璃也要采到同样的像素，否则玻璃会比周围亮一截
            LiquidGlassScene.drawScrim(canvas, w, h);
        }
        canvas.restore();
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void invalidateEffect() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (shader == null) {
            shader = new RuntimeShader(AGSL);
        }
        float minDim = Math.min(w, h);
        // 折射量是按「最短边」归一化的：卡片尺寸下刚好，占满屏幕就会把整块画面拧成哈哈镜。
        // 按 480px 做一个参考尺寸缩放，尺寸越大越收敛。
        float sizeDamp = Math.min(1f, 480f / minDim);
        float r = Math.min(cornerPx, minDim * 0.5f) / minDim;
        // cornerRadii 顺序：bottomEnd, topEnd, bottomStart, topStart
        radii[0] = r;
        radii[1] = r;
        radii[2] = r;
        radii[3] = r;

        shader.setFloatUniform("size", (float) w, (float) h);
        shader.setFloatUniform("cornerRadii", radii);
        // 边缘折射带和位移都按像素封顶：不然占满屏幕时整块画面会像被吸进透镜
        float refractionNorm = Math.min(refraction * sizeDamp, 64f / minDim);
        float curveNorm = Math.min(curve * sizeDamp, 36f / minDim);
        shader.setFloatUniform("refraction", refractionNorm);
        shader.setFloatUniform("curve", curveNorm);
        shader.setFloatUniform("edge", edge);
        shader.setColorUniform("tint", tint);
        shader.setFloatUniform("saturation", saturation);
        shader.setFloatUniform("dispersion", dispersion);
        shader.setFloatUniform("contrast", contrast);
        shader.setFloatUniform("phase", phase);

        RenderEffect liquid = RenderEffect.createRuntimeShaderEffect(shader, "content");
        if (frostPx < 1f) {
            setRenderEffect(liquid);
            return;
        }
        if (blurEffect == null) {
            blurEffect = RenderEffect.createBlurEffect(frostPx, frostPx, Shader.TileMode.CLAMP);
        }
        setRenderEffect(RenderEffect.createChainEffect(liquid, blurEffect));
    }


}
