package com.guixing.jixunying

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 拖进来的东西怎么认（2026-10-17，用户：「拖进去没动、第二次失败……把所有限制全部删掉，随意加任何文件」）。
 * 真拖要动用户的鼠标，这里用假的 Transferable 把几种来源走一遍：资源管理器的文件列表（含文件夹、大文件）、
 * 网址列表、一段是路径的文字、从浏览器 / 微信拖来的图片。
 */
class DropTest {
    private val tmp = kotlin.io.path.createTempDirectory("jxy-drop").toFile()

    @AfterTest
    fun cleanup() { tmp.deleteRecursively() }

    private class Fake(private val data: Map<DataFlavor, Any>) : Transferable {
        override fun getTransferDataFlavors() = data.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in data
        override fun getTransferData(flavor: DataFlavor) = data[flavor] ?: throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
    }

    private val platform = DesktopPlatform { null }

    @Test
    fun fileListKeepsFoldersAndBigFiles() {
        val small = File(tmp, "a.txt").apply { writeText("hi") }
        val dir = File(tmp, "folder").apply { mkdirs() }
        val big = File(tmp, "big.bin").also { java.io.RandomAccessFile(it, "rw").use { f -> f.setLength(60L * 1024 * 1024) } }
        val got = platform.readTransfer(Fake(mapOf(DataFlavor.javaFileListFlavor to listOf(small, dir, big))))
        assertEquals(3, got.size, "文件夹、大文件都不跳过")
        assertEquals("hi", got[0].bytes.toString(Charsets.UTF_8))
        assertTrue(got[1].pathOnly && got[1].path == dir.absolutePath, "文件夹只给路径")
        assertTrue(got[2].pathOnly && got[2].bytes.isEmpty(), "大文件不读内容，只给路径")
    }

    @Test
    fun uriListAndPathTextAndImage() {
        val f = File(tmp, "b.md").apply { writeText("x") }
        val uri = DataFlavor("text/uri-list;class=java.lang.String")
        assertEquals(f.absolutePath, platform.readTransfer(Fake(mapOf(uri to f.toURI().toString() + "\r\n"))).single().path)
        assertEquals(f.absolutePath, platform.readTransfer(Fake(mapOf(DataFlavor.stringFlavor to "\"${f.absolutePath}\""))).single().path)
        val img = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val got = platform.readTransfer(Fake(mapOf(DataFlavor.imageFlavor to img))).single()
        assertEquals("image/png", got.mime)
        assertTrue(got.bytes.isNotEmpty())
        // 不是文件、不是图片、也不是路径的文字：认不出来，交给界面说
        assertTrue(platform.readTransfer(Fake(mapOf(DataFlavor.stringFlavor to "随便一段字"))).isEmpty())
    }
}
