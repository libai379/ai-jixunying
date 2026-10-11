package com.guixing.jixunying

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 电脑上 Ctrl+V：什么时候当附件、截图怎么变成 PNG（拖文件和真剪贴板要人手试，见 当前任务.md）。 */
class PasteTest {
    @Test
    fun whenPasteIsAFile() {
        assertTrue(pasteAsFiles(files = true, image = false, text = false), "资源管理器里复制的文件")
        assertTrue(pasteAsFiles(files = false, image = true, text = false), "截图")
        assertFalse(pasteAsFiles(files = false, image = true, text = true), "从 Word 复制的字（带一张图）还是粘贴文字")
        assertFalse(pasteAsFiles(files = false, image = false, text = true), "普通文字")
    }

    @Test
    fun screenshotBecomesPng() {
        // 剪贴板给的常常不是 BufferedImage：用一个缩放后的 Image 模拟
        val src = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB)
        src.createGraphics().apply { color = java.awt.Color.RED; fillRect(0, 0, 40, 30); dispose() }
        val scaled = src.getScaledInstance(20, 15, java.awt.Image.SCALE_FAST)
        java.awt.MediaTracker(java.awt.Canvas()).apply { addImage(scaled, 0); waitForAll() }
        val png = imageToPng(scaled)
        assertContentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.copyOf(4))
        val back = javax.imageio.ImageIO.read(png.inputStream())
        assertEquals(20, back.width)
        assertEquals(java.awt.Color.RED.rgb, back.getRGB(5, 5))
    }
}
