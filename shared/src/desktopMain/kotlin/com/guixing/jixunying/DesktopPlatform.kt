package com.guixing.jixunying

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.guixing.jixunying.ui.PickedFile
import com.guixing.jixunying.ui.Platform
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.composed
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.nio.file.Files

/** fileLog：诊断日志写不写进数据目录（测试里关掉，2026-10-17 测试把日志写进了用户真的数据目录）。 */
class DesktopPlatform(private val fileLog: Boolean = true, private val window: () -> Frame?) : Platform {
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
            files.filter { it.isFile }.map { f -> PickedFile(f.name, mimeOf(f), f.readBytes(), f.absolutePath) }
        }
    }

    private fun mimeOf(f: File): String = runCatching { Files.probeContentType(f.toPath()) }.getOrNull() ?: "application/octet-stream"

    /**
     * 读拖进来 / 粘贴进来的文件。2026-10-17 用户：「把所有限制全部删掉，随意加任何文件」——
     * 不再跳过任何东西：文件夹、超过 50MB 的文件、读不了的（被占用、没权限）都照样交出去，只是不读内容（pathOnly），
     * 用的地方按路径处理（Code 栏直接把路径给 AI）。一个文件出错不连累别的。
     */
    private fun readFiles(list: List<*>): List<PickedFile> = list.mapNotNull { x ->
        val f = when (x) {
            is File -> x
            is java.nio.file.Path -> x.toFile()
            is String -> File(x)
            else -> null
        } ?: return@mapNotNull null
        if (f.isDirectory || f.length() > 50L * 1024 * 1024) return@mapNotNull PickedFile(f.name.ifBlank { f.path }, if (f.isDirectory) "inode/directory" else mimeOf(f), ByteArray(0), f.absolutePath, pathOnly = true)
        val bytes = runCatching { f.readBytes() }.onFailure { log("JxyDrop", "读不了 ${f.absolutePath}：$it") }.getOrNull()
        PickedFile(f.name, mimeOf(f), bytes ?: ByteArray(0), f.absolutePath, pathOnly = bytes == null)
    }

    /** 拖进来的东西能认出哪些：文件列表、网址列表（file:// 的转成文件）、图片（从浏览器 / 微信拖的图）、一段是路径的文字。 */
    internal fun readTransfer(t: java.awt.datatransfer.Transferable): List<PickedFile> {
        val flavors = t.transferDataFlavors.orEmpty()
        log("JxyDrop", "松开：" + flavors.joinToString { it.mimeType.substringBefore(';') }.take(400))
        runCatching {
            if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                val files = readFiles(t.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<*>)
                if (files.isNotEmpty()) return files
            }
        }.onFailure { log("JxyDrop", "文件列表读不了：$it") }
        // text/uri-list：一行一个网址，file:// 的是本机文件
        flavors.firstOrNull { it.mimeType.startsWith("text/uri-list") && it.representationClass == String::class.java }?.let { fl ->
            runCatching {
                val uris = (t.getTransferData(fl) as String).lines().map { it.trim() }.filter { it.startsWith("file:") }
                val files = readFiles(uris.mapNotNull { u -> runCatching { File(java.net.URI(u)) }.getOrNull() })
                if (files.isNotEmpty()) return files
            }.onFailure { log("JxyDrop", "网址列表读不了：$it") }
        }
        runCatching {
            if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                val img = t.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
                return listOf(PickedFile("图片_${stamp()}.png", "image/png", imageToPng(img)))
            }
        }.onFailure { log("JxyDrop", "图片读不了：$it") }
        runCatching {
            if (t.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                val s = (t.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as String).trim().removeSurrounding("\"")
                val paths = s.lines().map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() && File(it).exists() }
                if (paths.isNotEmpty()) return readFiles(paths)
            }
        }.onFailure { log("JxyDrop", "文字读不了：$it") }
        return emptyList()
    }

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

    /**
     * 拖文件进窗口（2026-10-17 第二次改）。
     * 用户实测：从资源管理器拖进来、松了手，四次里只有一次送到了（日志里三次只有「开始拖」没有「松开」）。
     * 以前走的是 Compose 的 dragAndDropTarget；它架在 AWT 上，Windows 资源管理器默认是「移动」，Compose 那层没接成就不交「松开」
     * （Kotlin Slack 上有人报过同样的事：光标在加号和禁止之间跳，禁止时 onDrop 不来，
     * https://slack-chats.kotlinlang.org/t/34310586/are-there-any-apis-for-file-dropping-like-dragging-a-file-fr ，没有解决办法）。
     * 现在不走 Compose 那层：直接在窗口的每个 AWT 组件上装我们自己的 DropTarget，进来就按「复制」收下（acceptDrag / acceptDrop），
     * 松开时交给当前最上面那个页面（聊天或 Code，后注册的在上面）。Compose 自己的拖放我们没用到（输入框是旧式的，不带拖放）。
     * 拿不到窗口（截图、测试）时什么都不做。
     */
    override fun fileDropTarget(modifier: androidx.compose.ui.Modifier, onHover: (Boolean) -> Unit, onFiles: (List<PickedFile>) -> Unit): androidx.compose.ui.Modifier =
        modifier.then(androidx.compose.ui.Modifier.composed {
            val h = androidx.compose.runtime.remember { DropHandler() }
            h.onHover = onHover
            h.onFiles = onFiles
            androidx.compose.runtime.DisposableEffect(Unit) {
                dropHandlers.add(h)
                installWindowDrop()
                onDispose { dropHandlers.remove(h) }
            }
            androidx.compose.ui.Modifier
        })

    private class DropHandler {
        @Volatile var onHover: (Boolean) -> Unit = {}
        @Volatile var onFiles: (List<PickedFile>) -> Unit = {}
    }

    /** 正在显示、能收拖放的页面；最后一个是最上面的。 */
    private val dropHandlers = java.util.concurrent.CopyOnWriteArrayList<DropHandler>()

    private val windowDrop = object : java.awt.dnd.DropTargetAdapter() {
        override fun dragEnter(e: java.awt.dnd.DropTargetDragEvent) {
            e.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
            log("JxyDrop", "开始拖：" + runCatching { e.currentDataFlavors.joinToString { it.mimeType.substringBefore(';') } }.getOrDefault("?").take(400))
            dropHandlers.lastOrNull()?.onHover?.invoke(true)
        }
        override fun dragOver(e: java.awt.dnd.DropTargetDragEvent) = e.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
        override fun dropActionChanged(e: java.awt.dnd.DropTargetDragEvent) = e.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
        override fun dragExit(e: java.awt.dnd.DropTargetEvent) { dropHandlers.lastOrNull()?.onHover?.invoke(false) }
        override fun drop(e: java.awt.dnd.DropTargetDropEvent) {
            val h = dropHandlers.lastOrNull()
            h?.onHover?.invoke(false)
            // 一律按「复制」收下；内容要在这里（松开的这一刻）取出来，过了这一刻系统就收回了
            e.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
            val files = runCatching { readTransfer(e.transferable) }.onFailure { log("JxyDrop", "读拖进来的东西出错：$it") }.getOrDefault(emptyList())
            e.dropComplete(true)
            log("JxyDrop", "认出 ${files.size} 个：" + files.joinToString { "${it.path.ifBlank { it.name }}（${it.bytes.size} 字节${if (it.pathOnly) "，只给路径" else ""}）" }.take(800) +
                if (h == null) "（这一页不收文件）" else "")
            if (h != null) dropScope.launch { withContext(Dispatchers.Swing) { h.onFiles(files) } }
        }
    }

    @Volatile private var dropTimer: javax.swing.Timer? = null

    /** 给窗口里每个组件装上我们的 DropTarget（Compose 自己装的换掉）；之后每 2 秒查一次，新冒出来的组件也装上。 */
    private fun installWindowDrop() {
        java.awt.EventQueue.invokeLater {
            val w = window() ?: return@invokeLater
            fun apply(c: java.awt.Component) {
                val dt = c.dropTarget
                if (dt == null || dt !is OurDropTarget) runCatching { c.dropTarget = OurDropTarget(c, windowDrop) }
                if (c is java.awt.Container) c.components.forEach { apply(it) }
            }
            apply(w)
            if (dropTimer == null) dropTimer = javax.swing.Timer(2000) { window()?.let { apply(it) } }.apply { start() }
        }
    }

    private class OurDropTarget(c: java.awt.Component, l: java.awt.dnd.DropTargetListener) :
        java.awt.dnd.DropTarget(c, java.awt.dnd.DnDConstants.ACTION_COPY_OR_MOVE or java.awt.dnd.DnDConstants.ACTION_LINK, l, true)

    private val dropScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * 诊断日志（2026-10-17：用户报「拖进去没动、第二次失败」，我碰不了用户的屏幕，只能留日志等用户再拖一次看）。
     * 写在 <数据目录>\logs\jxy.log，超过 2MB 就从头写。不写文件内容、不写 Key。
     */
    override fun log(tag: String, text: String) {
        if (!fileLog) return
        runCatching {
            val dir = File(System.getProperty("jxy.data") ?: (System.getenv("APPDATA") ?: (System.getProperty("user.home") + "/.config")) + "/ai-jixunying", "logs")
            dir.mkdirs()
            val f = File(dir, "jxy.log")
            if (f.length() > 2L * 1024 * 1024) f.writeText("")
            f.appendText("${java.time.LocalDateTime.now().toString().take(19)} [$tag] $text\n", Charsets.UTF_8)
        }
    }

    override fun decodeImage(bytes: ByteArray): ImageBitmap? = runCatching {
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()

    override val brandIcon: ImageBitmap? by lazy { loadBrandIcon() }

    companion object {
        /** shared/src/desktopMain/resources/jxy-app-icon.png（tools/make_icons.py 生成）；窗口图标也用它。 */
        fun loadBrandIcon(): ImageBitmap? = runCatching {
            DesktopPlatform::class.java.getResourceAsStream("/jxy-app-icon.png")?.use { it.readBytes() }
                ?.let { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }
        }.getOrNull()
    }

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
    override fun fileExists(path: String): Boolean = runCatching { File(path.trim()).isFile }.getOrDefault(false)

    // ---- Code 栏的系统通知（第二期 2026-10-13，设计说明第 1 节）：用系统托盘的气泡。
    //      托盘不支持（有些精简版 Windows、无桌面会话）就什么都不做，绝不抛出来影响主流程。

    private val convRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override val openConvRequest: kotlinx.coroutines.flow.StateFlow<String?> get() = convRequest
    override fun clearOpenConvRequest() { convRequest.value = null }

    /** 应用在不在前台：窗口有没有焦点。窗口还没建好（启动那一下）当前台，免得平白弹通知。 */
    override val inForeground: Boolean get() = runCatching { window()?.isActive ?: true }.getOrDefault(true)

    /** 最后一条通知说的是哪个会话：点托盘图标就打开它。 */
    private var noticeConv: String? = null
    private var tray: java.awt.TrayIcon? = null

    override fun notify(id: String, title: String, text: String, convId: String) {
        noticeConv = convId
        runCatching {
            val t = tray ?: makeTray() ?: return@runCatching
            t.displayMessage(title, text, java.awt.TrayIcon.MessageType.NONE)
        }
    }

    /** 建托盘图标（第一次弹通知时建，之后一直用这一个）。建不出来返回 null。 */
    private fun makeTray(): java.awt.TrayIcon? = runCatching {
        if (!java.awt.SystemTray.isSupported()) return null
        val img = DesktopPlatform::class.java.getResourceAsStream("/jxy-app-icon.png")?.use { javax.imageio.ImageIO.read(it) }
            ?: java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val t = java.awt.TrayIcon(img, "AI集训营")
        t.isImageAutoSize = true
        // 点一下：把窗口叫到前面，并切到通知说的那个会话（界面在 App 里收）
        t.addActionListener {
            noticeConv?.let { convRequest.value = it }
            runCatching {
                window()?.apply { isVisible = true; state = Frame.NORMAL; toFront() }
            }
        }
        java.awt.SystemTray.getSystemTray().add(t)
        tray = t
        t
    }.getOrNull()

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

    // ---- 读出来（放音）和听写（录音）：用 Java 自带的 javax.sound。
    //      统一是 wav、16 位 PCM；录的时候 16000 Hz 单声道（设计说明第 5 节）。

    override val canPlayAudio = true

    /**
     * 放音在单独的线程里跑，协程被取消（点了「停止」）时从这边把 line 停掉、关掉：
     * javax.sound 的文档写明 line 被停止 / 关闭时 write 不再阻塞、drain 也会返回，
     * 所以缓冲里剩下的那一截不会再接着响（以前在协程里直接 drain，点停止还要等它放完，主会话审查 10-12 改）。
     */
    override suspend fun playWav(bytes: ByteArray) {
        val (stream, line) = withContext(Dispatchers.IO) {
            val s = javax.sound.sampled.AudioSystem.getAudioInputStream(java.io.ByteArrayInputStream(bytes))
            val l = try {
                javax.sound.sampled.AudioSystem.getSourceDataLine(s.format).also { it.open(s.format) }
            } catch (e: Throwable) {
                runCatching { s.close() }
                throw e
            }
            s to l
        }
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        fun shut() {
            runCatching { line.stop() }
            runCatching { line.flush() }
            runCatching { line.close() }
            runCatching { stream.close() }
        }
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { stopped.set(true); shut() }
            Thread({
                val result = runCatching {
                    line.start()
                    val buf = ByteArray(8192)
                    while (!stopped.get()) {
                        // 有的 wav 读到尾巴会直接抛，当成读完了
                        val n = runCatching { stream.read(buf, 0, buf.size) }.getOrDefault(-1)
                        if (n <= 0) break
                        line.write(buf, 0, n)
                    }
                    if (!stopped.get()) line.drain() // 等最后一块放完才返回
                }
                shut()
                // 已经取消的不用再回（取消时协程那边已经结束了）
                if (cont.isActive) result.fold({ cont.resume(Unit) }, { cont.resumeWithException(it) })
            }, "jxy-play").apply { isDaemon = true; start() }
        }
    }

    override val canRecord = true

    /** 正在录的这段：后台线程一边读一边攒在内存里，停下来时再拼 wav 头。 */
    private class Recording(val line: javax.sound.sampled.TargetDataLine, val out: java.io.ByteArrayOutputStream) {
        @Volatile var stopped = false
        var thread: Thread? = null
    }

    private var recording: Recording? = null

    override suspend fun startRecording(): String? {
        if (recording != null) return null // 已经在录了
        val format = javax.sound.sampled.AudioFormat(16000f, 16, 1, true, false)
        val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine::class.java, format)
        if (!javax.sound.sampled.AudioSystem.isLineSupported(info)) return "这台电脑上没找到麦克风（没有能录音的设备）"
        val line = try {
            javax.sound.sampled.AudioSystem.getLine(info) as javax.sound.sampled.TargetDataLine
        } catch (e: Throwable) {
            return "打不开麦克风：${e.message ?: e::class.simpleName}"
        }
        val out = java.io.ByteArrayOutputStream()
        val rec = Recording(line, out)
        try {
            line.open(format)
            line.start()
        } catch (e: Throwable) {
            runCatching { line.close() }
            return "打不开麦克风（可能被别的程序占着）：${e.message ?: e::class.simpleName}"
        }
        rec.thread = Thread({
            val buf = ByteArray(4096)
            while (!rec.stopped) {
                val n = runCatching { line.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n > 0) out.write(buf, 0, n) else if (n < 0) break // 关了以后 read 会返回 -1，收工
            }
        }, "jxy-record").also {
            it.isDaemon = true
            it.start()
        }
        recording = rec
        return null
    }

    override suspend fun stopRecording(): ByteArray? = withContext(Dispatchers.IO) {
        val rec = recording ?: return@withContext null
        recording = null
        rec.stopped = true
        runCatching { rec.line.stop() }
        runCatching { rec.line.close() } // 关掉，后台线程的 read 才会返回
        rec.thread?.join(2000)
        val data = rec.out.toByteArray() // 线程已经收工，这会儿读是安全的
        if (data.isEmpty()) null else com.guixing.jixunying.engine.SpeechText.wavOf(16000, 1, 16, data)
    }

    override fun cancelRecording() {
        val rec = recording ?: return
        recording = null
        rec.stopped = true
        runCatching { rec.line.stop() }
        runCatching { rec.line.close() }
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

