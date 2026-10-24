package com.guixing.jixunying.engine

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 账号（2026-10-24，docs/账号设计说明.md）。用户 10-23：「账号登录就用 GitHub 加密钥两种方式登录」，没有服务器。
 * - 本机密钥：第一次用时生成一对 EC P-256 密钥（电脑和安卓都自带，不加依赖），账号编号由公钥算（SHA-256 前 10 字节）。
 *   私钥只在 <数据>/account/identity.json，不进 state.json、不同步；能导出成口令加密的备份码，换设备导入就是同一个账号。
 * - GitHub：官方设备码登录（docs.github.com「device flow」：不要 client secret），拿到 GitHub 用户名和头像绑到本机账号上；拿到就把令牌扔了，不存。
 */
class Account(
    private val dir: File,
    private val proxy: () -> String?,
    /** 测试里换成假服务器。 */
    private val githubWeb: String = System.getProperty("jxy.github.web") ?: "https://github.com",
    private val githubApi: String = System.getProperty("jxy.github.api") ?: "https://api.github.com",
) {
    @Serializable
    data class Identity(
        val publicKey: String = "",
        val privateKey: String = "",
        val createdAt: Long = 0,
        val githubLogin: String = "",
        val githubId: Long = 0,
        val githubName: String = "",
        val githubAvatar: String = "",
        val githubAt: Long = 0,
    )

    class AccountException(msg: String) : Exception(msg)

    private val file = File(dir, "identity.json")
    @Volatile var identity: Identity = load()
        private set

    private fun load(): Identity {
        val old = runCatching { com.guixing.jixunying.model.AppJson.decodeFromString(Identity.serializer(), file.readText()) }.getOrNull()
        if (old != null && old.publicKey.isNotBlank() && old.privateKey.isNotBlank()) return old
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return Identity(b64(kp.public.encoded), b64(kp.private.encoded), System.currentTimeMillis()).also(::save)
    }

    private fun save(i: Identity) {
        identity = i
        dir.mkdirs()
        val tmp = File(dir, "identity.json.tmp")
        tmp.writeText(com.guixing.jixunying.model.AppJson.encodeToString(Identity.serializer(), i))
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** 账号编号：jxy- 加公钥 SHA-256 的前 10 字节（20 个十六进制字），每 4 个一段。 */
    val accountId: String get() = idOf(identity.publicKey)

    /** 用本机私钥签名（以后进群、证明「是我」用）。 */
    fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(identity.privateKey)))); update(data); sign()
    }

    /** 导出备份码：私钥 + 公钥 + GitHub 绑定，用口令（PBKDF2 20 万次 → AES-256-GCM）加密。 */
    fun export(passphrase: String): String {
        if (passphrase.length < 6) throw AccountException("口令至少 6 个字")
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val plain = com.guixing.jixunying.model.AppJson.encodeToString(Identity.serializer(), identity).toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keyOf(passphrase, salt), GCMParameterSpec(128, iv)) }
        return PREFIX + b64(salt + iv + c.doFinal(plain))
    }

    /** 导入备份码：换成那个账号（这台设备原来的密钥被替换掉）。 */
    fun import(code: String, passphrase: String): Identity {
        val raw = runCatching { unb64(code.trim().removePrefix(PREFIX)) }.getOrNull()
        if (!code.trim().startsWith(PREFIX) || raw == null || raw.size < 16 + 12 + 16) throw AccountException("这不是 AI集训营 的账号备份码")
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keyOf(passphrase, raw.copyOfRange(0, 16)), GCMParameterSpec(128, raw.copyOfRange(16, 28)))
            }.doFinal(raw.copyOfRange(28, raw.size))
        } catch (_: Exception) { throw AccountException("口令不对，或者备份码不完整") }
        val i = com.guixing.jixunying.model.AppJson.decodeFromString(Identity.serializer(), String(plain))
        // 公钥和私钥要是一对（防止拼错的备份码把账号弄坏）
        val probe = "jxy-probe".toByteArray()
        val ok = runCatching {
            val sig = Signature.getInstance("SHA256withECDSA").run { initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(i.privateKey)))); update(probe); sign() }
            verify(i.publicKey, probe, sig)
        }.getOrDefault(false)
        if (!ok) throw AccountException("备份码里的密钥对不上")
        save(i)
        return i
    }

    // ———— GitHub 设备码登录 ————

    class DeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val interval: Long, val expiresIn: Long)

    /** 第一步：拿设备码和给用户看的码（scope 空 = 只读公开资料）。 */
    suspend fun githubStart(clientId: String): DeviceCode {
        if (clientId.isBlank()) throw AccountException("GitHub 登录还没开通（程序里还没填 GitHub 应用的编号）")
        val o = postForm("$githubWeb/login/device/code", mapOf("client_id" to clientId, "scope" to ""))
        o.str("error")?.let { throw AccountException("GitHub 回了错误：$it ${o.str("error_description").orEmpty()}") }
        return DeviceCode(o.str("device_code") ?: throw AccountException("GitHub 没给设备码"), o.str("user_code").orEmpty(),
            o.str("verification_uri") ?: "$githubWeb/login/device", o.num("interval") ?: 5, o.num("expires_in") ?: 900)
    }

    /** 第二步：按 GitHub 说的间隔等用户在网页上输码同意；拿到令牌就查一次用户资料，绑好，令牌扔掉。 */
    suspend fun githubWait(clientId: String, dc: DeviceCode): Identity {
        var interval = dc.interval.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            val o = postForm("$githubWeb/login/oauth/access_token", mapOf(
                "client_id" to clientId, "device_code" to dc.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            when (o.str("error")) {
                null -> {
                    val token = o.str("access_token") ?: throw AccountException("GitHub 没给令牌")
                    val resp = Http.client(proxy()).get("$githubApi/user") {
                        header("Authorization", "Bearer $token"); header("Accept", "application/vnd.github+json")
                    }
                    val text = resp.bodyAsText()
                    if (resp.status.value !in 200..299) throw AccountException("查 GitHub 用户资料失败：${resp.status.value}")
                    val u = Json.parse(text).jsonObject
                    val i = identity.copy(githubLogin = u.str("login").orEmpty(), githubId = u.num("id") ?: 0, githubName = u.str("name").orEmpty(),
                        githubAvatar = u.str("avatar_url").orEmpty(), githubAt = System.currentTimeMillis())
                    save(i)
                    return i
                }
                "authorization_pending" -> {}
                "slow_down" -> interval = (o.num("interval") ?: (interval + 5))
                "expired_token" -> throw AccountException("码过期了，重新点一次「用 GitHub 登录」")
                "access_denied" -> throw AccountException("你在 GitHub 页面上点了取消")
                else -> throw AccountException("GitHub 回了错误：${o.str("error")} ${o.str("error_description").orEmpty()}")
            }
        }
        throw AccountException("码过期了，重新点一次「用 GitHub 登录」")
    }

    fun githubUnbind() = save(identity.copy(githubLogin = "", githubId = 0, githubName = "", githubAvatar = "", githubAt = 0))

    private suspend fun postForm(url: String, form: Map<String, String>): JsonObject {
        val resp = Http.client(proxy()).submitForm(url, parameters { form.forEach { (k, v) -> append(k, v) } }) { header("Accept", "application/json") }
        val text = resp.bodyAsText()
        return runCatching { Json.parse(text).jsonObject }.getOrNull() ?: throw AccountException("GitHub 回的不是 JSON（${resp.status.value}）：${text.take(120)}")
    }

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.longOrNull

    companion object {
        const val PREFIX = "JXYID1:"
        private val rnd = SecureRandom()

        private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = Base64.getDecoder().decode(s)

        private fun keyOf(passphrase: String, salt: ByteArray) =
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, 200_000, 256)).encoded, "AES")

        fun idOf(publicKey: String): String {
            val h = MessageDigest.getInstance("SHA-256").digest(unb64(publicKey)).take(10).joinToString("") { "%02x".format(it) }
            return "jxy-" + h.chunked(4).joinToString("-")
        }

        fun verify(publicKey: String, data: ByteArray, sig: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(publicKey)))); update(data); verify(sig)
        }
    }
}
