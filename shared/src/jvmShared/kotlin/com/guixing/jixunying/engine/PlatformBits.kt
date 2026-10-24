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
 * 手机上开着的 VPN 给的本机代理（比如 NekoBox 的 127.0.0.1:2080），写成 http://主机:端口；没有就 null。电脑上一律 null（用设置里填的代理）。
 * 10-24 用户手机：NekoBox 的分应用名单把 AI集训营 排除在外，浏览器走 VPN、App 直连，GitHub 登录换令牌超时。
 */
expect fun systemVpnProxy(): String?
