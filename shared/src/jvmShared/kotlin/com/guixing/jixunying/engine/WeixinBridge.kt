package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.WeixinBot
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
    /** 手机上：这份绑定是从配对的电脑同步来的。解除时只删本机这份，不通知微信。 */
    val shared: Boolean = false,
    /** 谁绑的（2026-10-30 多微信）：空 = 这台设备自己；配对设备编号 = 那台手机遥控电脑时绑的。 */
    val boundBy: String = "",
    /** 这个微信里 AI 能不能读电脑上的文件（收录范围、我的文档）。电脑自己绑的默认能，手机绑的默认只聊天。 */
    val readFiles: Boolean = true,
)

@Serializable
private data class WeixinCursor(
    val buf: String = "",
    /** 每个微信用户最近一条消息的 context_token（回复时必须带回去）。 */
    val contextTokens: Map<String, String> = emptyMap(),
    /** 只回答这个时间（微信消息的 create_time_ms）以后发来的：之前的另一台设备已经答过。 */
    val answerAfter: Long = 0,
    /** 这台设备答过的最新一条消息的时间（手机告诉电脑用）。 */
    val answeredUntil: Long = 0,
    /** 电脑上：绑定同步给手机的时间。同步过的话，电脑刚打开时积压的消息先等一会儿再答（手机可能已经答过）。 */
    val sharedAt: Long = 0,
)

/** 电脑交给手机的绑定：凭证 + 从什么时候起归手机算（之前的电脑答过了）。 */
@Serializable
private data class WeixinShareBundle(val account: WeixinAccount, val answerAfter: Long)

/**
 * 微信助理：腾讯官方 ClawBot 的 iLink 协议（和 WorkBuddy 的「微信助理」同一套）。
 * 协议细节照腾讯官方插件 @tencent-weixin/openclaw-weixin 2.4.9 的源码核对，见 docs/参考资料.md。
 *
 * 电脑和手机都能接（1.5.0 起）。一个微信只能绑一台设备，所以手机不重新扫码，而是经加密线路用电脑的同一个绑定
 * （WeixinShareAll / importSharedAll）；电脑优先，电脑在接时手机待命（standby，见 WeixinHandover），按消息时间划界不重复回答。
 * 一台设备能挂几个微信（2026-10-30 多微信方案甲，照官方插件 dist/src/auth/accounts.js：一个微信一份，同一个微信重绑只换它自己那份）：
 * 每个记是谁绑的（手机遥控电脑时绑的记那台手机）；电脑开着由电脑答全部，电脑关了每台手机只接自己绑的那个（电脑自己绑的归接班手机）。
 * 凭证存在 <数据目录>\weixinots\<botId>\，不进 AppState。
 * 微信里的每个人一个对话（channel = "weixin:<微信用户编号>"），界面上也能看到、也能接着聊。
 */
class WeixinBridge(
    private val engine: Engine,
    private val dir: File,
    private val loginBase: String = DEFAULT_BASE,
    private val cdnBase: String = CDN_BASE,
    /** 电脑刚打开、绑定给过手机时，积压的消息先等多久（测试里改短）。 */
    private val backlogHoldMs: Long = BACKLOG_HOLD_MS,
) {
    companion object {
        const val DEFAULT_BASE = "https://ilinkai.weixin.qq.com"
        const val CDN_BASE = "https://novac2c.cdn.weixin.qq.com/c2c"
        /** 协议按官方插件这个版本对齐（请求头 iLink-App-ClientVersion 由它算出来）。 */
        const val CHANNEL_VERSION = "2.4.9"
        const val APP_ID = "bot"
        const val BOT_AGENT = "AIJixunying/1.13.0"
        /** 单独处理、不交给 AI 的几句话。 */
        val COMMANDS = setOf("/新对话", "新对话", "/new", "/帮助", "/help")

        /**
         * 发到微信前去掉引用编号：正文里的「[1]」「[2][3]」（来源列表不发了，编号留着就是对不上号的数字），
         * 还有模型自己在末尾写的「来源：/ 参考：」那一段（后面跟的全是编号或网址的才算，免得把正文里的「来源：」删了）。
         */
        fun withoutCitations(text: String): String {
            var t = text.replace(Regex("""(?:\[\d{1,2}\])+"""), "")
            val tail = Regex("""\n+[ \t]*(?:来源|参考|参考资料|引用|出处|Sources?)[:：][ \t]*\n(?:[^\n]*(?:https?://|www\.)[^\n]*(?:\n|$))+\s*$""")
            t = tail.replace(t, "")
            return t.replace(Regex("""[ \t]+\n"""), "\n").trim()
        }
        /** 会话过期（要重新扫码），官方插件遇到后暂停一小时。 */
        const val STALE_TOKEN = -14
        /** 一条微信消息最长发多少字，再长就拆开。 */
        const val CHUNK = 1800
        /** 电脑刚打开、绑定给过手机时，积压的消息先等这么久（手机连上来会说它答到了哪儿）。 */
        const val BACKLOG_HOLD_MS = 30_000L

        /** 微信不显示 Markdown：把标题、加粗、列表、链接、表格分隔线换成普通文字。 */
        fun plainText(md: String): String {
            var t = com.guixing.jixunying.model.MathText.plain(md).replace("\r\n", "\n")
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

        /** 一个微信的文件夹名（botId 里有 @ 之类的字符）。 */
        fun folderOf(botId: String) = botId.replace(Regex("""[^A-Za-z0-9_.-]"""), "_").ifBlank { "bot" }
    }

    /** 给交接（WeixinHandover）看的一个微信：不含凭证。 */
    data class BotRef(val botId: String, val userId: String, val shared: Boolean, val boundBy: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rnd = SecureRandom()
    /** 每个微信一个文件夹：bots/<botId>/account.json、cursor.json（2026-10-30 多微信；以前只有 weixin/account.json 一份，启动时搬过去）。 */
    private val botsDir = File(dir, "bots")
    private val bots = java.util.concurrent.CopyOnWriteArrayList<Bot>()
    private var loginJob: Job? = null
    @Volatile private var pendingVerify: String? = null
    /** 正在扫码绑定的情况（一次只绑一个）：说明、二维码链接、要不要输数字、是谁发起的（手机遥控电脑时是那台手机）。 */
    @Volatile private var loginStatus = ""
    @Volatile private var loginLink = ""
    @Volatile private var loginNeedCode = false
    @Volatile private var loginBy = ""
    private val publishLock = Any()

    /** 别的设备在接这个微信时返回给人看的原因（手机上：配对的电脑在线、在接它），这台就不收它的消息。 */
    @Volatile var standby: (BotRef) -> String? = { null }
    /** 答完一条微信消息（哪个微信、那条消息的 create_time_ms）。手机用它告诉电脑答到了哪儿。 */
    @Volatile var onAnswered: (String, Long) -> Unit = { _, _ -> }

    val isBound: Boolean get() = bots.isNotEmpty()
    val isShared: Boolean get() = bots.any { it.account.shared }
    /** 打头那个微信（电脑自己绑的优先）。 */
    val botId: String get() = primary()?.account?.botId.orEmpty()
    val answeredUntil: Long get() = bots.maxOfOrNull { it.cursor.answeredUntil } ?: 0
    fun refs(): List<BotRef> = bots.map { it.ref() }
    fun answeredUntil(botId: String): Long = bots.firstOrNull { it.account.botId == botId }?.cursor?.answeredUntil ?: 0

    /**
     * 微信对话（channel = weixin:<userId>）里 AI 能不能读电脑上的文件：看是哪个微信发来的。
     * 不认识的（解绑了的旧对话）照以前，能读收录范围。
     */
    fun readFilesFor(userId: String): Boolean = bots.firstOrNull { it.account.userId == userId }?.account?.readFiles ?: true

    init {
        dir.mkdirs()
        migrateOld()
        botsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { home ->
            load(File(home, "account.json"), WeixinAccount.serializer())?.let { Bot(it, home, load(File(home, "cursor.json"), WeixinCursor.serializer())) }
        }?.sortedBy { it.account.boundAt }?.let { bots.addAll(it) }
    }

    /** 以前只有一份 weixin/account.json：搬进 bots/<botId>/，算这台设备自己绑的（能读文件，和以前一样），不用重扫。 */
    private fun migrateOld() {
        val old = File(dir, "account.json")
        if (!old.isFile) return
        val acc = load(old, WeixinAccount.serializer()) ?: return
        val home = File(botsDir, folderOf(acc.botId)).apply { mkdirs() }
        runCatching {
            java.nio.file.Files.move(old.toPath(), File(home, "account.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            File(dir, "cursor.json").takeIf { it.isFile }?.let {
                java.nio.file.Files.move(it.toPath(), File(home, "cursor.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun primary(): Bot? = bots.firstOrNull { !it.account.shared && it.account.boundBy.isEmpty() } ?: bots.firstOrNull()

    private fun <T> load(f: File, ser: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { AppJson.decodeFromString(ser, f.readText(Charsets.UTF_8)) }.getOrNull()

    private fun <T> store(f: File, ser: kotlinx.serialization.KSerializer<T>, v: T) = runCatching {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(AppJson.encodeToString(ser, v), Charsets.UTF_8)
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** 把几个微信的情况汇总给界面（上面几项给老版本手机看，bots 是每一个的）。 */
    private fun publish() = synchronized(publishLock) {
        val list = bots.map { it.view() }
        val p = primary()
        val summary = when {
            list.isEmpty() -> "未绑定"
            list.size == 1 -> list[0].status
            else -> "${list.size} 个微信，${list.count { it.answering }} 个在收消息"
        }
        engine.setWeixinInfo(WeixinInfo(
            bound = list.isNotEmpty(),
            status = loginStatus.ifBlank { summary },
            loginLink = loginLink,
            needVerifyCode = loginNeedCode,
            boundAt = p?.account?.boundAt ?: 0,
            lastMessageAt = list.maxOfOrNull { it.lastMessageAt } ?: 0,
            answering = list.any { it.answering },
            botId = p?.account?.botId.orEmpty(),
            shared = p?.account?.shared == true,
            bots = list,
        ))
    }

    private fun setLogin(status: String = loginStatus, link: String = loginLink, needCode: Boolean = loginNeedCode) {
        loginStatus = status; loginLink = link; loginNeedCode = needCode
        publish()
    }

    fun start() {
        publish()
        bots.forEach { it.startMonitor() }
    }

    fun stop() {
        loginJob?.cancel()
        bots.forEach { it.stop() }
    }

    /** 加一个微信（扫码绑好的、或者电脑同步来的）：同一个微信（同一个人）原来那份换掉。 */
    private fun addBot(acc: WeixinAccount, cursor: WeixinCursor): Bot {
        val same = bots.filter { (acc.userId.isNotEmpty() && it.account.userId == acc.userId) || it.account.botId == acc.botId }
        // 同一个人重绑：开关照原来的（换了绑的人才用默认）
        val keepRead = same.firstOrNull { it.account.boundBy == acc.boundBy && it.account.shared == acc.shared }?.account?.readFiles
        same.forEach { it.halt(); bots.remove(it); if (it.home.name != folderOf(acc.botId)) it.home.deleteRecursively() }
        val final = if (keepRead != null) acc.copy(readFiles = keepRead) else acc
        val bot = Bot(final, File(botsDir, folderOf(final.botId)), cursor)
        bot.save()
        bots += bot
        publish()
        bot.startMonitor()
        return bot
    }

    private fun removeBot(bot: Bot) {
        bot.halt()
        bots.remove(bot)
        bot.home.deleteRecursively()
    }

    /** 电脑上：这个微信里 AI 能不能读电脑上的文件。 */
    fun setReadFiles(botId: String, on: Boolean): CommandResult {
        val bot = bots.firstOrNull { it.account.botId == botId } ?: return CommandResult(false, "没有这个微信")
        bot.account = bot.account.copy(readFiles = on)
        bot.save()
        publish()
        return CommandResult()
    }

    /** 另一台设备答到了 t：t 以前（含）发来的这台不再答。botId 空 = 老版本手机报的，算这台设备自己绑的那几个。 */
    fun raiseFence(botId: String, t: Long) {
        bots.filter { if (botId.isEmpty()) !it.account.shared && it.account.boundBy.isEmpty() else it.account.botId == botId }
            .forEach { b -> if (t > b.cursor.answerAfter) b.editCursor { it.copy(answerAfter = maxOf(it.answerAfter, t)) } }
    }

    fun raiseFence(t: Long) = raiseFence("", t)

    /** 电脑上：把打头那个绑定交给配对的手机（老版本手机要的那一份）。手机从现在起才开始算，之前的消息电脑已经答过。 */
    fun shareAccount(): String? {
        val b = bots.firstOrNull { !it.account.shared && it.account.boundBy.isEmpty() } ?: bots.firstOrNull { !it.account.shared } ?: return null
        return AppJson.encodeToString(WeixinShareBundle.serializer(), b.bundle())
    }

    /** 电脑上：这台手机该拿的几份——它自己遥控电脑时绑的，加上电脑自己绑的（电脑关了由接班的手机答）。别的手机绑的不给。 */
    fun shareFor(deviceId: String): String =
        AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(WeixinShareBundle.serializer()),
            bots.filter { !it.account.shared && (it.account.boundBy.isEmpty() || it.account.boundBy == deviceId) }.map { it.bundle() })

    /** 手机上：存下电脑交过来的一份绑定（老版本电脑只给一份）。 */
    fun importShared(json: String): CommandResult {
        // 「现在同步」按钮拿的是 WeixinShareAll 的列表时也走这里
        if (json.trimStart().startsWith("[")) return importSharedAll(json)
        val b = runCatching { AppJson.decodeFromString(WeixinShareBundle.serializer(), json) }.getOrNull()
            ?: return CommandResult(false, "电脑发来的微信绑定看不懂（两边版本不一样？）")
        importBundles(listOf(b), full = false)
        return CommandResult(message = "已同步电脑上的微信绑定")
    }

    /** 手机上：存下电脑交过来的全部（WeixinShareAll）；电脑那边已经没有的同步来的那份删掉。 */
    fun importSharedAll(json: String): CommandResult {
        val list = runCatching { AppJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(WeixinShareBundle.serializer()), json) }.getOrNull()
            ?: return CommandResult(false, "电脑发来的微信绑定看不懂（两边版本不一样？）")
        importBundles(list, full = true)
        return CommandResult(message = if (list.isEmpty()) "电脑上没有这台手机要接的微信" else "已同步电脑上的 ${list.size} 个微信绑定")
    }

    private fun importBundles(list: List<WeixinShareBundle>, full: Boolean) {
        for (b in list) {
            val acc = b.account.copy(shared = true)
            val mine = bots.firstOrNull { it.account.botId == acc.botId }
            // 已经有了：只更新开关、谁绑的（凭证和进度不动）
            if (mine != null && mine.account.shared) {
                if (mine.account.readFiles != acc.readFiles || mine.account.boundBy != acc.boundBy) {
                    mine.account = mine.account.copy(readFiles = acc.readFiles, boundBy = acc.boundBy); mine.save(); publish()
                }
                continue
            }
            // 这台手机自己扫码绑的同一个微信：不动
            if (bots.any { !it.account.shared && acc.userId.isNotEmpty() && it.account.userId == acc.userId }) continue
            addBot(acc, WeixinCursor(answerAfter = b.answerAfter))
        }
        if (full) {
            val keep = list.map { it.account.botId }.toSet()
            bots.filter { it.account.shared && it.account.botId !in keep }.forEach(::removeBot)
            publish()
        }
    }

    /** 手机上：电脑解除了绑定，同步来的这份也删掉（不通知微信，绑定是电脑的）。botId 空 = 同步来的全删。 */
    fun forgetShared(botId: String = "") {
        val gone = bots.filter { it.account.shared && (botId.isEmpty() || it.account.botId == botId) }
        if (gone.isEmpty()) return
        gone.forEach(::removeBot)
        if (bots.isEmpty()) loginStatus = "电脑上解除了微信绑定"
        publish()
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
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray

    // ———————————————— 绑定 ————————————————

    /**
     * 扫码绑一个微信（已经绑了的照样能再绑一个；同一个微信再扫只换掉它自己那份）。
     * boundBy：手机遥控电脑时发起的，是那台手机的编号（这个微信记成它绑的，电脑关了由它接，默认只聊天、不读电脑上的文件）。
     */
    suspend fun login(boundBy: String = ""): CommandResult {
        loginJob?.cancel()
        pendingVerify = null
        loginBy = boundBy
        // 官方插件把本机已有的几份凭证都带上（同一个微信重扫时微信那边认得出来）
        val resp = post(loginBase, "ilink/bot/get_bot_qrcode?bot_type=3",
            buildJsonObject { putJsonArray("local_token_list") { bots.filter { !it.account.shared }.forEach { add(it.account.botToken) } } }, null, 15_000)
        val qrcode = resp.str("qrcode") ?: return CommandResult(false, "微信没有返回二维码：${resp.toString().take(200)}")
        val link = resp.str("qrcode_img_content").orEmpty()
        setLogin("用手机微信扫一扫下面的二维码", link, false)
        loginJob = scope.launch { pollLogin(qrcode) }
        return CommandResult(message = "二维码已生成", data = link)
    }

    fun verify(code: String): CommandResult {
        if (code.isBlank()) return CommandResult(false, "数字是空的")
        pendingVerify = code.trim()
        setLogin(status = "已提交，正在核对…", needCode = false)
        return CommandResult()
    }

    /** 取消正在扫的码。 */
    fun cancelLogin() {
        loginJob?.cancel()
        setLogin("", "", false)
    }

    /** 解除绑定：botId 空 = 全部（老版本界面只有一个）。 */
    suspend fun logout(botId: String = ""): CommandResult {
        val targets = bots.filter { botId.isEmpty() || it.account.botId == botId }
        if (targets.isEmpty()) return CommandResult(false, "没有这个微信")
        if (botId.isEmpty()) cancelLogin()
        for (b in targets) {
            val acc = b.account
            // 从电脑同步来的绑定：只删手机上这份，电脑照常
            if (!acc.shared) runCatching { post(baseOf(acc), "ilink/bot/msg/notifystop", buildJsonObject { put("base_info", baseInfo()) }, acc.botToken, 5_000) }
            removeBot(b)
        }
        loginStatus = ""
        publish()
        return CommandResult(message = if (targets.all { it.account.shared }) "这台手机不再用电脑上的这份微信绑定，电脑上照常。"
            else "已解除绑定。微信里那个助理的对话可以删掉。")
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
                    if (!loginStatus.startsWith("已扫码")) setLogin(status = "已扫码，请在手机微信上点「确认」", needCode = false)
                }
                "need_verifycode" -> {
                    val wrong = pendingVerify != null
                    pendingVerify = null
                    setLogin(status = if (wrong) "数字不对，请重新输入手机微信上显示的数字" else "请输入手机微信上显示的数字", needCode = true)
                    while (pendingVerify == null && System.currentTimeMillis() < deadline) delay(500)
                    continue
                }
                "expired", "verify_code_blocked" -> {
                    refresh++
                    if (refresh > 3) {
                        setLogin("二维码多次过期，请重新点「绑定微信」", "", false); return
                    }
                    val r = runCatching { post(loginBase, "ilink/bot/get_bot_qrcode?bot_type=3", buildJsonObject { putJsonArray("local_token_list") {} }, null, 15_000) }.getOrNull()
                    val next = r?.str("qrcode")
                    if (next == null) { setLogin("刷新二维码失败，请重新点「绑定微信」", "", loginNeedCode); return }
                    qrcode = next
                    base = loginBase
                    setLogin("二维码过期了，已换新的，请重新扫", r.str("qrcode_img_content").orEmpty(), false)
                }
                "scaned_but_redirect" -> st.str("redirect_host")?.let { base = "https://$it" }
                "binded_redirect" -> {
                    setLogin(if (bots.isNotEmpty()) "这个微信已经绑定过了，可以直接用" else "微信说已经绑定过；如果用不了，先在微信里删掉助理再重新绑定", "", false)
                    return
                }
                "confirmed" -> {
                    val token = st.str("bot_token")
                    val botId = st.str("ilink_bot_id")
                    if (token.isNullOrBlank() || botId.isNullOrBlank()) {
                        setLogin("绑定失败：微信没有返回凭证", "", false); return
                    }
                    val by = loginBy
                    val acc = WeixinAccount(token, botId, st.str("ilink_user_id").orEmpty(), st.str("baseurl").orEmpty(), System.currentTimeMillis(),
                        boundBy = by, readFiles = by.isEmpty())
                    loginStatus = ""; loginLink = ""; loginNeedCode = false
                    addBot(acc, WeixinCursor())
                    return
                }
            }
            delay(1_000)
        }
        setLogin("等太久了，二维码已失效，请重新点「绑定微信」", "", false)
    }

    /** 第一条到了以后等多久再答：连发的几条攒在一起（测试里调小）。 */
    internal var batchWaitMs = 1_500L

    /** 一条微信消息拆出来的东西：文字、附件、回复要带的 context_token、对方发出的时间。 */
    private class Incoming(val text: String, val attIds: List<String>, val ctx: String?, val at: Long)

    private fun clientId() = "jxy-${System.currentTimeMillis()}-${bytesToHex(ByteArray(4).also(rnd::nextBytes))}"

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

    private class Uploaded(val downloadParam: String, val aesHex: String, val rawSize: Int, val cipherSize: Int)

    /** 挂着的一个微信：自己的凭证、进度、收消息的循环、每个人一条队列。 */
    private inner class Bot(@Volatile var account: WeixinAccount, val home: File, initial: WeixinCursor?) {
        private val cursorFile = File(home, "cursor.json")
        @Volatile var cursor: WeixinCursor = initial ?: WeixinCursor()
        private val cursorLock = Any()
        private var monitorJob: Job? = null
        /** 每个人一条队列、一个处理者：连发几条时严格按收到的顺序答（以前每条各自抢锁，先发的可能后答）。 */
        private val queues = ConcurrentHashMap<String, kotlinx.coroutines.channels.Channel<JsonObject>>()
        private val typingTickets = ConcurrentHashMap<String, String>()
        private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        @Volatile private var monitorStartedAt = 0L
        @Volatile private var status = "正在连接…"
        @Volatile private var answering = false
        @Volatile private var lastMessageAt = 0L

        fun ref() = BotRef(account.botId, account.userId, account.shared, account.boundBy)

        fun view() = WeixinBot(account.botId, account.userId, status, answering, account.boundAt, lastMessageAt, account.boundBy, account.readFiles, account.shared)

        fun bundle(): WeixinShareBundle {
            val now = System.currentTimeMillis()
            editCursor { it.copy(sharedAt = now) }
            return WeixinShareBundle(account.copy(shared = true), now)
        }

        fun save() {
            store(File(home, "account.json"), WeixinAccount.serializer(), account)
            store(cursorFile, WeixinCursor.serializer(), cursor)
        }

        fun editCursor(f: (WeixinCursor) -> WeixinCursor) = synchronized(cursorLock) {
            cursor = f(cursor)
            store(cursorFile, WeixinCursor.serializer(), cursor)
        }

        private fun show(s: String, on: Boolean = answering) {
            if (s == status && on == answering) return
            status = s; answering = on
            publish()
        }

        /** 停下来不说下线（解绑、换掉、重新同步时）。 */
        fun halt() { monitorJob?.cancel() }

        fun stop() {
            val wasAnswering = answering
            halt()
            // 只有正在接的那台说「我下线了」（待命的手机说了，微信会以为电脑也下线了）
            if (wasAnswering) runCatching { kotlinx.coroutines.runBlocking { post(baseOf(account), "ilink/bot/msg/notifystop", buildJsonObject { put("base_info", baseInfo()) }, account.botToken, 3_000) } }
        }

        // ———————————————— 收消息 ————————————————

        fun startMonitor() {
            monitorJob?.cancel()
            monitorJob = scope.launch {
                var failures = 0
                var pollMs = 35_000L
                // 开始收（或者从暂停 / 待命恢复）时告诉微信「我在线」；待命时不说下线，接着的那台还在线
                var live = false
                while (isActive) {
                    val acc = account
                    if (!engine.state.settings.weixin.enabled) {
                        show("已暂停", false)
                        live = false
                        delay(3_000); continue
                    }
                    val other = standby(ref())
                    if (other != null) {
                        show(other, false)
                        live = false
                        delay(2_000); continue
                    }
                    if (!live) {
                        live = true
                        monitorStartedAt = System.currentTimeMillis()
                        runCatching { post(baseOf(acc), "ilink/bot/msg/notifystart", buildJsonObject { put("base_info", baseInfo()) }, acc.botToken, 10_000) }
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
                            show(if (acc.shared) "微信那边的会话过期了：电脑上重新扫码绑定后，这台手机会自动同步"
                                else "微信那边的会话过期了，请重新扫码绑定（一小时后会再自动试一次）", false)
                            delay(if (acc.shared) 60_000L else 60 * 60_000L)
                            continue
                        }
                        if (ret != 0 || err != 0) {
                            failures++
                            // 偶尔一次出错不算断（不然配对的手机会马上抢着接）
                            show("微信返回错误（$ret / $err）：${resp.str("errmsg").orEmpty().take(60)}", answering && failures < 3)
                            delay(if (failures >= 3) 30_000 else 2_000)
                            continue
                        }
                        failures = 0
                        resp.int("longpolling_timeout_ms")?.takeIf { it > 0 }?.let { pollMs = it.toLong() }
                        resp.str("get_updates_buf")?.takeIf { it.isNotEmpty() }?.let { buf -> editCursor { it.copy(buf = buf) } }
                        if (!status.startsWith("已连接") || !answering) show("已连接：在微信里给助理发消息就行", true)
                        resp.arr("msgs")?.forEach { m -> (m as? JsonObject)?.let(::handle) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failures++
                        show("连不上微信（${engine.friendlyError(e).take(60)}），稍后自动重试", answering && failures < 3)
                        delay(if (failures >= 3) 30_000 else 2_000)
                    }
                }
            }
        }

        private fun msgId(m: JsonObject): String? =
            m.str("message_id") ?: m.arr("item_list")?.firstNotNullOfOrNull { (it as? JsonObject)?.str("msg_id") }

        /** 一条微信消息：先在收消息的循环里过一遍（是不是本人、收过没有），再排进这个人的队列，按顺序转给 AI、答完发回去。 */
        private fun handle(m: JsonObject) {
            val from = m.str("from_user_id") ?: return
            if ((m.int("message_type") ?: 1) != 1) return
            // 只回答扫码绑定的本人（和官方插件的默认规则一样）。别人能给这个助理发消息，
            // 不拦的话陌生人就能用你的 AI、还能让它搜你电脑上的文档。
            val owner = account.userId.trim()
            if (owner.isNotEmpty() && from != owner) return
            msgId(m)?.let { id ->
                if (!seen.add(id)) return
                if (seen.size > 500) synchronized(seen) { seen.iterator().let { it.next(); it.remove() } }
            }
            m.str("context_token")?.let { tok -> editCursor { it.copy(contextTokens = it.contextTokens + (from to tok)) } }
            val at = m.long("create_time_ms") ?: 0L
            // 另一台设备接着的时候发来的，它已经答过了
            if (at in 1..cursor.answerAfter) return
            lastMessageAt = System.currentTimeMillis()
            publish()
            // 电脑刚打开、绑定同步给过手机：积压的消息先等一会儿，手机连上来会说它答到了哪儿，答过的就不再答
            val wait = monitorStartedAt + backlogHoldMs - System.currentTimeMillis()
            if (cursor.sharedAt > 0 && at in 1 until monitorStartedAt && wait > 0) {
                scope.launch { delay(wait); if (at > cursor.answerAfter) enqueue(from, m) }
                return
            }
            enqueue(from, m)
        }

        private fun enqueue(from: String, m: JsonObject) {
            // 收消息的循环和上面「等一会儿」的协程都会调到这里：加锁，免得并发建出两个处理者
            synchronized(queues) {
                queues.getOrPut(from) {
                    kotlinx.coroutines.channels.Channel<JsonObject>(kotlinx.coroutines.channels.Channel.UNLIMITED).also { ch ->
                        scope.launch {
                            for (first in ch) {
                                // 连发几条、或者 AI 答上一句时又来了几条：等一小会儿，攒在一起答一次（2026-10-30 用户：「后来新的那个话题，它似乎没有看到」）
                                delay(batchWaitMs)
                                val batch = mutableListOf(first)
                                while (true) batch += ch.tryReceive().getOrNull() ?: break
                                try {
                                    processBatch(from, batch)
                                } catch (e: CancellationException) {
                                    // 整个助理在关：退出；只是这一批被取消：接着处理下一批（处理者没了，后面的消息就永远没人管）
                                    if (!isActive) throw e
                                } catch (_: Throwable) {
                                }
                                batch.forEach { markAnswered(it) }
                            }
                        }
                    }
                }
            }.trySend(m)
        }

        private fun markAnswered(m: JsonObject) {
            val at = m.long("create_time_ms") ?: return
            if (at > cursor.answeredUntil) editCursor { it.copy(answeredUntil = maxOf(it.answeredUntil, at)) }
            runCatching { onAnswered(account.botId, at) }
        }

        /**
         * 一批消息：「/新对话」这种命令照顺序单独处理，命令之间的几条一起放进对话、答一次；
         * 回复带最后一条的 context_token（答的是最新的）。
         */
        private suspend fun processBatch(from: String, batch: List<JsonObject>) {
            val pending = mutableListOf<Incoming>()
            for (m in batch) {
                val inc = parse(from, m)
                if (inc.text in COMMANDS) {
                    if (pending.isNotEmpty()) { turn(from, pending.toList()); pending.clear() }
                    command(from, inc)
                } else if (inc.text.isNotBlank() || inc.attIds.isNotEmpty()) pending += inc
            }
            if (pending.isNotEmpty()) turn(from, pending)
        }

        private fun membersFor(): List<String> {
            val ws = engine.state.settings.weixin
            return ws.memberIds.filter { engine.state.member(it) != null }.ifEmpty { engine.state.members.take(1).map { it.id } }
        }

        private suspend fun command(from: String, inc: Incoming) {
            val ws = engine.state.settings.weixin
            val members = membersFor()
            if (members.isEmpty()) { sendText(from, inc.ctx, "电脑上的 AI集训营 还没有 AI 成员，先在电脑上添加一位。"); return }
            when (inc.text) {
                "/新对话", "新对话", "/new" -> {
                    engine.newChannelConversation("weixin:$from", "微信对话", members, ws.webSearch)
                    sendText(from, inc.ctx, "好，换个新话题。之前的聊天还留在电脑上。")
                }
                "/帮助", "/help" ->
                    sendText(from, inc.ctx, "直接发文字、图片、文件（PDF、Word、Excel 等）就行，${members.size} 位 AI 成员在电脑上回答。\n发「/新对话」开始一个新话题。\n想让 AI 画图就说「画一张……」。")
            }
        }

        private suspend fun turn(from: String, items: List<Incoming>) {
            val ctx = items.last().ctx
            val ws = engine.state.settings.weixin
            val members = membersFor()
            if (members.isEmpty()) { sendText(from, ctx, "电脑上的 AI集训营 还没有 AI 成员，先在电脑上添加一位。"); return }
            val convId = engine.channelConversation("weixin:$from", "微信对话", members, ws.webSearch)
            val typingJob = scope.launch {
                while (isActive) { typing(from, ctx, true); delay(8_000) }
            }
            val replies = try {
                engine.channelTurn(convId, items.map { Engine.ChannelInput(it.text, it.attIds, it.at) })
            } finally {
                typingJob.cancel()
                typing(from, ctx, false)
            }
            if (replies.isEmpty()) { sendText(from, ctx, "这次没答上来，可以在电脑上的「微信对话」里看看出了什么问题。"); return }
            val multi = replies.map { it.senderId }.distinct().size > 1
            for (r in replies) sendReply(from, ctx, r, multi)
        }

        /** 拆一条微信消息：文字、引用、语音转的字；图片和文件下载下来存成附件。 */
        private suspend fun parse(from: String, m: JsonObject): Incoming {
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
            return Incoming(parts.joinToString("\n").trim(), attIds, ctx, m.long("create_time_ms") ?: 0L)
        }

        private suspend fun sendReply(to: String, ctx: String?, r: Message, withName: Boolean) {
            val name = engine.memberName(r.senderId) ?: "AI"
            val body = when (r.status) {
                MsgStatus.ERROR -> "出错了：${r.error.take(300)}"
                MsgStatus.STOPPED -> plainText(r.content).ifBlank { "（在电脑上被停止了）" }
                else -> plainText(r.content.replace(Regex("<think>[\\s\\S]*?</think>"), ""))
            }
            // 来源列表不发到微信，正文里的 [1][2] 编号也去掉（2026-10-30 用户：「每次回答后面都加入来源最下面这一段，传到微信的时候自动过滤」）；
            // 出处在电脑、手机的 AI集训营 里照样看得到
            val text = buildString {
                if (withName) append("【").append(name).append("】\n")
                append(withoutCitations(body))
            }.trim()
            if (text.isNotBlank()) chunks(text).forEach { sendText(to, ctx, it) }
            for (a in r.attachments.filter { it.kind == AttachmentKind.GENERATED_IMAGE }) {
                val bytes = engine.fileBytes(a.id) ?: continue
                runCatching { sendImage(to, ctx, bytes) }.onFailure { sendText(to, ctx, "（画好了一张图，但发到微信失败了：${it.message?.take(80)}。可以在 AI集训营 里看。）") }
            }
            // AI 做的 Word / Excel 也发回微信；视频按视频消息发（2026-10-30 用户：「微信聊天里边用 MiniMax 生成的两个视频文件都读不到，qwen 生成的视频可以读到」——
            // 以前视频也当普通文件发（media_type 3、消息 type 4），微信里只是一个附件、不能直接播，大的打不开；官方插件 send-media.js 是 video/* 走视频）
            for (a in r.attachments.filter { it.generated && it.kind == AttachmentKind.DOCUMENT }) {
                val bytes = engine.fileBytes(a.id) ?: continue
                val video = a.mime.startsWith("video/") || a.name.substringAfterLast('.').lowercase() in setOf("mp4", "mov", "webm", "m4v")
                runCatching { if (video) sendVideo(to, ctx, bytes) else sendFile(to, ctx, a.name, bytes) }
                    .onFailure { sendText(to, ctx, "（做好了《${a.name}》，但发到微信失败了：${it.message?.take(80)}。可以在 AI集训营 里打开。）") }
            }
        }

        // ———————————————— 发消息 ————————————————

        private suspend fun sendItem(to: String, ctx: String?, item: JsonObject) {
            val acc = account
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
            val acc = account
            runCatching {
                val ticket = typingTickets[to] ?: post(baseOf(acc), "ilink/bot/getconfig",
                    buildJsonObject { put("ilink_user_id", to); if (ctx != null) put("context_token", ctx); put("base_info", baseInfo()) }, acc.botToken, 10_000)
                    .str("typing_ticket")?.also { typingTickets[to] = it } ?: return
                post(baseOf(acc), "ilink/bot/sendtyping",
                    buildJsonObject { put("ilink_user_id", to); put("typing_ticket", ticket); put("status", if (on) 1 else 2); put("base_info", baseInfo()) },
                    acc.botToken, 10_000)
            }
        }

        /** 把图片加密传到微信 CDN，再发一条图片消息。 */
        private suspend fun sendImage(to: String, ctx: String?, plain: ByteArray) {
            val u = uploadMedia(to, plain, 1)
            sendItem(to, ctx, buildJsonObject {
                put("type", 2)
                putJsonObject("image_item") {
                    putJsonObject("media") {
                        put("encrypt_query_param", u.downloadParam)
                        put("aes_key", Base64.getEncoder().encodeToString(u.aesHex.toByteArray()))
                        put("encrypt_type", 1)
                    }
                    put("mid_size", u.cipherSize)
                }
            })
        }

        /** 把文件（AI 做的 Word / Excel）加密传到微信 CDN，再发一条文件消息（照官方插件 sendFileMessageWeixin：上传 media_type 3，消息 type 4）。 */
        private suspend fun sendFile(to: String, ctx: String?, name: String, plain: ByteArray) {
            val u = uploadMedia(to, plain, 3)
            sendItem(to, ctx, buildJsonObject {
                put("type", 4)
                putJsonObject("file_item") {
                    putJsonObject("media") {
                        put("encrypt_query_param", u.downloadParam)
                        put("aes_key", Base64.getEncoder().encodeToString(u.aesHex.toByteArray()))
                        put("encrypt_type", 1)
                    }
                    put("file_name", name)
                    put("len", u.rawSize.toString())
                }
            })
        }

        /**
         * 视频照官方插件 sendVideoMessageWeixin（@tencent-weixin/openclaw-weixin 2.4.10 dist/src/messaging/send.js）：
         * 上传 media_type 2（UploadMediaType.VIDEO），消息 type 5（MessageItemType.VIDEO），video_item 里是 media 和 video_size（密文字节数）；不用缩略图。
         */
        private suspend fun sendVideo(to: String, ctx: String?, plain: ByteArray) {
            val u = uploadMedia(to, plain, 2)
            sendItem(to, ctx, buildJsonObject {
                put("type", 5)
                putJsonObject("video_item") {
                    putJsonObject("media") {
                        put("encrypt_query_param", u.downloadParam)
                        put("aes_key", Base64.getEncoder().encodeToString(u.aesHex.toByteArray()))
                        put("encrypt_type", 1)
                    }
                    put("video_size", u.cipherSize)
                }
            })
        }

        /** 加密（AES-128-ECB）传到微信 CDN。mediaType：1 图片，2 视频，3 文件。 */
        private suspend fun uploadMedia(to: String, plain: ByteArray, mediaType: Int): Uploaded {
            val acc = account
            val filekey = bytesToHex(ByteArray(16).also(rnd::nextBytes))
            val aes = ByteArray(16).also(rnd::nextBytes)
            val cipher = aesEcb(plain, aes, encrypt = true)
            val md5 = bytesToHex(MessageDigest.getInstance("MD5").digest(plain))
            val up = post(baseOf(acc), "ilink/bot/getuploadurl", buildJsonObject {
                put("filekey", filekey)
                put("media_type", mediaType)
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
            return Uploaded(dp, bytesToHex(aes), plain.size, cipher.size)
        }
    }
}
