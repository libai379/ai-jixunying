package com.guixing.jixunying.ui

import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.GlassDefaults
import com.guixing.jixunying.model.GlassParams
import com.guixing.jixunying.model.GlassPreset
import com.guixing.jixunying.model.GlassTarget
import com.guixing.jixunying.model.GlassTuning
import com.guixing.jixunying.model.HighlightStyleKind
import com.guixing.jixunying.ui.glass.LocalGlass
import com.guixing.jixunying.ui.glass.ambientHighlightWorks
import com.guixing.jixunying.ui.glass.drawWallpaper
import com.guixing.jixunying.ui.glass.glass
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

/*
 * 设置 → 外观 → 液态玻璃调节（2026-10-24，model/GlassTuning.kt）。照癸星科技的调参台（GlassTuningActivity.kt）：
 * 选位置 → 上面实时预览（背景能换）→ 下面一组组调；恢复默认、复制 JSON；预设（一个预设 = 每个位置当前参数的一份快照）。
 * 拖滑块时只改预览，松手才存（不然每拖一下都写一遍设置，手机遥控电脑时还要过网）。
 */

private enum class PreviewBg(val label: String) { WALLPAPER("现在的背景"), GRADIENT("彩色渐变"), SOLID("纯色"), TEXT("文字") }

private val swatches = listOf(0xFFFFFFFFL, 0xFF000000L, 0xFF0088FFL, 0xFF4F5BEBL, 0xFF34C759L, 0xFFFF8D28L, 0xFFFF5FC9L, 0xFFFFB74DL)

@Composable
fun GlassTuningPage(ctl: AppController, tuning: GlassTuning, onBack: (() -> Unit)?, embedded: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    var target by remember { mutableStateOf(GlassTarget.CARD) }
    // 草稿：拖的时候改它（预览跟着变），松手 / 点开关时存进设置
    var draft by remember(tuning, target) { mutableStateOf(tuning.paramsFor(target)) }
    fun commit(p: GlassParams = draft) { draft = p; ctl.updateSettings { it.copy(glassTuning = it.glassTuning.with(target, p)) } }
    fun change(p: GlassParams) { draft = p }

    if (onBack != null) Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onBack) { Text("‹ 返回外观") }
    }
    // embedded = 放在 外观 页里（10-24 用户：「液态玻璃和外观，这不是一个东西吗？为什么要分两栏？」——并回外观，标题和总开关外观页自己有）
    if (!embedded) {
        PageHeader("液态玻璃调节", "每个位置各调各的；上面是预览，下面拖一拖马上看到，松手就存，界面上同时变。调乱了点「恢复默认」或者应用一个预设。")
        SwitchRow("液态玻璃", "关掉就是原来的纯色界面", LocalGlass.current.on) { v -> ctl.updateSettings { it.copy(glass = v) } }
    } else Hint("每个位置各调各的；上面是预览，拖一拖马上看到，松手就存，界面上同时变。调乱了点「恢复默认」或者应用一个预设。")
    Spacer(Modifier.height(8.dp))
    FlowRowCompat {
        GlassTarget.entries.forEach { t ->
            FilterChip(selected = t == target, onClick = { target = t }, label = { Text(t.label) }, modifier = Modifier.padding(end = 6.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Preview(target, draft)
    Spacer(Modifier.height(14.dp))

    val p = draft
    Section("形状") {
        if (target == GlassTarget.CONTROL) Hint("按钮和开关是圆的 / 胶囊形，不用圆角这一项。")
        else Labeled("圆角", "${p.cornerRadiusDp.roundToInt()}dp", p.cornerRadiusDp, 0f..64f, { change(p.copy(cornerRadiusDp = it)) }, { commit() })
    }
    Section("底色（保证上面的字看得清）") {
        Labeled("浅色时", "${(p.surfaceLight * 100).roundToInt()}%", p.surfaceLight, 0f..1f, { change(p.copy(surfaceLight = it)) }, { commit() })
        Labeled("深色时", "${(p.surfaceDark * 100).roundToInt()}%", p.surfaceDark, 0f..1f, { change(p.copy(surfaceDark = it)) }, { commit() })
        Hint("0% 就是完全透明（癸星科技那套就是 0），字压在什么颜色上就是什么颜色。")
    }
    Section("模糊 / 折射") {
        Toggle("饱和度提升（vibrancy）", p.vibrancy) { commit(p.copy(vibrancy = it)) }
        Labeled("模糊半径", "${p.blurRadiusDp.roundToInt()}dp", p.blurRadiusDp, 0f..32f, { change(p.copy(blurRadiusDp = it)) }, { commit() })
        Labeled("折射高度", "${p.refractionHeightDp.roundToInt()}dp", p.refractionHeightDp, 0f..64f, { change(p.copy(refractionHeightDp = it)) }, { commit() })
        Labeled("折射强度", "${p.refractionAmountDp.roundToInt()}dp", p.refractionAmountDp, 0f..96f, { change(p.copy(refractionAmountDp = it)) }, { commit() })
        Toggle("深度效果（depthEffect）", p.depthEffect) { commit(p.copy(depthEffect = it)) }
        Toggle("色散（彩边）", p.chromaticAberration) { commit(p.copy(chromaticAberration = it)) }
        Hint("色散：库的着色器有毛病，背后有字、面板窄长时会花屏，安卓上严重的会闪退（癸星科技实测）。想看效果可以开，不对就关掉。" +
            "折射量超过这块玻璃本身大小的一半左右会被自动限住。")
    }
    Section("高光") {
        val h = p.highlight
        Toggle("启用", h.enabled) { commit(p.copy(highlight = h.copy(enabled = it))) }
        if (h.enabled) {
            FlowRowCompat {
                HighlightStyleKind.entries.forEach { k ->
                    FilterChip(selected = h.style == k, onClick = { commit(p.copy(highlight = h.copy(style = k))) },
                        label = { Text(when (k) { HighlightStyleKind.Plain -> "细边"; HighlightStyleKind.Default -> "斜光"; HighlightStyleKind.Ambient -> "环境光" }) },
                        modifier = Modifier.padding(end = 6.dp))
                }
            }
            if (!ambientHighlightWorks && h.style != HighlightStyleKind.Plain) Hint("电脑上只能画「细边」（另外两种的着色器和电脑端不兼容），手机上能看到。")
            Labeled("宽度", "${"%.1f".format(h.widthDp)}dp", h.widthDp, 0f..8f, { change(p.copy(highlight = h.copy(widthDp = it))) }, { commit() })
            Labeled("模糊", "${"%.1f".format(h.blurRadiusDp)}dp", h.blurRadiusDp, 0f..8f, { change(p.copy(highlight = h.copy(blurRadiusDp = it))) }, { commit() })
            Labeled("透明度", "${(h.alpha * 100).roundToInt()}%", h.alpha, 0f..1f, { change(p.copy(highlight = h.copy(alpha = it))) }, { commit() })
            if (h.style == HighlightStyleKind.Default) {
                Labeled("角度", "${h.angleDeg.roundToInt()}°", h.angleDeg, 0f..360f, { change(p.copy(highlight = h.copy(angleDeg = it))) }, { commit() })
                Labeled("衰减", "%.2f".format(h.falloff), h.falloff, 0.1f..4f, { change(p.copy(highlight = h.copy(falloff = it))) }, { commit() })
            }
            if (h.style == HighlightStyleKind.Ambient)
                Labeled("强度", "%.2f".format(h.ambientIntensity), h.ambientIntensity, 0f..1f, { change(p.copy(highlight = h.copy(ambientIntensity = it))) }, { commit() })
        }
    }
    Section("外阴影") {
        val s = p.shadow
        Toggle("启用", s.enabled) { commit(p.copy(shadow = s.copy(enabled = it))) }
        if (s.enabled) {
            Labeled("半径", "${s.radiusDp.roundToInt()}dp", s.radiusDp, 0f..48f, { change(p.copy(shadow = s.copy(radiusDp = it))) }, { commit() })
            Labeled("左右偏移", "${s.offsetXDp.roundToInt()}dp", s.offsetXDp, -24f..24f, { change(p.copy(shadow = s.copy(offsetXDp = it))) }, { commit() })
            Labeled("上下偏移", "${s.offsetYDp.roundToInt()}dp", s.offsetYDp, -24f..24f, { change(p.copy(shadow = s.copy(offsetYDp = it))) }, { commit() })
            Labeled("透明度", "${(s.alpha * 100).roundToInt()}%", s.alpha, 0f..1f, { change(p.copy(shadow = s.copy(alpha = it))) }, { commit() })
            Swatches(s.colorArgb) { c -> commit(p.copy(shadow = s.copy(colorArgb = (c and 0x00FFFFFFL) or (s.colorArgb and 0xFF000000L)))) }
        }
    }
    Section("内阴影") {
        val s = p.innerShadow
        Toggle("启用", s.enabled) { commit(p.copy(innerShadow = s.copy(enabled = it))) }
        if (s.enabled) {
            Labeled("半径", "${s.radiusDp.roundToInt()}dp", s.radiusDp, 0f..48f, { change(p.copy(innerShadow = s.copy(radiusDp = it))) }, { commit() })
            Labeled("左右偏移", "${s.offsetXDp.roundToInt()}dp", s.offsetXDp, -24f..24f, { change(p.copy(innerShadow = s.copy(offsetXDp = it))) }, { commit() })
            Labeled("上下偏移", "${s.offsetYDp.roundToInt()}dp", s.offsetYDp, -24f..24f, { change(p.copy(innerShadow = s.copy(offsetYDp = it))) }, { commit() })
            Labeled("透明度", "${(s.alpha * 100).roundToInt()}%", s.alpha, 0f..1f, { change(p.copy(innerShadow = s.copy(alpha = it))) }, { commit() })
            Swatches(s.colorArgb) { c -> commit(p.copy(innerShadow = s.copy(colorArgb = (c and 0x00FFFFFFL) or (s.colorArgb and 0xFF000000L)))) }
        }
    }
    Section("染色") {
        val t = p.tint
        Toggle("启用", t.enabled) { commit(p.copy(tint = t.copy(enabled = it))) }
        if (t.enabled) {
            Swatches(t.colorArgb) { c -> commit(p.copy(tint = t.copy(colorArgb = c))) }
            Labeled("浓度", "${(t.alpha * 100).roundToInt()}%", t.alpha, 0f..1f, { change(p.copy(tint = t.copy(alpha = it))) }, { commit() })
        }
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton({ ctl.updateSettings { it.copy(glassTuning = it.glassTuning.reset(target)) }; ctl.toast("「${target.label}」已恢复默认") }) { Text("恢复默认") }
        OutlinedButton({
            clipboard.setText(AnnotatedString(AppJson.encodeToString(GlassParams.serializer(), draft)))
            ctl.toast("已复制「${target.label}」的参数（JSON）")
        }) { Text("复制参数") }
    }
    Spacer(Modifier.height(16.dp))
    Presets(ctl, tuning)
    Spacer(Modifier.height(24.dp))
}

/** 预览：一块和界面上同一个位置一样的玻璃，压在可换的背景上，上面放两行字（看字清不清楚）；轻轻呼吸，一直在重画。 */
@Composable
private fun Preview(target: GlassTarget, p: GlassParams) {
    var bg by remember { mutableStateOf(PreviewBg.WALLPAPER) }
    val dark = LocalGlass.current.dark
    val paint = LocalGlass.current.paint
    FlowRowCompat {
        PreviewBg.entries.forEach { b -> FilterChip(selected = b == bg, onClick = { bg = b }, label = { Text(b.label) }, modifier = Modifier.padding(end = 6.dp)) }
    }
    val pulse by rememberInfiniteTransition(label = "preview").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
        val backdrop = rememberLayerBackdrop()
        // 采样源要和玻璃是兄弟、背景画在挂 layerBackdrop 的这个节点自己身上（癸星科技踩过的两个坑）
        Box(Modifier.layerBackdrop(backdrop).fillMaxSize().drawBehind {
            when (bg) {
                PreviewBg.WALLPAPER -> paint()
                PreviewBg.GRADIENT -> drawRect(Brush.linearGradient(listOf(Color(0xFF8B7CFF), Color(0xFF3CE8D8), Color(0xFFFF5FC9))))
                PreviewBg.SOLID -> drawRect(if (dark) Color(0xFF3A3A3C) else Color(0xFFD9DCE6))
                PreviewBg.TEXT -> {
                    drawRect(if (dark) Color(0xFF15161F) else Color(0xFFF6F7FB))
                    val line = if (dark) Color.White.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.16f)
                    var y = 18.dp.toPx()
                    var i = 0
                    while (y < size.height) {
                        drawRect(line, androidx.compose.ui.geometry.Offset(18.dp.toPx(), y),
                            androidx.compose.ui.geometry.Size(size.width * (if (i % 3 == 2) 0.5f else 0.86f), 9.dp.toPx()))
                        y += 20.dp.toPx(); i++
                    }
                }
            }
        })
        val (w, h) = when (target) {
            GlassTarget.SIDEBAR -> 150.dp to 190.dp
            GlassTarget.BAR -> 280.dp to 64.dp
            GlassTarget.CARD -> 240.dp to 130.dp
            GlassTarget.CONTROL -> 64.dp to 64.dp
            GlassTarget.DIALOG -> 240.dp to 160.dp
        }
        val shape = if (target == GlassTarget.CONTROL) CircleShape else RoundedCornerShape(p.cornerRadiusDp.coerceIn(0f, 64f).dp)
        Box(
            Modifier.align(Alignment.Center).size(w, h).glass(shape, target, fallback = MaterialTheme.colorScheme.surface,
                backdrop = backdrop, params = p, layerBlock = { val s = 1f - 0.03f * pulse; scaleX = s; scaleY = s }),
            contentAlignment = Alignment.Center,
        ) {
            if (target == GlassTarget.CONTROL) Text("↑", style = MaterialTheme.typography.titleLarge)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(target.label, style = MaterialTheme.typography.titleSmall)
                Text("上面的字看得清吗", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
        }
    }
}

@Composable
private fun Presets(ctl: AppController, tuning: GlassTuning) {
    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<GlassPreset?>(null) }
    Text("预设", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Hint("一个预设 = 上面 ${GlassTarget.entries.size} 个位置各自现在的参数。「应用」会把这 ${GlassTarget.entries.size} 个位置一起换掉。")
    Spacer(Modifier.height(6.dp))
    (GlassDefaults.builtIns + tuning.presets).forEach { pr ->
        val builtIn = pr.id.startsWith("builtin-")
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pr.name + if (builtIn) "（自带）" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            OutlinedButton({ ctl.updateSettings { it.copy(glassTuning = it.glassTuning.apply(pr)) }; ctl.toast("已应用「${pr.name}」") }) { Text("应用") }
            if (!builtIn) {
                TextButton({ renaming = pr }) { Text("改名") }
                TextButton({ ctl.updateSettings { s -> s.copy(glassTuning = s.glassTuning.copy(presets = s.glassTuning.presets.filterNot { it.id == pr.id })) } }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Button({ saving = true }) { Text("把现在的存成新预设") }
    if (saving) NameDialog("存成预设", "") { name ->
        saving = false
        if (name != null) {
            ctl.updateSettings { s -> s.copy(glassTuning = s.glassTuning.copy(presets = s.glassTuning.presets +
                GlassPreset("p" + com.guixing.jixunying.ui.nowMillis().toString(36), name, s.glassTuning.snapshot()))) }
            ctl.toast("已存「$name」")
        }
    }
    renaming?.let { pr ->
        NameDialog("预设改名", pr.name) { name ->
            renaming = null
            if (name != null) ctl.updateSettings { s -> s.copy(glassTuning = s.glassTuning.copy(presets = s.glassTuning.presets.map { if (it.id == pr.id) it.copy(name = name) else it })) }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AppDialog(title, onDismiss = { onDone(null) }, width = 420.dp, actions = {
        TextButton({ onDone(null) }) { Text("取消") }
        Button(enabled = name.isNotBlank(), onClick = { onDone(name.trim().take(20)) }) { Text("确定") }
    }) { AppTextField(name, { name = it }, placeholder = "预设名字") }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(vertical = 2.dp))

@Composable
private fun Labeled(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column {
        Text("$label  $valueText", style = MaterialTheme.typography.labelMedium)
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        AppSwitch(checked, onChange)
    }
}

/** 颜色：点开取色器（10-24：以前只有 8 个固定色块）。透明度那一位保留原来的。 */
@Composable
private fun Swatches(current: Long, onPick: (Long) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorSwatch(current or 0xFF000000L, "选颜色", 30) { onPick(it) }
        Spacer(Modifier.width(8.dp))
        Text("点色块换颜色", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
    }
}

/**
 * 背景（2026-10-24）：默认 / 纯色 / 渐变 / 图片；纯色、渐变的颜色用取色器，渐变能调角度；图片从本机选；
 * 亮度、对比度、饱和度三种都给，模糊给渐变和图片，透明度只给图片（照癸星科技 GlassBackgroundEditor）。
 */
@Composable
fun BackgroundSection(ctl: AppController, bg: com.guixing.jixunying.model.AppBackground) {
    val platform = LocalPlatform.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var draft by remember(bg) { mutableStateOf(bg) }
    var busy by remember { mutableStateOf(false) }
    fun commit(b: com.guixing.jixunying.model.AppBackground = draft) { draft = b; ctl.updateSettings { it.copy(background = b) } }
    FlowRowCompat {
        com.guixing.jixunying.model.BgMode.entries.forEach { m ->
            FilterChip(selected = draft.mode == m, onClick = { commit(draft.copy(mode = m)) }, label = { Text(m.label) }, modifier = Modifier.padding(end = 6.dp))
        }
    }
    when (draft.mode) {
        com.guixing.jixunying.model.BgMode.DEFAULT -> Hint("默认：开着液态玻璃时是几团淡淡的柔光，关着是主题的底色。")
        com.guixing.jixunying.model.BgMode.SOLID -> Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ColorSwatch(draft.solidArgb, "背景颜色") { commit(draft.copy(solidArgb = it)) }
            Spacer(Modifier.width(10.dp))
            Text("点色块选颜色", style = MaterialTheme.typography.bodySmall)
        }
        com.guixing.jixunying.model.BgMode.GRADIENT -> {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val a = draft.gradientArgb.getOrElse(0) { 0xFF8B7CFFL }
                val b = draft.gradientArgb.getOrElse(1) { 0xFF3CE8D8L }
                ColorSwatch(a, "渐变起点颜色") { commit(draft.copy(gradientArgb = listOf(it, b))) }
                Text("  →  ", style = MaterialTheme.typography.bodyMedium)
                ColorSwatch(b, "渐变终点颜色") { commit(draft.copy(gradientArgb = listOf(a, it))) }
            }
            Labeled("角度", "${draft.gradientAngle.roundToInt()}°", draft.gradientAngle, 0f..360f, { draft = draft.copy(gradientAngle = it) }, { commit() })
        }
        com.guixing.jixunying.model.BgMode.IMAGE -> {
            if (draft.imageId.isNotBlank()) {
                val img = rememberImage(ctl, draft.imageId)
                if (img != null) androidx.compose.foundation.Image(img, null, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = draft.alpha,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(com.guixing.jixunying.ui.glass.bgColorMatrix(draft.brightness, draft.contrast, draft.saturation)))
                Spacer(Modifier.height(6.dp))
            }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val f = platform.pickFiles(imagesOnly = true).firstOrNull()
                    if (f != null) {
                        val att = ctl.backend.upload(f.name, f.mime.ifBlank { "image/jpeg" }, f.bytes)
                        if (att != null) commit(draft.copy(mode = com.guixing.jixunying.model.BgMode.IMAGE, imageId = att.id)) else ctl.toast("图片存不进去")
                    }
                    busy = false
                }
            }) { Text(if (busy) "正在导入…" else if (draft.imageId.isBlank()) "选一张图片" else "换一张图片") }
        }
    }
    if (draft.mode != com.guixing.jixunying.model.BgMode.DEFAULT) {
        Labeled("亮度", "%.1f".format(draft.brightness), draft.brightness, 0.3f..1.7f, { draft = draft.copy(brightness = it) }, { commit() })
        Labeled("对比度", "%.1f".format(draft.contrast), draft.contrast, 0.3f..1.7f, { draft = draft.copy(contrast = it) }, { commit() })
        Labeled("饱和度", "%.1f".format(draft.saturation), draft.saturation, 0f..2f, { draft = draft.copy(saturation = it) }, { commit() })
        if (draft.mode != com.guixing.jixunying.model.BgMode.SOLID)
            Labeled("模糊", "${draft.blurDp.roundToInt()}dp", draft.blurDp, 0f..30f, { draft = draft.copy(blurDp = it) }, { commit() })
        if (draft.mode == com.guixing.jixunying.model.BgMode.IMAGE)
            Labeled("透明度", "${(draft.alpha * 100).roundToInt()}%", draft.alpha, 0.1f..1f, { draft = draft.copy(alpha = it) }, { commit() })
        Hint("背景深了，字会自动看不清：深色背景配 设置里主题选「深色」，浅色背景选「浅色」。")
    }
}
