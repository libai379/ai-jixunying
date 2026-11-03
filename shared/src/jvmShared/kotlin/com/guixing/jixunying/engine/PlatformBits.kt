package com.guixing.jixunying.engine

/** 长边超过 maxSide 或体积超过 maxBytes 时压成 JPEG（发给模型前省流量和 token）；不需要压返回 null。 */
expect fun shrinkImageToJpeg(bytes: ByteArray, maxSide: Int, maxBytes: Int): ByteArray?

/** P 图用：解码成像素（不管 EXIF 方向，PhotoEdit.load 会转）；读不了返回 null。 */
expect fun decodePixels(bytes: ByteArray): Pixels?

/** 像素存成图片：jpegQuality 为 null 存 PNG（一个像素都不变），否则存 JPEG。 */
expect fun encodePixels(p: Pixels, jpegQuality: Int? = null): ByteArray

/** 在像素上写字（box 是原图坐标 [左, 上, 右, 下]；align：left / right，默认居中；字的笔画按实际范围对齐）。 */
expect fun drawTextOn(p: Pixels, text: String, box: IntArray, color: Int, sizePx: Int, bold: Boolean, align: String)

/** 在像素上画框（rect）或圈（ellipse）。 */
expect fun drawShapeOn(p: Pixels, shape: String, box: IntArray, color: Int, thickness: Int)

/** PDF 抽文字，返回 (文字, 说明)。电脑用 PDFBox，手机用 PDFBox 的安卓移植版。 */
expect fun extractPdfText(bytes: ByteArray): Pair<String?, String>

/** 默认收录的文档文件夹：电脑是 文档、桌面、下载；手机是整个存储（要有「所有文件访问」权限）。 */
expect fun defaultDocRoots(): List<java.io.File>

/** 微信收到的文件存放的文件夹（电脑：Documents\WeChat Files\*\FileStorage\File、xwechat_files\*\msg\file）。 */
expect fun weixinDocRoots(): List<java.io.File>

/** 手机上还没给「所有文件访问」权限（读不了别的 App 存的文档）。 */
expect fun docAccessMissing(): Boolean

/** AI 做的 Word / Excel 默认另存到哪：电脑是「文档\AI集训营」，手机返回 null（存在应用自己的目录里）。 */
expect fun defaultSaveFolder(): java.io.File?

/**
 * 这台设备上网忙不忙（两端同步避让用，2026-10-27 用户：「后台占的带宽或者流量太大……有其他运行的东西就要学会避让」）。
 * otherBytesPerSec：别的应用最近每秒收发多少字节（不算我们自己）；-1 = 量不了。metered：用的是按流量计费的网（手机流量、热点）。
 */
class NetLoad(val otherBytesPerSec: Long, val metered: Boolean)

expect fun networkLoad(): NetLoad

/**
 * 日志另抄一份到系统日志（安卓 logcat，tag 前加 Jxy）：手机装的是正式包，run-as 读不到 jxy.log，
 * 只读的 adb logcat -d 能看到后台活出错的原因（2026-10-27 手机记忆连续出错 21 次，当时什么也看不到）。电脑上不抄。
 */
expect fun platformLog(tag: String, text: String)

/**
 * 这台设备自己解码视频（手机：系统解码器；电脑：程序带的 FFmpeg，2026-11-03）。按时间均匀取 count 张画面（JPEG，长边不超过 maxSide），返回 (画面, 整段多少秒)。
 * 解不了返回 null，电脑上再交给 Edge（PageBrowser.fileFrames）。
 */
expect fun localVideoFrames(file: java.io.File, count: Int, maxSide: Int): Pair<List<ByteArray>, Double>?

/** 同上，取视频里的声音：16k 单声道 16 位 PCM（最多 maxSeconds 秒），返回 (PCM, 整段多少秒)。没有声轨时 PCM 是空的。电脑返回 null。 */
expect fun localVideoAudio(file: java.io.File, maxSeconds: Int): Pair<ByteArray, Double>?
