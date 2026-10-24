package com.guixing.jixunying.ui.glass

import com.kashif_e.backdrop.BackdropEffectScope
import com.kashif_e.backdrop.effects.colorFilter
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix

/**
 * 电脑端的 vibrancy（2026-10-22）：库（照旧版 skiko 编的）的 vibrancy() 调 ColorMatrix(float[]) 构造，
 * 我们用的 skiko 0.150.1 里 ColorMatrix 成了值类，那个构造是私有的，一调就 IllegalAccessError（ShotsTest 实测）。
 * 照库里 colorControlsColorFilter(saturation = 1.5f) 的同一个矩阵，用新版 skiko 的接口自己建，再走库公开的 colorFilter()。
 */
actual val ambientHighlightWorks: Boolean = false

actual fun BackdropEffectScope.glassVibrancy() {
    colorFilter(Vibrant)
}

private val Vibrant: ColorFilter by lazy {
    val s = 1.5f
    val inv = 1f - s
    val r = 0.213f * inv
    val g = 0.715f * inv
    val b = 0.072f * inv
    ColorFilter.makeMatrix(ColorMatrix(
        r + s, g, b, 0f, 0f,
        r, g + s, b, 0f, 0f,
        r, g, b + s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
}

/** 截图测试（ShotsTest）里壁纸光斑不漂，每次截出来一样：-Djxy.wallpaper.still=true。 */
actual val wallpaperStill: Boolean = System.getProperty("jxy.wallpaper.still") == "true"
