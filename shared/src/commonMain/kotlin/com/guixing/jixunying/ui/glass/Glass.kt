package com.guixing.jixunying.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberCanvasBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.highlight.HighlightStyle
import com.kashif_e.backdrop.shadow.InnerShadow
import com.guixing.jixunying.model.GlassHighlightParams
import com.guixing.jixunying.model.GlassInnerShadowParams
import com.guixing.jixunying.model.GlassParams
import com.guixing.jixunying.model.GlassShadowParams
import com.guixing.jixunying.model.GlassTarget
import com.guixing.jixunying.model.GlassTuning
import com.guixing.jixunying.model.HighlightStyleKind
import com.kashif_e.backdrop.shadow.Shadow

/*
 * 液态玻璃（2026-10-22，docs/界面换装说明.md）：和癸星科技（F:\guixingkeji）同一个库、同一套调好的参数。
 * 界面里所有玻璃面都走 Modifier.glass()，不直接调库的 drawBackdrop：开关、退回纯色、夹住折射量都在这一处。
 */

/** 增加饱和度（vibrancy）：电脑端库自带的那个和新版 skiko 二进制不兼容，两端各写一份（GlassVibrancy.*.kt）。 */
expect fun com.kashif_e.backdrop.BackdropEffectScope.glassVibrancy()

/** 库的 Ambient / Default 高光能不能用：电脑端那两个着色器调了新版 skiko 里没有的方法（RuntimeShaderBuilder.makeShader$default），一画就崩。 */
expect val ambientHighlightWorks: Boolean

/**
 * 折射：安卓上用自己重写的一份（库的着色器在分叉里取图，用户手机显卡上折射带内边会出一圈细缝，见 GlassLens.android.kt），电脑上用库的。
 */
expect fun com.kashif_e.backdrop.BackdropEffectScope.glassLens(refractionHeight: Float, refractionAmount: Float, depthEffect: Boolean, chromaticAberration: Boolean)

/** 壁纸光斑停着不漂（截图测试用）。 */
expect val wallpaperStill: Boolean

/** 玻璃要用的东西：采样源（窗口最底下的壁纸）、开没开（设置 → 外观 → 液态玻璃）、深浅色、各位置调好的参数（设置 → 外观 → 液态玻璃调节）。 */
@Immutable
class GlassEnv(val backdrop: Backdrop?, val on: Boolean, val dark: Boolean, val tuning: GlassTuning = GlassTuning(),
    /** 背景怎么画（弹窗是另一个窗口，自己照这个画一份当采样源）。 */
    val paint: DrawScope.() -> Unit = { drawWallpaper(dark) })

val LocalGlass = staticCompositionLocalOf { GlassEnv(null, false, false) }

/** 这个位置现在的圆角（调参里改的；按钮、开关不用）。 */
@Composable
fun glassRadius(target: GlassTarget): androidx.compose.ui.unit.Dp = LocalGlass.current.tuning.paramsFor(target).cornerRadiusDp.coerceIn(0f, 64f).dp

fun GlassHighlightParams.toHighlight(): Highlight {
    // 电脑端 Ambient / Default 的着色器和新版 skiko 不兼容（一画就崩），一律退成 Plain
    val kind = if (ambientHighlightWorks) style else HighlightStyleKind.Plain
    val st: HighlightStyle = when (kind) {
        HighlightStyleKind.Ambient -> HighlightStyle.Ambient(intensity = ambientIntensity)
        // 这一版库（0.0.1-alpha02 多平台分支）的 Default / Plain 不带颜色参数（癸星科技那版带），颜色这一项先不给调
        HighlightStyleKind.Default -> HighlightStyle.Default(angle = angleDeg, falloff = falloff)
        HighlightStyleKind.Plain -> HighlightStyle.Plain
    }
    return Highlight(width = widthDp.dp, blurRadius = blurRadiusDp.dp, alpha = alpha, style = st)
}

fun GlassShadowParams.toShadow() = Shadow(radius = radiusDp.dp, offset = DpOffset(offsetXDp.dp, offsetYDp.dp), color = Color(colorArgb.toInt()), alpha = alpha)
fun GlassInnerShadowParams.toInnerShadow() = InnerShadow(radius = radiusDp.dp, offset = DpOffset(offsetXDp.dp, offsetYDp.dp), color = Color(colorArgb.toInt()), alpha = alpha)

/** 用一套参数画玻璃效果（玻璃面、预览、液态按钮共用这一处，调参台里看到的就是界面上的样子）。 */
fun com.kashif_e.backdrop.BackdropEffectScope.glassEffects(p: GlassParams) {
    if (p.vibrancy) glassVibrancy()
    if (p.blurRadiusDp > 0f) blur(p.blurRadiusDp.dp.toPx())
    // 折射量超过元素本身会读出界（癸星科技真机：安卓上原生崩溃），夹到短边的 0.45 倍
    val cap = size.minDimension * 0.45f
    val h = p.refractionHeightDp.dp.toPx().coerceAtMost(cap)
    val a = p.refractionAmountDp.dp.toPx().coerceAtMost(cap)
    if (h > 0f && a > 0f) glassLens(h, a, p.depthEffect, p.chromaticAberration)
}

/** 玻璃表面上画的：底色（跟着深浅色）→ 染色（色相混一遍再盖半透明，癸星科技 tint 的画法）→ 调用方要的颜色。 */
fun DrawScope.glassSurface(p: GlassParams, surface: Color, dark: Boolean, extra: Color?) {
    val a = if (dark) p.surfaceDark else p.surfaceLight
    if (a > 0f) drawRect(surface.copy(alpha = a.coerceIn(0f, 1f)))
    if (p.tint.enabled) {
        val c = Color(p.tint.colorArgb.toInt())
        drawRect(c, alpha = p.tint.alpha, blendMode = androidx.compose.ui.graphics.BlendMode.Hue)
        drawRect(c.copy(alpha = p.tint.alpha))
    }
    if (extra != null) drawRect(extra)
}

/**
 * 玻璃面。没开玻璃、没有采样源时画成 fallback 纯色（+ 细边框），和换装以前一样。
 * [target] 决定用哪一套参数（设置 → 外观 → 液态玻璃调节里各调各的）。参数在绘制阶段读（rememberUpdatedState）：
 * 癸星科技踩过，组合阶段拍扁成普通值的话静止后玻璃冻在最后一帧。tint = 玻璃上再叠一层颜色（有主色的），盖在底色上面。
 * [params] 不为空就用它（调参台的预览）。
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    target: GlassTarget = GlassTarget.BAR,
    fallback: Color,
    border: Color? = null,
    tint: Color? = null,
    backdrop: Backdrop? = null,
    layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)? = null,
    params: GlassParams? = null,
): Modifier {
    val env = LocalGlass.current
    val source = backdrop ?: env.backdrop
    if (!env.on || source == null) {
        val solid = this.clip(shape).background(tint ?: fallback)
        return if (border != null) solid.border(1.dp, border, shape) else solid
    }
    val p = params ?: env.tuning.paramsFor(target)
    val live = rememberUpdatedState(p)
    val liveShape = rememberUpdatedState(shape)
    val liveFallback = rememberUpdatedState(fallback)
    val liveTint = rememberUpdatedState(tint)
    val dark = env.dark
    return this.drawBackdrop(
        backdrop = source,
        shape = { liveShape.value },
        effects = { glassEffects(live.value) },
        highlight = if (p.highlight.enabled) { { live.value.highlight.toHighlight() } } else null,
        shadow = if (p.shadow.enabled) { { live.value.shadow.toShadow() } } else null,
        innerShadow = if (p.innerShadow.enabled) { { live.value.innerShadow.toInnerShadow() } } else null,
        layerBlock = layerBlock,
        onDrawSurface = { glassSurface(live.value, liveFallback.value, dark, liveTint.value) },
    )
}

/**
 * 壁纸：渐变底 + 几团柔光。整张画在挂 layerBackdrop 的这个节点自己的 drawBehind 里——
 * 癸星科技踩过：拆成子组件分别画，采样回来是空的。它必须和界面是兄弟（不能是界面的上层），见 docs/界面换装说明.md 第 2 节。
 */
@Composable
fun GlassWallpaper(backdrop: LayerBackdrop, dark: Boolean, modifier: Modifier = Modifier) {
    // 10-24 试过照癸星科技启动页换成四团会漂的浓光斑，用户：「把这 4 个颜色的背景给撤掉……不是全抄过来」——撤回原来的淡底，不动
    Box(modifier.layerBackdrop(backdrop).drawBehind { drawWallpaper(dark) })
}

/** 弹窗是另一个窗口，不能采样主窗口的壁纸：弹窗里自己画一份当采样源（不挂图层，不跨窗口）。 */
@Composable
fun rememberWallpaperBackdrop(paint: DrawScope.() -> Unit): Backdrop {
    // rememberCanvasBackdrop 按 lambda 记，lambda 要先记住，不然每次重组都换一个新的
    val draw: DrawScope.() -> Unit = androidx.compose.runtime.remember(paint) { paint }
    return rememberCanvasBackdrop(draw)
}

fun DrawScope.drawWallpaper(dark: Boolean) {
    val w = size.width
    val h = size.height
    val base = if (dark) listOf(Color(0xFF0E0F15), Color(0xFF15161F), Color(0xFF101118))
    else listOf(Color(0xFFEEF0FB), Color(0xFFF6F7FB), Color(0xFFEFF4F8))
    drawRect(Brush.linearGradient(base, Offset.Zero, Offset(w, h)))
    val r = maxOf(w, h)
    // 主色靛蓝（左上）、青（右边）、紫（下面）、一点琥珀（右下）：和主题的 primary / secondary 同一家
    val blobs = if (dark) listOf(
        Triple(Offset(w * 0.12f, h * 0.10f), r * 0.55f, Color(0xFF4F5BEB).copy(alpha = 0.34f)),
        Triple(Offset(w * 0.92f, h * 0.30f), r * 0.45f, Color(0xFF0EA5A0).copy(alpha = 0.24f)),
        Triple(Offset(w * 0.35f, h * 1.00f), r * 0.55f, Color(0xFF8B5CF6).copy(alpha = 0.26f)),
        Triple(Offset(w * 0.95f, h * 0.95f), r * 0.35f, Color(0xFFF59E0B).copy(alpha = 0.10f)),
    ) else listOf(
        Triple(Offset(w * 0.12f, h * 0.10f), r * 0.55f, Color(0xFF4F5BEB).copy(alpha = 0.26f)),
        Triple(Offset(w * 0.92f, h * 0.30f), r * 0.45f, Color(0xFF0EA5A0).copy(alpha = 0.20f)),
        Triple(Offset(w * 0.35f, h * 1.00f), r * 0.55f, Color(0xFFA855F7).copy(alpha = 0.18f)),
        Triple(Offset(w * 0.95f, h * 0.95f), r * 0.35f, Color(0xFFF59E0B).copy(alpha = 0.14f)),
    )
    for ((c, radius, color) in blobs) {
        drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), c, radius), radius, c)
    }
}
