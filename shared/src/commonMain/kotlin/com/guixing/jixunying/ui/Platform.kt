package com.guixing.jixunying.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

/** 各平台各自实现的能力：选文件、解码图片、存文件、开网页、二维码、本地小配置。 */
interface Platform {
    val isDesktop: Boolean
    val deviceName: String
    suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile>
    fun decodeImage(bytes: ByteArray): ImageBitmap?
    suspend fun saveFile(name: String, bytes: ByteArray): Boolean
    fun openUrl(url: String)
    /** 把文字编成二维码点阵（电脑端显示配对码用）。 */
    fun qrMatrix(text: String): List<BooleanArray>? = null
    /** 打开摄像头扫二维码（手机端配对用），取消返回 null。 */
    suspend fun scanQr(): String? = null
    /** 安卓的返回键（电脑上没有）。 */
    @androidx.compose.runtime.Composable
    fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}
    fun getPref(key: String): String? = null
    fun setPref(key: String, value: String?) {}
    /** 用系统默认程序打开本机文件。 */
    fun openFile(path: String): Boolean = false
    /** 把收到的文件（比如手机遥控电脑时电脑做的 Word）存成临时文件，再用默认程序打开。 */
    suspend fun openBytes(name: String, bytes: ByteArray): Boolean = false
    /** 选一个文件夹，返回路径；取消返回 null。 */
    suspend fun pickFolder(title: String = "选择文件夹"): String? = null
    /**
     * 剪贴板里是文件（资源管理器里复制的）或者截图：输入框里 Ctrl+V 时当附件，不当文字粘贴。
     * 剪贴板里有文字时不算（从 Word、网页复制的字往往还带一张图，这时用户要的是字）。
     */
    fun clipboardHasFiles(): Boolean = false
    suspend fun clipboardFiles(): List<PickedFile> = emptyList()
    /** 让一块区域能把文件拖进来（电脑）。onHover：拖着文件经过 / 离开；onFiles：松开后读好的文件。手机上原样返回。 */
    fun fileDropTarget(modifier: androidx.compose.ui.Modifier, onHover: (Boolean) -> Unit, onFiles: (List<PickedFile>) -> Unit): androidx.compose.ui.Modifier = modifier
    /** 安卓：打开这个应用的系统设置页（电池设成「不限制」，手机才能一直在后台接微信）。 */
    val canOpenAppSettings: Boolean get() = false
    fun openAppSettings() {}
    /** 安卓：跳到系统设置，让用户给「所有文件访问」权限（读别的 App 存的文档要用）。 */
    fun requestFileAccess() {}
    /** 别的 App 用「打开方式 / 分享」发来的文件（比如在微信里点文件 → 用其他应用打开）。 */
    val incomingFiles: kotlinx.coroutines.flow.StateFlow<List<PickedFile>> get() = NoIncoming
    fun clearIncoming() {}
}

private val NoIncoming = kotlinx.coroutines.flow.MutableStateFlow<List<PickedFile>>(emptyList())

val LocalPlatform = staticCompositionLocalOf<Platform> { error("Platform not provided") }
