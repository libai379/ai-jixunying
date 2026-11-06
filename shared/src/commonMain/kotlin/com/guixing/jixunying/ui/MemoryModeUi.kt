package com.guixing.jixunying.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 两种记忆（2026-11-06，用户：「对话里边最好是放一个小按钮随时切……按钮要做动画，弹簧动画这种按钮，让人喜欢点的那种感觉，而不是太死板」）。
 * "full" 完整记忆：关于你的整份带上（原来的做法）；"recall" 联想记忆：按眼下聊的挑相关的带上，久不用的往后排。
 */
object MemoryModes {
    fun name(mode: String) = if (mode == "recall") "联想记忆" else "完整记忆"
    fun short(mode: String) = if (mode == "recall") "联想" else "完整"
    fun desc(mode: String) = if (mode == "recall") "按这会儿聊的，从记忆里挑相关的带上；很久没用到的往后排，聊到了再浮上来"
        else "关于你的记忆整份带上，别的话题的按需去查（原来的做法）"
    fun other(mode: String) = if (mode == "recall") "full" else "recall"
    /** 这个对话实际用的（对话没单独选就跟设置里的默认）。 */
    fun of(convMode: String, defaultMode: String) = (convMode.ifEmpty { defaultMode }).takeIf { it == "recall" } ?: "full"
}

/**
 * 弹簧按钮：按下去缩一下、松手弹回来；切换时整个按钮弹一下，图标和字从下往上换进来。
 * compact = 手机上只有圆按钮（图标 + 右下角小字）；电脑上带字。
 */
@Composable
fun MemoryModeToggle(mode: String, compact: Boolean, onToggle: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val recall = mode == "recall"
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    // 按下去缩、松手带回弹（低阻尼才有「弹」的感觉）
    val pressScale by animateFloatAsState(if (pressed) 0.84f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    // 切换时整个按钮先胀一下再弹回原样
    val pop = remember { Animatable(1f) }
    LaunchedEffect(mode) {
        pop.snapTo(1.22f)
        pop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 420f))
    }
    val tint by animateColorAsState(if (recall) primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "tint")
    val fill by animateColorAsState(if (recall) primary.copy(alpha = 0.16f) else composerFill(), label = "fill")
    val scaleMod = Modifier.graphicsLayer { val k = pressScale * pop.value; scaleX = k; scaleY = k }
    val desc = "记忆：${MemoryModes.name(mode)}（点一下换成${MemoryModes.name(MemoryModes.other(mode))}）"
    val swap: androidx.compose.animation.AnimatedContentTransitionScope<Boolean>.() -> androidx.compose.animation.ContentTransform = {
        (slideInVertically(spring(dampingRatio = 0.55f, stiffness = 500f)) { it } + fadeIn() + scaleIn(initialScale = 0.6f))
            .togetherWith(slideOutVertically { -it } + fadeOut() + scaleOut(targetScale = 0.6f))
    }
    if (compact) {
        Box(
            scaleMod.size(PhoneButton).clip(CircleShape).background(fill)
                .clickable(interactionSource = press, indication = null, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(recall, transitionSpec = swap, label = "icon") { r ->
                Icon(if (r) Icons.Rounded.Psychology else Icons.Rounded.LibraryBooks, desc, Modifier.size(PhoneGlyph), tint = tint)
            }
        }
    } else {
        Row(
            scaleMod.clip(RoundedCornerShape(50)).background(fill)
                .border(1.dp, if (recall) primary.copy(alpha = 0.45f) else Ext.c.border, RoundedCornerShape(50))
                .clickable(interactionSource = press, indication = null, onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(recall, transitionSpec = swap, label = "chip") { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r) Icons.Rounded.Psychology else Icons.Rounded.LibraryBooks, desc, Modifier.size(15.dp), tint = tint)
                    Spacer(Modifier.width(4.dp))
                    Text(MemoryModes.name(if (r) "recall" else "full"), style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
