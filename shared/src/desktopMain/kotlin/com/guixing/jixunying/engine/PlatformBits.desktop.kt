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
