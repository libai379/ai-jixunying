package com.guixing.jixunying.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guixing.jixunying.model.Attachment
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.launch

/** 视频放在这台设备上的哪个文件（遥控电脑时先整个取过来）。同一段只取一次。 */
private object VideoFiles {
    val paths = HashMap<String, String>()
    val thumbs = HashMap<String, ImageBitmap>()
}

private fun cacheName(a: Attachment): String = a.id + "." + a.name.substringAfterLast('.', "mp4").lowercase().ifBlank { "mp4" }

/** 拿到视频的本机文件：做在这台设备上的直接用，否则取过来存进临时文件夹。 */
private suspend fun localVideoPath(ctl: AppController, platform: Platform, a: Attachment): String? {
    // 记着的缓存可能被清掉了（三天前的会清）：电脑上看一眼还在不在
    VideoFiles.paths[a.id]?.takeIf { !platform.isDesktop || platform.fileExists(it) }?.let { return it }
    val here = if (ctl.backend === ctl.hub.local && a.savedPath.isNotEmpty() && platform.fileExists(a.savedPath)) a.savedPath else null
    val path = here ?: ctl.backend.fileBytes(a.id)?.let { platform.cacheFile(cacheName(a), it) }
    if (path != null) VideoFiles.paths[a.id] = path
    return path
}

/**
 * 聊天里的视频（2026-11-04 用户：「咱能自己在软件里边就是直接看到画面内容，然后直接点开播放那种，而不是上面写着一个 MP4 文件」）：
 * 一帧画面当封面、中间一个播放键，点开在软件里放。遥控电脑时封面要先把视频取过来，取到之前显示名字和大小。
 */
@Composable
internal fun VideoCard(ctl: AppController, a: Attachment, large: Boolean = false) {
    val platform = LocalPlatform.current
    var open by remember { mutableStateOf(false) }
    val downloads by ctl.backend.downloads.collectAsState()
    val pct = downloads[a.id]
    // 本机的视频才自动取封面；遥控电脑时不为了封面去拉十几 MB，点开时再取
    val local = ctl.backend === ctl.hub.local
    val thumb by produceState(VideoFiles.thumbs[a.id], a.id, local) {
        if (value != null || !local) return@produceState
        val p = localVideoPath(ctl, platform, a) ?: return@produceState
        value = platform.videoThumb(p)?.also { VideoFiles.thumbs[a.id] = it }
    }
    val w = if (large) 320.dp else 240.dp
    Box(
        Modifier.width(w).height(w * 9 / 16).clip(RoundedCornerShape(12.dp)).background(Color(0xFF111318))
            .border(1.dp, Ext.c.border, RoundedCornerShape(12.dp)).clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let { Image(it, a.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            if (pct != null) Text("${(pct * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            else Icon(Icons.Rounded.PlayArrow, "播放", Modifier.size(30.dp), tint = Color.White)
        }
        Text(listOf(a.name, formatSize(a.size)).filter { it.isNotBlank() }.joinToString(" · "),
            Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (open) VideoPlayerDialog(ctl, a) { open = false }
}

/** 聊天里的音频（2026-11-04）：名字、时长、听写了多少字，点一下在软件里放。 */
@Composable
internal fun AudioChip(ctl: AppController, a: Attachment) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).border(1.dp, Ext.c.border, RoundedCornerShape(12.dp))
            .clickable { open = true }.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF0EA5E9)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "播放", Modifier.size(22.dp), tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(200.dp)) {
            Text(a.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = listOfNotNull(
                a.seconds.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60} 分 ${it % 60} 秒" else "$it 秒" },
                formatSize(a.size),
                a.textChars.takeIf { it > 0 }?.let { "听写 $it 字" },
            ).joinToString(" · ")
            Text(info, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (open) VideoPlayerDialog(ctl, a) { open = false }
}

/** 软件里放视频：播放 / 暂停、拖进度、全屏，另存为；这台设备放不了的（编码不认）退回系统播放器。 */
@Composable
internal fun VideoPlayerDialog(ctl: AppController, a: Attachment, onClose: () -> Unit) {
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val player = rememberVideoPlayerState()
    val downloads by ctl.backend.downloads.collectAsState()
    val pct = downloads[a.id]
    var path by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(a.id) {
        val p = path ?: localVideoPath(ctl, platform, a)
        if (p == null) { failed = "视频没取过来：网络不好或电脑没开，关掉再点一次"; return@LaunchedEffect }
        path = p
        // 直接给本机路径：Windows 那边不认 file: 网址（实测报 File not found），安卓 Media3 也认路径
        player.openUri(p, InitialPlayerState.PLAY)
    }
    val err = player.error
    /** 正在把放不了的视频转成能放的（电脑上微信发来的 HEVC）。 */
    var converting by remember { mutableStateOf(false) }
    var triedConvert by remember { mutableStateOf(false) }
    LaunchedEffect(err) {
        if (err == null) return@LaunchedEffect
        val p = path
        // 先转一份能放的再放（电脑：Windows 没装 HEVC 扩展时，用程序带的 FFmpeg 转成 H.264），转不了才说放不了
        if (p != null && !triedConvert) {
            triedConvert = true
            converting = true
            player.clearError()
            val q = platform.playableCopy(p)
            converting = false
            if (q != null) { player.openUri(q, InitialPlayerState.PLAY); return@LaunchedEffect }
        }
        failed = "这台设备放不了这段视频（${err.toString().substringAfter("message=", "编码不支持").removeSuffix(")")}），可以用系统播放器打开"
    }
    Dialog(onDismissRequest = { player.stop(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                path?.let { p ->
                    IconButton(onClick = { player.pause(); if (!platform.openFile(p)) ctl.toast("没有能打开它的播放器") }) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, "用系统播放器打开", tint = Color.White)
                    }
                }
                IconButton(onClick = {
                    scope.launch {
                        val bytes = ctl.backend.fileBytes(a.id)
                        if (bytes == null) ctl.toast("文件没取过来：网络不好或电脑没开，再点一次") else if (platform.saveFile(a.name, bytes)) ctl.toast("已保存《${a.name}》")
                    }
                }) { Icon(Icons.Rounded.Download, "另存为", tint = Color.White) }
                IconButton(onClick = { player.stop(); onClose() }) { Icon(Icons.Rounded.Close, "关闭", tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clickable { if (player.isPlaying) player.pause() else player.play() }, contentAlignment = Alignment.Center) {
                if (path != null && failed == null) VideoPlayerSurface(player, Modifier.fillMaxSize())
                // 音频没有画面：中间放个音符和名字，别是一大块黑
                if (a.isAudio && failed == null && path != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(96.dp), tint = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.height(12.dp))
                    Text(a.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp))
                }
                when {
                    failed != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Text(failed!!, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        path?.let { p -> TextButton(onClick = { if (!platform.openFile(p)) ctl.toast("没有能打开它的播放器") }) { Text("用系统播放器打开", color = Color.White) } }
                    }
                    path == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(if (pct != null) "正在从电脑取 ${(pct * 100).toInt()}%" else "正在准备…", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                    converting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("这段视频的格式要先转一下才能放，稍等…", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                    player.isLoading -> CircularProgressIndicator(color = Color.White)
                    !player.isPlaying && player.hasMedia && !a.isAudio -> Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, "播放", Modifier.size(40.dp), tint = Color.White)
                    }
                }
            }
            if (failed == null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                    Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "暂停" else "播放", tint = Color.White)
                }
                Text(player.positionText, color = Color.White, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = player.sliderPos, onValueChange = { player.seekStart(it) }, onValueChangeFinished = { player.seekFinished() },
                    valueRange = 0f..1000f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                )
                Text(player.durationText, color = Color.White, style = MaterialTheme.typography.labelSmall)
                if (!a.isAudio) IconButton(onClick = { player.toggleFullscreen() }) { Icon(Icons.Rounded.Fullscreen, "全屏", tint = Color.White) }
            }
        }
    }
}
