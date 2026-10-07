package com.guixing.jixunying.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

class PickedFile(val name: String, val mime: String, val bytes: ByteArray)

class FoundHost(val name: String, val address: String)

/** 各平台各自实现的能力：选文件、解码图片、存文件、开网页、局域网发现、本地小配置。 */
interface Platform {
    val isDesktop: Boolean
    val deviceName: String
    suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile>
    fun decodeImage(bytes: ByteArray): ImageBitmap?
    suspend fun saveFile(name: String, bytes: ByteArray): Boolean
    fun openUrl(url: String)
    suspend fun discoverHosts(): List<FoundHost> = emptyList()
    fun getPref(key: String): String? = null
    fun setPref(key: String, value: String?) {}
}

val LocalPlatform = staticCompositionLocalOf<Platform> { error("Platform not provided") }
