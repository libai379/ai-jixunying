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

actual fun extractPdfText(bytes: ByteArray): Pair<String?, String> {
    Loader.loadPDF(bytes).use { doc ->
        val text = PDFTextStripper().getText(doc).trim()
        return if (text.length < 20 && doc.numberOfPages > 0)
            null to "这个 PDF 是扫描件（${doc.numberOfPages} 页图片），抽不出文字；可以截图后按图片发送"
        else text to "${doc.numberOfPages} 页"
    }
}
