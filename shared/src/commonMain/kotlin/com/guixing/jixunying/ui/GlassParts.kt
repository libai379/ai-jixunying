package com.guixing.jixunying.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.ui.glass.LiquidButton
import com.guixing.jixunying.ui.glass.LocalGlass
import com.guixing.jixunying.ui.glass.glass
import kotlinx.coroutines.launch

/*
 * 界面里换成液态玻璃的几样共用件（2026-10-22，docs/界面换装说明.md）。关了玻璃（设置 → 外观）就画原来的样子，一像素不差。
 */

/**
 * 玻璃面：开着玻璃时是 Modifier.glass + 细高光边，关着时就是原来那个 Surface（底色、边框、阴影都照旧）。
 * 输入框、弹窗用它。
 */
@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    target: com.guixing.jixunying.model.GlassTarget = com.guixing.jixunying.model.GlassTarget.BAR,
    color: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    tonalElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val env = LocalGlass.current
    if (!env.on || env.backdrop == null) {
        Surface(modifier, shape = shape, color = color, border = border, shadowElevation = shadowElevation, tonalElevation = tonalElevation, content = content)
        return
    }
    Box(modifier.glass(shape, target, fallback = color).then(
        // 有颜色的边框（比如画图模式的青色框）照样画；普通的灰边框交给玻璃自己的高光
        if (border != null && border.brush != androidx.compose.ui.graphics.SolidColor(Ext.c.border)) Modifier.border(border, shape) else Modifier
    ).clip(shape)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

/**
 * 发送键这类圆形主按钮：开着玻璃时是液态按钮（按下跟手形变 + 高光，弹回），关着时是原来的实心圆。
 * enabled = false 时是灰的、点不了。
 */
@Composable
fun GlassRoundButton(
    icon: ImageVector,
    desc: String,
    enabled: Boolean,
    color: Color,
    onColor: Color,
    onClick: () -> Unit,
    size: Dp = 36.dp,
    iconSize: Dp = 19.dp,
) {
    val env = LocalGlass.current
    val idle = MaterialTheme.colorScheme.surfaceContainerHighest
    val iconTint = if (enabled) onColor else Ext.c.subtle
    if (!env.on || env.backdrop == null) {
        Box(
            Modifier.size(size).clip(CircleShape).background(if (enabled) color else idle).clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, desc, Modifier.size(iconSize), tint = iconTint) }
        return
    }
    LiquidButton(
        onClick = onClick, backdrop = env.backdrop, enabled = enabled, shape = CircleShape,
        tint = if (enabled) color else Color.Unspecified,
        surfaceColor = if (enabled) Color.Unspecified else idle.copy(alpha = 0.55f),
        modifier = Modifier.size(size),
    ) { Icon(icon, desc, Modifier.size(iconSize), tint = iconTint) }
}

/**
 * 开关：开着玻璃时是液态开关（能拖、按下圆钮变成放大的透明玻璃、带速度挤压），关着时是原来的 Material 开关。
 * 原来的 4 处 Switch 都换成它。
 */
@Composable
fun AppSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val env = LocalGlass.current
    if (!env.on || env.backdrop == null) {
        androidx.compose.material3.Switch(checked, onCheckedChange, enabled = enabled)
        return
    }
    val live = androidx.compose.runtime.rememberUpdatedState(checked)
    com.guixing.jixunying.ui.glass.LiquidToggle(
        checked = { live.value }, onCheckedChange = onCheckedChange, backdrop = env.backdrop, enabled = enabled,
        // 和 Material 开关一样占 52 × 32，换了不挤动旁边
        modifier = Modifier.size(52.dp, 32.dp),
    )
}

/** 玻璃上的「选中」底色：开着玻璃时用半透明的白（深色用一点点白），关着时照旧 sidebarSelected。 */
@Composable
fun selectedFill(): Color {
    val env = LocalGlass.current
    return if (!env.on) Ext.c.sidebarSelected else Color.White.copy(alpha = if (env.dark) 0.09f else 0.62f)
}

/** 玻璃上的小卡片底色：开着玻璃时半透明，关着时照旧 surface。 */
@Composable
fun cardFill(): Color {
    val env = LocalGlass.current
    return if (!env.on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = if (env.dark) 0.45f else 0.7f)
}

/**
 * 按下时弹簧缩小一点、松开弹回（2026-10-22）。和 clickable 用同一个 interactionSource。
 * 关了玻璃也照样有（动画不费什么，和玻璃无关）。
 */
@Composable
fun Modifier.springPress(source: androidx.compose.foundation.interaction.MutableInteractionSource, pressedScale: Float = 0.95f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) pressedScale else 1f, com.guixing.jixunying.ui.glass.Springs.press())
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * 一条条出现：第 [index] 条晚 index × 90 毫秒，淡入 420 毫秒、从下面 24dp 弹上来（照癸星科技启动页 rememberEntranceModifier）。
 * 只在第一次出现时动；排在后面的最多等 8 条的时间，长列表不会一直往外冒。
 */
@Composable
fun Modifier.entrance(index: Int): Modifier {
    if (com.guixing.jixunying.ui.glass.wallpaperStill) return this   // 截图测试：一下就是最后的样子
    val density = androidx.compose.ui.platform.LocalDensity.current
    val start = with(density) { 24.dp.toPx() }
    val alpha = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val offset = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(start) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index.coerceIn(0, 8) * 90L)
        kotlinx.coroutines.coroutineScope {
            launch { alpha.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            launch { offset.animateTo(0f, com.guixing.jixunying.ui.glass.Springs.entrance()) }
        }
    }
    return this.graphicsLayer { this.alpha = alpha.value; translationY = offset.value }
}

/** 侧栏列表的选中底色：换选中时用弹簧淡过去（2026-10-22）。 */
@Composable
fun animatedSelection(selected: Boolean): Color {
    val target = if (selected) selectedFill() else Color.Transparent
    val c by androidx.compose.animation.animateColorAsState(target, com.guixing.jixunying.ui.glass.Springs.snappy())
    return c
}
