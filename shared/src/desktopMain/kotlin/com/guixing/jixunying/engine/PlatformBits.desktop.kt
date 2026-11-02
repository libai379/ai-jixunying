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

/** 电脑上视频交给 Edge 解码（PageBrowser.fileFrames / fileAudioPcm），这里不自己解。 */
actual fun localVideoFrames(file: java.io.File, count: Int, maxSide: Int): Pair<List<ByteArray>, Double>? = null

actual fun localVideoAudio(file: java.io.File, maxSeconds: Int): Pair<ByteArray, Double>? = null

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
