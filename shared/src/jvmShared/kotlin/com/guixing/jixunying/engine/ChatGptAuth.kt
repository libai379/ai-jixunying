package com.guixing.jixunying.engine

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID

/**
 * 用 ChatGPT 登录（2026-10-23，docs/ChatGPT订阅设计说明.md）：OpenAI 给开源、本机运行的程序开的官方通道，
 * 用户在 OpenAI 的页面上同意后，用他自己的 ChatGPT 订阅额度调模型。照官方文档 developers.openai.com/siwc/token-sharing-open-source：
 * 第一次用 client_id=dynamic_agent_client 注册，回调里给正式的 client_id（oaiapp_…）存下来以后用；回调只能是 127.0.0.1 上的 /auth/callback；
 * PKCE S256；换令牌不要密钥；访问令牌 1 小时、刷新令牌 30 天滑动（每次刷新给新的，旧的作废）。
 *
 * 凭据只存在这台设备（<数据>/chatgpt/auth.json），不同步到别的设备：刷新令牌用一次就换，两台设备共用会互相顶掉（refresh_token_reused）。
 */
class ChatGptAuth(
    private val dir: File,
    private val proxy: () -> String?,
    /** 测试里换成假服务器（jxy.chatgpt.auth）。 */
    private val authBase: String = System.getProperty("jxy.chatgpt.auth") ?: "https://auth.openai.com",
) {
    @Serializable
    data class Creds(
        val hostId: String = "",
        val clientId: String = "",
        val email: String = "",
        val sub: String = "",
        val idToken: String = "",
        val accessToken: String = "",
        val refreshToken: String = "",
        val expiresAt: Long = 0,
        val scope: String = "",
    ) {
        val signedIn get() = refreshToken.isNotBlank() || accessToken.isNotBlank()
        val canUsePlan get() = SCOPE_PLAN in scope.split(' ')
    }

    class AuthException(msg: String) : Exception(msg)

    private val file = File(dir, "auth.json")
    private val lock = Mutex()
    @Volatile var creds: Creds = load()
        private set

    private fun load(): Creds = runCatching { com.guixing.jixunying.model.AppJson.decodeFromString(Creds.serializer(), file.readText()) }.getOrNull() ?: Creds()

    private fun save(c: Creds) {
        creds = c
        dir.mkdirs()
        val tmp = File(dir, "auth.json.tmp")
        tmp.writeText(com.guixing.jixunying.model.AppJson.encodeToString(Creds.serializer(), c))
        tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.setWritable(false, false); tmp.setWritable(true, true)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** 每台设备一个固定的编号（官方要求每个宿主不同、第一次登录前定好）。 */
    private fun hostId(): String = creds.hostId.ifBlank { "urn:uuid:" + UUID.randomUUID() }.also { if (creds.hostId != it) save(creds.copy(hostId = it)) }

    /** 一次登录：先开好本机接收回调的端口，返回要在浏览器打开的地址；[await] 等用户在网页上点完。 */
    inner class Pending internal constructor(
        val url: String,
        private val server: ServerSocket,
        private val redirect: String,
        private val state: String,
        private val nonce: String,
        private val verifier: String,
        private val firstClientId: String,
    ) {
        suspend fun await(timeoutMs: Long = 5 * 60_000): Creds = withContext(Dispatchers.IO) {
            val q = try { waitCallback(server, timeoutMs) } finally { runCatching { server.close() } }
            if (q["state"] != state) throw AuthException("登录回来的校验码对不上，再登一次")
            q["error"]?.let { e ->
                throw AuthException(if (e == "access_denied") "你在 OpenAI 页面上没有同意，没登录" else "OpenAI 回了错误：$e ${q["error_description"].orEmpty()}")
            }
            val code = q["code"] ?: throw AuthException("OpenAI 没给授权码")
            val clientId = q["client_id"]?.takeIf { it.isNotBlank() } ?: firstClientId
            if (clientId == FIRST_CLIENT) throw AuthException("OpenAI 没给正式的 client_id")
            val tok = postToken(mapOf(
                "grant_type" to "authorization_code", "client_id" to clientId, "code" to code,
                "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to RESOURCE,
            ))
            val idToken = tok.str("id_token").orEmpty()
            val claims = verifyIdToken(idToken, clientId, nonce)
            val scope = tok.str("scope") ?: q["scope"].orEmpty()
            val c = creds.copy(
                clientId = clientId,
                email = claims.str("email").orEmpty(),
                sub = claims.str("sub").orEmpty(),
                idToken = idToken,
                accessToken = tok.str("access_token").orEmpty(),
                refreshToken = tok.str("refresh_token").orEmpty(),
                expiresAt = System.currentTimeMillis() + ((tok["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600) * 1000,
                scope = scope,
            )
            lock.withLock { save(c) }
            c
        }

        fun cancel() { runCatching { server.close() } }
    }

    /** 开始登录。端口先试官方例子里的 1455，占用了换一个（官方：只有端口能变）。 */
    fun begin(appName: String): Pending {
        val server = runCatching { ServerSocket(1455, 8, InetAddress.getByName("127.0.0.1")) }.getOrNull()
            ?: ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
        val verifier = randomUrlSafe(48)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomUrlSafe(24)
        val nonce = randomUrlSafe(24)
        val saved = creds.clientId
        val clientId = saved.ifBlank { FIRST_CLIENT }
        val params = buildList {
            add("client_id" to clientId)
            if (saved.isBlank()) add("agent_name_hint" to appName)
            add("ext_agent_host_id" to hostId())
            creds.idToken.takeIf { it.isNotBlank() }?.let { add("id_token_hint" to it) }
            add("response_type" to "code")
            add("redirect_uri" to redirect)
            add("scope" to SCOPES)
            add("resource" to RESOURCE)
            add("state" to state)
            add("nonce" to nonce)
            add("code_challenge_method" to "S256")
            add("code_challenge" to challenge)
        }
        val url = "$authBase/api/accounts/authorize?" + params.joinToString("&") { (k, v) -> k + "=" + enc(v) }
        return Pending(url, server, redirect, state, nonce, verifier, clientId)
    }

    /** 拿一个能用的访问令牌，快过期（剩不到 2 分钟）先刷新。没登录、刷新失败抛 AuthException。 */
    suspend fun accessToken(forceRefresh: Boolean = false): String = lock.withLock {
        val c = creds
        if (!c.signedIn) throw AuthException("还没用 ChatGPT 登录：设置 → 模型服务 → ChatGPT 订阅 → 用 ChatGPT 登录")
        if (!forceRefresh && c.accessToken.isNotBlank() && c.expiresAt - System.currentTimeMillis() > 120_000) return@withLock c.accessToken
        if (c.refreshToken.isBlank()) throw AuthException("ChatGPT 登录过期了，重新登录一次")
        val tok = try {
            postToken(mapOf("grant_type" to "refresh_token", "client_id" to c.clientId, "refresh_token" to c.refreshToken))
        } catch (e: AuthException) {
            // 刷新令牌不能用了（过期、被用过、被撤销）：清掉令牌，留着 client_id 和设备编号，让用户再登一次（官方「Refresh errors」）
            if (TERMINAL_REFRESH.any { it in (e.message ?: "") }) save(c.copy(accessToken = "", refreshToken = "", expiresAt = 0))
            throw e
        }
        val n = c.copy(
            accessToken = tok.str("access_token").orEmpty(),
            refreshToken = tok.str("refresh_token") ?: c.refreshToken,
            idToken = tok.str("id_token") ?: c.idToken,
            expiresAt = System.currentTimeMillis() + ((tok["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600) * 1000,
            scope = tok.str("scope") ?: c.scope,
        )
        save(n)
        n.accessToken
    }

    /** 退出：清掉令牌；client_id 和设备编号留着（官方：以后登录用存下的 client_id）。 */
    suspend fun signOut() = lock.withLock { save(creds.copy(accessToken = "", refreshToken = "", idToken = "", expiresAt = 0, scope = "", email = "", sub = "")) }

    private suspend fun postToken(form: Map<String, String>): JsonObject {
        val resp = Http.client(proxy()).submitForm("$authBase/api/accounts/oauth/token", parameters { form.forEach { (k, v) -> append(k, v) } })
        val text = resp.bodyAsText()
        val obj = runCatching { Json.parse(text).jsonObject }.getOrNull()
        if (resp.status.value !in 200..299 || obj == null) {
            val err = obj?.str("error") ?: (obj?.get("error") as? JsonObject)?.str("code")
            throw AuthException("OpenAI 登录服务回了 ${resp.status.value}" + (err?.let { "（$it）" } ?: "：" + text.take(200)))
        }
        return obj
    }

    /** 校验 ID 令牌：签名对得上 OpenAI 公布的公钥，签发方、受众（正式 client_id）、过期、nonce 都对。 */
    private suspend fun verifyIdToken(token: String, clientId: String, nonce: String): JsonObject {
        val parts = token.split('.')
        if (parts.size != 3) throw AuthException("OpenAI 给的身份令牌格式不对")
        val header = Json.parse(String(b64d(parts[0]))).jsonObject
        val claims = Json.parse(String(b64d(parts[1]))).jsonObject
        val conf = getJson("$authBase/.well-known/openid-configuration")
        val issuer = conf.str("issuer") ?: authBase
        val jwks = getJson(conf.str("jwks_uri") ?: "$authBase/.well-known/jwks.json")
        val kid = header.str("kid")
        val key = (jwks["keys"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { kid == null || it.str("kid") == kid } ?: throw AuthException("找不到 OpenAI 的公钥（kid=$kid）")
        if (header.str("alg") != "RS256" || key.str("kty") != "RSA") throw AuthException("身份令牌的签名算法不认识：${header.str("alg")}")
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, b64d(key.str("n")!!)), BigInteger(1, b64d(key.str("e")!!))))
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(pub); update("${parts[0]}.${parts[1]}".toByteArray()); verify(b64d(parts[2]))
        }
        if (!ok) throw AuthException("身份令牌的签名不对")
        if (claims.str("iss") != issuer) throw AuthException("身份令牌的签发方不对：${claims.str("iss")}")
        val aud = claims["aud"]
        val audOk = (aud as? JsonPrimitive)?.content == clientId || (aud as? JsonArray)?.any { (it as? JsonPrimitive)?.content == clientId } == true
        if (!audOk) throw AuthException("身份令牌不是发给这个程序的")
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: 0
        if (exp * 1000 < System.currentTimeMillis() - 300_000) throw AuthException("身份令牌已经过期")
        if (claims.str("nonce") != nonce) throw AuthException("身份令牌的 nonce 对不上")
        return claims
    }

    private suspend fun getJson(url: String): JsonObject {
        val resp = Http.client(proxy()).get(url)
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw AuthException("取 $url 失败：${resp.status.value}")
        return Json.parse(text).jsonObject
    }

    companion object {
        const val FIRST_CLIENT = "dynamic_agent_client"
        const val RESOURCE = "https://api.openai.com/v1"
        const val SCOPE_PLAN = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $SCOPE_PLAN"
        private val TERMINAL_REFRESH = listOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
        private val rnd = SecureRandom()

        private fun randomUrlSafe(bytes: Int) = ByteArray(bytes).also { rnd.nextBytes(it) }.let(::b64url)
        private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        private fun b64d(s: String) = Base64.getUrlDecoder().decode(s.trimEnd('='))
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** 等浏览器跳回 127.0.0.1：只认 GET [callbackPath]，回一页「可以关掉」。别的请求（浏览器要图标）回 404 接着等。GitHub 登录（Account）也用这个。 */
        internal fun waitCallback(server: ServerSocket, timeoutMs: Long, callbackPath: String = "/auth/callback", what: String = "ChatGPT"): Map<String, String> {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw AuthException("等了 ${timeoutMs / 60_000} 分钟没等到你在网页上点同意，登录取消了")
                server.soTimeout = left.toInt().coerceAtLeast(1)
                val s = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                s.use { sock ->
                    // 浏览器常先开几条空连接（预连接）不发东西：读不到就丢掉这条接着等，别把整个登录报错（10-24 GitHub 真登录踩到「Read timed out」）
                    sock.soTimeout = 3_000
                    val line = try { sock.getInputStream().bufferedReader().readLine().orEmpty() } catch (_: java.io.IOException) { return@use }
                    if (line.isBlank()) return@use
                    val path = line.split(' ').getOrNull(1).orEmpty()
                    val out = sock.getOutputStream()
                    if (path.substringBefore('?') != callbackPath) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return@use
                    }
                    val q = path.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    val ok = "error" !in q
                    val html = "<!doctype html><meta charset=utf-8><title>AI集训营</title><body style=\"font-family:sans-serif;padding:40px\">" +
                        (if (ok) "<h2>$what 登录好了</h2><p>可以关掉这个页面，回到 AI集训营。</p>" else "<h2>没有登录</h2><p>可以关掉这个页面，回到 AI集训营 再试。</p>") + "</body>"
                    val body = html.toByteArray()
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    out.write(body)
                    out.flush()
                    return q
                }
            }
        }
    }
}
