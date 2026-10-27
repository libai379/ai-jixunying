package com.guixing.jixunying.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream

/** 安卓上引擎要用到的 Context（读 PDF 字体资源）。Application.onCreate 里设置。 */
object AndroidEnv {
    @Volatile var context: Context? = null
        set(value) {
            field = value
            if (value != null) runCatching { PDFBoxResourceLoader.init(value) }
        }
}

actual fun shrinkImageToJpeg(bytes: ByteArray, maxSide: Int, maxBytes: Int): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val side = maxOf(bounds.outWidth, bounds.outHeight)
    if (side <= 0) return null
    if (side <= maxSide && bytes.size < maxBytes) return null
    var sample = 1
    while (side / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = minOf(1.0, maxSide.toDouble() / maxOf(decoded.width, decoded.height))
    val bmp = if (scale < 1.0) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
}

@Suppress("DEPRECATION")
private val storageRoot get() = android.os.Environment.getExternalStorageDirectory()

actual fun defaultDocRoots(): List<java.io.File> =
    if (docAccessMissing()) emptyList() else listOf(storageRoot).filter { it.isDirectory && it.canRead() }

/** 微信「保存到手机」的文件在 Download/WeiXin；收到但没保存的在微信自己的目录里，别的 App 读不到。 */
actual fun weixinDocRoots(): List<java.io.File> =
    listOf("Download/WeiXin", "Download/WeChat", "Documents/WeiXin").map { java.io.File(storageRoot, it) }.filter { it.isDirectory }

actual fun docAccessMissing(): Boolean =
    android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()

actual fun extractPdfText(bytes: ByteArray): Pair<String?, String> {
    PDDocument.load(bytes).use { doc ->
        val text = PDFTextStripper().getText(doc).trim()
        return if (text.length < 20 && doc.numberOfPages > 0)
            null to "这个 PDF 是扫描件（${doc.numberOfPages} 页图片），抽不出文字；可以截图后按图片发送"
        else text to "${doc.numberOfPages} 页"
    }
}

actual fun defaultSaveFolder(): java.io.File? = null

private var lastTraffic: Triple<Long, Long, Long>? = null

/**
 * 整机收发字节减去我们自己（TrafficStats，按 uid 算），和上一次量的比出每秒多少；第一次量返回 -1。
 * 按流量计费看 ConnectivityManager.isActiveNetworkMetered（手机流量、标了计费的 Wi-Fi 都算）。
 */
actual fun networkLoad(): NetLoad {
    val ctx = AndroidEnv.context
    val metered = runCatching {
        (ctx?.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager)?.isActiveNetworkMetered ?: false
    }.getOrDefault(false)
    val uid = android.os.Process.myUid()
    val all = android.net.TrafficStats.getTotalRxBytes() + android.net.TrafficStats.getTotalTxBytes()
    val mine = android.net.TrafficStats.getUidRxBytes(uid) + android.net.TrafficStats.getUidTxBytes(uid)
    val now = android.os.SystemClock.elapsedRealtime()
    if (all < 0 || mine < 0) return NetLoad(-1, metered)
    val prev = lastTraffic
    lastTraffic = Triple(now, all, mine)
    if (prev == null || now - prev.first < 200) return NetLoad(-1, metered)
    val other = ((all - prev.second) - (mine - prev.third)).coerceAtLeast(0)
    return NetLoad(other * 1000 / (now - prev.first), metered)
}

actual fun platformLog(tag: String, text: String) {
    runCatching { android.util.Log.i(if (tag.startsWith("Jxy")) tag else "Jxy$tag", text.take(3000)) }
}
