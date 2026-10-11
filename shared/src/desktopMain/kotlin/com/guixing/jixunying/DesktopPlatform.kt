package com.guixing.jixunying

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.guixing.jixunying.ui.PickedFile
import com.guixing.jixunying.ui.Platform
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.nio.file.Files

class DesktopPlatform(private val window: () -> Frame?) : Platform {
    override val isDesktop = true
    override val deviceName: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("电脑")

    override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(window(), if (imagesOnly) "选择图片" else "选择文件", FileDialog.LOAD)
        dialog.isMultipleMode = true
        if (imagesOnly) dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") }
        dialog.isVisible = true
        dialog.files.orEmpty().toList()
    }.let { files ->
        withContext(Dispatchers.IO) {
            files.filter { it.isFile }.map { f -> PickedFile(f.name, mimeOf(f), f.readBytes()) }
        }
    }

    private fun mimeOf(f: File): String = runCatching { Files.probeContentType(f.toPath()) }.getOrNull() ?: "application/octet-stream"

    /** 读拖进来 / 粘贴进来的文件：文件夹跳过，单个超过 50MB 的跳过（上传也传不了那么大）。 */
    private fun readFiles(list: List<*>): List<PickedFile> = list.filterIsInstance<File>()
        .filter { it.isFile && it.length() <= 50L * 1024 * 1024 }
        .map { f -> PickedFile(f.name, mimeOf(f), f.readBytes()) }

    private fun stamp() = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MMdd_HHmmss"))

    override fun clipboardHasFiles(): Boolean = runCatching {
        val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        pasteAsFiles(cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor),
            cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor), cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor))
    }.getOrDefault(false)

    override suspend fun clipboardFiles(): List<PickedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            if (cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                readFiles(cb.getData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>)
            } else {
                val img = cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
                listOf(PickedFile("截图_${stamp()}.png", "image/png", imageToPng(img)))
            }
        }.getOrDefault(emptyList())
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun fileDropTarget(modifier: androidx.compose.ui.Modifier, onHover: (Boolean) -> Unit, onFiles: (List<PickedFile>) -> Unit): androidx.compose.ui.Modifier =
        modifier.then(androidx.compose.ui.Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { e -> e.awtTransferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor) },
            target = object : androidx.compose.ui.draganddrop.DragAndDropTarget {
                override fun onEntered(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = onHover(true)
                override fun onExited(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = onHover(false)
                override fun onEnded(event: androidx.compose.ui.draganddrop.DragAndDropEvent) = onHover(false)
                override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                    onHover(false)
                    val list = runCatching { event.awtTransferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*> }.getOrNull() ?: return false
                    dropScope.launch {
                        val files = readFiles(list)
                        withContext(Dispatchers.Swing) { onFiles(files) }
                    }
                    return true
                }
            },
        ))

    private val dropScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    override fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

    override suspend fun saveFile(name: String, bytes: ByteArray): Boolean {
        val target = withContext(Dispatchers.Swing) {
            val dialog = FileDialog(window(), "保存", FileDialog.SAVE)
            dialog.file = name
            dialog.isVisible = true
            if (dialog.file == null) null else File(dialog.directory, dialog.file)
        } ?: return false
        return withContext(Dispatchers.IO) { runCatching { target.writeBytes(bytes) }.isSuccess }
    }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun qrMatrix(text: String): List<BooleanArray>? = runCatching {
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 0, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints)
        List(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y) } }
    }.getOrNull()

    override fun openFile(path: String): Boolean = runCatching { Desktop.getDesktop().open(File(path)); true }.getOrDefault(false)

    override suspend fun openBytes(name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(System.getProperty("java.io.tmpdir"), "AI集训营").apply { mkdirs() }
            val f = File(dir, name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
            f.writeBytes(bytes)
            openFile(f.absolutePath)
        }.getOrDefault(false)
    }

    override suspend fun pickFolder(title: String): String? = withContext(Dispatchers.Swing) {
        // 文件夹选择框用 Windows 自己的样式，别用 Java 默认那套
        runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
        val chooser = javax.swing.JFileChooser().apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = title
        }
        if (chooser.showOpenDialog(window()) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.path else null
    }

    private val prefs = java.util.prefs.Preferences.userRoot().node("ai-jixunying")
    override fun getPref(key: String): String? = prefs.get(key, null)
    override fun setPref(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.put(key, value)
    }
}


/** 剪贴板里的截图（java.awt.Image）转成 PNG。 */
fun imageToPng(img: java.awt.Image): ByteArray {
    val buf = img as? java.awt.image.BufferedImage ?: java.awt.image.BufferedImage(img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB).also {
        val g = it.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
    }
    return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(buf, "png", it) }.toByteArray()
}

/** Ctrl+V 时剪贴板里的东西当不当附件：复制的文件当；截图（只有图没有字）当；有字的（从 Word、网页复制的字常常还带一张图）当文字粘贴。 */
fun pasteAsFiles(files: Boolean, image: Boolean, text: Boolean): Boolean = files || (image && !text)

