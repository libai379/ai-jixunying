package com.guixing.jixunying.ui.glass

import com.kashif_e.backdrop.BackdropEffectScope
import com.kashif_e.backdrop.effects.vibrancy

/** 安卓端照用库自己的 vibrancy()（癸星科技一直这么用）。 */
actual val ambientHighlightWorks: Boolean = true

actual fun BackdropEffectScope.glassVibrancy() {
    vibrancy()
}
