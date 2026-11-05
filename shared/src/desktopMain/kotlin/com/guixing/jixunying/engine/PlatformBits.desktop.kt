package com.guixing.jixunying.engine

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual fun shrinkImageToJpeg(bytes: ByteArray, maxSide: Int, maxBytes: Int): ByteArray? {
    val img = ImageIO.read(bytes.inputStream()) ?: return null
    val side = maxOf(img.width, img.height)
    if (side <= maxSide && bytes.size < maxBytes) return null
    val scale = minOf(1.0, maxSide.toDouble() / side)
    val w = (img.width * scale).toInt().coerceAtLeast(1)
    val h = (img.height * scale).toInt().coerceAtLeast(1)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.color = Color.WHITE
    g.fillRect(0, 0, w, h)
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    return ByteArrayOutputStream().also { ImageIO.write(out, "jpg", it) }.toByteArray()
}

actual fun decodePixels(bytes: ByteArray): Pixels? {
    val img = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()
    if (img != null) return Pixels(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    // ImageIO 不认 WEBP 和 CMYK 的 JPEG，Skia 认
    return runCatching {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val info = org.jetbrains.skia.ImageInfo(image.width, image.height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
        val bmp = org.jetbrains.skia.Bitmap()
        bmp.allocPixels(info)
        image.readPixels(bmp, 0, 0)
        val raw = bmp.readPixels(info, image.width * 4, 0, 0) ?: return null
        val out = IntArray(image.width * image.height)
        java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
        Pixels(image.width, image.height, out)
    }.getOrNull()
}

actual fun encodePixels(p: Pixels, jpegQuality: Int?): ByteArray {
    val out = ByteArrayOutputStream()
    if (jpegQuality == null) {
        val alpha = p.argb.any { it ushr 24 != 255 }
        val img = BufferedImage(p.w, p.h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, p.w, p.h, p.argb, 0, p.w)
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }
    val img = BufferedImage(p.w, p.h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, p.w, p.h)
    val src = BufferedImage(p.w, p.h, BufferedImage.TYPE_INT_ARGB)
    src.setRGB(0, 0, p.w, p.h, p.argb, 0, p.w)
    g.drawImage(src, 0, 0, null)
    g.dispose()
    val w = ImageIO.getImageWritersByFormatName("jpeg").next()
    ImageIO.createImageOutputStream(out).use { ios ->
        w.output = ios
        val param = w.defaultWriteParam
        param.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = jpegQuality.coerceIn(1, 100) / 100f
        w.write(null, javax.imageio.IIOImage(img, null, null), param)
    }
    w.dispose()
    return out.toByteArray()
}

/** 画完把像素写回去。 */
private fun drawOn(p: Pixels, draw: (java.awt.Graphics2D) -> Unit) {
    val img = BufferedImage(p.w, p.h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, p.w, p.h, p.argb, 0, p.w)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    draw(g)
    g.dispose()
    img.getRGB(0, 0, p.w, p.h, p.argb, 0, p.w)
}

actual fun drawTextOn(p: Pixels, text: String, box: IntArray, color: Int, sizePx: Int, bold: Boolean, align: String) = drawOn(p) { g ->
    g.color = Color(color)
    var font = java.awt.Font("Microsoft YaHei", if (bold) java.awt.Font.BOLD else java.awt.Font.PLAIN, sizePx)
    val lines = text.split('\n')
    val frc = g.fontRenderContext
    fun widest(f: java.awt.Font) = lines.maxOf { f.createGlyphVector(frc, it).visualBounds.width }
    // 比整张图还宽就缩小
    while (widest(font) > p.w - 4 && font.size > 6) font = font.deriveFont((font.size - 1).toFloat())
    g.font = font
    val lineH = (font.size * 1.3).toInt()
    val first = font.createGlyphVector(frc, lines.first()).visualBounds
    val inkH = first.height + lineH * (lines.size - 1)
    val centerY = (box[1] + box[3]) / 2.0
    lines.forEachIndexed { i, line ->
        val ink = font.createGlyphVector(frc, line).visualBounds
        val x = when (align) {
            "left" -> box[0] - ink.x
            "right" -> box[2] - ink.x - ink.width
            else -> (box[0] + box[2]) / 2.0 - ink.x - ink.width / 2
        }
        val y = centerY - inkH / 2 - first.y + lineH * i
        g.drawString(line, x.toFloat(), y.toFloat())
    }
}

actual fun drawShapeOn(p: Pixels, shape: String, box: IntArray, color: Int, thickness: Int) = drawOn(p) { g ->
    g.color = Color(color)
    g.stroke = java.awt.BasicStroke(thickness.toFloat())
    val x = box[0]; val y = box[1]; val w = box[2] - box[0]; val h = box[3] - box[1]
    if (shape == "ellipse") g.drawOval(x, y, w, h) else g.drawRect(x, y, w, h)
}

/**
 * 电脑上取视频画面用程序带的 FFmpeg（2026-11-03）：微信发来的视频是 HEVC（H.265），Edge 解不了（画面全黑），
 * 这台电脑也没装 Chrome 和 Windows 的 HEVC 扩展。FFmpeg 什么编码都能解，不联网。
 * FFmpeg 加载不了（比如不是 Windows 64 位）或者这段解不开就返回 null，照旧交给 Edge（PageBrowser.fileFrames）。声音还是 Edge 取。
 */
actual fun localVideoFrames(file: java.io.File, count: Int, maxSide: Int): Pair<List<ByteArray>, Double>? = try {
    VideoFrames.grab(file, count, maxSide)
} catch (e: Throwable) {
    // 本地库加载不了是 UnsatisfiedLinkError / NoClassDefFoundError（都是 Error），也退回 Edge；真正的虚拟机故障照抛
    if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
    null
}

/**
 * 软件里放不了的视频（2026-11-04 实测：微信发来的 HEVC，Windows 没装 HEVC 扩展，Media Foundation 打不开）转成能放的：
 * 视频用 Windows 自带的 H.264 编码器（h264_mf，系统组件，不另外牵扯专利授权），不行退回 FFmpeg 自带的 mpeg4；声音 aac。
 * 成功返回 true。只在电脑上用（手机的 Media3 用系统硬解，HEVC 放得了）。
 */
fun transcodePlayable(src: java.io.File, dst: java.io.File): Boolean {
    for (codec in listOf("h264_mf", "mpeg4")) {
        val ok = runCatching { transcodeWith(src, dst, codec) }.getOrElse { false }
        if (ok) return true
        dst.delete()
    }
    return false
}

private fun transcodeWith(src: java.io.File, dst: java.io.File, codec: String): Boolean {
    org.bytedeco.ffmpeg.global.avutil.av_log_set_level(org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR)
    val g = org.bytedeco.javacv.FFmpegFrameGrabber(src)
    g.start()
    try {
        val w = g.imageWidth.let { it - it % 2 }
        val h = g.imageHeight.let { it - it % 2 }
        if (w <= 0 || h <= 0) return false
        val r = org.bytedeco.javacv.FFmpegFrameRecorder(dst, w, h, if (g.audioChannels > 0) g.audioChannels else 0)
        r.format = "mp4"
        r.videoCodecName = codec
        r.frameRate = if (g.frameRate in 1.0..120.0) g.frameRate else 30.0
        r.videoBitrate = maxOf(1_500_000, minOf(8_000_000, w * h * 4))
        r.pixelFormat = org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12.takeIf { codec == "h264_mf" } ?: org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
        if (codec == "mpeg4") r.videoQuality = 3.0
        if (g.audioChannels > 0) {
            r.audioCodec = org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC
            r.sampleRate = if (g.sampleRate > 0) g.sampleRate else 44100
            r.audioBitrate = 128_000
        }
        // 竖拍的手机视频带旋转标记：照原样带过去
        g.getVideoMetadata("rotate")?.let { r.setVideoMetadata("rotate", it) }
        r.start()
        try {
            var frames = 0
            while (true) {
                val f = g.grab() ?: break
                if (f.image != null) frames++
                r.record(f)
            }
            return frames > 0
        } finally {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
    } finally {
        runCatching { g.stop() }
        runCatching { g.release() }
    }
}

/** 用 FFmpeg（bytedeco javacv）按时间均匀取 count 张画面，JPEG，长边不超过 maxSide。 */
internal object VideoFrames {
    fun grab(file: java.io.File, count: Int, maxSide: Int): Pair<List<ByteArray>, Double>? {
        org.bytedeco.ffmpeg.global.avutil.av_log_set_level(org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR)
        val g = org.bytedeco.javacv.FFmpegFrameGrabber(file)
        try {
            g.start()
            if (g.imageWidth <= 0 || g.imageHeight <= 0) return null
            val seconds = g.lengthInTime / 1_000_000.0
            val conv = org.bytedeco.javacv.Java2DFrameConverter()
            val out = mutableListOf<ByteArray>()
            for (i in 0 until count) {
                if (seconds > 0) g.setVideoTimestamp((seconds * (i + 0.5) / count * 1_000_000).toLong())
                val frame = g.grabImage() ?: continue
                val img = conv.convert(frame) ?: continue
                out += jpegOf(img, maxSide)
            }
            return if (out.isEmpty()) null else out to seconds
        } finally {
            runCatching { g.stop() }
            runCatching { g.release() }
        }
    }

    private fun jpegOf(src: java.awt.image.BufferedImage, maxSide: Int): ByteArray {
        val s = minOf(1.0, maxSide.toDouble() / maxOf(src.width, src.height))
        val w = maxOf(1, (src.width * s).toInt()); val h = maxOf(1, (src.height * s).toInt())
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        gr.drawImage(src, 0, 0, w, h, null)
        gr.dispose()
        return java.io.ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }
}

/**
 * 声音也用程序带的 FFmpeg 解（2026-11-04 加音频附件：mp3、m4a、amr、wav……都要听写）：重采样成 16k 单声道 16 位 PCM。
 * 解不了返回 null，视频照旧退回 Edge 取声音。
 */
actual fun localVideoAudio(file: java.io.File, maxSeconds: Int): Pair<ByteArray, Double>? = try {
    org.bytedeco.ffmpeg.global.avutil.av_log_set_level(org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR)
    val g = org.bytedeco.javacv.FFmpegFrameGrabber(file)
    try {
        g.sampleRate = 16000
        g.audioChannels = 1
        g.sampleFormat = org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
        g.start()
        if (g.audioChannels <= 0) null else {
            val seconds = g.lengthInTime / 1_000_000.0
            val out = java.io.ByteArrayOutputStream()
            val limit = maxSeconds.toLong() * 16000 * 2
            while (out.size() < limit) {
                val f = g.grabSamples() ?: break
                val buf = f.samples?.firstOrNull() as? java.nio.ShortBuffer ?: continue
                val n = buf.remaining()
                val bb = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(buf.get(buf.position() + i))
                out.write(bb.array())
            }
            val pcm = out.toByteArray().let { if (it.size > limit) it.copyOf(limit.toInt()) else it }
            if (pcm.isEmpty()) null else pcm to (if (seconds > 0) seconds else pcm.size / 32000.0)
        }
    } finally {
        runCatching { g.stop() }
        runCatching { g.release() }
    }
} catch (e: Throwable) {
    if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
    null
}

private val home get() = java.io.File(System.getProperty("user.home"))

/** 文档文件夹可能被 OneDrive 接管（「OneDrive\文档」），两种都看。 */
private fun documentsDirs() = listOf("Documents", "OneDrive/Documents", "OneDrive/文档").map { java.io.File(home, it) }.filter { it.isDirectory }

actual fun defaultDocRoots(): List<java.io.File> =
    (documentsDirs() + listOf("Desktop", "OneDrive/Desktop", "OneDrive/桌面", "Downloads").map { java.io.File(home, it) })
        .filter { it.isDirectory }
        .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.path) }

actual fun weixinDocRoots(): List<java.io.File> {
    val out = mutableListOf<java.io.File>()
    for (d in documentsDirs()) {
        // 微信 3.x
        java.io.File(d, "WeChat Files").listFiles()?.filter { it.isDirectory }?.forEach { acc ->
            java.io.File(acc, "FileStorage/File").takeIf { it.isDirectory }?.let(out::add)
        }
        // 微信 4.x
        java.io.File(d, "xwechat_files").listFiles()?.filter { it.isDirectory }?.forEach { acc ->
            java.io.File(acc, "msg/file").takeIf { it.isDirectory }?.let(out::add)
        }
    }
    return out
}

actual fun docAccessMissing(): Boolean = false

actual fun extractPdfText(bytes: ByteArray): Pair<String?, String> {
    Loader.loadPDF(bytes).use { doc ->
        val text = PDFTextStripper().getText(doc).trim()
        return if (text.length < 20 && doc.numberOfPages > 0)
            null to "这个 PDF 是扫描件（${doc.numberOfPages} 页图片），抽不出文字；可以截图后按图片发送"
        else text to "${doc.numberOfPages} 页"
    }
}

actual fun defaultSaveFolder(): java.io.File? = java.io.File(documentsDirs().firstOrNull() ?: java.io.File(home, "Documents"), "AI集训营")

/** 电脑上不量（同步由手机发起、手机那边避让）；电脑一般是宽带，不按流量算。 */
actual fun networkLoad(): NetLoad = NetLoad(-1, false)

actual fun platformLog(tag: String, text: String) {}
