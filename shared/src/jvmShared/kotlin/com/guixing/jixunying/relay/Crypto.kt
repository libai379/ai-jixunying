package com.guixing.jixunying.relay

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 端到端加密：AES-256-GCM。中转服务器上的人只看得到乱码，也改不了内容（改了解密就失败）。 */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun randomHex(bytes: Int) = randomBytes(bytes).joinToString("") { "%02x".format(it) }

    /** 一次性配对密钥是 16 字节，扩成 32 字节再用。 */
    private fun normalize(key: ByteArray): ByteArray =
        if (key.size == 32) key else java.security.MessageDigest.getInstance("SHA-256").digest(key)

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(normalize(key), "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(plain)
    }

    fun open(key: ByteArray, sealed: ByteArray): ByteArray? = runCatching {
        if (sealed.size < 12 + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(normalize(key), "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.doFinal(sealed, 12, sealed.size - 12)
    }.getOrNull()

    fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { bo -> GZIPOutputStream(bo).use { it.write(data) } }.toByteArray()

    fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }
}
