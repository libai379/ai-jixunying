/*
 * 液态开关、液态按钮（2026-10-22，docs/界面换装说明.md）：从癸星科技的 LiquidToggle / LiquidButton 搬来
 * （再往上是 Kyant0/AndroidLiquidGlass 的示例，Apache License 2.0，https://github.com/Kyant0/AndroidLiquidGlass/blob/main/LICENSE）。
 * 和癸星科技一样：胶囊用 RoundedCornerShape(50)（这个库的 lens 只认 CornerBasedShape），色散关掉。
 * 改的地方：开关的颜色跟主题的主色走；按钮能禁用、能给形状；没开玻璃时由调用方（AppSwitch 等）画原来的样子。
 */
package com.guixing.jixunying.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kashif_e.backdrop.Backdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberBackdrop
import com.kashif_e.backdrop.backdrops.rememberCombinedBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.shadow.InnerShadow
import com.kashif_e.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 液态开关：点一下切换，也能按住拖；按下时圆钮放大成透明的玻璃，带速度挤压。
 * checked 用读取函数（癸星科技踩过：拖到一半外面的状态回写会把圆钮拽回去，拖动时不理外面的值）。
 */
@Composable
fun LiquidToggle(
    checked: () -> Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dark = LocalGlass.current.dark
    val liveToggleParams = rememberUpdatedState(LocalGlass.current.tuning.paramsFor(com.guixing.jixunying.model.GlassTarget.CONTROL))
    val accentColor = MaterialTheme.colorScheme.primary
    val trackColor = if (!dark) Color(0xFF787878).copy(0.2f) else Color(0xFF787880).copy(0.36f)
    val onChange by rememberUpdatedState(onCheckedChange)
    val enabledNow by rememberUpdatedState(enabled)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidth = with(density) { 16f.dp.toPx() }
    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked()) 1f else 0f) }
    val dampedDragAnimation = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = { isDragging = true },
            onDragStopped = {
                if (enabledNow) {
                    if (didDrag) {
                        fraction = if (targetValue >= 0.5f) 1f else 0f
                        didDrag = false
                    } else {
                        fraction = if (checked()) 0f else 1f
                    }
                    onChange(fraction == 1f)
                } else {
                    didDrag = false
                    fraction = if (checked()) 1f else 0f
                }
                isDragging = false
            },
            onDrag = { _, dragAmount ->
                if (!enabledNow) return@DampedDragAnimation
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / dragWidth
                fraction = if (isLtr) (fraction + delta).coerceIn(0f, 1f) else (fraction - delta).coerceIn(0f, 1f)
            },
        )
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { dampedDragAnimation.updateValue(it) }
    }
    // 以 dampedDragAnimation 为键（不以外面传进来的 lambda 为键）：癸星科技踩过，lambda 每次重组都是新的，效果一重启就把拖到一半的圆钮拽回去
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { checked() }.collectLatest { isChecked ->
            if (isDragging) return@collectLatest
            val target = if (isChecked) 1f else 0f
            if (target != fraction) {
                fraction = target
                dampedDragAnimation.animateToValue(target)
            }
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            // 整个开关都能点、能拖（癸星科技只有圆钮上能）；外层再把事件吃掉，免得放在可点的设置行里时，开关和那一行各切一次
            .pointerInput(Unit) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            .then(dampedDragAnimation.modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(RoundedCornerShape(50))
                .drawBehind { drawRect(lerp(trackColor, accentColor, dampedDragAnimation.value)) }
                .size(52.dp, 28.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    val padding = 2f.dp.toPx()
                    val f = dampedDragAnimation.value
                    // 52 宽的轨道里，32 宽的圆钮从左 2 走到右 2，行程 16（癸星科技是 64 宽轨道 + 20 的行程，这里窄一点放进设置行）
                    translationX = if (isLtr) lerp(padding, padding + 16f.dp.toPx(), f) else lerp(-padding, -(padding + 16f.dp.toPx()), f)
                }
                .semantics {
                    role = Role.Switch
                    stateDescription = if (checked()) "开" else "关"
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 0.75f, progress)
                            val scaleY = lerp(0f, 0.75f, progress)
                            scale(scaleX, scaleY) { drawBackdrop() }
                        },
                    ),
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        // 按下时圆钮变成透明玻璃：折射、饱和度、深度、色散照 设置 → 外观 → 液态玻璃调节 的「按钮和开关」（2026-10-24），
                        // 跟着按下的程度长出来；没按时是模糊的实心圆（照原样）
                        val p = liveToggleParams.value
                        val progress = dampedDragAnimation.pressProgress
                        if (p.vibrancy && progress > 0f) glassVibrancy()
                        blur(8f.dp.toPx() * (1f - progress))
                        val cap = size.minDimension * 0.45f
                        glassLens((p.refractionHeightDp.dp.toPx().coerceAtMost(cap)) * progress, (p.refractionAmountDp.dp.toPx().coerceAtMost(cap)) * progress,
                            p.depthEffect, p.chromaticAberration)
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        // 电脑端库的 Ambient 高光着色器和新版 skiko 不兼容（NoSuchMethodError，ShotsTest 实测），用 Plain
                        val base = if (ambientHighlightWorks) Highlight.Ambient else Highlight.Plain
                        base.copy(width = base.width / 1.5f, blurRadius = base.blurRadius / 1.5f, alpha = progress)
                    },
                    shadow = { liveToggleParams.value.shadow.takeIf { it.enabled }?.toShadow() ?: Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 4f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    },
                )
                .size(32.dp, 24.dp),
        )
    }
}

/**
 * 液态按钮：按下时跟着手指的高光，整个按钮朝手指方向弹性形变（tanh 限幅），松开弹回。
 * tint = 有主色的按钮（发送键）：先按色相混一遍再盖一层半透明的主色，和癸星科技一样。
 */
@Composable
fun LiquidButton(
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(50),
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    role: Role = Role.Button,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { InteractiveHighlight(animationScope = animationScope) }
    val liveShape by rememberUpdatedState(shape)
    val liveTint by rememberUpdatedState(tint)
    val liveSurface by rememberUpdatedState(surfaceColor)
    // 按钮的玻璃照 设置 → 外观 → 液态玻璃调节 里「按钮和开关」那一套（2026-10-24）；按下的形变和跟手高光是按钮自己的
    val env = LocalGlass.current
    val p = env.tuning.paramsFor(com.guixing.jixunying.model.GlassTarget.CONTROL)
    val liveParams by rememberUpdatedState(p)
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { liveShape },
                effects = { glassEffects(liveParams) },
                highlight = if (p.highlight.enabled) { { liveParams.highlight.toHighlight() } } else null,
                shadow = if (p.shadow.enabled) { { liveParams.shadow.toShadow() } } else null,
                innerShadow = if (p.innerShadow.enabled) { { liveParams.innerShadow.toInnerShadow() } } else null,
                layerBlock = if (enabled) {
                    {
                        val width = size.width
                        val height = size.height
                        val progress = highlight.pressProgress
                        val scale = lerp(1f, 1f + 4f.dp.toPx() / size.height, progress)
                        val maxOffset = size.minDimension
                        val initialDerivative = 0.05f
                        val offset = highlight.offset
                        translationX = maxOffset * tanh(initialDerivative * offset.x / maxOffset)
                        translationY = maxOffset * tanh(initialDerivative * offset.y / maxOffset)
                        val maxDragScale = 4f.dp.toPx() / size.height
                        val offsetAngle = atan2(offset.y, offset.x)
                        scaleX = scale + maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) * (width / height).coerceAtMost(1f)
                        scaleY = scale + maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) * (height / width).coerceAtMost(1f)
                    }
                } else null,
                onDrawSurface = {
                    val t = liveTint
                    if (t != Color.Unspecified) {
                        drawRect(t, blendMode = BlendMode.Hue)
                        drawRect(t.copy(alpha = 0.75f * t.alpha))
                    } else glassSurface(liveParams, if (env.dark) Color.Black else Color.White, env.dark, null)
                    if (liveSurface != Color.Unspecified) drawRect(liveSurface)
                },
            )
            .clickable(enabled = enabled, interactionSource = null, indication = null, role = role, onClick = onClick)
            .then(if (enabled) Modifier.then(highlight.modifier).then(highlight.gestureModifier) else Modifier),
        contentAlignment = contentAlignment,
        content = content,
    )
}
