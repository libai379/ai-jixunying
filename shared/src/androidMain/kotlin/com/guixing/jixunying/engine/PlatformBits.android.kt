package com.guixing.jixunying.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

/** 安卓上引擎要用到的 Context（读 PDF 字体资源）。Application.onCreate 里设置。 */
object AndroidEnv {
    @Volatile var context: Context? = null
        set(value) {
            field = value
            if (value != null) runCatching { PDFBoxResourceLoader.init(value) }
        }
}

actual fun shrinkImageToJpeg(bytes: ByteArray, maxSide: Int, maxBytes: Int): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val side = maxOf(bounds.outWidth, bounds.outHeight)
    if (side <= 0) return null
    if (side <= maxSide && bytes.size < maxBytes) return null
    var sample = 1
    while (side / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = minOf(1.0, maxSide.toDouble() / maxOf(decoded.width, decoded.height))
    val bmp = if (scale < 1.0) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
}

actual fun decodePixels(bytes: ByteArray): Pixels? {
    val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val out = IntArray(bmp.width * bmp.height)
    bmp.getPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    return Pixels(bmp.width, bmp.height, out).also { bmp.recycle() }
}

actual fun encodePixels(p: Pixels, jpegQuality: Int?): ByteArray {
    val bmp = Bitmap.createBitmap(p.argb, p.w, p.h, Bitmap.Config.ARGB_8888)
    val out = ByteArrayOutputStream()
    if (jpegQuality == null) bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    else {
        // JPEG 没有透明：先铺白底
        val flat = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(flat)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(bmp, 0f, 0f, null)
        flat.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), out)
        flat.recycle()
    }
    bmp.recycle()
    return out.toByteArray()
}

/** 画完把像素写回去。 */
private fun drawOn(p: Pixels, draw: (android.graphics.Canvas) -> Unit) {
    val bmp = Bitmap.createBitmap(p.argb, p.w, p.h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    draw(android.graphics.Canvas(bmp))
    bmp.getPixels(p.argb, 0, p.w, 0, 0, p.w, p.h)
    bmp.recycle()
}

actual fun drawTextOn(p: Pixels, text: String, box: IntArray, color: Int, sizePx: Int, bold: Boolean, align: String) = drawOn(p) { c ->
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color or (0xFF shl 24)
        textSize = sizePx.toFloat()
        typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }
    val lines = text.split('\n')
    // 比整张图还宽就缩小
    while (lines.maxOf { paint.measureText(it) } > p.w - 4 && paint.textSize > 6f) paint.textSize -= 1f
    val lineH = (paint.textSize * 1.3f).toInt()
    val first = android.graphics.Rect().also { paint.getTextBounds(lines.first(), 0, lines.first().length, it) }
    val inkH = first.height() + lineH * (lines.size - 1)
    val centerY = (box[1] + box[3]) / 2f
    lines.forEachIndexed { i, line ->
        val ink = android.graphics.Rect().also { paint.getTextBounds(line, 0, line.length, it) }
        val x = when (align) {
            "left" -> box[0] - ink.left.toFloat()
            "right" -> box[2] - ink.right.toFloat()
            else -> (box[0] + box[2]) / 2f - (ink.left + ink.right) / 2f
        }
        val y = centerY - inkH / 2f - first.top + lineH * i
        c.drawText(line, x, y, paint)
    }
}

actual fun drawShapeOn(p: Pixels, shape: String, box: IntArray, color: Int, thickness: Int) = drawOn(p) { c ->
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color or (0xFF shl 24)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = thickness.toFloat()
    }
    val r = android.graphics.RectF(box[0].toFloat(), box[1].toFloat(), box[2].toFloat(), box[3].toFloat())
    if (shape == "ellipse") c.drawOval(r, paint) else c.drawRect(r, paint)
}

@Suppress("DEPRECATION")
private val storageRoot get() = android.os.Environment.getExternalStorageDirectory()

actual fun defaultDocRoots(): List<java.io.File> =
    if (docAccessMissing()) emptyList() else listOf(storageRoot).filter { it.isDirectory && it.canRead() }

/** 微信「保存到手机」的文件在 Download/WeiXin；收到但没保存的在微信自己的目录里，别的 App 读不到。 */
actual fun weixinDocRoots(): List<java.io.File> =
    listOf("Download/WeiXin", "Download/WeChat", "Documents/WeiXin").map { java.io.File(storageRoot, it) }.filter { it.isDirectory }

actual fun docAccessMissing(): Boolean =
    android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()

actual fun extractPdfText(bytes: ByteArray): Pair<String?, String> {
    PDDocument.load(bytes).use { doc ->
        val text = PDFTextStripper().getText(doc).trim()
        return if (text.length < 20 && doc.numberOfPages > 0)
            null to "这个 PDF 是扫描件（${doc.numberOfPages} 页图片），抽不出文字；可以截图后按图片发送"
        else text to "${doc.numberOfPages} 页"
    }
}

actual fun defaultSaveFolder(): java.io.File? = null

private var lastTraffic: Triple<Long, Long, Long>? = null

/**
 * 整机收发字节减去我们自己（TrafficStats，按 uid 算），和上一次量的比出每秒多少；第一次量返回 -1。
 * 按流量计费看 ConnectivityManager.isActiveNetworkMetered（手机流量、标了计费的 Wi-Fi 都算）。
 */
actual fun networkLoad(): NetLoad {
    val ctx = AndroidEnv.context
    val metered = runCatching {
        (ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.isActiveNetworkMetered ?: false
    }.getOrDefault(false)
    val uid = android.os.Process.myUid()
    val all = android.net.TrafficStats.getTotalRxBytes() + android.net.TrafficStats.getTotalTxBytes()
    val mine = android.net.TrafficStats.getUidRxBytes(uid) + android.net.TrafficStats.getUidTxBytes(uid)
    val now = android.os.SystemClock.elapsedRealtime()
    if (all < 0 || mine < 0) return NetLoad(-1, metered)
    val prev = lastTraffic
    lastTraffic = Triple(now, all, mine)
    if (prev == null || now - prev.first < 200) return NetLoad(-1, metered)
    val other = ((all - prev.second) - (mine - prev.third)).coerceAtLeast(0)
    return NetLoad(other * 1000 / (now - prev.first), metered)
}

actual fun platformLog(tag: String, text: String) {
    runCatching { android.util.Log.i(if (tag.startsWith("Jxy")) tag else "Jxy$tag", text.take(3000)) }
}

/**
 * 手机上用系统的 MediaMetadataRetriever 取画面，按时间均匀取。附件文件没有扩展名，它按内容认格式。
 * 用 OPTION_CLOSEST（准确的那一帧）：模拟器上量过，OPTION_CLOSEST_SYNC 只取关键帧，5 秒的视频六张里只有三张不一样，HEVC 那段六张全一样。
 */
actual fun localVideoFrames(file: java.io.File, count: Int, maxSide: Int): Pair<List<ByteArray>, Double>? {
    val r = android.media.MediaMetadataRetriever()
    try {
        r.setDataSource(file.path)
        val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val out = mutableListOf<ByteArray>()
        for (i in 0 until count) {
            val us = ms * 1000 * (2 * i + 1) / (2 * count)
            val frame = r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
            val scale = minOf(1.0, maxSide.toDouble() / maxOf(frame.width, frame.height))
            val bmp = if (scale < 1.0) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true) else frame
            out += ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            if (bmp !== frame) bmp.recycle()
            frame.recycle()
        }
        return out to ms / 1000.0
    } finally {
        runCatching { r.release() }
    }
}

/** 手机上用 MediaExtractor + MediaCodec 把声轨解成 PCM，混成单声道、重采样到 16k（线性插值，给听写用够了）。 */
actual fun localVideoAudio(file: java.io.File, maxSeconds: Int): Pair<ByteArray, Double>? {
    val ex = android.media.MediaExtractor()
    var codec: android.media.MediaCodec? = null
    try {
        ex.setDataSource(file.path)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: return ByteArray(0) to 0.0
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val seconds = if (fmt.containsKey(android.media.MediaFormat.KEY_DURATION)) fmt.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000_000.0 else 0.0
        var rate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
        val c = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME)!!)
        codec = c
        c.configure(fmt, null, null, 0)
        c.start()
        val info = android.media.MediaCodec.BufferInfo()
        val pcm = java.io.ByteArrayOutputStream()
        // 重采样：上一个样本和小数位置留着，跨缓冲区接得上
        var pos = 0.0
        var prev = 0f
        var inputDone = false
        var heard = 0L
        val want = maxSeconds * 16000L
        while (heard < want) {
            if (!inputDone) {
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                    if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = c.dequeueOutputBuffer(info, 10_000)
            if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            } else if (o >= 0) {
                val buf = c.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                val frames = (info.size / 2) / channels.coerceAtLeast(1)
                val step = rate / 16000.0
                for (k in 0 until frames) {
                    var sum = 0f
                    for (ch in 0 until channels) sum += buf.get(k * channels + ch)
                    val cur = sum / channels
                    // pos 是下一个要出的样本落在输入里的位置（相对这一帧）
                    while (pos <= 1.0 && heard < want) {
                        val s = (prev + (cur - prev) * pos.toFloat()).toInt().coerceIn(-32768, 32767)
                        pcm.write(s and 0xff); pcm.write((s shr 8) and 0xff)
                        heard++
                        pos += step
                    }
                    pos -= 1.0
                    prev = cur
                }
                c.releaseOutputBuffer(o, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        return pcm.toByteArray() to seconds
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { ex.release() }
    }
}
