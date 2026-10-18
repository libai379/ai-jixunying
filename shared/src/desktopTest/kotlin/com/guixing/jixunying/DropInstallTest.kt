package com.guixing.jixunying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.guixing.jixunying.ui.PickedFile
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-10-17 第二次改拖放：不走 Compose 的 dragAndDropTarget，在窗口每个 AWT 组件上装自己的 DropTarget。
 * 真拖要动用户的鼠标，不做；这里开一个放在屏幕外、不抢焦点的窗口，确认：
 * ① 窗口里每个组件的 DropTarget 都换成了我们的（Compose 自己装的被换掉）；
 * ② 「松开」交给最上面那个页面，文件原样送到。
 */
class DropInstallTest {
    @Test
    fun windowGetsOurDropTargetAndDeliversFiles() {
        if (GraphicsEnvironment.isHeadless()) { println("没有图形环境，跳过"); return }
        var window: ComposeWindow? = null
        val platform = DesktopPlatform(fileLog = false) { window }
        val got = mutableListOf<List<PickedFile>>()
        EventQueue.invokeAndWait {
            val w = ComposeWindow()
            w.focusableWindowState = false
            w.isAutoRequestFocus = false
            w.setSize(400, 300)
            w.setLocation(-4000, -4000)
            w.setContent { Box(platform.fileDropTarget(Modifier.fillMaxSize(), onHover = {}) { got += it }) }
            w.isVisible = true
            window = w
        }
        try {
            // 装在 invokeLater 里，等它跑完
            repeat(50) { EventQueue.invokeAndWait {}; Thread.sleep(40) }
            val all = mutableListOf<java.awt.Component>()
            fun walk(c: java.awt.Component) { all += c; if (c is java.awt.Container) c.components.forEach { walk(it) } }
            EventQueue.invokeAndWait { walk(window!!) }
            val names = all.map { (it.dropTarget?.javaClass?.simpleName ?: "无") + "@" + it.javaClass.simpleName }
            assertTrue(all.all { it.dropTarget?.javaClass?.simpleName == "OurDropTarget" }, "每个组件都要是我们的：$names")

            // 「松开」：直接调我们装的那个 DropTarget 的处理（真 DropTargetDropEvent 要系统给，这里只验交付这一段）
            val f = File.createTempFile("jxy-drop", ".txt").apply { writeText("hi"); deleteOnExit() }
            val files = platform.readTransfer(object : java.awt.datatransfer.Transferable {
                override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
                override fun isDataFlavorSupported(fl: java.awt.datatransfer.DataFlavor) = fl == java.awt.datatransfer.DataFlavor.javaFileListFlavor
                override fun getTransferData(fl: java.awt.datatransfer.DataFlavor) = listOf(f)
            })
            assertEquals(f.absolutePath, files.single().path)
        } finally {
            EventQueue.invokeAndWait { window?.dispose() }
        }
    }
}
