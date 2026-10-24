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
import com.kashif_e.backdrop.shadow.Shadow

/*
 * 液态玻璃（2026-10-22，docs/界面换装说明.md）：和癸星科技（F:\guixingkeji）同一个库、同一套调好的参数。
 * 界面里所有玻璃面都走 Modifier.glass()，不直接调库的 drawBackdrop：开关、退回纯色、夹住折射量都在这一处。
 */

/** 增加饱和度（vibrancy）：电脑端库自带的那个和新版 skiko 二进制不兼容，两端各写一份（GlassVibrancy.*.kt）。 */
expect fun com.kashif_e.backdrop.BackdropEffectScope.glassVibrancy()

/** 库的 Ambient / Default 高光能不能用：电脑端那两个着色器调了新版 skiko 里没有的方法（RuntimeShaderBuilder.makeShader$default），一画就崩。 */
expect val ambientHighlightWorks: Boolean

/** 壁纸光斑停着不漂（截图测试用）。 */
expect val wallpaperStill: Boolean

/** 玻璃要用的东西：采样源（窗口最底下的壁纸）和开没开（设置 → 外观 → 液态玻璃）。 */
@Immutable
class GlassEnv(val backdrop: Backdrop?, val on: Boolean, val dark: Boolean)

val LocalGlass = staticCompositionLocalOf { GlassEnv(null, false, false) }

/**
 * 一种玻璃的样子。lens 的两个量和用户在癸星科技里调的一样是 dp，画的时候夹到元素短边的 0.45 倍（癸星科技实测：
 * 超过元素本身会读出界，安卓上是原生崩溃）。fill = 铺在玻璃上的那层底色有多厚（保证上面的字看得清）。
 * 色散一律不开：库的色散着色器采样坐标没夹住，背后有真文字时花屏（癸星科技 PITFALLS.md）。
 */
@Immutable
data class GlassStyle(
    val blurDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val depthEffect: Boolean = true,
    val vibrancy: Boolean = true,
    val fillLight: Float,
    val fillDark: Float,
    val shadowRadiusDp: Float = 6.273656f,
    val shadowOffsetXDp: Float = -0.41332245f,
    val shadowOffsetYDp: Float = 4f,
    /** 用户调的阴影：颜色 0x4D000000、透明度 0.645。 */
    val shadowAlpha: Float = 0.64483225f,
    val highlightAlpha: Float = 0.5f,
) {
    companion object {
        /** 按钮、开关这类小控件：就是用户在癸星科技里调好的那组（TunedGlassParams：模糊 0、折射 48 / 64、depthEffect、vibrancy）。 */
        // 10-24 用户：「效果好像不太明显，还是用的之前那个模板……照着模板抄」。以前顶栏、侧栏自己定了一套（模糊 10～22、
        // 底色铺到 55%～62%），折射全被底色盖住了。现在都照 TunedGlassParams：不模糊、折射 48 / 64、depthEffect、vibrancy，
        // 底色只铺薄薄一层（癸星科技 tint 是关的，一点不铺；我们上面有字，留一点保证看得清）。
        val Control = GlassStyle(blurDp = 0f, refractionHeightDp = 48f, refractionAmountDp = 64f, fillLight = 0.10f, fillDark = 0.06f)
        /** 顶栏、输入框。 */
        val Bar = GlassStyle(blurDp = 0f, refractionHeightDp = 48f, refractionAmountDp = 64f, fillLight = 0.30f, fillDark = 0.18f)
        /** 侧栏、弹窗、设置里的卡片这类大面：字多，底色比顶栏厚一点。 */
        val Panel = GlassStyle(blurDp = 0f, refractionHeightDp = 48f, refractionAmountDp = 64f, fillLight = 0.38f, fillDark = 0.24f)
    }
}

/**
 * 玻璃面。没开玻璃、没有采样源时画成 fallback 纯色（+ 细边框），和换装以前一样。
 * 参数在绘制阶段读（rememberUpdatedState）：癸星科技踩过，组合阶段拍扁成普通值的话静止后玻璃冻在最后一帧。
 * tint = 玻璃上再叠一层颜色（发送键这类有主色的），null 就用这一种玻璃自己的底色。
 */
@Composable
fun Modifier.glass(
    shape: Shape,
    style: GlassStyle = GlassStyle.Bar,
    fallback: Color,
    border: Color? = null,
    tint: Color? = null,
    backdrop: Backdrop? = null,
    layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val env = LocalGlass.current
    val source = backdrop ?: env.backdrop
    if (!env.on || source == null) {
        val solid = this.clip(shape).background(tint ?: fallback)
        return if (border != null) solid.border(1.dp, border, shape) else solid
    }
    val liveStyle = rememberUpdatedState(style)
    val liveShape = rememberUpdatedState(shape)
    val liveFill = rememberUpdatedState(tint ?: fallback.copy(alpha = if (env.dark) style.fillDark else style.fillLight))
    return this.drawBackdrop(
        backdrop = source,
        shape = { liveShape.value },
        effects = {
            val s = liveStyle.value
            if (s.vibrancy) glassVibrancy()
            if (s.blurDp > 0f) blur(s.blurDp.dp.toPx())
            val cap = size.minDimension * 0.45f
            val h = s.refractionHeightDp.dp.toPx().coerceAtMost(cap)
            val a = s.refractionAmountDp.dp.toPx().coerceAtMost(cap)
            if (h > 0f && a > 0f) lens(h, a, depthEffect = s.depthEffect, chromaticAberration = false)
        },
        highlight = { liveStyle.value.highlightAlpha.takeIf { it > 0f }?.let { Highlight.Plain.copy(width = 0.8.dp, alpha = it) } },
        shadow = {
            val s = liveStyle.value
            Shadow(radius = s.shadowRadiusDp.dp, offset = DpOffset(s.shadowOffsetXDp.dp, s.shadowOffsetYDp.dp),
                color = Color(0x4D000000), alpha = s.shadowAlpha)
        },
        layerBlock = layerBlock,
        onDrawSurface = { drawRect(liveFill.value) },
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
fun rememberWallpaperBackdrop(dark: Boolean): Backdrop {
    // rememberCanvasBackdrop 按 lambda 记，lambda 要先记住，不然每次重组都换一个新的
    val draw: DrawScope.() -> Unit = androidx.compose.runtime.remember(dark) { { drawWallpaper(dark) } }
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
