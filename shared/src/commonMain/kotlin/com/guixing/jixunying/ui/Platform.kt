package com.guixing.jixunying.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * 用户没给麦克风权限时 startRecording 返回的这句话。
 * 界面认这一句（不要改字），好在提示条上多给一个「去设置」按钮（安卓的 MainActivity 也用它）。
 */
const val NO_RECORD_PERMISSION = "没有录音权限：去 系统设置 → 应用 → AI集训营 → 权限 里打开麦克风"

/** 各平台各自实现的能力：选文件、解码图片、存文件、开网页、二维码、本地小配置。 */
interface Platform {
    val isDesktop: Boolean
    val deviceName: String
    suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile>
    fun decodeImage(bytes: ByteArray): ImageBitmap?
    /** App 图标（左上角、「关于」页用，和启动图标同一张，tools/make_icons.py 生成）；没有就画老的「集」字方块。 */
    val brandIcon: ImageBitmap? get() = null
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
    /** 这个路径上有没有一个文件（设置 → Code 栏 填的外壳程序对不对；手机上是只读的，用不到）。 */
    fun fileExists(path: String): Boolean = false
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

    /**
     * 应用现在在不在前台（2026-10-13，Code 栏的通知用）：不在前台时才弹系统通知。
     * 电脑上看窗口有没有在焦点上；不认识「前台」的台面（测试、截图）都当前台，这样不会平白弹通知。
     */
    val inForeground: Boolean get() = true

    /**
     * 弹一条系统通知（Code 栏某一步开始等批准、或者一条回答做完了）。id 相同的会盖掉旧的。
     * convId 是这条通知说的是哪个 Code 会话：点通知要能打开应用并切到那个会话（见 openConvRequest）。
     * 默认什么都不做（不认识通知的平台、测试里）。
     */
    fun notify(id: String, title: String, text: String, convId: String) {}

    /** 要用通知之前问一下权限（安卓 13 起要用户同意）。在用户正操作的时候调（应用在前台，弹窗出得来）。默认什么都不做。 */
    fun askNotifyPermission() {}

    /**
     * 点了通知要打开哪个 Code 会话（界面消费掉以后调 clearOpenConvRequest）。默认一直没有。
     * 手机上是 MainActivity 从通知的 Intent 里取出来，电脑上是托盘图标被点了。
     */
    val openConvRequest: kotlinx.coroutines.flow.StateFlow<String?> get() = NoConvRequest
    fun clearOpenConvRequest() {}
    /** 这台设备能不能放声音（回答下面「读出来」按钮、设置里的「试听」）。默认不能。 */
    val canPlayAudio: Boolean get() = false
    /**
     * 放一段 wav（16 位 PCM），放完才返回。
     * 协程被取消时（点了「停止」）要马上停下、把放音设备放掉，不要再出声。
     */
    suspend fun playWav(bytes: ByteArray) {}
    /**
     * 写一行诊断日志（不进界面）。安卓写进系统日志（插线后 adb logcat -s JxySpeech 能看），电脑上什么都不做。
     * 2026-10-13 加：用户报「读出来有的只读第一句就卡住」，模拟器上复现不出来，留着日志等真机插线时看。
     */
    fun log(tag: String, text: String) {}
    /** 这台设备能不能录音（输入框旁边的话筒按钮）。默认不能。 */
    val canRecord: Boolean get() = false
    /**
     * 开始录音。成功返回 null；不行就返回一句人话（没有麦克风、没有权限……），界面直接把这句话弹出来。
     * 要权限的平台在这里问用户（第一次点话筒时）。
     */
    suspend fun startRecording(): String? = "这台设备不能录音"
    /** 停下并取出刚才录的：wav（16000 Hz、单声道、16 位）。没录到东西返回 null。 */
    suspend fun stopRecording(): ByteArray? = null
    /** 不要这段录音了（离开对话、切到别的页面时用）。 */
    fun cancelRecording() {}
}

private val NoIncoming = kotlinx.coroutines.flow.MutableStateFlow<List<PickedFile>>(emptyList())
private val NoConvRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

val LocalPlatform = staticCompositionLocalOf<Platform> { error("Platform not provided") }
