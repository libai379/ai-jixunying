package com.guixing.jixunying.ui.glass

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import com.kashif_e.backdrop.BackdropEffectScope
import com.kashif_e.backdrop.effects.effect

/*
 * 安卓上的折射（2026-10-24）。用户手机（三星，安卓 17）上侧栏玻璃里面有一圈很细的暗线框，拖「折射高度」线跟着动、拖到 0 消失；
 * 模拟器上没有。库（kashif-mehmood-km backdrop 0.0.1-alpha02，effects/LensKt 里的 AGSL 原文）的写法是：
 *   if (-sd >= refractionHeight) return content.eval(coord);   // 折射带里面
 *   ...                       return content.eval(refractedCoord); // 折射带上
 * 在分叉的两边各取一次图。有的手机显卡在分叉边界上那几个像素取样会算错（同一小块像素一半走这边一半走那边），
 * 沿着折射带的内边留下一道 1～2 像素的缝，就是那圈线框。
 * 这里照原样重写一份，只改一处：先算出要取哪个位置，最后只取一次图（不在分叉里取）。数学上和原来完全一样。
 * 色散那一支照癸星科技的 PatchedLens（F:\guixingkeji\...\glasstuning\PatchedLens.kt）把七个取样坐标夹在 [0, size]，
 * 不然窄长面板上会花屏。用的都是库公开的东西（obtainRuntimeShader、effect、padding、shape），没改库。
 */
actual fun BackdropEffectScope.glassLens(refractionHeight: Float, refractionAmount: Float, depthEffect: Boolean, chromaticAberration: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (refractionHeight <= 0f || refractionAmount <= 0f) return
    // 和库自己的 lens() 一样：折射带用掉了一部分边距
    if (padding > 0f) padding = (padding - refractionHeight).coerceAtLeast(0f)
    val radii = cornerRadii() ?: return
    val shader = if (chromaticAberration) obtainRuntimeShader("JxyLensDispersion", DISPERSION) else obtainRuntimeShader("JxyLens", LENS)
    shader.setFloatUniform("size", size.width, size.height)
    shader.setFloatUniform("offset", -padding, -padding)
    shader.setFloatUniform("cornerRadii", radii)
    shader.setFloatUniform("refractionHeight", refractionHeight)
    shader.setFloatUniform("refractionAmount", -refractionAmount)
    shader.setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
    if (chromaticAberration) shader.setFloatUniform("chromaticAberration", 1f)
    effect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
}

/** 和库里私有的 cornerRadii 一样的算法（癸星科技 PatchedLens.resolvedCornerRadii 照抄的那份）。 */
private fun BackdropEffectScope.cornerRadii(): FloatArray? {
    val max = size.minDimension / 2f
    val ltr = layoutDirection == LayoutDirection.Ltr
    return when (val s = shape) {
        is AbsoluteRoundedCornerShape -> floatArrayOf(
            s.topStart.toPx(size, this).coerceAtMost(max), s.topEnd.toPx(size, this).coerceAtMost(max),
            s.bottomEnd.toPx(size, this).coerceAtMost(max), s.bottomStart.toPx(size, this).coerceAtMost(max),
        )
        is CornerBasedShape -> {
            val tl = if (ltr) s.topStart.toPx(size, this) else s.topEnd.toPx(size, this)
            val tr = if (ltr) s.topEnd.toPx(size, this) else s.topStart.toPx(size, this)
            val br = if (ltr) s.bottomEnd.toPx(size, this) else s.bottomStart.toPx(size, this)
            val bl = if (ltr) s.bottomStart.toPx(size, this) else s.bottomEnd.toPx(size, this)
            floatArrayOf(tl.coerceAtMost(max), tr.coerceAtMost(max), br.coerceAtMost(max), bl.coerceAtMost(max))
        }
        else -> null
    }
}

private const val COMMON = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

// 返回：xy = 位移方向 × 位移量（折射带里面是 0）；z = 位移量 d（色散用）
float3 refraction(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    float3 r = float3(0.0);
    if (-sd < refractionHeight) {
        sd = min(sd, 0.0);
        float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
        float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
        float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
        r = float3(d * grad, d);
    }
    return r;
}
"""

/** 不分叉取图：先算坐标，最后取一次。 */
private const val LENS = COMMON + """
half4 main(float2 coord) {
    float3 r = refraction(coord);
    return content.eval(coord + r.xy);
}
"""

/** 色散：七个取样都在分叉外面，坐标夹在 [0, size]（癸星科技 PatchedLens 的修法）。 */
private const val DISPERSION = COMMON + """
uniform float chromaticAberration;

half4 main(float2 coord) {
    float3 r = refraction(coord);
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float2 refractedCoord = coord + r.xy;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = r.xy * dispersionIntensity;
    half4 color = half4(0.0);
    half4 red = content.eval(clamp(refractedCoord + dispersedCoord, float2(0.0), size));
    color.r += red.r / 3.5; color.a += red.a / 7.0;
    half4 orange = content.eval(clamp(refractedCoord + dispersedCoord * (2.0 / 3.0), float2(0.0), size));
    color.r += orange.r / 3.5; color.g += orange.g / 7.0; color.a += orange.a / 7.0;
    half4 yellow = content.eval(clamp(refractedCoord + dispersedCoord * (1.0 / 3.0), float2(0.0), size));
    color.r += yellow.r / 3.5; color.g += yellow.g / 3.5; color.a += yellow.a / 7.0;
    half4 green = content.eval(clamp(refractedCoord, float2(0.0), size));
    color.g += green.g / 3.5; color.a += green.a / 7.0;
    half4 cyan = content.eval(clamp(refractedCoord - dispersedCoord * (1.0 / 3.0), float2(0.0), size));
    color.g += cyan.g / 3.5; color.b += cyan.b / 3.0; color.a += cyan.a / 7.0;
    half4 blue = content.eval(clamp(refractedCoord - dispersedCoord * (2.0 / 3.0), float2(0.0), size));
    color.b += blue.b / 3.0; color.a += blue.a / 7.0;
    half4 purple = content.eval(clamp(refractedCoord - dispersedCoord, float2(0.0), size));
    color.r += purple.r / 7.0; color.b += purple.b / 3.0; color.a += purple.a / 7.0;
    return color;
}
"""
