package com.guixing.jixunying.model

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 配对码：电脑显示成二维码，也可以复制成文字发到手机上粘贴。
 * 里面有电脑的编号、一次性配对密钥、电脑名和中转服务器列表。谁拿到它谁就能配对，所以只用一次。
 */
@Serializable
data class PairingCode(
    val h: String,
    val p: String,
    val n: String = "",
    val b: List<String> = RelaySettings.DEFAULT_BROKERS,
) {
    /** 默认中转不写进码里，二维码更稀疏、更好扫。 */
    fun encode(): String = PREFIX + Base64.UrlSafe.encode(compact.encodeToString(serializer(), this).encodeToByteArray()).trimEnd('=')

    companion object {
        const val PREFIX = "JXY1-"
        private val compact = kotlinx.serialization.json.Json { encodeDefaults = false; ignoreUnknownKeys = true }

        fun decode(text: String): PairingCode? = runCatching {
            val t = text.trim().substringAfter(PREFIX, "").filterNot { it.isWhitespace() }
            if (t.isEmpty()) return null
            val padded = t + "=".repeat((4 - t.length % 4) % 4)
            AppJson.decodeFromString(serializer(), Base64.UrlSafe.decode(padded).decodeToString())
        }.getOrNull()?.takeIf { it.h.isNotBlank() && it.p.isNotBlank() }
    }
}

/** 手机端保存的配对结果。 */
@Serializable
data class PairedHost(
    val hostId: String,
    val hostName: String,
    val deviceId: String,
    val key: String,
    val brokers: List<String>,
)
