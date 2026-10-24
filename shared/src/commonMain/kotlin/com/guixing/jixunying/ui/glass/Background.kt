package com.guixing.jixunying.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppBackground
import com.guixing.jixunying.model.BgMode
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * 界面背景（2026-10-24，model/GlassTuning.kt 的 AppBackground）。照癸星科技 GlassBackground.kt 的画法：
 * - 采样层（挂 layerBackdrop 的那个节点）自己 drawBehind 把背景画全，不拆子组件（拆了采样回来是空的）；
 * - 模糊不叠在采样层上（叠上去会糊到整屏，癸星科技 PITFALLS），另画一层带模糊的给人看，玻璃折射的是没糊的那张；
 * - 纯色、渐变的亮度 / 对比度 / 饱和度直接算进颜色（逐色号和逐像素等价，省一层离屏），图片挂 ColorFilter。
 */

/** 亮度 / 对比度 / 饱和度合成一个色彩矩阵：先饱和度（Rec.601 灰度插值），再对比度（绕中灰），最后乘亮度。 */
fun bgColorMatrix(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
    val s = saturation
    val lr = 0.299f; val lg = 0.587f; val lb = 0.114f
    val sat = floatArrayOf(
        lr * (1 - s) + s, lg * (1 - s), lb * (1 - s), 0f, 0f,
        lr * (1 - s), lg * (1 - s) + s, lb * (1 - s), 0f, 0f,
        lr * (1 - s), lg * (1 - s), lb * (1 - s) + s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    val t = 128f * (1 - contrast)
    val out = FloatArray(20)
    for (r in 0 until 4) for (c in 0 until 5) {
        var v = sat[r * 5 + c]
        if (r < 3) { v *= contrast; if (c == 4) v += t; v *= brightness }
        out[r * 5 + c] = v
    }
    return ColorMatrix(out)
}

/** 同一个变换算到单个颜色上（纯色、渐变用）。 */
fun Color.adjusted(brightness: Float, contrast: Float, saturation: Float): Color {
    val m = bgColorMatrix(brightness, contrast, saturation).values
    val r = red * 255f; val g = green * 255f; val b = blue * 255f
    fun ch(i: Int) = ((m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 4]) / 255f).coerceIn(0f, 1f)
    return Color(ch(0), ch(1), ch(2), 1f)
}

/** 画背景。default = 默认模式下画什么（开着玻璃是那几团柔光，关着是主题的底色）。 */
fun DrawScope.drawBackground(bg: AppBackground, image: ImageBitmap?, default: DrawScope.() -> Unit) {
    when (bg.mode) {
        BgMode.DEFAULT -> default()
        BgMode.SOLID -> drawRect(Color(bg.solidArgb.toInt()).adjusted(bg.brightness, bg.contrast, bg.saturation))
        BgMode.GRADIENT -> drawAngledGradient(bg.gradientArgb.map { Color(it.toInt()).adjusted(bg.brightness, bg.contrast, bg.saturation) }, bg.gradientAngle)
        BgMode.IMAGE -> {
            default()
            if (image != null) drawImageCrop(image, bg.alpha.coerceIn(0f, 1f), ColorFilter.colorMatrix(bgColorMatrix(bg.brightness, bg.contrast, bg.saturation)))
        }
    }
}

fun DrawScope.drawAngledGradient(colors: List<Color>, angleDeg: Float) {
    if (colors.size < 2) { drawRect(colors.firstOrNull() ?: Color.Gray); return }
    val rad = angleDeg / 180f * kotlin.math.PI.toFloat()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val len = (abs(size.width * cos(rad)) + abs(size.height * sin(rad))) / 2f
    val dx = cos(rad) * len
    val dy = sin(rad) * len
    drawRect(Brush.linearGradient(colors, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy)))
}

/** 图片铺满（裁掉多出来的，和 ContentScale.Crop 一样）。 */
fun DrawScope.drawImageCrop(image: ImageBitmap, alpha: Float, filter: ColorFilter?) {
    val iw = image.width.toFloat()
    val ih = image.height.toFloat()
    if (iw <= 0 || ih <= 0 || size.width <= 0 || size.height <= 0) return
    val scale = maxOf(size.width / iw, size.height / ih)
    val sw = size.width / scale
    val sh = size.height / scale
    val sx = (iw - sw) / 2f
    val sy = (ih - sh) / 2f
    drawImage(image, IntOffset(sx.toInt(), sy.toInt()), IntSize(sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1)),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = alpha, colorFilter = filter)
}

/**
 * 窗口最底下的背景：采样层（有 backdrop 时挂 layerBackdrop，界面上的玻璃折射它）+ 要模糊时上面再画一层糊的给人看。
 * 必须和界面是兄弟，不能是界面的上层（安卓上原生崩溃）。
 */
@Composable
fun AppWallpaper(backdrop: LayerBackdrop?, bg: AppBackground, image: ImageBitmap?, default: DrawScope.() -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .drawBehind { drawBackground(bg, image, default) })
        val blurred = bg.blurDp > 0.5f && (bg.mode == BgMode.GRADIENT || bg.mode == BgMode.IMAGE)
        if (blurred) Box(Modifier.fillMaxSize().blur(bg.blurDp.dp).drawBehind { drawBackground(bg, image, default) })
    }
}
