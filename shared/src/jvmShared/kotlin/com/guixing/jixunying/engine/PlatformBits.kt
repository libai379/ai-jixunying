package com.guixing.jixunying.engine

/** 长边超过 maxSide 或体积超过 maxBytes 时压成 JPEG（发给模型前省流量和 token）；不需要压返回 null。 */
expect fun shrinkImageToJpeg(bytes: ByteArray, maxSide: Int, maxBytes: Int): ByteArray?

/** PDF 抽文字，返回 (文字, 说明)。电脑用 PDFBox，手机用 PDFBox 的安卓移植版。 */
expect fun extractPdfText(bytes: ByteArray): Pair<String?, String>
