package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.WeixinInfo
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Serializable
private data class WeixinAccount(
    val botToken: String,
    val botId: String,
    val userId: String = "",
    val baseUrl: String = "",
    val boundAt: Long = 0,
)

@Serializable
private data class WeixinCursor(
    val buf: String = "",
    /** 每个微信用户最近一条消息的 context_token（回复时必须带回去）。 */
    val contextTokens: Map<String, String> = emptyMap(),
)

/**
 * 微信助理：腾讯官方 ClawBot 的 iLink 协议（和 WorkBuddy 的「微信助理」同一套）。
 * 协议细节照腾讯官方插件 @tencent-weixin/openclaw-weixin 2.4.9 的源码核对，见 docs/参考资料.md。
 *
 * 只接在电脑上（电脑常开，手机在外面也能通过微信用）。凭证存在 <数据目录>\weixin\，不进 AppState。
 * 微信里的每个人一个对话（channel = "weixin:<微信用户编号>"），界面上也能看到、也能接着聊。
 */
class WeixinBridge(
    private val engine: Engine,
    private val dir: File,
    private val loginBase: String = DEFAULT_BASE,
    private val cdnBase: String = CDN_BASE,
) {
    companion object {
        const val DEFAULT_BASE = "https://ilinkai.weixin.qq.com"
        const val CDN_BASE = "https://novac2c.cdn.weixin.qq.com/c2c"
        /** 协议按官方插件这个版本对齐（请求头 iLink-App-ClientVersion 由它算出来）。 */
        const val CHANNEL_VERSION = "2.4.9"
        const val APP_ID = "bot"
        const val BOT_AGENT = "AIJixunying/1.1.1"
        /** 会话过期（要重新扫码），官方插件遇到后暂停一小时。 */
        const val STALE_TOKEN = -14
        /** 一条微信消息最长发多少字，再长就拆开。 */
        const val CHUNK = 1800

        /** 微信不显示 Markdown：把标题、加粗、列表、链接、表格分隔线换成普通文字。 */
        fun plainText(md: String): String {
            var t = md.replace("\r\n", "\n")
            t = t.replace(Regex("```[A-Za-z0-9_+-]*\\n?"), "")
            t = t.replace(Regex("(?m)^#{1,6}\\s+"), "")
            t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("__(.+?)__"), "$1")
            t = t.replace(Regex("\\[([^\\]\\n]+)]\\((https?://[^)\\s]+)\\)"), "$1（$2）")
            t = t.replace(Regex("`([^`\\n]+)`"), "$1")
            t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
            t = t.replace(Regex("(?m)^>\\s?"), "")
            t = t.replace(Regex("(?m)^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$\\n?"), "")
            t = t.replace(Regex("\\[ref_(\\d+)]"), "[$1]")
            t = t.replace(Regex("\\n{3,}"), "\n\n")
            return t.trim()
        }

        /** 按段落拆成不超过 max 字的几段。 */
        fun chunks(text: String, max: Int = CHUNK): List<String> {
            if (text.length <= max) return listOf(text)
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (para in text.split("\n")) {
                var p = para
                while (p.length > max) {
                    if (sb.isNotEmpty()) { out += sb.toString().trim(); sb.clear() }
                    out += p.take(max); p = p.drop(max)
                }
                if (sb.length + p.length + 1 > max) { out += sb.toString().trim(); sb.clear() }
                sb.append(p).append('\n')
            }
            if (sb.isNotBlank()) out += sb.toString().trim()
            return out.filter { it.isNotBlank() }
        }

        fun aesEcb(data: ByteArray, key: ByteArray, encrypt: Boolean): ByteArray {
            val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
            c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            return c.doFinal(data)
        }

        fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        /** media.aes_key 有两种写法：base64(16 字节) 或 base64(32 位十六进制字符串)。 */
        fun parseAesKey(b64: String): ByteArray? {
            val d = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
            if (d.size == 16) return d
            val s = String(d, Charsets.US_ASCII)
            return if (d.size == 32 && s.all { it in "0123456789abcdefABCDEF" }) hexToBytes(s) else null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rnd = SecureRandom()
    private val accountFile = File(dir, "account.json")
    private val cursorFile = File(dir, "cursor.json")
    @Volatile private var account: WeixinAccount? = load(accountFile, WeixinAccount.serializer())
    @Volatile private var cursor: WeixinCursor = load(cursorFile, WeixinCursor.serializer()) ?: WeixinCursor()
    private var monitorJob: Job? = null
    private var loginJob: Job? = null
    @Volatile private var pendingVerify: String? = null
    private val userLocks = ConcurrentHashMap<String, Mutex>()
    private val typingTickets = ConcurrentHashMap<String, String>()
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile private var info = WeixinInfo(bound = account != null, status = if (account != null) "正在连接…" else "未绑定", boundAt = account?.boundAt ?: 0)

    init { dir.mkdirs() }

    private fun <T> load(f: File, ser: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { AppJson.decodeFromString(ser, f.readText(Charsets.UTF_8)) }.getOrNull()

    private fun <T> store(f: File, ser: kotlinx.serialization.KSerializer<T>, v: T) = runCatching {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(ser, v), Charsets.UTF_8)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun update(next: WeixinInfo) {
        info = next
        engine.setWeixinInfo(next)
    }

    fun start() {
        update(info)
        if (account != null) startMonitor()
    }

    fun stop() {
        val acc = account
        monitorJob?.cancel()
        loginJob?.cancel()
        if (acc != null) runCatching { kotlinx.coroutines.runBlocking { post(baseOf(acc), "ilink/bot/msg/notifystop", buildJsonObject { put("base_info", baseInfo()) }, acc.botToken, 3_000) } }
    }

    // ———————————————— HTTP ————————————————

    private val http get() = Http.client(null)

    private fun clientVersion(): Int {
        val p = CHANNEL_VERSION.split('.').map { it.toIntOrNull() ?: 0 }
        return ((p.getOrElse(0) { 0 } and 0xff) shl 16) or ((p.getOrElse(1) { 0 } and 0xff) shl 8) or (p.getOrElse(2) { 0 } and 0xff)
    }

    /** X-WECHAT-UIN：随机 uint32 → 十进制字符串 → base64。 */
    private fun randomUin(): String {
        val n = rnd.nextInt().toLong() and 0xffffffffL
        return Base64.getEncoder().encodeToString(n.toString().toByteArray())
    }

    private fun baseInfo() = buildJsonObject { put("channel_version", CHANNEL_VERSION); put("bot_agent", BOT_AGENT) }

    private fun baseOf(acc: WeixinAccount) = acc.baseUrl.ifBlank { loginBase }

    private suspend fun post(base: String, endpoint: String, body: JsonObject, token: String?, timeoutMs: Long): JsonObject {
        val resp = http.post(base.trimEnd('/') + "/" + endpoint) {
            timeout { requestTimeoutMillis = timeoutMs }
            header("iLink-App-Id", APP_ID)
            header("iLink-App-ClientVersion", clientVersion().toString())
            header("AuthorizationType", "ilink_bot_token")
            header("X-WECHAT-UIN", randomUin())
            if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        return Json.parse(text) as? JsonObject ?: JsonObject(emptyMap())
    }

    private suspend fun getJson(base: String, endpoint: String, timeoutMs: Long): JsonObject {
        val resp = http.get(base.trimEnd('/') + "/" + endpoint) {
            timeout { requestTimeoutMillis = timeoutMs }
            header("iLink-App-Id", APP_ID)
            header("iLink-App-ClientVersion", clientVersion().toString())
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        return Json.parse(text) as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray

    // ———————————————— 绑定 ————————————————

    suspend fun login(): CommandResult {
        loginJob?.cancel()
        pendingVerify = null
        val resp = post(loginBase, "ilink/bot/get_bot_qrcode?bot_type=3",
            buildJsonObject { putJsonArray("local_token_list") { account?.botToken?.let { add(it) } } }, null, 15_000)
        val qrcode = resp.str("qrcode") ?: return CommandResult(false, "微信没有返回二维码：${resp.toString().take(200)}")
        val link = resp.str("qrcode_img_content").orEmpty()
        update(info.copy(status = "用手机微信扫一扫下面的二维码", loginLink = link, needVerifyCode = false))
        loginJob = scope.launch { pollLogin(qrcode) }
        return CommandResult(message = "二维码已生成", data = link)
    }

    fun verify(code: String): CommandResult {
        if (code.isBlank()) return CommandResult(false, "数字是空的")
        pendingVerify = code.trim()
        update(info.copy(needVerifyCode = false, status = "已提交，正在核对…"))
        return CommandResult()
    }

    suspend fun logout(): CommandResult {
        val acc = account
        monitorJob?.cancel()
        loginJob?.cancel()
        if (acc != null) runCatching { post(baseOf(acc), "ilink/bot/msg/notifystop", buildJsonObject { put("base_info", baseInfo()) }, acc.botToken, 5_000) }
        account = null
        cursor = WeixinCursor()
        accountFile.delete()
        cursorFile.delete()
        update(WeixinInfo(status = "未绑定"))
        return CommandResult(message = "已解除绑定。微信里那个助理的对话可以删掉。")
    }

    private suspend fun pollLogin(initial: String) {
        var qrcode = initial
        var base = loginBase
        var refresh = 1
        val deadline = System.currentTimeMillis() + 8 * 60_000
        while (System.currentTimeMillis() < deadline) {
            var endpoint = "ilink/bot/get_qrcode_status?qrcode=${enc(qrcode)}"
            pendingVerify?.let { endpoint += "&verify_code=${enc(it)}" }
            val st = try { getJson(base, endpoint, 38_000) } catch (e: CancellationException) { throw e } catch (e: Throwable) { buildJsonObject { put("status", "wait") } }
            when (st.str("status")) {
                "scaned" -> {
                    pendingVerify = null
                    if (!info.status.startsWith("已扫码")) update(info.copy(status = "已扫码，请在手机微信上点「确认」", needVerifyCode = false))
                }
                "need_verifycode" -> {
                    val wrong = pendingVerify != null
                    pendingVerify = null
                    update(info.copy(needVerifyCode = true, status = if (wrong) "数字不对，请重新输入手机微信上显示的数字" else "请输入手机微信上显示的数字"))
                    while (pendingVerify == null && System.currentTimeMillis() < deadline) delay(500)
                    continue
                }
                "expired", "verify_code_blocked" -> {
                    refresh++
                    if (refresh > 3) {
                        update(info.copy(status = "二维码多次过期，请重新点「绑定微信」", loginLink = "", needVerifyCode = false)); return
                    }
                    val r = runCatching { post(loginBase, "ilink/bot/get_bot_qrcode?bot_type=3", buildJsonObject { putJsonArray("local_token_list") {} }, null, 15_000) }.getOrNull()
                    val next = r?.str("qrcode")
                    if (next == null) { update(info.copy(status = "刷新二维码失败，请重新点「绑定微信」", loginLink = "")); return }
                    qrcode = next
                    base = loginBase
                    update(info.copy(status = "二维码过期了，已换新的，请重新扫", loginLink = r.str("qrcode_img_content").orEmpty(), needVerifyCode = false))
                }
                "scaned_but_redirect" -> st.str("redirect_host")?.let { base = "https://$it" }
                "binded_redirect" -> {
                    update(info.copy(status = if (account != null) "这个微信已经绑定过了，可以直接用" else "微信说已经绑定过；如果用不了，先在微信里删掉助理再重新绑定", loginLink = ""))
                    return
                }
                "confirmed" -> {
                    val token = st.str("bot_token")
                    val botId = st.str("ilink_bot_id")
                    if (token.isNullOrBlank() || botId.isNullOrBlank()) {
                        update(info.copy(status = "绑定失败：微信没有返回凭证", loginLink = "")); return
                    }
                    val acc = WeixinAccount(token, botId, st.str("ilink_user_id").orEmpty(), st.str("baseurl").orEmpty(), System.currentTimeMillis())
                    account = acc
                    cursor = WeixinCursor()
                    store(accountFile, WeixinAccount.serializer(), acc)
                    store(cursorFile, WeixinCursor.serializer(), cursor)
                    update(WeixinInfo(bound = true, status = "已绑定，正在连接…", boundAt = acc.boundAt))
                    startMonitor()
                    return
                }
            }
            delay(1_000)
        }
        update(info.copy(status = "等太久了，二维码已失效，请重新点「绑定微信」", loginLink = "", needVerifyCode = false))
    }

    // ———————————————— 收消息 ————————————————

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            account?.let { acc -> runCatching { post(baseOf(acc), "ilink/bot/msg/notifystart", buildJsonObject { put("base_info", baseInfo()) }, acc.botToken, 10_000) } }
            var failures = 0
            var pollMs = 35_000L
            while (isActive) {
                val acc = account ?: return@launch
                if (!engine.state.settings.weixin.enabled) {
                    if (info.status != "已暂停") update(info.copy(status = "已暂停"))
                    delay(3_000); continue
                }
                try {
                    val resp = try {
                        post(baseOf(acc), "ilink/bot/getupdates",
                            buildJsonObject { put("get_updates_buf", cursor.buf); put("base_info", baseInfo()) }, acc.botToken, pollMs + 5_000)
                    } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
                        // 长轮询本地超时：正常，接着下一轮
                        continue
                    }
                    val ret = resp.int("ret") ?: 0
                    val err = resp.int("errcode") ?: 0
                    if (ret == STALE_TOKEN || err == STALE_TOKEN) {
                        update(info.copy(status = "微信那边的会话过期了，请重新扫码绑定（一小时后会再自动试一次）"))
                        delay(60 * 60_000L)
                        continue
                    }
                    if (ret != 0 || err != 0) {
                        failures++
                        update(info.copy(status = "微信返回错误（$ret / $err）：${resp.str("errmsg").orEmpty().take(60)}"))
                        delay(if (failures >= 3) 30_000 else 2_000)
                        continue
                    }
                    failures = 0
                    resp.int("longpolling_timeout_ms")?.takeIf { it > 0 }?.let { pollMs = it.toLong() }
                    resp.str("get_updates_buf")?.takeIf { it.isNotEmpty() }?.let {
                        cursor = cursor.copy(buf = it)
                        store(cursorFile, WeixinCursor.serializer(), cursor)
                    }
                    if (!info.status.startsWith("已连接")) update(info.copy(bound = true, status = "已连接：在微信里给助理发消息就行"))
                    resp.arr("msgs")?.forEach { m -> (m as? JsonObject)?.let { msg -> scope.launch { handle(msg) } } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failures++
                    update(info.copy(status = "连不上微信（${engine.friendlyError(e).take(60)}），稍后自动重试"))
                    delay(if (failures >= 3) 30_000 else 2_000)
                }
            }
        }
    }

    private fun msgId(m: JsonObject): String? =
        m.str("message_id") ?: m.arr("item_list")?.firstNotNullOfOrNull { (it as? JsonObject)?.str("msg_id") }

    /** 一条微信消息：转成文字 + 附件，交给 AI，答完发回去。同一个人的消息按顺序处理。 */
    private suspend fun handle(m: JsonObject) {
        val from = m.str("from_user_id") ?: return
        if ((m.int("message_type") ?: 1) != 1) return
        // 只回答扫码绑定的本人（和官方插件的默认规则一样）。别人能给这个助理发消息，
        // 不拦的话陌生人就能用你的 AI、还能让它搜你电脑上的文档。
        val owner = account?.userId?.trim().orEmpty()
        if (owner.isNotEmpty() && from != owner) return
        msgId(m)?.let { id ->
            if (!seen.add(id)) return
            if (seen.size > 500) synchronized(seen) { seen.iterator().let { it.next(); it.remove() } }
        }
        m.str("context_token")?.let { tok ->
            cursor = cursor.copy(contextTokens = cursor.contextTokens + (from to tok))
            store(cursorFile, WeixinCursor.serializer(), cursor)
        }
        update(info.copy(lastMessageAt = System.currentTimeMillis()))
        userLocks.getOrPut(from) { Mutex() }.withLock { process(from, m) }
    }

    private suspend fun process(from: String, m: JsonObject) {
        // 回复要带「这一条」消息的 context_token（连发几条时，存下来的可能已经是后面那条的了）
        val ctx = m.str("context_token") ?: cursor.contextTokens[from]
        val parts = mutableListOf<String>()
        val attIds = mutableListOf<String>()
        for (el in m.arr("item_list").orEmpty()) {
            val item = el as? JsonObject ?: continue
            item.obj("ref_msg")?.let { ref ->
                val quoted = listOfNotNull(ref.str("title")?.takeIf { it.isNotBlank() },
                    ref.obj("message_item")?.obj("text_item")?.str("text")).joinToString(" | ")
                if (quoted.isNotBlank()) parts += "（引用：$quoted）"
            }
            when (item.int("type")) {
                1 -> item.obj("text_item")?.str("text")?.let { parts += it }
                3 -> parts += item.obj("voice_item")?.str("text")?.takeIf { it.isNotBlank() }?.let { "[语音] $it" } ?: "[一段语音，微信没转出文字]"
                2 -> {
                    val img = item.obj("image_item")
                    val bytes = runCatching { download(img?.obj("media"), img?.str("aeskey")) }.getOrNull()
                    val att = bytes?.let { engine.upload("微信图片_${System.currentTimeMillis() % 100000}.jpg", "image/jpeg", it) }
                    if (att != null) attIds += att.id else parts += "[一张图片，没下载下来]"
                }
                4 -> {
                    val f = item.obj("file_item")
                    val name = f?.str("file_name")?.takeIf { it.isNotBlank() } ?: "微信文件"
                    val bytes = runCatching { download(f?.obj("media"), null) }.getOrNull()
                    val att = bytes?.let { engine.upload(name, "application/octet-stream", it) }
                    if (att != null) attIds += att.id else parts += "[文件「$name」没下载下来]"
                }
                5 -> parts += "[一段视频：暂时看不了视频内容]"
                else -> parts += "[一条暂不支持的消息（比如合并转发的聊天记录），可以改发文字、截图或文件]"
            }
        }
        val text = parts.joinToString("\n").trim()
        val ws = engine.state.settings.weixin
        val members = ws.memberIds.filter { engine.state.member(it) != null }.ifEmpty { engine.state.members.take(1).map { it.id } }
        if (members.isEmpty()) { sendText(from, ctx, "电脑上的 AI集训营 还没有 AI 成员，先在电脑上添加一位。"); return }

        when (text) {
            "/新对话", "新对话", "/new" -> {
                engine.newChannelConversation("weixin:$from", "微信对话", members, ws.webSearch)
                sendText(from, ctx, "好，换个新话题。之前的聊天还留在电脑上。")
                return
            }
            "/帮助", "/help" -> {
                sendText(from, ctx, "直接发文字、图片、文件（PDF、Word、Excel 等）就行，${members.size} 位 AI 成员在电脑上回答。\n发「/新对话」开始一个新话题。\n想让 AI 画图就说「画一张……」。")
                return
            }
        }
        if (text.isBlank() && attIds.isEmpty()) return

        val convId = engine.channelConversation("weixin:$from", "微信对话", members, ws.webSearch)
        val typingJob = scope.launch {
            while (isActive) { typing(from, ctx, true); delay(8_000) }
        }
        val replies = try {
            engine.channelTurn(convId, text, attIds)
        } finally {
            typingJob.cancel()
            typing(from, ctx, false)
        }
        if (replies.isEmpty()) { sendText(from, ctx, "这次没答上来，可以在电脑上的「微信对话」里看看出了什么问题。"); return }
        val multi = replies.map { it.senderId }.distinct().size > 1
        for (r in replies) sendReply(from, ctx, r, multi)
    }

    private suspend fun sendReply(to: String, ctx: String?, r: Message, withName: Boolean) {
        val name = engine.memberName(r.senderId) ?: "AI"
        val body = when (r.status) {
            MsgStatus.ERROR -> "出错了：${r.error.take(300)}"
            MsgStatus.STOPPED -> plainText(r.content).ifBlank { "（在电脑上被停止了）" }
            else -> plainText(r.content.replace(Regex("<think>[\\s\\S]*?</think>"), ""))
        }
        val sources = r.tools.filter { it.kind == "search" }.flatMap { it.sources }.take(5)
        val text = buildString {
            if (withName) append("【").append(name).append("】\n")
            append(body)
            if (sources.isNotEmpty()) {
                append("\n\n来源：")
                sources.forEachIndexed { i, s -> append("\n").append(i + 1).append(". ").append(s.title.take(40)).append(" ").append(s.url) }
            }
        }.trim()
        if (text.isNotBlank()) chunks(text).forEach { sendText(to, ctx, it) }
        for (a in r.attachments.filter { it.kind == AttachmentKind.GENERATED_IMAGE }) {
            val bytes = engine.fileBytes(a.id) ?: continue
            runCatching { sendImage(to, ctx, bytes) }.onFailure { sendText(to, ctx, "（画好了一张图，但发到微信失败了：${it.message?.take(80)}。可以在电脑上看。）") }
        }
    }

    // ———————————————— 发消息 ————————————————

    private fun clientId() = "jxy-${System.currentTimeMillis()}-${bytesToHex(ByteArray(4).also(rnd::nextBytes))}"

    private suspend fun sendItem(to: String, ctx: String?, item: JsonObject) {
        val acc = account ?: return
        val body = buildJsonObject {
            putJsonObject("msg") {
                put("from_user_id", "")
                put("to_user_id", to)
                put("client_id", clientId())
                put("message_type", 2)
                put("message_state", 2)
                putJsonArray("item_list") { add(item) }
                if (ctx != null) put("context_token", ctx)
            }
            put("base_info", baseInfo())
        }
        val resp = post(baseOf(acc), "ilink/bot/sendmessage", body, acc.botToken, 15_000)
        val ret = resp.int("ret") ?: 0
        if (ret != 0) throw IllegalStateException("发消息失败（ret=$ret）：${resp.str("errmsg").orEmpty()}")
    }

    suspend fun sendText(to: String, ctx: String?, text: String) = runCatching {
        sendItem(to, ctx, buildJsonObject { put("type", 1); putJsonObject("text_item") { put("text", text) } })
    }

    private suspend fun typing(to: String, ctx: String?, on: Boolean) {
        val acc = account ?: return
        runCatching {
            val ticket = typingTickets[to] ?: post(baseOf(acc), "ilink/bot/getconfig",
                buildJsonObject { put("ilink_user_id", to); if (ctx != null) put("context_token", ctx); put("base_info", baseInfo()) }, acc.botToken, 10_000)
                .str("typing_ticket")?.also { typingTickets[to] = it } ?: return
            post(baseOf(acc), "ilink/bot/sendtyping",
                buildJsonObject { put("ilink_user_id", to); put("typing_ticket", ticket); put("status", if (on) 1 else 2); put("base_info", baseInfo()) },
                acc.botToken, 10_000)
        }
    }

    /** 下载微信 CDN 上的图片 / 文件并解密（AES-128-ECB）。 */
    private suspend fun download(media: JsonObject?, hexKey: String?): ByteArray? {
        media ?: return null
        val url = media.str("full_url")?.takeIf { it.isNotBlank() }
            ?: media.str("encrypt_query_param")?.let { "$cdnBase/download?encrypted_query_param=${enc(it)}" } ?: return null
        val resp = http.get(url) { timeout { requestTimeoutMillis = 120_000 } }
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, resp.bodyAsText().take(200))
        val bytes = resp.bodyAsBytes()
        if (bytes.size > 50 * 1024 * 1024) throw IllegalStateException("文件超过 50MB")
        val key = hexKey?.takeIf { it.length == 32 }?.let(::hexToBytes) ?: media.str("aes_key")?.let(::parseAesKey) ?: return bytes
        return aesEcb(bytes, key, encrypt = false)
    }

    /** 把图片加密传到微信 CDN，再发一条图片消息。 */
    private suspend fun sendImage(to: String, ctx: String?, plain: ByteArray) {
        val acc = account ?: return
        val filekey = bytesToHex(ByteArray(16).also(rnd::nextBytes))
        val aes = ByteArray(16).also(rnd::nextBytes)
        val cipher = aesEcb(plain, aes, encrypt = true)
        val md5 = bytesToHex(MessageDigest.getInstance("MD5").digest(plain))
        val up = post(baseOf(acc), "ilink/bot/getuploadurl", buildJsonObject {
            put("filekey", filekey)
            put("media_type", 1)
            put("to_user_id", to)
            put("rawsize", plain.size)
            put("rawfilemd5", md5)
            put("filesize", cipher.size)
            put("no_need_thumb", true)
            put("aeskey", bytesToHex(aes))
            put("base_info", baseInfo())
        }, acc.botToken, 15_000)
        val url = up.str("upload_full_url")?.takeIf { it.isNotBlank() }
            ?: up.str("upload_param")?.let { "$cdnBase/upload?encrypted_query_param=${enc(it)}&filekey=${enc(filekey)}" }
            ?: throw IllegalStateException("微信没给上传地址")
        var downloadParam: String? = null
        var last: Throwable? = null
        for (attempt in 1..3) {
            try {
                val r = http.post(url) {
                    timeout { requestTimeoutMillis = 120_000 }
                    contentType(ContentType.Application.OctetStream)
                    setBody(cipher)
                }
                if (r.status.value in 400..499) throw ApiException(r.status.value, r.headers["x-error-message"] ?: r.bodyAsText().take(200))
                downloadParam = r.headers["x-encrypted-param"] ?: throw IllegalStateException("上传后没拿到下载参数")
                break
            } catch (e: ApiException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
        }
        val dp = downloadParam ?: throw last ?: IllegalStateException("上传失败")
        sendItem(to, ctx, buildJsonObject {
            put("type", 2)
            putJsonObject("image_item") {
                putJsonObject("media") {
                    put("encrypt_query_param", dp)
                    put("aes_key", Base64.getEncoder().encodeToString(bytesToHex(aes).toByteArray()))
                    put("encrypt_type", 1)
                }
                put("mid_size", cipher.size)
            }
        })
    }
}
