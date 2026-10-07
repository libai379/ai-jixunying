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
    fun getPref(key: String): String? = null
    fun setPref(key: String, value: String?) {}
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("Platform not provided") }
