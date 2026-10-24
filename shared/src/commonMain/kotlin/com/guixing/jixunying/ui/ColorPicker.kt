package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/*
 * 取色器（2026-10-24 用户：「色彩渐变纯色我自己改不了颜色」）。照癸星科技 TeleprompterColorPickerPopup 的用法：
 * 预设色块点一下就选；要别的颜色拖色相 / 饱和度 / 亮度三条，或者直接输色号（#RRGGBB）。背景、阴影、染色共用。
 */

private val presetColors = listOf(
    0xFFFFFFFFL, 0xFF000000L, 0xFF15151AL, 0xFFF6F7FBL, 0xFF0088FFL, 0xFF4F5BEBL, 0xFF8B7CFFL, 0xFF3CE8D8L,
    0xFF34C759L, 0xFFFFB74DL, 0xFFFF8D28L, 0xFFFF5FC9L, 0xFFE11D48L, 0xFF0EA5A0L, 0xFF2E3192L, 0xFF414345L,
)

/** 一个能点的色块：点开取色器。 */
@Composable
fun ColorSwatch(argb: Long, title: String, size: Int = 34, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(argb.toInt()))
        .border(2.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), CircleShape).clickable { open = true })
    if (open) ColorPickerDialog(title, argb, { open = false }) { onPick(it); open = false }
}

@Composable
fun ColorPickerDialog(title: String, initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val hsv0 = remember { toHsv(initial) }
    var h by remember { mutableStateOf(hsv0[0]) }
    var s by remember { mutableStateOf(hsv0[1]) }
    var v by remember { mutableStateOf(hsv0[2]) }
    var hex by remember { mutableStateOf(hexOf(initial)) }
    val alphaBits = initial and 0xFF000000L
    fun current(): Long = (Color.hsv(h, s, v).toArgb().toLong() and 0x00FFFFFFL) or (if (alphaBits == 0L) 0xFF000000L else alphaBits)
    fun setFrom(argb: Long) { val x = toHsv(argb); h = x[0]; s = x[1]; v = x[2]; hex = hexOf(argb) }
    AppDialog(title, onDismiss = onDismiss, width = 440.dp, actions = {
        TextButton(onDismiss) { Text("取消") }
        Button({ onPick(current()) }) { Text("用这个颜色") }
    }) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(current().toInt()))
                    .border(1.dp, Ext.c.border, RoundedCornerShape(14.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel("色号")
                    AppTextField(hex, { t ->
                        hex = t.take(7)
                        parseHex(t)?.let { val x = toHsv(it); h = x[0]; s = x[1]; v = x[2] }
                    }, placeholder = "#8B7CFF")
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presetColors.forEach { c ->
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c.toInt()))
                        .border(1.dp, Ext.c.border, CircleShape).clickable { setFrom(c) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("色相", style = MaterialTheme.typography.labelMedium)
            // 色相条底下画一道彩虹，拖到哪个颜色一目了然
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(
                Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })))
            Slider(h, { h = it; hex = hexOf(current()) }, valueRange = 0f..359f)
            Text("饱和度  ${(s * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(s, { s = it; hex = hexOf(current()) }, valueRange = 0f..1f)
            Text("亮度  ${(v * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(v, { v = it; hex = hexOf(current()) }, valueRange = 0f..1f)
        }
    }
}

private fun hexOf(argb: Long) = "#" + (argb and 0xFFFFFFL).toString(16).uppercase().padStart(6, '0')

private fun parseHex(t: String): Long? {
    val x = t.trim().removePrefix("#")
    if (x.length != 6) return null
    return x.toLongOrNull(16)?.let { it or 0xFF000000L }
}

/** argb → 色相 0～360、饱和度 0～1、亮度 0～1。 */
private fun toHsv(argb: Long): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val hue = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0) it + 360f else it }
    return floatArrayOf(hue.coerceIn(0f, 359f), if (max == 0f) 0f else d / max, max)
}
