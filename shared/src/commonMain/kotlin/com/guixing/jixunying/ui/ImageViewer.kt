package com.guixing.jixunying.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guixing.jixunying.model.Attachment
import kotlinx.coroutines.launch

/**
 * 点开看大图（2026-11-04 用户：「一次性生成多张图片……点开之后不可以左右滑」「放大看细节也看不了」）：
 * 同一条消息里的几张图左右翻（电脑上两边有箭头），双指 / 双击 / 鼠标滚轮放大，放大以后拖着看细节；
 * 放大时不翻页，缩回原样才翻。点图外面或者没放大时点一下图关掉。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ImageGallery(ctl: AppController, images: List<Attachment>, start: Int, onClose: () -> Unit) {
    if (images.isEmpty()) return
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
    /** 当前这张放大了没有（放大时不让翻页）。 */
    var zoomed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.97f)).padding(vertical = 12.dp)) {
            val cur = images[pager.currentPage.coerceIn(0, images.lastIndex)]
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (images.size > 1) "${pager.currentPage + 1} / ${images.size}  ${cur.name}" else cur.name,
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        val bytes = ctl.backend.fileBytes(cur.id) ?: return@launch
                        if (platform.saveFile(cur.name, bytes)) ctl.toast("已保存")
                    }
                }) {
                    Icon(Icons.Rounded.Download, null, tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("保存", color = Color.White)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { images[it].id }) { page ->
                    ZoomableImage(ctl, images[page], active = page == pager.currentPage,
                        onZoomChange = { if (page == pager.currentPage) zoomed = it }, onTapOutside = onClose)
                }
                // 电脑上没有手指划：两边放箭头（手机上也能点）
                if (images.size > 1 && !zoomed) {
                    if (pager.currentPage > 0) ArrowButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一张", Modifier.align(Alignment.CenterStart)) {
                        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                    }
                    if (pager.currentPage < images.lastIndex) ArrowButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一张", Modifier.align(Alignment.CenterEnd)) {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                }
            }
            val note = cur.note
            Text(note.ifBlank { if (platform.isDesktop) "滚轮放大，双击还原" else "双指或双击放大，放大后拖动看细节" },
                color = Color.White.copy(alpha = if (note.isBlank()) 0.45f else 0.7f), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun ArrowButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.padding(6.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))) {
        Icon(icon, desc, tint = Color.White)
    }
}

@Composable
private fun ZoomableImage(ctl: AppController, a: Attachment, active: Boolean, onZoomChange: (Boolean) -> Unit, onTapOutside: () -> Unit) {
    val img = rememberImage(ctl, a.id)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // 翻走了就缩回原样
    if (!active && scale != 1f) { scale = 1f; offset = Offset.Zero }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    fun setScale(s: Float, o: Offset = offset) {
        scale = s.coerceIn(1f, 8f)
        offset = if (scale <= 1.01f) Offset.Zero.also { scale = 1f } else clamp(o, scale)
        onZoomChange(scale > 1f)
    }
    Box(
        // 放大的图只画在自己这块里，不盖住上面的按钮和下面的提示
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }
            .pointerInput(a.id) {
                detectTapGestures(
                    onDoubleTap = { p ->
                        if (scale > 1f) setScale(1f)
                        else {
                            // 双击哪里就放大哪里
                            val center = Offset(box.width / 2f, box.height / 2f)
                            setScale(2.5f, (center - p) * 1.5f)
                        }
                    },
                    onTap = { if (scale <= 1f) onTapOutside() },
                )
            }
            .pointerInput(a.id) {
                // 两根手指缩放；放大以后一根手指拖着看。没放大时一根手指留给翻页
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val multi = ev.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val z = ev.calculateZoom()
                            val p = ev.calculatePan()
                            setScale(scale * z, offset + p)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            .pointerInput(a.id) {
                // 电脑：滚轮放大（不按键也收得到滚轮事件）
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        if (ev.type == PointerEventType.Scroll) {
                            val dy = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (dy != 0f) setScale(scale * if (dy < 0) 1.15f else 1f / 1.15f)
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) Image(img, a.name,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            contentScale = ContentScale.Fit)
        else CircularProgressIndicator(color = Color.White)
    }
}
