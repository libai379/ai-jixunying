package com.guixing.jixunying.engine

/** 长边超过 maxSide 或体积超过 maxBytes 时压成 JPEG（发给模型前省流量和 token）；不需要压返回 null。 */
expect fun shrinkImageToJpeg(bytes: ByteArray, maxSide: Int, maxBytes: Int): ByteArray?

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
