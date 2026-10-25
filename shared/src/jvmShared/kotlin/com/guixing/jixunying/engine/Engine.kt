package com.guixing.jixunying.engine

import com.guixing.jixunying.client.Backend
import com.guixing.jixunying.client.ClientStore
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.AccountInfo
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.BgJob
import com.guixing.jixunying.model.CodeMode
import com.guixing.jixunying.model.CodeSpec
import com.guixing.jixunying.model.TeamSeat
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.DocHit
import com.guixing.jixunying.model.EmbedChoice
import com.guixing.jixunying.model.EmbedModels
import com.guixing.jixunying.model.EmbedPick
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.HistoryHit
import com.guixing.jixunying.model.ImageChoice
import com.guixing.jixunying.model.ImagePick
import com.guixing.jixunying.model.VideoMediaUse
import com.guixing.jixunying.model.VideoPick
import com.guixing.jixunying.model.JudgePick
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.PairedDevice
import com.guixing.jixunying.model.Presets
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.ReplyMode
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SearchSource
import com.guixing.jixunying.model.SemanticEstimatePart
import com.guixing.jixunying.model.SemanticEstimateResult
import com.guixing.jixunying.model.SemanticPart
import com.guixing.jixunying.model.SemanticProgress
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.model.SpeechAudio
import com.guixing.jixunying.model.SpeechModels
import com.guixing.jixunying.model.SpeechPick
import com.guixing.jixunying.model.SpeechPlan
import com.guixing.jixunying.model.SpeechUnits
import com.guixing.jixunying.model.StanceEntry
import com.guixing.jixunying.model.StanceOption
import com.guixing.jixunying.model.StanceTopic
import com.guixing.jixunying.model.Stances
import com.guixing.jixunying.model.Thinking
import com.guixing.jixunying.model.ToolStep
import com.guixing.jixunying.model.USER_ID
import com.guixing.jixunying.model.Usage
import com.guixing.jixunying.model.UsageKinds
import com.guixing.jixunying.model.UsageRecord
import com.guixing.jixunying.model.isMaskedKey
import com.guixing.jixunying.model.maskKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.ConfigBundle
import com.guixing.jixunying.model.MemoryBundle
import com.guixing.jixunying.model.MemoryTombstone
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val PAINTER_ID = "tool:image"
/** 「直接做视频」的回答（不经过 AI）。 */
const val VIDEO_ID = "tool:video"
/** 会审模式里写汇总的「主席」（用裁判模型，不是成员）。 */
const val CHAIR_ID = "tool:chair"
/** Code 栏的回答（编码循环里的模型，不是成员）。 */
const val CODE_ID = "tool:code"

/** 一次回答最多调几次工具（搜索、读网页、画图）。 */
/** 一次回答最多用几次工具（2026-10-17 从 8 放到 40：对话栏也要能多步干活，读文件、改文件常常要十几步）。 */
const val MAX_TOOL_USES = 40

const val NO_IMAGE_MODEL = "还没有能画图的服务商。到 设置 → 模型服务 添加任意一家能画图的平台并填 Key：千问AI平台或阿里百炼（千问图像，画里的中文字写得准）、" +
    "火山方舟（豆包 Seedream）、MiniMax（image-01）、智谱开放平台（cogview-3-flash 免费，但写不好字）等都行。加好以后在聊天里说「画一张……」就会自动用它，不用别的设置。"

/**
 * 引擎：电脑和手机各有一个，都能单独用。数据、Key、模型调用都在本机。
 * 电脑上的引擎还会通过 RelayHost 接受配对手机的遥控。
 */
class Engine(
    private val storage: Storage,
    private val isPhone: Boolean = false,
    /** 启动后自动扫描本机文档。自动测试里关掉（-Djxy.docs.autoscan=false），免得去扫开发机上真实的文档。 */
    private val autoScanDocs: Boolean = System.getProperty("jxy.docs.autoscan") != "false",
    /** 启动后自动联网更新美元汇率。自动测试里关掉（-Djxy.fx.auto=false），免得每个测试都去联网。 */
    private val autoUsdRate: Boolean = System.getProperty("jxy.fx.auto") != "false",
    /** 读平台链接用的浏览器（测试里换成假的）。 */
    private val browserFactory: (java.io.File) -> PageBrowser? = { createPageBrowser(it) },
) : Backend {
    override val store = ClientStore()
    override val isHost = true
    override val conn: StateFlow<ConnState> = MutableStateFlow(ConnState.Local)

    /** 同步回调（不用 Flow），保证事件和指令回执在线路上的先后顺序不乱。 */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Event) -> Unit>()

    fun addListener(l: (Event) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    @Volatile var state: AppState = storage.loadState() ?: AppState()
        private set
    private val convs = ConcurrentHashMap<String, MutableList<Message>>()
    private val running = ConcurrentHashMap<String, MutableSet<Job>>()

    private fun proxyFor(useProxy: Boolean) = if (useProxy) state.settings.proxy.trim().ifEmpty { null } else null
    private val llm = LlmClient { proxyFor(it.useProxy) }
    /** 用 ChatGPT 登录（2026-10-23）：凭据只在本机 chatgpt/auth.json，不进 state.json、不同步。OpenAI 在国内要走代理。 */
    private val chatgptAuth = ChatGptAuth(java.io.File(storage.root, "chatgpt"), proxy = { proxyFor(true) })
    @Volatile private var chatgptPending: ChatGptAuth.Pending? = null
    /** 读平台链接用的本机浏览器（2026-10-23，engine/PageBrowser.kt）：只在电脑上有；第一次用才开 Edge。同时最多读两个页面。 */
    private val pageBrowser: PageBrowser? by lazy { if (isPhone) null else browserFactory(java.io.File(storage.root, "browser")) }
    private val pageSlots = kotlinx.coroutines.sync.Semaphore(2)
    /** 账号（2026-10-24，engine/Account.kt）：本机密钥 + 绑的 GitHub；私钥只在 account/identity.json。GitHub 要走代理。 */
    private val account by lazy { Account(java.io.File(storage.root, "account"), proxy = { proxyFor(true) }) }
    @Volatile private var githubPending: Account.GithubPending? = null
    /**
     * 手机连着电脑时，把电脑绑的 GitHub 带过来（10-24 用户：「电脑上登录了，手机上就不用登录了」）。只由本机的 GitHubViaHost 调，不是指令，手机遥控时发不进来。
     * 返回 true = 换了。
     */
    fun adoptGithub(u: Account.GithubUser): Boolean {
        val i = account.identity
        if (u.login.isBlank() || (i.githubLogin == u.login && i.githubName == u.name && i.githubAvatar == u.avatar)) return false
        account.bindGithub(u)
        updateState { it.copy(account = accountInfo { a -> a.copy(githubSigningIn = false, githubError = "") }) }
        emit(Event.Notice("已同步电脑上登录的 GitHub：@${u.login}"))
        return true
    }

    /** 手机上：电脑本机的模型经电脑问（HostModels 装上）。 */
    var hostChat: (suspend (ProviderConfig, String, String) -> ChatResult)?
        get() = llm.viaHost
        set(v) { llm.viaHost = v }

    /** 手机上：GitHub 登录换令牌交给配对的电脑（GitHubViaHost 装上）。返回 null = 电脑不在，自己换。 */
    @Volatile var githubViaHost: (suspend (code: String, redirect: String, verifier: String) -> Account.GithubUser?)? = null
    private fun accountInfo(extra: (AccountInfo) -> AccountInfo = { it }) = account.identity.let { i ->
        extra(state.account.copy(id = account.accountId, createdAt = i.createdAt, githubLogin = i.githubLogin, githubName = i.githubName, githubAvatar = i.githubAvatar))
    }
    /** 读过的作品链接 → 视频地址（listen_video 直接用，不用再开一次页面）。 */
    private val pageVideos = ConcurrentHashMap<String, String>()

    /**
     * 听平台视频（2026-10-23，docs/平台链接设计说明.md 第 4 节）：浏览器把声音解码成 16k 单声道 → 2 分钟一段交给听写（设置 → 语音 自动挑的那家）。
     * 最长 10 分钟；几乎没声音的不发（听写对着静音会编话）。
     */
    private suspend fun listenVideo(convId: String, msgId: String, url: String): String {
        if (!url.startsWith("http")) return "参数错误：url 要以 http 开头"
        val browser = pageBrowser ?: return "这台设备上不能听视频（要电脑上的 Edge）"
        val target = speechTarget("", "", tts = false) ?: return Speech.NO_ASR + "。请告诉用户去 设置 → 语音 加一家能听写的服务商。"
        fun step(ok: Boolean, note: String) = updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("listen", url, ok = ok, note = note)) }
        return try {
            val media = pageVideos[url] ?: if (SocialLinks.platformOf(url) != null) {
                val page = pageSlots.withPermit { browser.snapshot(url, SocialLinks.EXTRACT_JS) }
                SocialLinks.videoOf(page)?.also { pageVideos[url] = it }
            } else url
            if (media == null) { step(false, "页面上没找到视频"); return "这个页面上没找到视频地址（可能是图文，或者要登录）。" }
            val (pcm, total) = pageSlots.withPermit { browser.audioPcm(media, LISTEN_MAX_SEC) }
            val wav = SpeechText.wavOf(16000, 1, 16, pcm)
            val w = SpeechText.parseWav(wav)
            if (w == null || SpeechText.isSilent(w)) { step(false, "没有说话声"); return "视频里几乎没有声音（可能只有画面或很轻的音乐），听不出说了什么。" }
            val parts = SpeechText.splitWav(wav, 120.0)
            val text = buildString { for (p in parts) append(speech.transcribe(target.provider, target.model, p.bytes)) }
            val heard = w.seconds.toInt()
            step(true, "${heard} 秒" + if (total > heard + 1) "（整段 ${total.toInt()} 秒，只听了前 ${LISTEN_MAX_SEC / 60} 分钟）" else "")
            "视频里说的话（语音转文字，${heard} 秒" + (if (total > heard + 1) "，整段 ${total.toInt()} 秒只听了前 ${LISTEN_MAX_SEC / 60} 分钟" else "") +
                "；可能有同音错字，背景音乐里的歌词也会被听进来）：\n" + text.ifBlank { "（没听出字）" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            step(false, friendlyError(e))
            "视频没听成：${friendlyError(e)}。请如实告诉用户。"
        }
    }
    private val search = WebSearch { state.settings.proxy.trim().ifEmpty { null } }
    private val images = ImageGen { proxyFor(it.useProxy) }
    /** 做视频；查任务进度的间隔测试里调小（jxy.videoPollMs）。 */
    private val videos = VideoGen({ proxyFor(it.useProxy) }, System.getProperty("jxy.videoPollMs")?.toLongOrNull() ?: 10_000)

    /** 语音：读出来 / 听写（model/Speech.kt 是设置和数据、engine/Speech.kt 是各家的协议）。 */
    private val speech = Speech { proxyFor(it.useProxy) }.also { sp ->
        // 服务商收下了请求就记一条账（教训 53：后面解析、下载失败也已经扣钱了）
        sp.onBilled = { p, model, units, unit ->
            runCatching {
                ledger.add(UsageRecord(now(), if (unit == SpeechUnits.CHAR) UsageKinds.TTS else UsageKinds.ASR,
                    p.id, p.name, Thinking.platformOf(p), model, units = units, unit = unit))
            }
        }
    }

    /** 账本：每次调用模型、每张图记一条，花费页用（见 Ledger.kt、model/Costs.kt）。 */
    private val ledger = Ledger(java.io.File(storage.root, "usage"))

    /** 花费页：按账本和价格表估算；能查余额的平台查余额。 */
    private val costs = CostReporter(ledger, { state })
    private val balances = Balances { proxyFor(it.useProxy) }
    /** 取汇率的（测试里换成指向本地假服务器的）。 */
    internal var usdRates = UsdRate({ state.settings.proxy.trim().ifEmpty { null } })

    /** 记录员后台活出错时记下来、提示用户（以前悄悄停掉）。 */
    private val bg = BackgroundWatch({ state }, { f -> updateState(f) }, { emit(Event.Notice(it, error = true)) })

    /** 本机文档库（AI 能搜、能读）。 */
    private val docs = DocLibrary(java.io.File(storage.root, "docindex").apply { mkdirs() })

    /** 按意思搜：算向量（engine/Embeddings.kt 是接口，model/Semantic.kt 是设置）。 */
    private val embeddings = Embeddings { proxyFor(it.useProxy) }.also { em ->
        // 服务商收下了就记一条账（教训 53），花费页里算「按意思搜」这一用途
        em.onBilled = { p, model, tokens ->
            runCatching { ledger.add(UsageRecord(now(), UsageKinds.EMBED, p.id, p.name, Thinking.platformOf(p), model, prompt = tokens)) }
        }
    }

    /** 本机向量库：数据目录 vectors\ 下的两份（聊天记录、我的文档），见 engine/VectorStore.kt。 */
    private val vectors = VectorStore(java.io.File(storage.root, "vectors"))

    /** 正在补算的那一份（同一时间只跑一份，见 kickEmbedding）。 */
    @Volatile private var embedJob: Job? = null

    /** 联机设置变了（开关、中转列表）时通知外面重启联机服务。 */
    var onRelaySettingsChanged: (() -> Unit)? = null

    /**
     * Code 栏跑命令的执行器（安全核心，见 engine/code/CodeRunner.kt）。
     * 电脑上由 Main.kt 设成 CodeShell（底层是 ProcessHandle，安卓 8.0 上没有，所以放在 desktopMain）；
     * 手机上是 null —— 手机不在本机跑命令，手机上看到的 Code 会话第一期只读。
     */
    var codeRunner: com.guixing.jixunying.engine.code.CodeRunner? = null

    /**
     * 本机 MCP 服务的端口（2026-10-22，Claude Code 当总指挥，docs/Code栏设计说明.md 第 18 节）。
     * 电脑上由 Main.kt 起 DesktopMcpServer（JDK 自带的 HTTP 服务，安卓没有）后填上；null = 不当总指挥。
     */
    @Volatile var mcpPort: Int? = null

    /** 总指挥的派活工具（本机 MCP 服务拿它处理请求）。 */
    val commander: com.guixing.jixunying.engine.code.Commander get() = codeAgent.commander

    /**
     * Code 栏的编码循环（engine/CodeAgent.kt，docs/Code栏设计说明.md 第 3、11 节）：上面几条 Code 指令由 Engine 转给它，循环本身在它里面跑。
     * 这里把引擎的几样能力交给它（消息怎么存、怎么推给界面、改前的备份放哪），循环那边不直接翻引擎内部。
     */
    private val codeAgent = CodeAgent(object : CodeHost {
        override val state get() = this@Engine.state
        override val runner get() = codeRunner
        override val llmClient get() = llm
        override fun backupOf(convId: String) = codeBackup(convId)
        override fun messagesOf(convId: String) = snapshot(convId)
        override fun newId() = this@Engine.newId()
        override fun now() = this@Engine.now()
        override fun addMessage(m: Message) = this@Engine.addMessage(m)
        override fun updateMessage(convId: String, id: String, persist: Boolean, transform: (Message) -> Message) =
            this@Engine.updateMessage(convId, id, persist, transform)
        override fun appendDelta(convId: String, id: String, content: String, reasoning: String) =
            this@Engine.appendDelta(convId, id, content, reasoning)
        override fun launch(convId: String, block: suspend () -> Unit) = launchFor(convId) { block() }
        override fun cancel(convId: String) = stop(convId)
        override fun friendlyError(e: Throwable, p: ProviderConfig?) = this@Engine.friendlyError(e, p)
        override fun updateCode(convId: String, transform: (CodeSpec) -> CodeSpec) = updateCodeSpec(convId, transform)
        override suspend fun webSearch(query: String) = runSearch(query, mutableListOf())
        override suspend fun autoCheck(convId: String, request: String, action: String) = autoVerdict(convId, request, action)
        override fun commanderPort(): Int? = mcpPort
        override suspend fun commitLocal(dir: File, message: String): Pair<Boolean, String> =
            gitHub()?.commit(dir, message)?.let { it.ok to it.text } ?: (false to "这台设备不能跑 git")
        override fun keepImage(file: File): String = runCatching {
            if (!file.isFile || file.length() > 30L * 1024 * 1024) return@runCatching ""
            val bytes = file.readBytes()
            // 缩到长边 1280、四百来 KB：看得清界面截图，又不撑大数据目录；缩不了的小图原样存
            val small = shrinkImageToJpeg(bytes, 1280, 400_000)
            if (small == null && bytes.size > 2_000_000) return@runCatching ""
            val id = newId()
            val name = file.nameWithoutExtension + if (small != null) ".jpg" else "." + file.extension.lowercase()
            storage.putFile(Attachment(id, name, if (small != null) "image/jpeg" else DocExtract.imageMime(file.name, ""),
                (small ?: bytes).size.toLong(), AttachmentKind.IMAGE, note = "Code 栏里 AI 看过的图"), small ?: bytes, null)
            id
        }.getOrDefault("")
        override suspend fun mcpToolDefs() = mcpTools().map { (n, _, t) -> Triple(n, "（外部工具 ${t.name}）" + t.description, t.inputSchema) }
        override suspend fun mcpCall(name: String, args: JsonObject): String {
            val (_, c, t) = mcpTools().firstOrNull { it.first == name } ?: return "没有这个外部工具：$name"
            return runCatching { c.call(t.name, args) }.getOrElse { "外部工具出错：${it.message?.take(300)}" }
        }
        override fun startBackground(convId: String, command: String, dir: File) = this@Engine.startBackground(convId, command, dir)
        override fun summaryOf(convId: String) = storage.loadMemo(convId).let { it.summary to it.upToTime }
        override fun removeMessages(convId: String, ids: List<String>) {
            val list = messages(convId)
            synchronized(list) { list.removeAll { it.id in ids } }
            save(convId)
            ids.forEach { emit(Event.MessageRemoved(convId, it)) }
        }
        override fun compactLater(convId: String) { scope.launch { runCatching { compactIfLong(convId) } } }
        override fun backgroundOutput(id: String) = codeRunner?.backgroundOutput(id)
        override fun stopBackground(convId: String, id: String) { codeRunner?.stopBackground(id); markBg(convId, id) { it.copy(running = false) } }
        override suspend fun fetchUrl(url: String): String = runCatching { search.fetch(url, false) }.getOrElse { search.fetch(url, true) }
        override fun attachmentParts(m: Message, latest: Boolean, vision: Boolean): Pair<String, List<String>> {
            val sb = StringBuilder()
            val imgs = mutableListOf<String>()
            for (a in m.attachments) when (a.kind) {
                AttachmentKind.DOCUMENT -> {
                    val text = storage.fileText(a.id)
                    val limit = if (latest) 80_000 else 4_000
                    sb.append("\n\n[附件：").append(a.name).append("]")
                    if (text == null) sb.append("（读不出文字：").append(a.note).append("）")
                    else {
                        sb.append("\n```\n").append(text.take(limit))
                        if (text.length > limit) sb.append("\n…（共 ${text.length} 字，后面省略）")
                        sb.append("\n```")
                    }
                }
                AttachmentKind.IMAGE, AttachmentKind.GENERATED_IMAGE -> {
                    // 只把最新一条任务里的图片发过去（以前的图片每轮都发太费 token），别的写个名字
                    val url = if (vision && latest) imageDataUrl(a) else null
                    if (url != null) imgs += url
                    else sb.append("\n[图片：").append(a.name).append(if (vision) "（以前发过的）]" else "，这个模型看不了图片]")
                }
            }
            return sb.toString() to imgs
        }
    })

    private fun updateCodeSpec(convId: String, transform: (CodeSpec) -> CodeSpec) {
        updateState { s ->
            s.copy(conversations = s.conversations.map { c -> if (c.id == convId && c.code != null) c.copy(code = transform(c.code)) else c })
        }
    }

    /** 上一个配对密钥：手机补发的配对请求可能还用旧的，留 3 分钟。 */
    @Volatile private var previousSecret: Pair<String, Long>? = null

    init {
        var s = state
        if (s.hostId.isEmpty()) s = s.copy(hostId = com.guixing.jixunying.relay.Crypto.randomHex(8))
        if (s.pairingSecret.isEmpty()) s = s.copy(pairingSecret = newSecret())
        // 手机上一般没有本地 HTTP 代理（用的是 VPN 类软件），默认直连
        if (isPhone && storage.loadState() == null) s = s.copy(settings = s.settings.copy(proxy = ""))
        // 后台命令随上次程序退出都没了：清空列表（2026-10-18）
        if (s.conversations.any { it.background.isNotEmpty() }) s = s.copy(conversations = s.conversations.map { it.copy(background = emptyList()) })
        // 这一版认得的设置版本（新版界面拿到状态再改、再发回来，带的就是它）
        if (s.settings.schema < Settings.SCHEMA) s = s.copy(settings = s.settings.copy(schema = Settings.SCHEMA))
        // ChatGPT 登录状态以本机凭据为准（上次停在「正在登录」的也清掉）
        s = s.copy(chatgpt = chatgptInfo())
        s = s.copy(account = account.identity.let { i -> AccountInfo(Account.idOf(i.publicKey), i.createdAt, i.githubLogin, i.githubName, i.githubAvatar) })
        // 以前重复导入留下的同一个服务商 / 同一位成员，启动时合并掉
        val (deduped, memberMap, removed) = ConfigMerge.dedupe(s)
        if (removed > 0) s = deduped
        if (s != state) { state = s; persistState(s) }
        if (memberMap.isNotEmpty()) remapSenders(memberMap)
        emit(Event.State(state))
        storage.brokenNotes.forEach { emit(Event.Notice(it, error = true)) }
        llm.onUsage = { p, model, u, tag -> recordUsage(p, model, u, tag?.kind ?: UsageKinds.OTHER, tag) }
        llm.chatgptToken = { force -> chatgptAuth.accessToken(force) }
        // 补记只补引擎启动以前的（启动以后的回答已经实时记过，免得记两遍）
        val backfillBefore = now()
        if (!ledger.backfilled) scope.launch { runCatching { backfillLedger(backfillBefore) } }
        // 文档库：启动后稍等一会儿在后台扫一遍，之后每半小时看一次有没有新文件
        if (autoScanDocs) scope.launch {
            kotlinx.coroutines.delay(4_000)
            while (true) {
                rescanDocs()
                kotlinx.coroutines.delay(30 * 60_000L)
            }
        }
        // 按意思搜：启动后把还没算过的聊天记录 / 文档在后台补上（开关开着才动）
        scope.launch { kotlinx.coroutines.delay(3_000); kickEmbedding() }
        // 美元汇率：启动后取一次，之后每小时看一眼，上次取到超过 12 小时就再取（开着「自动更新」才取）
        if (autoUsdRate) scope.launch {
            kotlinx.coroutines.delay(5_000)
            while (true) {
                refreshUsdRate(force = false)
                kotlinx.coroutines.delay(60 * 60_000L)
            }
        }
    }

    /**
     * 联网取最新的美元汇率（engine/UsdRate）。force = false 时只在开着自动更新、并且上次取到超过 12 小时才取。
     * 取不到不改原来的汇率，只返回失败（界面上点「现在更新」时显示原因）。
     */
    suspend fun refreshUsdRate(force: Boolean): CommandResult {
        val c = state.settings.costs
        if (!force && (!c.usdAuto || now() - c.usdRateAt < UsdRate.EVERY_MS)) return CommandResult()
        return try {
            val r = usdRates.fetch()
            val rate = kotlin.math.round(r.rate * 10_000) / 10_000
            updateState { s -> s.copy(settings = s.settings.copy(costs = s.settings.costs.copy(
                usdRate = rate, usdAuto = true, usdRateAt = now(), usdRateFrom = r.source,
                fx = r.perUsd.ifEmpty { s.settings.costs.fx }))) }
            CommandResult(message = "1 美元 = $rate 元（${r.source}）")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            CommandResult(false, "取不到最新汇率：${e.message ?: e::class.simpleName}；先用原来的 ${c.usdRate}")
        }
    }

    // ———————————————— 本机文档 ————————————————

    fun docRoots(): List<java.io.File> {
        val ds = state.settings.docs
        val base = if (ds.folders.isEmpty()) defaultDocRoots() else ds.folders.map { java.io.File(it) }.filter { it.isDirectory }
        val wx = if (ds.includeWeixin) weixinDocRoots() else emptyList()
        return (base + wx).distinctBy { it.path }
    }

    /** 文档索引的情况只给界面看（和中转状态一样，不单独存盘）。 */
    private fun refreshDocInfo(progress: String = "", count: Int = docs.count) {
        val info = com.guixing.jixunying.model.DocIndexInfo(
            count = count, scanning = docs.isScanning || progress.isNotEmpty(), progress = progress, scannedAt = docs.scannedAt,
            roots = docRoots().map { it.path }, weixinRoots = weixinDocRoots().map { it.path }, needPermission = docAccessMissing(),
        )
        if (state.docs == info) return
        synchronized(stateLock) { state = state.copy(docs = info) }
        emit(Event.State(state))
    }

    fun rescanDocs() {
        if (!state.settings.docs.enabled) { refreshDocInfo(); return }
        scope.launch(Dispatchers.IO) {
            refreshDocInfo("准备扫描…")
            runCatching { docs.scan(docRoots()) { p, n -> refreshDocInfo(p, n) } }
            refreshDocInfo()
            // 扫进来的新文档也要算进按意思搜（开关开着才动）
            kickEmbedding()
        }
    }

    private fun docsUsable() = state.settings.docs.enabled && docs.count > 0

    /** 成员合并后，聊天记录里的发言人跟着改到保留的那一份。 */
    private fun remapSenders(map: Map<String, String>) {
        if (map.isEmpty()) return
        for (conv in state.conversations) {
            val list = messages(conv.id)
            var changed = false
            synchronized(list) {
                for (i in list.indices) {
                    val to = map[list[i].senderId] ?: continue
                    list[i] = list[i].copy(senderId = to)
                    changed = true
                }
            }
            if (changed) {
                save(conv.id)
                emit(Event.Messages(conv.id, snapshot(conv.id)))
            }
        }
    }

    // ———————————————— 基础 ————————————————

    private fun emit(e: Event) {
        store.apply(e)
        listeners.forEach { runCatching { it(e) } }
    }

    private fun now() = System.currentTimeMillis()
    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(20)

    private fun chatgptInfo() = chatgptAuth.creds.let { c ->
        com.guixing.jixunying.model.ChatGptInfo(signedIn = c.signedIn, email = c.email, planOk = c.canUsePlan)
    }
    private fun newSecret() = Base64.getEncoder().encodeToString(com.guixing.jixunying.relay.Crypto.randomBytes(16))

    private fun updateState(transform: (AppState) -> AppState): AppState {
        val s = synchronized(stateLock) {
            state = transform(state)
            persistState(state)
            state
        }
        emit(Event.State(s))
        return s
    }

    /** 中转连接情况只给界面看，不存盘。 */
    fun setRelayStatus(text: String) {
        if (state.relayStatus == text) return
        synchronized(stateLock) { state = state.copy(relayStatus = text) }
        emit(Event.State(state))
    }

    fun hostName(): String = state.settings.relay.deviceName.ifBlank {
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("我的电脑")
    }

    /** 当前和刚换掉的配对密钥（字节）。 */
    fun pairingSecrets(): List<ByteArray> = buildList {
        add(Base64.getDecoder().decode(state.pairingSecret))
        previousSecret?.let { (sec, at) -> if (now() - at < 180_000) add(Base64.getDecoder().decode(sec)) }
    }

    /** 新手机配对成功：记下它，换一个新的配对密钥（二维码只能用一次）。同一台手机补发的请求不重复记。 */
    fun registerDevice(d: PairedDevice) {
        val existing = state.devices.firstOrNull { it.id == d.id }
        if (existing != null && existing.key == d.key) return
        previousSecret = state.pairingSecret to now()
        updateState { s -> s.copy(devices = s.devices.filterNot { it.id == d.id } + d, pairingSecret = newSecret()) }
        emit(Event.Notice("「${d.name}」已配对，以后在哪儿都能连这台电脑"))
    }

    /** 手机来拿微信绑定（RelayHost 调）：还没指定接班的手机、或者指定的那台不在了，就定成它。 */
    fun noteWeixinPhone(deviceId: String) {
        updateState { s ->
            val cur = s.weixinBackup
            if (cur == "none" || (cur.isNotEmpty() && s.devices.any { it.id == cur })) s else s.copy(weixinBackup = deviceId)
        }
    }

    fun touchDevice(id: String) {
        updateState { s -> s.copy(devices = s.devices.map { if (it.id == id) it.copy(lastSeen = now()) else it }) }
    }

    /** 发给手机的状态：Key 打码，配对密钥和设备密钥不给。 */
    fun remoteView(s: AppState): AppState = s.copy(
        providers = s.providers.map { it.copy(apiKey = maskKey(it.apiKey)) },
        settings = s.settings.copy(search = s.settings.search.copy(apiKeys = s.settings.search.apiKeys.mapValues { maskKey(it.value) })),
        devices = s.devices.map { it.copy(key = "") },
        pairingSecret = "",
    )

    /** 当前的配对码（给电脑界面显示成二维码）。 */
    fun pairingCode(): com.guixing.jixunying.model.PairingCode =
        com.guixing.jixunying.model.PairingCode(state.hostId, state.pairingSecret, hostName(), state.settings.relay.brokers)

    /**
     * Code 会话改文件之前存的原样（docs/Code栏设计说明.md 第 5 节）：<数据目录>/code/<对话 id>/。
     * 会话自己存在 conversations（和普通对话一样），备份另放一处；删会话时用这里的 deleteAll 一起删掉。
     */
    // ———————————————— 后台命令（2026-10-18，开发一套补齐第 1 样） ————————————————

    /** 开一个后台命令，记到这个对话上；返回编号，开不了返回 null。 */
    fun startBackground(convId: String, command: String, dir: File): String? {
        val runner = codeRunner ?: return null
        val id = "bg" + newId().take(8)
        if (!runner.startBackground(id, command, dir, state.settings.code.shell)) return null
        val p = com.guixing.jixunying.model.BackgroundProc(id, command, dir.absolutePath, now())
        updateState { s -> s.copy(conversations = s.conversations.map { if (it.id == convId) it.copy(background = it.background + p) else it }) }
        ensureBgWatch()
        return id
    }

    private fun markBg(convId: String, id: String, f: (com.guixing.jixunying.model.BackgroundProc) -> com.guixing.jixunying.model.BackgroundProc) {
        updateState { s -> s.copy(conversations = s.conversations.map { c ->
            if (c.id != convId) c else c.copy(background = c.background.map { if (it.id == id) f(it) else it })
        }) }
    }

    @Volatile private var bgWatch: Job? = null

    /** 每 2 秒看一眼后台命令：结束了标出来；输出里出现本机网址（开发服务器）就记下来，预览用。 */
    private fun ensureBgWatch() {
        if (bgWatch?.isActive == true) return
        bgWatch = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                val runner = codeRunner ?: break
                val live = state.conversations.flatMap { c -> c.background.filter { it.running }.map { c.id to it } }
                if (live.isEmpty()) continue
                for ((cid, p) in live) {
                    val alive = runner.backgroundAlive(p.id)
                    val url = if (p.url.isNotBlank()) p.url else LOCAL_URL.find(runner.backgroundOutput(p.id, 20_000).orEmpty())?.value?.trimEnd('/', '.', ',') ?: ""
                    if (!alive || url != p.url) markBg(cid, p.id) { it.copy(running = alive, url = url) }
                }
            }
        }
    }

    // ———————————————— MCP（2026-10-18，开发补齐第 12 样） ————————————————

    private val mcpClients = ConcurrentHashMap<String, com.guixing.jixunying.engine.code.McpClient>()
    private val mcpLock = kotlinx.coroutines.sync.Mutex()

    /** 给 AI 的 MCP 工具名：mcp__服务器__工具（只留英文数字下划线减号，最长 64）。 */
    private fun mcpName(server: String, tool: String): String {
        fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "x" + s.hashCode().toUInt().toString(16) }
        return "mcp__${clean(server)}__${clean(tool)}".take(64)
    }

    /**
     * 设置里开着的 MCP 服务器都连上（已经连着、命令没变的不重连），返回（给 AI 的名字, 服务器, 工具）。
     * 连不上的跳过，记一条后台提示。只在电脑上（能跑命令的设备）。
     */
    internal suspend fun mcpTools(): List<Triple<String, com.guixing.jixunying.engine.code.McpClient, com.guixing.jixunying.engine.code.McpClient.Tool>> {
        if (codeRunner == null) return emptyList()
        val wanted = state.settings.code.mcpServers.filter { it.enabled && it.command.isNotBlank() }
        mcpLock.withLock {
            // 设置里删掉的、命令改了的：关掉
            mcpClients.entries.toList().forEach { (k, c) ->
                if (wanted.none { it.name + "\u0000" + it.command == k } || !c.alive) { c.close(); mcpClients.remove(k) }
            }
            for (w in wanted) {
                val key = w.name + "\u0000" + w.command
                if (mcpClients.containsKey(key)) continue
                val c = com.guixing.jixunying.engine.code.McpClient(w.name, w.command, File(System.getProperty("user.home")))
                try {
                    c.start()
                    mcpClients[key] = c
                } catch (e: CancellationException) {
                    c.close(); throw e
                } catch (e: Throwable) {
                    c.close()
                    emit(Event.Notice("MCP 服务器「${w.name}」连不上：${e.message?.take(200)}", error = true))
                }
            }
        }
        return mcpClients.values.flatMap { c -> c.tools.map { t -> Triple(mcpName(c.name, t.name), c, t) } }
    }

    /** 关掉全部 MCP 服务器（退出程序时）。 */
    fun closeMcp() { mcpClients.values.forEach { it.close() }; mcpClients.clear() }

    /** 连 GitHub 用的（只有能跑命令的设备有）：外壳、代理、gh 位置都照设置。 */
    private fun gitHub(): com.guixing.jixunying.engine.code.GitHub? = codeRunner?.let {
        com.guixing.jixunying.engine.code.GitHub(it, state.settings.code.shell, state.settings.proxy, state.settings.code.ghPath)
    }

    /**
     * Codex / Gemini CLI 装没装（2026-10-20）：跑它自己的 --version；Codex 再跑 login status（只看它报的结果，不碰凭据文件）。
     * Gemini 没有不进界面就能查登录的命令，只报版本。
     */
    private suspend fun agentStatus(agent: String, exe: File): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cc = com.guixing.jixunying.engine.code.ClaudeCli
        fun run(vararg args: String): String? = runCatching {
            val p = ProcessBuilder(cc.launcher(exe) + args).directory(File(System.getProperty("user.home"))).redirectErrorStream(true)
                .also { pb -> pb.environment().clear(); pb.environment().putAll(cc.env(System.getenv(), state.settings.proxy)) }.start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out.trim()
        }.getOrNull()
        val name = CodeSpec.agentName(agent)
        val version = run("--version")?.lineSequence()?.lastOrNull { it.isNotBlank() }
            ?: return@withContext false to "找到了 ${exe.absolutePath}，但运行不起来"
        if (agent != CodeSpec.CODEX) return@withContext true to "$name $version\n${exe.absolutePath}\n登录状态这里查不了：没登录的话发任务时会报错，到终端里运行一次 gemini 登录"
        val login = run("login", "status")?.lineSequence()?.lastOrNull { it.isNotBlank() }.orEmpty()
        val ok = Regex("(?i)logged in").containsMatchIn(login) && !Regex("(?i)not logged in").containsMatchIn(login)
        ok to "$name $version\n${exe.absolutePath}\n" + if (ok) login else "还没登录：到终端里运行一次 codex login（它说：${login.take(200)}）"
    }

    /**
     * 外部 Claude Code 装没装、登没登录（2026-10-18）：跑它自己的 --version 和 auth status（只看它报的结果，不碰凭据文件）。
     * 返回（能不能用，给人看的几行字）。
     */
    private suspend fun claudeStatus(exe: File): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cc = com.guixing.jixunying.engine.code.ClaudeCli
        fun run(vararg args: String): String? = runCatching {
            val p = ProcessBuilder(cc.launcher(exe) + args).directory(File(System.getProperty("user.home"))).redirectErrorStream(true)
                .also { pb -> pb.environment().clear(); pb.environment().putAll(cc.env(System.getenv(), state.settings.proxy)) }.start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out.trim()
        }.getOrNull()
        val version = run("--version")?.lineSequence()?.firstOrNull { it.isNotBlank() }
            ?: return@withContext false to "找到了 ${exe.absolutePath}，但运行不起来"
        val auth = run("auth", "status")?.let { t -> runCatching { kotlinx.serialization.json.Json.parseToJsonElement(t.substring(t.indexOf('{'))).jsonObject }.getOrNull() }
        val loggedIn = auth?.get("loggedIn")?.toString() == "true"
        val how = when (auth?.str("authMethod")) {
            "claude.ai" -> "Claude 订阅" + auth.str("subscriptionType")?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            null, "" -> ""
            else -> auth.str("authMethod").orEmpty()
        }
        val lines = listOf(
            "Claude Code：$version（${exe.absolutePath}）",
            if (loggedIn) "已登录" + if (how.isNotEmpty()) "，用的是 $how" else "" else "还没登录：点「打开 Claude Code 登录」",
        )
        loggedIn to lines.joinToString("\n")
    }

    private fun codeBackup(convId: String): com.guixing.jixunying.engine.code.CodeBackup =
        com.guixing.jixunying.engine.code.CodeBackup(File(File(storage.root, "code"), convId))

    /**
     * Code 栏的改前备份一共占了多少字节：<数据目录>/code/ 底下所有文件加起来（设置 → Code 栏 显示，超过 1 GB 提醒）。
     * 读不出来就按 0 算，不为了一个数字让设置页报错。
     */
    private fun codeBackupSize(): Long = runCatching {
        val root = File(storage.root, "code")
        if (!root.isDirectory) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun messages(convId: String): MutableList<Message> = convs.getOrPut(convId) { storage.loadMessages(convId) }

    private fun snapshot(convId: String): List<Message> = messages(convId).let { synchronized(it) { it.toList() } }

    /**
     * 存盘失败（文件被别的程序占着、磁盘满……）不让正在跑的任务跟着出错（2026-10-22 实测一次就把 Code 任务判死了）：
     * 内存里的记录是全的，记一笔日志、一分钟内最多提示一次，两秒后自动再存（存的是那时最新的全部）。
     */
    private fun save(convId: String) {
        try {
            storage.saveMessages(convId, snapshot(convId))
        } catch (e: Exception) {
            saveFailed("这个会话的记录", e, "conv:$convId") { save(convId) }
        }
    }

    private fun persistState(s: AppState) {
        try {
            storage.saveState(s)
        } catch (e: Exception) {
            saveFailed("设置和会话列表", e, "state") { synchronized(stateLock) { persistState(state) } }
        }
    }

    private val resaving: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var lastSaveNotice = 0L

    private fun saveFailed(what: String, e: Exception, key: String, retry: () -> Unit) {
        val why = if (e is java.nio.file.FileSystemException) "文件被别的程序占着（杀毒、同步盘、正在打开它的程序都可能）" else (e.message ?: e.toString()).take(120)
        storage.log("JxySave", "$what 没存上（$key）：$e")
        if (now() - lastSaveNotice > 60_000) {
            lastSaveNotice = now()
            emit(Event.Notice("$what 刚才没存上：$why。内容都还在，过一会儿会自动再存。", error = true))
        }
        if (resaving.add(key)) scope.launch {
            kotlinx.coroutines.delay(2_000)
            resaving.remove(key)
            retry()
        }
    }

    private fun addMessage(m: Message) {
        val list = messages(m.convId)
        synchronized(list) { list.add(m) }
        emit(Event.MessageUpsert(m))
        save(m.convId)
    }

    private fun updateMessage(convId: String, id: String, persist: Boolean = true, transform: (Message) -> Message): Message? {
        val list = messages(convId)
        var before: MsgStatus? = null
        val updated = synchronized(list) {
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return null
            before = list[i].status
            transform(list[i]).also { list[i] = it }
        }
        // 任务出错记一行日志（2026-10-22：以前只在界面红框里，事后查不了）
        if (updated.status == MsgStatus.ERROR && before != MsgStatus.ERROR) storage.log("JxyTask", "$convId ${updated.senderId} 出错：${updated.error.take(500)}")
        emit(Event.MessageUpsert(updated))
        if (persist) save(convId)
        return updated
    }

    private fun appendDelta(convId: String, id: String, c: String, r: String) {
        val list = messages(convId)
        synchronized(list) {
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return
            list[i] = list[i].copy(content = list[i].content + c, reasoning = list[i].reasoning + r)
        }
        emit(Event.MessageDelta(convId, id, c, r))
    }

    // ———————————————— 指令 ————————————————

    override suspend fun call(command: Command): CommandResult = try {
        handle(command)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        CommandResult(false, friendlyError(e))
    }

    private suspend fun handle(c: Command): CommandResult = when (c) {
        is Command.SaveProvider -> {
            updateState { s ->
                val old = s.provider(c.provider.id)
                val p = if (old != null && isMaskedKey(c.provider.apiKey)) c.provider.copy(apiKey = old.apiKey) else c.provider
                s.copy(providers = if (old == null) s.providers + p else s.providers.map { if (it.id == p.id) p else it })
            }
            CommandResult()
        }
        is Command.DeleteProvider -> {
            updateState { s -> s.copy(providers = s.providers.filterNot { it.id == c.id }) }
            CommandResult()
        }
        is Command.FetchModels -> {
            val p = state.provider(c.providerId) ?: return CommandResult(false, "服务商不存在")
            val ids = withContext(Dispatchers.IO) { llm.listModels(p) }
            if (ids.isEmpty()) return CommandResult(false, "服务商没有返回模型列表，请手动填写模型名")
            updateState { s ->
                s.copy(providers = s.providers.map { pp ->
                    if (pp.id != p.id) pp else {
                        val known = pp.models.associateBy { it.id }
                        val merged = ids.map { id ->
                            known[id] ?: ModelInfo(id, vision = Presets.guessVision(id), imageGen = Presets.guessImageGen(id), tools = !Presets.guessImageGen(id))
                        }
                        pp.copy(models = merged + pp.models.filter { it.id !in ids })
                    }
                })
            }
            CommandResult(message = "拉到 ${ids.size} 个模型")
        }
        is Command.ChatGptSignIn -> {
            chatgptPending?.cancel()
            val pending = chatgptAuth.begin("AI集训营")
            chatgptPending = pending
            updateState { it.copy(chatgpt = it.chatgpt.copy(signingIn = true, error = "")) }
            scope.launch {
                val err = try {
                    pending.await(); null
                } catch (e: Throwable) {
                    if (chatgptPending !== pending) return@launch   // 又点了一次登录，这次的作废
                    e.message ?: e.toString()
                }
                chatgptPending = null
                updateState { it.copy(chatgpt = chatgptInfo().copy(error = err.orEmpty())) }
                if (err == null) {
                    // 登好了：没有 ChatGPT 订阅这个服务商就加一个，再拉一次模型列表
                    val p = state.providers.firstOrNull { Presets.isChatGpt(it) } ?: run {
                        val pre = Presets.byId(Presets.CHATGPT)
                        val np = ProviderConfig(newId(), Presets.CHATGPT, pre.name, pre.baseUrl, useProxy = true)
                        updateState { st -> st.copy(providers = st.providers + np) }
                        np
                    }
                    runCatching { handle(Command.FetchModels(p.id)) }
                        .onFailure { e -> emit(Event.Notice("ChatGPT 登录好了，但拉模型列表失败：${e.message}", error = true)) }
                    emit(Event.Notice("ChatGPT 登录好了" + state.chatgpt.email.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()))
                }
            }
            CommandResult(message = "在浏览器里打开的 OpenAI 页面上点同意", data = pending.url)
        }
        is Command.PlatformStatus -> {
            val b = pageBrowser ?: return CommandResult(data = """{"available":false,"platforms":{}}""")
            val started = c.launch || withContext(Dispatchers.IO) { b.running() }
            val st = SocialLinks.all.associate { p ->
                p.id to when {
                    !started -> "notstarted"
                    p.loginCookies.isEmpty() -> "unknown"
                    runCatching { b.cookieNames(p.cookieDomain) }.getOrDefault(emptySet()).any { it in p.loginCookies } -> "in"
                    else -> "out"
                }
            }
            CommandResult(data = buildJsonObject {
                put("available", true)
                put("started", started)
                put("platforms", buildJsonObject { st.forEach { (k, v) -> put(k, v) } })
            }.toString())
        }
        is Command.PlatformLogin -> {
            val b = pageBrowser ?: return CommandResult(false, "这台设备上没有能用的浏览器（要 Edge 或 Chrome）")
            val p = SocialLinks.byId(c.platform) ?: return CommandResult(false, "不认识的平台")
            b.showForLogin(p.loginUrl)
            CommandResult(message = "浏览器已经打开${p.name}，在里面扫码登录；登好了点「收起浏览器」")
        }
        is Command.PlatformHide -> {
            pageBrowser?.hide()
            CommandResult()
        }
        is Command.AccountGitHubSignIn -> {
            val clientId = System.getProperty("jxy.github.clientId") ?: com.guixing.jixunying.model.GITHUB_OAUTH_CLIENT_ID
            val secret = System.getProperty("jxy.github.secret") ?: BuildSecrets.githubSecret
            val pending = try { account.githubBegin(clientId, secret) } catch (e: Account.AccountException) { return CommandResult(false, e.message ?: "开不了 GitHub 登录") }
            githubPending?.cancel()
            githubPending = pending
            updateState { it.copy(account = accountInfo { a -> a.copy(githubSigningIn = true, githubError = "") }) }
            scope.launch {
                val viaHost = githubViaHost
                var byPc = false
                val err = try {
                    pending.await(exchange = { code, redirect, verifier ->
                        viaHost?.invoke(code, redirect, verifier)?.also { byPc = true } ?: account.githubExchange(clientId, secret, code, redirect, verifier)
                    }); null
                } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                    val m = e.message ?: e.toString()
                    // 手机自己去换没换成（多半是直连 github.com 不通）：告诉用户怎么办
                    if (isPhone && !byPc && !m.startsWith("你在 GitHub") && !m.startsWith("等了")) "$m\n手机连不上 GitHub：开着电脑、手机连上电脑后再点一次（换令牌交给电脑做），或者在电脑上登录 GitHub，手机连上会自动带过来。"
                    else m
                }
                if (githubPending !== pending) return@launch   // 又点了一次，这次的作废
                githubPending = null
                updateState { it.copy(account = accountInfo { a -> a.copy(githubSigningIn = false, githubError = err.orEmpty()) }) }
                if (err == null) emit(Event.Notice("GitHub 绑好了：@${account.identity.githubLogin}"))
            }
            CommandResult(message = "在打开的 GitHub 页面上点同意", data = pending.url)
        }
        is Command.HostChat -> try {
            // 只认地址是电脑本机的服务商（Ollama 这类）：手机不能借这条路花电脑上云端服务商的钱
            val p = state.provider(c.providerId)
            if (p == null || p.viaHost || !HostChatCodec.isLoopback(p.baseUrl)) CommandResult(false, "电脑上没有这个本机模型")
            else {
                val q = HostChatCodec.parseRequest(c.request)
                val r = withContext(UsageTag("chat")) { llm.chat(p, c.model, q.messages, q.temperature, q.tools, q.extra, thinking = q.thinking) { _, _ -> } }
                CommandResult(data = HostChatCodec.result(r))
            }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { CommandResult(false, "电脑上的模型没答上来：" + (e.message ?: e.toString())) }
        is Command.GitHubExchange -> try {
            val clientId = System.getProperty("jxy.github.clientId") ?: com.guixing.jixunying.model.GITHUB_OAUTH_CLIENT_ID
            val secret = System.getProperty("jxy.github.secret") ?: BuildSecrets.githubSecret
            val u = account.githubExchange(clientId, secret, c.code, c.redirectUri, c.verifier)
            CommandResult(data = AppJson.encodeToString(Account.GithubUser.serializer(), u))
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { CommandResult(false, "电脑去 GitHub 换令牌没成：" + (e.message ?: e.toString())) }
        is Command.AccountGitHubUnbind -> {
            githubPending?.cancel(); githubPending = null
            account.githubUnbind()
            updateState { it.copy(account = accountInfo { a -> a.copy(githubSigningIn = false, githubError = "") }) }
            CommandResult(message = "已解绑 GitHub")
        }
        is Command.AccountExport -> try {
            CommandResult(data = account.export(c.passphrase))
        } catch (e: Account.AccountException) { CommandResult(false, e.message ?: "导不出来") }
        is Command.AccountImport -> try {
            account.import(c.code, c.passphrase)
            updateState { it.copy(account = accountInfo { a -> a.copy(githubSigningIn = false, githubError = "") }) }
            CommandResult(message = "已换成账号 ${account.accountId}")
        } catch (e: Account.AccountException) { CommandResult(false, e.message ?: "导不进来") }
        is Command.ChatGptSignOut -> {
            chatgptPending?.cancel(); chatgptPending = null
            chatgptAuth.signOut()
            updateState { it.copy(chatgpt = chatgptInfo()) }
            CommandResult(message = "已退出 ChatGPT 登录")
        }
        is Command.TestProvider -> {
            val p = state.provider(c.providerId) ?: return CommandResult(false, "服务商不存在")
            val r = withContext(Dispatchers.IO + UsageTag(UsageKinds.TEST)) {
                llm.chat(p, c.modelId, listOf(buildJsonObject { put("role", "user"); put("content", "用一句话（不超过 20 个字）介绍你是谁、什么模型。") }), null, null) { _, _ -> }
            }
            val said = r.content.ifBlank { r.reasoning }.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            val secs = "%.1f".format(r.usage.millis / 1000.0)
            if (said.isBlank()) CommandResult(false, "接口通了（$secs 秒），但模型「${c.modelId}」没有返回文字。检查一下模型名是否写对。")
            else CommandResult(message = "连通了（$secs 秒，模型 ${c.modelId}）。它说：" + said.take(120))
        }
        is Command.TestImage -> {
            val only = if (c.providerId.isBlank()) null else {
                val p = state.provider(c.providerId) ?: return CommandResult(false, "服务商不存在")
                ImageChoice(p.id, p.name, c.modelId, free = false, inferred = false, note = "")
            }
            val t0 = now()
            val r = withContext(UsageTag(UsageKinds.TEST)) { paintAuto(c.prompt.ifBlank { "一只坐在窗台上晒太阳的橘猫，水彩画风格，暖色调" }, "1024x1024", only) }
            CommandResult(message = "用 ${r.label} 画好了（${"%.1f".format((now() - t0) / 1000.0)} 秒）",
                data = AppJson.encodeToString(Attachment.serializer(), r.att))
        }
        is Command.SaveMember -> {
            updateState { s ->
                val exists = s.member(c.member.id) != null
                s.copy(members = if (exists) s.members.map { if (it.id == c.member.id) c.member else it } else s.members + c.member)
            }
            CommandResult()
        }
        is Command.DeleteMember -> {
            updateState { s ->
                s.copy(
                    members = s.members.filterNot { it.id == c.id },
                    conversations = s.conversations.map { it.copy(memberIds = it.memberIds - c.id) },
                )
            }
            CommandResult()
        }
        is Command.SaveProfile -> { updateState { it.copy(profile = c.profile) }; CommandResult() }
        is Command.DismissBgProblem -> { bg.dismiss(c.job); CommandResult() }
        is Command.SaveCosts -> {
            if (c.costs.usdRate <= 0) return CommandResult(false, "汇率要大于 0")
            // 手填的余额只走 SaveManualBalance 改：1.5.0 的手机发来的 SaveCosts 里没有这块
            // 各种货币的汇率也是引擎取的：旧版发来的没有这块，保留（10-24）
            updateState { it.copy(settings = it.settings.copy(costs = c.costs.copy(manual = it.settings.costs.manual,
                fx = c.costs.fx.ifEmpty { it.settings.costs.fx }))) }
            CommandResult()
        }
        is Command.SaveManualBalance -> {
            val b = c.balance
            if (b != null && (b.amount.isNaN() || b.packTokens < 0 || b.currency !in setOf("CNY", "USD"))) return CommandResult(false, "金额或 token 数不对")
            updateState { s ->
                val m = s.settings.costs.manual.let { if (b == null) it - c.providerId else it + (c.providerId to b.copy(at = if (b.at > 0) b.at else now())) }
                s.copy(settings = s.settings.copy(costs = s.settings.costs.copy(manual = m)))
            }
            CommandResult()
        }
        is Command.RefreshUsdRate -> refreshUsdRate(force = true)
        is Command.GetBalances -> {
            val manual = state.settings.costs.manual
            val list = coroutineScope {
                state.providers.filter { it.enabled }.map { p ->
                    async(Dispatchers.IO) {
                        val b = balances.query(p, now())
                        val m = manual[p.id]
                        // 接口查到了就用接口的；查不了、出错了，有手填的就按手填的估算
                        if (m == null) b else if (b.ok) b.copy(manual = m) else withManual(b, p, m)
                    }
                }.awaitAll()
            }
            CommandResult(data = AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.BalanceInfo.serializer()), list))
        }
        is Command.GetCosts -> {
            val r = withContext(Dispatchers.IO) { costs.report(c.period) }
            CommandResult(data = AppJson.encodeToString(com.guixing.jixunying.model.CostReport.serializer(), r))
        }
        is Command.SaveSettings -> {
            val before = state.settings.relay
            val docsBefore = state.settings.docs
            val semanticBefore = state.settings.semantic
            updateState { s ->
                val oldKeys = s.settings.search.apiKeys
                val keys = c.settings.search.apiKeys.mapValues { (k, v) -> if (isMaskedKey(v)) oldKeys[k].orEmpty() else v }
                // 花费设置走 SaveCosts 单独存，这里保留原样（旧版手机发来的设置里没有这块）
                // 1.4.0 以前的手机不认识裁判和 AI 核实这几项，发来的是默认值：保留电脑上的
                val memory = if (c.settings.schema >= 1) c.settings.memory else c.settings.memory.copy(
                    judgeProviderId = s.settings.memory.judgeProviderId, judgeModelId = s.settings.memory.judgeModelId,
                    judgeAnonymous = s.settings.memory.judgeAnonymous, aiVerify = s.settings.memory.aiVerify, countAiVerdict = s.settings.memory.countAiVerdict)
                // 1.5.0 以前的不认识 Word / Excel 设置
                val office = if (c.settings.schema >= 2) c.settings.office else s.settings.office
                // 1.6.0 以前的不认识 Code 栏设置（外壳、超时、最大步数、允许手机用）
                val code = if (c.settings.schema >= 3) c.settings.code else s.settings.code
                // 1.7.0 以前的不认识语音设置（读出来 / 听写用哪家）
                val speechSettings = if (c.settings.schema >= 4) c.settings.speech else s.settings.speech
                // 1.7.0 以前的不认识按意思搜设置（开关、向量模型）
                val semantic = if (c.settings.schema >= 5) c.settings.semantic else s.settings.semantic
                // 2026-10-17 以前的不认识个性化（自定义指令）
                val personal = if (c.settings.schema >= 6) c.settings.personal else s.settings.personal
                // 2026-10-22 以前的不认识液态玻璃开关
                val glass = if (c.settings.schema >= 7) c.settings.glass else s.settings.glass
                // 2026-10-24 以前的不认识液态玻璃调参
                val glassTuning = if (c.settings.schema >= 8) c.settings.glassTuning else s.settings.glassTuning
                val background = if (c.settings.schema >= 9) c.settings.background else s.settings.background
                s.copy(settings = c.settings.copy(search = c.settings.search.copy(apiKeys = keys), costs = s.settings.costs, memory = memory, office = office, code = code,
                    speech = speechSettings, semantic = semantic, personal = personal, glass = glass, glassTuning = glassTuning, background = background, schema = Settings.SCHEMA))
            }
            if (before.enabled != state.settings.relay.enabled || before.brokers != state.settings.relay.brokers) onRelaySettingsChanged?.invoke()
            // 关掉了的后台活不会再跑，它以前的出错提示也就不会「成功一次自己消失」：直接清掉
            state.settings.memory.let { m ->
                if (!m.stances) bg.ok(BgJob.STANCES)
                if (!m.stances || !m.aiVerify) bg.ok(BgJob.AI_VERIFY)
                if (!m.enabled || !m.autoExtract) bg.ok(BgJob.MEMORY)
            }
            // 按意思搜：开关 / 模型变了就照新的来——关掉就停（算好的留着），开了或者换了模型就重新补算
            if (semanticBefore != state.settings.semantic) {
                val sem = state.settings.semantic
                scope.launch(Dispatchers.IO) {
                    if (!sem.historyOn && !sem.docsOn) {
                        haltEmbedding()
                        clearEmbedRunning()
                        bg.ok(BgJob.EMBED)
                    } else {
                        haltEmbedding()
                        kickEmbedding()
                    }
                }
            }
            if (docsBefore != state.settings.docs) rescanDocs()
            // 微信助理换了谁回答 / 联网开关：已有的「微信对话」也一起换（以前只对新对话生效，容易以为没改成）
            val wx = state.settings.weixin
            if (wx.memberIds.isNotEmpty() && state.conversations.any { it.channel.startsWith("weixin:") && (it.memberIds != wx.memberIds || it.webSearch != wx.webSearch) }) {
                updateState { s ->
                    s.copy(conversations = s.conversations.map {
                        if (it.channel.startsWith("weixin:")) it.copy(memberIds = wx.memberIds.filter { id -> s.member(id) != null }, webSearch = wx.webSearch) else it
                    })
                }
            }
            CommandResult()
        }
        is Command.DocSearch -> {
            if (docs.count == 0 && !docs.isScanning) rescanDocs()
            val hits = if (c.query.isBlank()) docs.recent(c.limit) else withContext(Dispatchers.IO) { docs.search(c.query, c.limit) }
            CommandResult(data = AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.DocHit.serializer()), hits))
        }
        is Command.DocRescan -> { rescanDocs(); CommandResult() }
        is Command.DocAttach -> {
            val e = docs.find(c.path) ?: return CommandResult(false, "文档库里没有这个文件")
            val f = java.io.File(e.path)
            if (!f.isFile) return CommandResult(false, "文件已经不在了：${e.path}")
            if (f.length() > 50L * 1024 * 1024) return CommandResult(false, "「${f.name}」超过 50MB，太大了")
            val att = upload(f.name, if (DocExtract.isImage(f.name, "")) DocExtract.imageMime(f.name, "") else "application/octet-stream",
                withContext(Dispatchers.IO) { f.readBytes() }) ?: return CommandResult(false, "「${f.name}」读取失败")
            CommandResult(data = AppJson.encodeToString(Attachment.serializer(), att))
        }
        is Command.CreateConversation -> {
            val conv = Conversation(
                id = newId(),
                title = c.title.ifBlank { "新对话" },
                memberIds = c.memberIds.filter { state.member(it) != null },
                createdAt = now(), updatedAt = now(),
                // 2026-10-17：电脑上新对话默认「自动」（动手先让检查员看，拦下的再问你），和 Code 栏一样
                fileAuto = !isPhone,
            )
            convs[conv.id] = mutableListOf()
            updateState { it.copy(conversations = listOf(conv) + it.conversations) }
            emit(Event.Messages(conv.id, emptyList()))
            CommandResult(data = conv.id)
        }
        is Command.UpdateConversation -> {
            updateState { s -> s.copy(conversations = s.conversations.map { if (it.id == c.conversation.id) c.conversation.copy(updatedAt = it.updatedAt) else it }) }
            CommandResult()
        }
        is Command.DeleteConversation -> {
            stop(c.id)
            // 步骤上挂的「AI 看过的图」（2026-10-22）也是附件文件，一起删
            val atts = snapshot(c.id).flatMap { m -> m.attachments.map { it.id } + m.tools.mapNotNull { it.image.ifBlank { null } } }
            convs.remove(c.id)
            storage.deleteConversation(c.id, atts)
            // Code 会话的改动备份（<数据目录>/code/<对话 id>/）跟着会话一起删，别留在盘上白占地方
            // 2026-10-17 起对话页改文件也有备份（同一个地方），一起删
            codeBackup(c.id).deleteAll()
            // 它开着的后台命令一起停掉
            state.conversation(c.id)?.background?.forEach { codeRunner?.stopBackground(it.id) }
            // 独立副本：清掉副本（分支留着，可能已经推上去了）
            state.conversation(c.id)?.code?.takeIf { it.worktreeOf.isNotBlank() }?.let { sp ->
                gitHub()?.run("git worktree remove --force \"${sp.folder}\"", File(sp.worktreeOf), 120)
            }
            if (synchronized(stanceBook) { stanceBook.removeAll { it.convId == c.id } }) storage.saveStances(synchronized(stanceBook) { stanceBook.toList() })
            updateState { s -> s.copy(conversations = s.conversations.filterNot { it.id == c.id }) }
            emit(Event.ConversationRemoved(c.id))
            CommandResult()
        }
        is Command.LoadMessages -> {
            emit(Event.Messages(c.convId, snapshot(c.convId)))
            CommandResult()
        }
        is Command.CodeCreate -> {
            // 第一期 Code 栏只在电脑上用：手机本机没有跑命令的执行器（docs/Code栏设计说明.md 第 6、11 节）
            if (codeRunner == null) return CommandResult(false, "这台设备不能跑 Code 栏（第一期只在电脑上用）")
            // 2026-10-17 用户：「新建 code 对话，为什么还需要选择一个文件夹」——不用先选：
            // 没给文件夹就用上一个 Code 会话的（还在的话），再没有就用户文件夹；没给模型就用上一个会话的，再没有就第一个能调工具的
            val last = state.conversations.firstNotNullOfOrNull { it.code }
            val folderPath = c.folder.trim().ifBlank { last?.folder?.takeIf { File(it).isDirectory } ?: System.getProperty("user.home") }
            val folder = File(folderPath)
            if (!folder.exists()) return CommandResult(false, "文件夹不存在：$folderPath")
            if (!folder.isDirectory) return CommandResult(false, "这不是文件夹：$folderPath")
            val pick = if (c.providerId.isNotBlank()) c.providerId to c.modelId else
                (last?.takeIf { it.isClaudeCode }?.let { it.providerId to it.modelId }
                    ?: last?.let { l -> state.provider(l.providerId)?.takeIf { p -> p.enabled && p.models.any { it.id == l.modelId && it.tools } }?.let { l.providerId to l.modelId } }
                    ?: state.providers.filter { it.enabled }.firstNotNullOfOrNull { p -> p.models.firstOrNull { it.tools }?.let { p.id to it.id } }
                    // 一个模型都没加、但电脑上装了 Claude Code：先用它
                    ?: com.guixing.jixunying.engine.code.ClaudeCli.findExe(state.settings.code.claudePath)?.let { CodeSpec.CLAUDE_CODE to "" })
                    ?: return CommandResult(false, "还没有能调用工具的模型：先到 设置 → 模型服务 加一个，或者装好 Claude Code")
            // 独立副本（2026-10-18）：git worktree add 到 <仓库>/.claude/worktrees/<编号>，新分支 jxy/<编号>
            var wtOf = ""
            var wtBranch = ""
            var workFolder = folder
            if (c.worktree) {
                val gh = gitHub() ?: return CommandResult(false, "这台设备不能跑命令")
                val top = gh.run("git rev-parse --show-toplevel", folder, 30)
                if (!top.ok) return CommandResult(false, "这个文件夹不是 Git 仓库，开不了独立副本：${top.text.take(200)}")
                val root = File(top.text.trim().lines().last().trim())
                val tag = newId().take(8)
                val wt = File(root, ".claude/worktrees/$tag")
                wtBranch = "jxy/$tag"
                val add = gh.run("git worktree add \"${wt.absolutePath}\" -b $wtBranch", root, 120)
                if (!add.ok) return CommandResult(false, "开不了独立副本：${add.text.take(300)}")
                wtOf = root.absolutePath
                workFolder = wt
                // 副本放在仓库里面：让主仓库的 git status 别把它们当成没提交的文件
                runCatching {
                    val ex = File(root, ".git/info/exclude")
                    if (ex.parentFile.isDirectory && ".claude/worktrees/" !in (if (ex.isFile) ex.readText() else "")) ex.appendText("\n.claude/worktrees/\n")
                }
            }
            // 外部 Claude Code（2026-10-18）、Codex / Gemini CLI（2026-10-20）：不用服务商，modelId 是给它的模型参数
            if (!CodeSpec.isAgent(pick.first)) {
                val provider = state.provider(pick.first) ?: return CommandResult(false, "服务商不存在（可能被删了）：${pick.first}")
                val model = provider.models.firstOrNull { it.id == pick.second }
                    ?: return CommandResult(false, "「${provider.name}」里没有模型 ${pick.second}")
                // 只有支持调用工具的模型才能进 Code 栏（docs/Code栏设计说明.md 第 2 节）
                if (!model.tools) return CommandResult(false, "「${model.id}」不能调用工具，干不了 Code 栏的活；到 设置 → 模型服务 换一个支持工具的模型")
            }
            val conv = Conversation(
                id = newId(),
                // 标题先叫「新对话」：第一条任务发出去就换成任务的开头（和聊天一样）；文件夹在侧栏和顶栏另外显示
                title = "新对话",
                memberIds = emptyList(),
                createdAt = now(), updatedAt = now(),
                code = CodeSpec(folder = workFolder.absolutePath, providerId = pick.first, modelId = pick.second, worktreeOf = wtOf, worktreeBranch = wtBranch,
                    // 电脑上点「+」直接开的（没给文件夹）默认「自动」：照 Claude Code，auto 是新会话的默认档；
                    // 独立副本开的照原会话的档（c.auto，2026-10-22：以前写死「接受编辑」、丢了自动）；手机上不许开自动
                    mode = if (c.folder.isBlank() && !c.fromPhone) CodeMode.ACCEPT_EDITS else c.mode,
                    autoMode = (c.folder.isBlank() || c.auto) && !c.fromPhone && c.mode != CodeMode.PLAN,
                    fromPhone = c.fromPhone, pcMode = if (c.fromPhone) null else c.mode),
            )
            convs[conv.id] = mutableListOf()
            updateState { it.copy(conversations = listOf(conv) + it.conversations) }
            emit(Event.Messages(conv.id, emptyList()))
            CommandResult(data = conv.id)
        }
        is Command.CodeSetProject -> {
            val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
            val spec = conv.code ?: return CommandResult(false, "这不是 Code 会话")
            var next = spec
            if (c.folder.isNotBlank()) {
                val f = File(c.folder.trim())
                if (!f.isDirectory) return CommandResult(false, "不是文件夹或者不存在：${c.folder}")
                next = next.copy(folder = f.absolutePath)
            }
            if (CodeSpec.isAgent(c.providerId)) {
                next = next.copy(providerId = c.providerId, modelId = c.modelId.trim())
            } else if (c.providerId.isNotBlank()) {
                val p = state.provider(c.providerId) ?: return CommandResult(false, "服务商不存在：${c.providerId}")
                val m = p.models.firstOrNull { it.id == c.modelId } ?: return CommandResult(false, "「${p.name}」里没有模型 ${c.modelId}")
                if (!m.tools) return CommandResult(false, "「${m.id}」不能调用工具，干不了 Code 栏的活")
                next = next.copy(providerId = p.id, modelId = m.id)
            }
            if (next == spec) return CommandResult()
            // 换了文件夹：以前改动记录里相对旧文件夹的路径换成完整路径，撤销照样找得到文件
            if (next.folder != spec.folder) {
                val old = File(spec.folder)
                for (m in snapshot(conv.id)) {
                    if (m.tools.none { it.change != null && !File(it.change.path).isAbsolute }) continue
                    updateMessage(conv.id, m.id) { msg -> msg.copy(tools = msg.tools.map { t ->
                        val ch = t.change
                        if (ch == null || File(ch.path).isAbsolute) t else t.copy(change = ch.copy(path = File(old, ch.path).absolutePath))
                    }) }
                }
            }
            updateCodeSpec(conv.id) { next }
            CommandResult()
        }
        is Command.CodeSetTeam -> {
            val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
            val spec = conv.code ?: return CommandResult(false, "这不是 Code 会话")
            if (TeamSeat.ROLES.none { it.first == c.role }) return CommandResult(false, "没有这个角色：${c.role}")
            val seat = when {
                c.providerId.isBlank() -> null
                CodeSpec.isAgent(c.providerId) -> TeamSeat(c.role, c.providerId, c.modelId.trim())
                else -> {
                    val p = state.provider(c.providerId) ?: return CommandResult(false, "服务商不存在：${c.providerId}")
                    val m = p.models.firstOrNull { it.id == c.modelId } ?: return CommandResult(false, "「${p.name}」里没有模型 ${c.modelId}")
                    if (!m.tools) return CommandResult(false, "「${m.id}」不能调用工具，干不了 Code 栏的活")
                    TeamSeat(c.role, p.id, m.id)
                }
            }
            // 按 ROLES 的顺序排，界面和给主控的说明都稳定
            val others = spec.team.filter { it.role != c.role }
            val team = (others + listOfNotNull(seat)).sortedBy { s -> TeamSeat.ROLES.indexOfFirst { it.first == s.role } }
            updateCodeSpec(conv.id) { it.copy(team = team) }
            CommandResult()
        }
        is Command.CodeSetFlow -> {
            val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
            val spec = conv.code ?: return CommandResult(false, "这不是 Code 会话")
            if (c.flow.isNotEmpty() && c.flow != CodeSpec.FLOW_DEV) return CommandResult(false, "没有这个流程：${c.flow}")
            if (c.flow.isNotEmpty() && (spec.seat("dev") == null || (spec.seat("review") == null && spec.seat("test") == null)))
                return CommandResult(false, "一条龙要先在团队里选好开发，再选审查或测试（至少一个）")
            updateCodeSpec(conv.id) { it.copy(flow = c.flow) }
            CommandResult()
        }
        is Command.CodeSetEffort -> {
            val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
            if (conv.code == null) return CommandResult(false, "这不是 Code 会话")
            if (CodeSpec.EFFORTS.none { it.first == c.effort }) return CommandResult(false, "没有这一档：${c.effort}")
            updateCodeSpec(conv.id) { it.copy(effort = c.effort) }
            CommandResult()
        }
        is Command.CodeSetMode -> {
            val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
            val spec = conv.code ?: return CommandResult(false, "这不是 Code 会话")
            // 电脑上选的档记下来：手机能切到哪档以它为准（PhoneGate）
            val next = spec.copy(mode = c.mode, pcMode = if (c.fromPhone) spec.pcMode else c.mode, openAll = c.openAll && c.mode != CodeMode.PLAN,
                autoMode = c.auto && !c.openAll && c.mode != CodeMode.PLAN)
            if (next == spec) return CommandResult()
            updateState { s ->
                s.copy(conversations = s.conversations.map { if (it.id == conv.id) it.copy(code = next, updatedAt = now()) else it })
            }
            CommandResult()
        }
        is Command.CodeSend -> {
            val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
            if (conv.code == null) return CommandResult(false, "这不是 Code 会话")
            if (codeRunner == null) return CommandResult(false, "这台设备不能跑 Code 栏（第一期只在电脑上用）")
            if (c.text.isBlank() && c.attachmentIds.isEmpty() && c.paths.isEmpty()) return CommandResult(false, "空消息")
            val atts = c.attachmentIds.mapNotNull { storage.fileMeta(it) }
            // 电脑上拖进来的文件：原路径写进任务里给模型看，并加进工作区（用户自己给的，读不用再问）
            val paths = c.paths.map { it.trim() }.filter { it.isNotEmpty() && File(it).exists() && !it.startsWith("\\\\") }.distinct()
            if (paths.isNotEmpty()) updateCodeSpec(conv.id) { s ->
                s.copy(extraPaths = s.extraPaths + paths.filter { p -> s.extraPaths.none { it.equals(p, ignoreCase = true) } })
            }
            val text = c.text.trim() + if (paths.isEmpty()) "" else
                (if (c.text.isBlank()) "" else "\n\n") + "附上的文件 / 文件夹（在这台电脑上，已经加进工作区，用 read_file / list_dir 直接读）：\n" + paths.joinToString("\n") { "- $it" }
            val userMsg = Message(newId(), conv.id, Role.USER, USER_ID, text, attachments = atts, createdAt = now())
            addMessage(userMsg)
            touchConversation(conv.id, c.text.ifBlank { atts.firstOrNull()?.name ?: paths.firstOrNull()?.let { File(it).name }.orEmpty() })
            // 循环本身在 CodeAgent 里；同一个会话已经在跑就排队，前一个跑完接着跑
            codeAgent.submit(conv.id, userMsg, c.fromPhone)
            CommandResult()
        }
        is Command.CodeStop -> { codeAgent.stop(c.convId); CommandResult() }
        is Command.CodeDecide -> {
            if (!codeAgent.decide(c.convId, c.stepId, c.allow, c.remember)) return CommandResult(false, "这一步已经不在等你了")
            CommandResult()
        }
        is Command.CodeSetAccess -> {
            val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
            if (conv.code == null) return CommandResult(false, "这不是 Code 会话")
            val paths = c.extraPaths.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.trimEnd('\\', '/').lowercase() }
            paths.firstOrNull { !File(it).exists() }?.let { return CommandResult(false, "这个路径不存在：$it") }
            if (paths.any { it.startsWith("\\\\") || it.startsWith("//") }) return CommandResult(false, "网络路径不能加进工作区；先映射成盘符再加")
            updateCodeSpec(conv.id) { it.copy(extraPaths = paths, allowedCommands = c.allowedCommands.map { p -> p.trim() }.filter { p -> p.isNotEmpty() }.distinct()) }
            CommandResult()
        }
        is Command.CodeDiff -> codeAgent.diff(c.convId, c.changeId)
        is Command.CodeReview -> codeAgent.review(c.convId, c.changeId, c.accept)
        is Command.CodeRevertTask -> codeAgent.revertTask(c.convId, c.messageId)
        is Command.CodeRewind -> codeAgent.rewind(c.convId, c.messageId)
        // 只读：备份占了多少（手机发来也放行，见 engine/code/PhoneGate）
        is Command.CodeBackupSize -> CommandResult(data = codeBackupSize().toString())
        is Command.TermRun -> {
            val spec = state.conversation(c.convId)?.code ?: return CommandResult(false, "这不是 Code 会话")
            val runner = codeRunner ?: return CommandResult(false, "这台设备不能跑命令")
            val dir = File(c.cwd.ifBlank { spec.folder })
            val cmd = c.command.trim()
            fun reply(cwd: File, out: String, code: Int?) = CommandResult(data = buildJsonObject {
                put("cwd", cwd.absolutePath); put("output", out); put("code", code?.toString() ?: "")
            }.toString())
            // cd：只换目录（每条命令是单独跑的，cd 本身留不住，在这里记）
            val cd = Regex("^(cd|chdir|Set-Location|sl)(\\s+(.*))?$", RegexOption.IGNORE_CASE).find(cmd)
            if (cd != null) {
                val arg = cd.groupValues[3].trim().removeSurrounding("\"").removeSurrounding("'")
                val next = when {
                    arg.isEmpty() -> File(spec.folder)
                    arg == "~" -> File(System.getProperty("user.home"))
                    File(arg).isAbsolute -> File(arg)
                    else -> File(dir, arg)
                }.let { runCatching { it.canonicalFile }.getOrDefault(it) }
                return if (next.isDirectory) reply(next, "", 0) else reply(dir, "没有这个文件夹：${next.path}", 1)
            }
            if (cmd.endsWith("&")) {
                val id = startBackground(c.convId, cmd.removeSuffix("&").trim(), dir)
                return reply(dir, if (id != null) "在后台跑了（编号 $id），输入框上面那一条能看输出、停掉" else "后台开不起来", if (id != null) 0 else 1)
            }
            val r = runner.run(cmd, dir, state.settings.code.shell, state.settings.code.commandTimeoutSec, 30_000)
            reply(dir, r.output + if (r.timedOut) "\n（超时了，已经停掉；开起来不结束的命令末尾加 & 放后台）" else "", r.exitCode)
        }
        is Command.SlashList -> {
            val conv = state.conversation(c.convId)
            val project = conv?.code?.folder?.let { withContext(Dispatchers.IO) { Slash.fromProject(File(it)) } }.orEmpty()
            val mine = state.settings.personal.commands
            val all = Slash.BUILTIN.map { it to "内置" } + mine.map { it to "我的" } + project.map { it to "项目" }
            CommandResult(data = buildJsonArray {
                all.distinctBy { it.first.name }.forEach { (s, src) ->
                    add(buildJsonObject { put("name", s.name); put("description", s.description); put("text", s.text); put("source", src) })
                }
            }.toString())
        }
        is Command.FileSuggest -> {
            val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
            val roots = conv.code?.let { listOf(File(it.folder)) } ?: conv.grantedPaths.map { File(it) }.filter { it.isDirectory }
            val q = c.query.trim().lowercase().replace('\\', '/')
            val hits = withContext(Dispatchers.IO) {
                val out = mutableListOf<Pair<Int, String>>()
                for (root in roots) {
                    val queue = ArrayDeque<File>().apply { add(root) }
                    var n = 0
                    while (queue.isNotEmpty() && n < 20_000) {
                        val f = queue.removeFirst()
                        if (f.isDirectory) {
                            if (f != root && f.name in SKIP_DIRS) continue
                            f.listFiles()?.forEach { queue.add(it) }
                            continue
                        }
                        n++
                        val rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                        val name = f.name.lowercase()
                        // 名字开头对上的排前面，其次名字里有，再次路径里有
                        val rank = when { q.isEmpty() -> 3; name.startsWith(q) -> 0; q in name -> 1; q in rel.lowercase() -> 2; else -> -1 }
                        if (rank >= 0) out += rank to (if (conv.code != null) rel else f.absolutePath)
                    }
                }
                out.sortedWith(compareBy({ it.first }, { it.second.length })).take(20).map { it.second }
            }
            CommandResult(data = kotlinx.serialization.json.JsonArray(hits.map { kotlinx.serialization.json.JsonPrimitive(it) }).toString())
        }
        is Command.McpTest -> {
            if (codeRunner == null) return CommandResult(false, "这台设备不能跑 MCP")
            val c = com.guixing.jixunying.engine.code.McpClient("测试", c.command, File(System.getProperty("user.home")))
            try {
                c.start()
                CommandResult(message = "连上了：${c.tools.size} 个工具", data = c.tools.joinToString("\n") { it.name + "　" + it.description.take(60) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                CommandResult(false, "连不上：${e.message?.take(200)}" + c.stderrTail().takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty())
            } finally {
                c.close()
            }
        }
        is Command.BgOutput -> {
            val out = codeRunner?.backgroundOutput(c.id) ?: return CommandResult(false, "这个后台命令已经不在了")
            CommandResult(data = out)
        }
        is Command.BgStop -> {
            codeRunner?.stopBackground(c.id)
            markBg(c.convId, c.id) { it.copy(running = false) }
            CommandResult(message = "停掉了")
        }
        is Command.GitHubStatus -> {
            val gh = gitHub() ?: return CommandResult(false, "这台设备不能跑命令")
            val (ok, text) = gh.status(File(System.getProperty("user.home")))
            CommandResult(ok, text, data = text)
        }
        is Command.GitHubLogin -> {
            val gh = gitHub() ?: return CommandResult(false, "这台设备不能跑命令")
            if (gh.openLoginWindow(File(System.getProperty("user.home")))) CommandResult(message = "开了一个登录窗口：按提示在浏览器里点同意，登好了回来点「重新检查」")
            else CommandResult(false, "开不了登录窗口")
        }
        is Command.ClaudeStatus -> {
            if (codeRunner == null) return CommandResult(false, "这台设备不能跑 Claude Code")
            val exe = com.guixing.jixunying.engine.code.ClaudeCli.findExe(state.settings.code.claudePath)
                ?: return CommandResult(false, "没找到 Claude Code：装好以后在这里点「重新检查」；装在别处的，在下面填 claude.exe 的位置")
            val (ok, text) = claudeStatus(exe)
            CommandResult(ok, text, data = text)
        }
        is Command.AgentStatus -> {
            if (codeRunner == null) return CommandResult(false, "这台设备不能跑 Codex / Gemini CLI")
            val name = CodeSpec.agentName(c.agent)
            val exe = com.guixing.jixunying.engine.code.AgentCli.findExe(c.agent,
                if (c.agent == CodeSpec.CODEX) state.settings.code.codexPath else state.settings.code.geminiPath)
                ?: return CommandResult(false, "没找到 $name：装好以后点「重新检查」；装在别处的，在下面填它的位置")
            val (ok, text) = agentStatus(c.agent, exe)
            CommandResult(ok, text, data = text)
        }
        is Command.ClaudeLogin -> {
            if (codeRunner == null) return CommandResult(false, "这台设备不能跑 Claude Code")
            val exe = com.guixing.jixunying.engine.code.ClaudeCli.findExe(state.settings.code.claudePath)
                ?: return CommandResult(false, "没找到 Claude Code，先装好它")
            // 开一个看得见的终端窗口运行它：登录在它自己的界面里完成（我们不碰账号和凭据）
            val ok = runCatching {
                // 没登录的话它一打开就会让你登录（登录过了再登用它里面的 /login）；Java 会给带空格的参数自己加引号
                ProcessBuilder(listOf("cmd.exe", "/c", "start", "Claude Code", "cmd.exe", "/k", exe.absolutePath))
                    .directory(File(System.getProperty("user.home"))).also { pb ->
                        pb.environment().clear(); pb.environment().putAll(com.guixing.jixunying.engine.code.ClaudeCli.env(System.getenv(), state.settings.proxy))
                    }.start()
            }.isSuccess
            if (ok) CommandResult(message = "开了一个 Claude Code 窗口：在里面按提示登录你的账号，登好了关掉它，回来点「重新检查」")
            else CommandResult(false, "开不了 Claude Code 窗口")
        }
        is Command.CodeGit -> {
            val spec = state.conversation(c.convId)?.code ?: return CommandResult(false, "这不是 Code 会话")
            val gh = gitHub() ?: return CommandResult(false, "这台设备不能跑命令")
            val dir = File(spec.folder)
            when (c.op) {
                "repo" -> CommandResult(data = JsonObject(gh.repo(dir).mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
                "commit" -> gh.commit(dir, c.message).let { CommandResult(it.ok, if (it.ok) "提交好了" else it.text.take(300), data = it.text) }
                "push" -> gh.push(dir).let { CommandResult(it.ok, if (it.ok) "推上去了" else it.text.take(300), data = it.text) }
                "commit_push" -> {
                    val a = gh.commit(dir, c.message)
                    if (!a.ok) CommandResult(false, a.text.take(300), data = a.text)
                    else gh.push(dir).let { CommandResult(it.ok, if (it.ok) "提交并推上去了" else it.text.take(300), data = a.text + "\n" + it.text) }
                }
                "checks" -> {
                    val t = gh.prChecks(dir) ?: return CommandResult(false, "这个分支还没有 PR")
                    if (t.first.isNotBlank() && spec.prUrl != t.first) updateCodeSpec(c.convId) { it.copy(prUrl = t.first) }
                    CommandResult(data = buildJsonObject {
                        put("url", t.first); put("state", t.second)
                        putJsonArray("checks") { t.third.forEach { (n, r) -> add(buildJsonArray { add(n); add(r) }) } }
                    }.toString())
                }
                "ci_fix" -> {
                    val log = gh.failedLog(dir)
                    if (log.startsWith("最近没有失败")) return CommandResult(false, log)
                    call(Command.CodeSend(c.convId, "PR 的自动检查失败了，下面是失败的日志。找出原因、改好，再跑一遍相关的测试确认：\n\n```\n$log\n```"))
                }
                "automerge" -> gh.autoMerge(dir).let { CommandResult(it.ok, if (it.ok) "设好了：检查全过就自动合并" else it.text.take(300), data = it.text) }
                "pr" -> {
                    val a = gh.commit(dir, c.message)
                    val b = if (a.ok) gh.push(dir) else a
                    val p = if (b.ok) gh.pr(dir) else b
                    Regex("https://github\\.com/\\S+/pull/\\d+").find(p.text)?.value?.let { u -> updateCodeSpec(c.convId) { it.copy(prUrl = u) } }
                    CommandResult(p.ok, if (p.ok) "PR 开好了：" + (Regex("https://\\S+").find(p.text)?.value ?: "") else p.text.take(300), data = listOf(a.text, b.text, p.text).distinct().joinToString("\n"))
                }
                // 2026-10-22：还不是仓库的，只在本地建仓库并提交，不传 GitHub
                "init" -> gh.initLocal(dir, c.message).let { CommandResult(it.ok, if (it.ok) "建好本地仓库并提交了" else it.text.take(300), data = it.text) }
                // 名字空着：独立副本用主仓库的文件夹名（以前用了副本的编号，比如 5a5f0291）
                "create" -> gh.create(dir, c.name.ifBlank { File(spec.worktreeOf.ifBlank { spec.folder }).name }, c.private, c.message).let {
                    CommandResult(it.ok, if (it.ok) "GitHub 上建好仓库并上传了：" + (Regex("https://github\\.com/\\S+").find(it.text)?.value ?: "") else it.text.take(300), data = it.text)
                }
                else -> CommandResult(false, "不认识的操作：${c.op}")
            }
        }
        is Command.SendMessage -> send(c)
        is Command.Stop -> { stop(c.convId); CommandResult() }
        is Command.DeleteMessage -> {
            val list = messages(c.convId)
            val removed = synchronized(list) { list.firstOrNull { it.id == c.messageId }?.also { list.remove(it) } }
            removed?.attachments?.filter { it.kind == AttachmentKind.GENERATED_IMAGE }?.forEach { storage.deleteFile(it.id) }
            save(c.convId)
            emit(Event.MessageRemoved(c.convId, c.messageId))
            dropStanceMessage(c.convId, c.messageId)
            CommandResult()
        }
        is Command.Regenerate -> regenerate(c.convId, c.messageId, c.fromPhone)
        is Command.ChatDecide -> {
            val w = chatWaitings[c.stepId] ?: return CommandResult(false, "这一步已经不在等你了")
            if (w.first != c.convId) return CommandResult(false, "这一步已经不在等你了")
            w.second.complete(c.allow to (if (c.allow) c.remember else ""))
            CommandResult()
        }
        is Command.ChatRevert -> chatRevert(c.convId, c.changeId)
        is Command.ChatSetGranted -> {
            if (state.conversation(c.convId) == null) return CommandResult(false, "对话不存在")
            val paths = c.paths.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            updateState { s -> s.copy(conversations = s.conversations.map { if (it.id == c.convId) it.copy(grantedPaths = paths) else it }) }
            CommandResult()
        }
        is Command.SaveMemory -> {
            val text = c.item.text.trim()
            if (text.isBlank()) return CommandResult(false, "内容是空的")
            if (looksSensitive(text)) return CommandResult(false, "这条像是密码、Key、证件号之类的敏感信息，不建议放进记忆（每次聊天都会发给模型）")
            updateState { s ->
                val item = c.item.copy(text = text.take(300), updatedAt = now(), createdAt = c.item.createdAt.takeIf { it > 0 } ?: now())
                s.copy(memories = if (s.memories.any { it.id == item.id }) s.memories.map { if (it.id == item.id) item else it } else s.memories + item)
            }
            CommandResult()
        }
        is Command.DeleteMemory -> {
            updateState { s -> s.copy(memories = s.memories.filterNot { it.id == c.id }, memoryDeleted = tombstoned(s, listOf(c.id))) }
            CommandResult()
        }
        is Command.TidyMemories -> tidyMemories()
        is Command.SyncMemories -> {
            var out = MemoryBundle()
            updateState { s ->
                out = MemorySync.merge(MemoryBundle(s.memories, s.memoryDeleted), c.bundle)
                s.copy(memories = out.items, memoryDeleted = out.deleted)
            }
            CommandResult(data = AppJson.encodeToString(MemoryBundle.serializer(), out))
        }
        is Command.SearchHistory -> CommandResult(data = AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(HistoryHit.serializer()), searchHistory(c.query, c.limit).hits))
        is Command.WeixinLogin -> weixin?.login() ?: CommandResult(false, "这台设备没有接微信助理")
        is Command.WeixinVerify -> weixin?.verify(c.code) ?: CommandResult(false, "这台设备没有接微信助理")
        is Command.WeixinLogout -> weixin?.logout() ?: CommandResult(false, "这台设备没有接微信助理")
        // 手机经加密线路来要绑定（RelayHost 只放已配对的手机进来）
        is Command.WeixinShare -> weixin?.shareAccount()?.let { CommandResult(data = it) } ?: CommandResult(false, "电脑上还没绑定微信")
        is Command.WeixinImport -> weixin?.importShared(c.shareJson) ?: CommandResult(false, "这台设备没有接微信助理")
        is Command.WeixinReport -> { weixin?.raiseFence(c.answeredUntil); CommandResult() }
        is Command.ExportMessage -> exportMessage(c)
        is Command.Speak -> speak(c)
        is Command.SpeakPlan -> speakPlan(c)
        is Command.Transcribe -> transcribe(c)
        is Command.SemanticEstimate -> semanticEstimate()
        is Command.SemanticStatus -> CommandResult(data = AppJson.encodeToString(SemanticProgress.serializer(), state.semanticProgress))
        is Command.SemanticRebuild -> semanticRebuild(c.scope)
        is Command.StanceList -> {
            val list = synchronized(stanceBook) { stanceBook.filter { c.convId.isBlank() || it.convId == c.convId } }
            CommandResult(data = AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(StanceTopic.serializer()), list))
        }
        is Command.StanceMark -> {
            val convId = synchronized(stanceBook) {
                val i = stanceBook.indexOfFirst { it.id == c.topicId }
                val t = stanceBook.getOrNull(i)
                val ok = t != null && (c.verdict.isEmpty() || c.verdict == StanceTopic.NONE || c.verdict == StanceTopic.OPEN || t.option(c.verdict) != null)
                if (t == null || !ok) null else { stanceBook[i] = t.copy(verdict = c.verdict, updatedAt = now()); t.convId }
            } ?: return CommandResult(false, "这个议题已经不在了，或者没有这个立场")
            saveStances(setOf(convId))
            CommandResult()
        }
        is Command.VerifyStance -> {
            val topic = synchronized(stanceBook) { stanceBook.firstOrNull { it.id == c.topicId } } ?: return CommandResult(false, "这个议题已经不在了")
            if (!state.settings.memory.aiVerify) return CommandResult(false, "AI 核实没开：到 设置 → 记忆 → 立场档案 打开它")
            // 旧结论先留着，新的判出来再换（判不出来也不会把原来的弄丢）
            if (topic.id in verifying) return CommandResult(false, "这道题正在核实，等一会儿")
            scope.launch { verifyTopic(topic.id) }
            CommandResult(message = "正在让裁判核实…")
        }
        is Command.StanceDelete -> {
            val convId = synchronized(stanceBook) {
                stanceBook.firstOrNull { it.id == c.topicId }?.also { t -> stanceBook.removeAll { it.id == t.id } }?.convId
            } ?: return CommandResult(false, "这个议题已经不在了")
            saveStances(setOf(convId))
            CommandResult()
        }
        is Command.NewPairingCode -> {
            previousSecret = null
            updateState { it.copy(pairingSecret = newSecret()) }
            CommandResult()
        }
        is Command.RemoveDevice -> {
            updateState { s -> s.copy(devices = s.devices.filterNot { it.id == c.id }) }
            CommandResult()
        }
        is Command.WeixinSetBackup -> {
            if (c.deviceId.isNotEmpty() && c.deviceId != "none" && state.devices.none { it.id == c.deviceId }) return CommandResult(false, "没有这台手机")
            updateState { it.copy(weixinBackup = c.deviceId) }
            CommandResult()
        }
        is Command.RenameDevice -> {
            val name = c.name.trim().take(30)
            if (name.isEmpty()) return CommandResult(false, "名字是空的")
            if (state.devices.none { it.id == c.id }) return CommandResult(false, "没有这台手机")
            updateState { s -> s.copy(devices = s.devices.map { if (it.id == c.id) it.copy(name = name) else it }) }
            CommandResult()
        }
        is Command.UploadFile -> {
            val bytes = runCatching { Base64.getDecoder().decode(c.base64) }.getOrNull() ?: return CommandResult(false, "文件数据损坏")
            val att = upload(c.name, c.mime, bytes) ?: return CommandResult(false, "「${c.name}」上传失败")
            CommandResult(data = AppJson.encodeToString(Attachment.serializer(), att))
        }
        is Command.GetFile -> {
            val bytes = storage.fileBytes(c.id) ?: return CommandResult(false, "文件不存在")
            CommandResult(data = Base64.getEncoder().encodeToString(bytes))
        }
        is Command.ExportConfig -> {
            val s = state
            val bundle = ConfigBundle(s.providers, s.members, s.profile, s.settings.search, s.settings.imageGen, s.settings.proxy, s.memories)
            CommandResult(data = AppJson.encodeToString(ConfigBundle.serializer(), bundle))
        }
        is Command.ImportConfig -> {
            val b = runCatching { AppJson.decodeFromString(ConfigBundle.serializer(), c.bundleJson) }.getOrNull()
                ?: return CommandResult(false, "配置数据损坏")
            // 按内容比对：同一个账号的服务商、同名同模型的成员不重复加；本机已有的重复项顺便合并
            var r: ConfigMerge.ImportResult? = null
            updateState { s -> ConfigMerge.import(s, b, keepLocalProxy = isPhone).also { r = it }.state }
            val res = r!!
            remapSenders(res.memberMap)
            val msg = if (res.nothingNew) "电脑和这台设备的模型服务、成员已经一样，没有要导入的"
            else buildList {
                if (res.addedProviders > 0) add("新增 ${res.addedProviders} 个服务商")
                if (res.addedMembers > 0) add("新增 ${res.addedMembers} 位成员")
                if (res.addedMemories > 0) add("新增 ${res.addedMemories} 条记忆")
                if (res.filled.size <= 3) addAll(res.filled)
                else { addAll(res.filled.take(2)); add("另有 ${res.filled.size - 2} 项补上了空缺") }
                if (res.removedDuplicates > 0) add("合并了 ${res.removedDuplicates} 个重复项")
            }.joinToString("，")
            CommandResult(message = msg)
        }
    }

    // ———————————————— 文件 ————————————————

    override suspend fun upload(name: String, mime: String, bytes: ByteArray): Attachment? = withContext(Dispatchers.IO) {
        if (bytes.size > 50 * 1024 * 1024) {
            emit(Event.Notice("「$name」超过 50MB，太大了", error = true)); return@withContext null
        }
        val id = newId()
        if (DocExtract.isImage(name, mime)) {
            val att = Attachment(id, name, DocExtract.imageMime(name, mime), bytes.size.toLong(), AttachmentKind.IMAGE)
            storage.putFile(att, bytes, null)
            att
        } else {
            val (text, note) = DocExtract.extract(name, bytes)
            val att = Attachment(id, name, mime.ifBlank { "application/octet-stream" }, bytes.size.toLong(), AttachmentKind.DOCUMENT,
                textChars = text?.length ?: 0, note = note)
            storage.putFile(att, bytes, text)
            if (text == null) emit(Event.Notice("「$name」：$note", error = true))
            att
        }
    }

    override suspend fun fileBytes(id: String): ByteArray? = withContext(Dispatchers.IO) { storage.fileBytes(id) }

    fun fileMeta(id: String) = storage.fileMeta(id)

    // ———————————————— 聊天 ————————————————

    private fun touchConversation(convId: String, titleFrom: String?) {
        updateState { s ->
            s.copy(conversations = s.conversations.map { cv ->
                if (cv.id != convId) cv else cv.copy(
                    updatedAt = now(),
                    title = if (cv.title == "新对话" && !titleFrom.isNullOrBlank()) titleFrom.replace(Regex("\\s+"), " ").trim().take(24) else cv.title,
                )
            }.sortedByDescending { it.updatedAt })
        }
    }

    private fun launchFor(convId: String, block: suspend CoroutineScope.() -> Unit): Job {
        val set = running.getOrPut(convId) { ConcurrentHashMap.newKeySet() }
        val job = scope.launch(block = block)
        set.add(job)
        job.invokeOnCompletion { set.remove(job) }
        return job
    }

    private fun stop(convId: String) {
        running[convId]?.toList()?.forEach { it.cancel() }
    }

    private suspend fun send(c: Command.SendMessage): CommandResult {
        var conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
        if (c.text.isBlank() && c.attachmentIds.isEmpty() && c.paths.isEmpty()) return CommandResult(false, "空消息")
        // @ 了（或者开头直接喊了）不在这个对话里的成员：直接拉进来让他回答（以前会悄悄换成别人答）
        val direct = c.drawImage || c.makeVideo
        val invited = if (direct) emptyList() else outsidersCalled(c.text, conv)
        if (invited.isNotEmpty()) {
            updateState { s -> s.copy(conversations = s.conversations.map { if (it.id == conv.id) it.copy(memberIds = it.memberIds + invited.map { m -> m.id }) else it }) }
            conv = state.conversation(conv.id) ?: return CommandResult(false, "对话不存在")
            emit(Event.Notice("已把「${invited.joinToString("、") { it.name }}」拉进这个对话"))
        }
        if (!direct && conv.memberIds.isEmpty()) return CommandResult(false, "这个对话还没有 AI 成员，点右上角加几位")
        val atts = c.attachmentIds.mapNotNull { storage.fileMeta(it) }
        // 2026-10-17：电脑上拖进来的文件 / 文件夹：路径写进消息，这个对话的 AI 读它不用再问
        val paths = c.paths.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("\\\\") && File(it).exists() }.distinct()
        if (paths.isNotEmpty()) grantPaths(conv.id, paths)
        val text = c.text.trim() + if (paths.isEmpty()) "" else
            (if (c.text.isBlank()) "" else "\n\n") + "附上的文件 / 文件夹（在这台电脑上，可以直接用 list_folder / read_document 读）：\n" + paths.joinToString("\n") { "- $it" }
        val userMsg = Message(newId(), conv.id, Role.USER, USER_ID, text, attachments = atts, createdAt = now())
        addMessage(userMsg)
        touchConversation(conv.id, c.text.ifBlank { atts.firstOrNull()?.name ?: paths.firstOrNull()?.let { File(it).name }.orEmpty() })
        launchFor(conv.id) { withContext(if (c.fromPhone) PhoneTurn() else kotlin.coroutines.EmptyCoroutineContext) {
            if (c.makeVideo) videoDirect(conv.id, userMsg, c.text.trim(), paths)
            else if (c.drawImage) drawDirect(conv.id, userMsg) else {
                runTurn(conv.id, userMsg)
                // 答完以后在后台：聊天太长就压缩，攒够了就挑出值得长期记住的
                scope.launch { afterTurn(conv.id) }
                // 新答的内容也要算进按意思搜（开关开着才动，见 kickEmbedding）
                kickEmbedding()
            }
        } }
        return CommandResult()
    }

    /** 这一轮是手机发起的（2026-10-17）：AI 不许读收录范围外的文件。放在协程上下文里，往下调的工具都看得到。 */
    private class PhoneTurn : kotlin.coroutines.AbstractCoroutineContextElement(PhoneTurn) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<PhoneTurn>
    }

    /** 把路径加进这个对话「不用再问」的清单（不分大小写去重）。 */
    private fun grantPaths(convId: String, paths: List<String>) {
        updateState { s ->
            s.copy(conversations = s.conversations.map { c ->
                if (c.id != convId) c else c.copy(grantedPaths = c.grantedPaths + paths.filter { p -> c.grantedPaths.none { it.equals(p, ignoreCase = true) } })
            })
        }
    }

    /** 对话页等用户批准的读文件：步骤编号 → （对话编号，结果：允不允许 + remember）。 */
    private val chatWaitings = ConcurrentHashMap<String, Pair<String, CompletableDeferred<Pair<Boolean, String>>>>()

    /** 收录范围或这个对话「不用再问」的清单里有没有它（按真实路径比，不分大小写）。 */
    private fun readable(convId: String, f: File): Boolean {
        val real = runCatching { f.toPath().toRealPath().toString() }.getOrElse { f.absolutePath }.lowercase()
        val roots = (state.conversation(convId)?.grantedPaths.orEmpty() + state.docs.roots)
            .mapNotNull { runCatching { File(it).toPath().toRealPath().toString() }.getOrNull()?.lowercase() }
        return roots.any { r -> real == r || real.startsWith(r.trimEnd('\\', '/') + File.separator) }
    }

    /**
     * 对话页 AI 要读 f。2026-10-17 用户：「比 Claude 更开放」——电脑上发起的，哪儿都能读，不问；
     * 微信对话、手机发起的这一轮不许读收录范围外的（手机、微信丢了就等于整台电脑的文件被人读）。
     * 返回 null = 可以读；返回文字 = 不读，把这句话回给模型。
     */
    private suspend fun askToRead(convId: String, msgId: String, f: File): String? {
        if (f.path.startsWith("\\\\") || f.path.startsWith("//")) return "网络路径不能读：${f.path}"
        if (readable(convId, f)) return null
        remoteRefusal(convId, "读", f)?.let { return it }
        return null
    }

    /** 微信对话、手机发起的这一轮：收录范围外的不许碰。返回不为空就是拒绝的说法。 */
    private suspend fun remoteRefusal(convId: String, what: String, f: File): String? {
        if (state.conversation(convId)?.channel?.startsWith("weixin:") == true)
            return "这是微信里发来的，收录范围外的文件不能$what（${f.path}）。如实告诉用户：要${what}就在电脑上的 AI集训营 里说。"
        if (kotlin.coroutines.coroutineContext[PhoneTurn] != null)
            return "这一轮是手机上发起的，收录范围外的文件不能$what（${f.path}）。如实告诉用户：要${what}就在电脑上说，或者把它加进「我的文档」。"
        return null
    }

    /**
     * 对话页 AI 要改 / 新建 f（2026-10-17，对话栏 = Chat + Cowork）：
     * 微信、手机发起的一律不许改；开了「全部放开」或者在拖进来 / 不用再问的文件夹里，直接改；别处的停下来问（卡片上带差异）。
     * 返回 null = 可以改；返回文字 = 不改。stepId 是这一步的编号（改完把改动挂在它上面）。
     */
    private suspend fun askToWrite(convId: String, msgId: String, f: File, stepId: String, diff: String, note: String): String? {
        if (f.path.startsWith("\\\\") || f.path.startsWith("//")) return "网络路径不能改：${f.path}"
        val conv = state.conversation(convId)
        if (conv?.channel?.startsWith("weixin:") == true) return "这是微信里发来的，不能改电脑上的文件（${f.path}）。如实告诉用户：要改就在电脑上的 AI集训营 里说。"
        if (kotlin.coroutines.coroutineContext[PhoneTurn] != null) return "这一轮是手机上发起的，不能改电脑上的文件（${f.path}）。如实告诉用户：要改就在电脑上说。"
        val step = ToolStep(if (note.startsWith("替换")) "edit" else "write", f.absolutePath, id = stepId, output = diff, ok = true, note = note,
            scopeDir = (f.absoluteFile.parentFile ?: f).absolutePath)
        if (conv?.fileOpen == true || grantedForWrite(convId, f)) {
            updateMessage(convId, msgId) { it.copy(tools = it.tools + step) }
            return null
        }
        var asked = step
        if (conv?.fileAuto == true) {
            val why = autoVerdict(convId, lastUserText(convId, null), "$note：${f.absolutePath}\n改动：\n${diff.take(3000)}")
            if (why == null) { updateMessage(convId, msgId) { it.copy(tools = it.tools + step.copy(note = "$note（自动检查放行）")) }; return null }
            asked = step.copy(note = "$note（自动检查拦下：$why）")
        }
        val done = CompletableDeferred<Pair<Boolean, String>>()
        chatWaitings[stepId] = convId to done
        updateMessage(convId, msgId) { it.copy(tools = it.tools + asked.copy(approval = "waiting")) }
        val (allow, remember) = try {
            done.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) it.copy(approval = "denied", ok = false, note = "停下来时没有改") else it }) } }
            throw e
        } finally {
            chatWaitings.remove(stepId)
        }
        if (allow && remember == "dir") grantPaths(convId, listOf(step.scopeDir))
        updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map {
            if (it.id != stepId) it else it.copy(approval = if (allow) "allowed" else "denied", ok = allow,
                note = if (!allow) "你拒绝了，没有改" else if (remember == "dir") "$note（${step.scopeDir} 这个对话里以后不再问）" else note)
        }) }
        return if (allow) null else "用户拒绝了改 ${f.path}。不要原样再试，可以换个做法或者问用户。"
    }

    /**
     * 「自动」档的检查（engine/AutoJudge）：用裁判模型（没有就用记录员）看一眼要做的动作。
     * 返回 null = 放行；返回文字 = 拦下的理由（调不通、看不懂也按拦下算，交给用户决定，绝不悄悄放行）。
     */
    private suspend fun autoVerdict(convId: String, request: String, action: String): String? {
        val (p, model) = JudgePick.pick(state) ?: Recorder.pick(state) ?: return "没有能用的检查模型（设置 → 记忆 里的裁判 / 记录员）"
        return try {
            val r = withContext(UsageTag(UsageKinds.OTHER, convId = convId)) {
                llm.chat(p, model, listOf(userMsg(AutoJudge.prompt(request, action))), null, null) { _, _ -> }
            }
            val v = AutoJudge.parse(r.content) ?: return "检查员的回答看不懂"
            if (v.first) null else v.second.ifBlank { "检查员觉得要先问你" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "检查员调不通：${friendlyError(e, p).take(80)}"
        }
    }

    /** 拖进来的 / 选了「不再问」的文件夹里（不含「我的文档」——那是只读的收录范围）。 */
    private fun grantedForWrite(convId: String, f: File): Boolean {
        val real = runCatching { (if (f.exists()) f else f.absoluteFile.parentFile ?: f).toPath().toRealPath().toString() }.getOrElse { f.absolutePath }.lowercase()
        return state.conversation(convId)?.grantedPaths.orEmpty()
            .mapNotNull { runCatching { File(it).toPath().toRealPath().toString() }.getOrNull()?.lowercase() }
            .any { r -> real == r || real.startsWith(r.trimEnd('\\', '/') + File.separator) }
    }

    /** 新建 / 重写（edit = false）或局部替换（edit = true）一个文本文件：先问（看情况）、备份、写，改动挂在这一步上能撤销。 */
    private suspend fun chatWrite(convId: String, msgId: String, args: JsonObject, edit: Boolean): String {
        val path = args.str("path").orEmpty().trim().removeSurrounding("\"").ifBlank { return "参数错误：缺少 path" }
        val f = File(path)
        if (!f.isAbsolute) return "path 要写完整路径（比如 G:\\资料\\笔记.md）：$path"
        if (f.isDirectory) return "这是文件夹，不能当文件写：$path"
        val before = withContext(Dispatchers.IO) { if (f.isFile) runCatching { f.readText() }.getOrNull() else "" } ?: return "读不了原来的 ${f.name}（可能没有权限）"
        val after = if (!edit) args.str("content") ?: return "参数错误：缺少 content" else {
            val old = args.str("old_text").orEmpty()
            val new = args.str("new_text") ?: return "参数错误：缺少 new_text"
            if (!f.isFile) return "${f.name} 不存在（要新建就用 write_file）"
            if (old.isEmpty()) return "参数错误：old_text 不能是空的"
            var n = 0
            var at = before.indexOf(old)
            while (at >= 0) { n++; at = before.indexOf(old, at + old.length) }
            if (n == 0) return "文件里没有这段文字，没有改动。先用 read_document 看一下现在的内容。"
            if (n > 1) return "这段文字在文件里出现了 $n 次，分不清改哪一处，没有改动。把 old_text 写长一点。"
            before.replaceFirst(old, new)
        }
        val diff = CodeDiff.make(f.absolutePath, before, after)
        val stepId = newId()
        val note = if (edit) "替换 ${f.name} 里的一段" else if (f.isFile) "重写这个文件" else "新建这个文件"
        askToWrite(convId, msgId, f, stepId, diff.text.take(20_000), note)?.let { return it }
        val created = withContext(Dispatchers.IO) { codeBackup(convId).save(stepId, f) }
        val err = withContext(Dispatchers.IO) { runCatching { f.absoluteFile.parentFile?.mkdirs(); f.writeText(after, Charsets.UTF_8) }.exceptionOrNull() }
        if (err != null) {
            updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) it.copy(ok = false, note = "没写成：${err.message}") else it }) }
            return "写不了 ${f.absolutePath}：${err.message}"
        }
        val change = com.guixing.jixunying.model.FileChange(stepId, f.absolutePath, diff.added, diff.removed, created = created)
        updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) it.copy(change = change) else it }) }
        // 改完文件后的钩子（2026-10-18，和 Code 栏同一条设置）
        val hookOut = state.settings.code.hookAfterEdit.trim().takeIf { it.isNotEmpty() }?.let { hook ->
            val runner = codeRunner ?: return@let ""
            val r = runner.run(hook.replace("\$FILE", "\"${f.absolutePath}\""), f.absoluteFile.parentFile ?: f, state.settings.code.shell, state.settings.code.commandTimeoutSec, 6000)
            updateMessage(convId, msgId) { m -> m.copy(tools = m.tools + ToolStep("run", "改完文件后的钩子：$hook", id = newId(), ok = r.exitCode == 0, output = r.output.take(6000), note = "钩子")) }
            "\n钩子（$hook）" + (if (r.exitCode == 0) "跑完了" else "报错了") + "：" + r.output.take(3000)
        }.orEmpty()
        return "${if (edit) "改好了" else if (created) "新建好了" else "重写好了"}：${f.absolutePath}（+${diff.added} −${diff.removed}）。用户能在回答里一键撤销。$hookOut"
    }

    /**
     * 对话页要动手（跑命令、删东西）之前看要不要问（2026-10-17）：微信、手机发起的一律不许；
     * 开了「全部放开」不问（危险命令照样问）；删东西在拖进来 / 不再问的文件夹里不问；其余的停下来问。返回 null = 可以做。
     */
    private suspend fun askToAct(convId: String, msgId: String, step: ToolStep, skipIfGranted: File?, dangerous: Boolean): String? {
        val conv = state.conversation(convId)
        val what = if (step.kind == "run") "跑命令" else "删电脑上的东西"
        if (conv?.channel?.startsWith("weixin:") == true) return "这是微信里发来的，不能$what。如实告诉用户：要做就在电脑上的 AI集训营 里说。"
        if (kotlin.coroutines.coroutineContext[PhoneTurn] != null) return "这一轮是手机上发起的，不能$what。如实告诉用户：要做就在电脑上说。"
        val free = !dangerous && (conv?.fileOpen == true || (skipIfGranted != null && grantedForWrite(convId, skipIfGranted)))
        if (free) {
            updateMessage(convId, msgId) { it.copy(tools = it.tools + step) }
            return null
        }
        // 自动：先让检查员看；放行就直接做，拦下的带着理由问用户（危险命令不经检查员，直接问）
        var note = step.note
        if (conv?.fileAuto == true && !dangerous) {
            val why = autoVerdict(convId, lastUserText(convId, null), "${step.note}：${step.input}")
            if (why == null) { updateMessage(convId, msgId) { it.copy(tools = it.tools + step.copy(note = step.note + "（自动检查放行）")) }; return null }
            note = step.note + "（自动检查拦下：$why）"
        }
        val stepQ = step.copy(note = note)
        val done = CompletableDeferred<Pair<Boolean, String>>()
        chatWaitings[step.id] = convId to done
        updateMessage(convId, msgId) { it.copy(tools = it.tools + stepQ.copy(approval = "waiting")) }
        val (allow, remember) = try {
            done.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == step.id) it.copy(approval = "denied", ok = false, note = "停下来时没有做") else it }) } }
            throw e
        } finally {
            chatWaitings.remove(step.id)
        }
        if (allow && remember == "dir" && step.scopeDir.isNotBlank()) grantPaths(convId, listOf(step.scopeDir))
        updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map {
            if (it.id != step.id) it else it.copy(approval = if (allow) "allowed" else "denied", ok = allow, note = if (allow) it.note else "你拒绝了，没有做")
        }) }
        return if (allow) null else "用户拒绝了这一步（${step.input.take(80)}），没有做。不要原样再试，可以换个做法或者问用户。"
    }

    /** 对话页跑命令（和 Code 栏同一个执行器、同一套外壳和超时设置）。 */
    private suspend fun chatRun(convId: String, msgId: String, args: JsonObject): String {
        val command = args.str("command").orEmpty().ifBlank { return "参数错误：缺少 command" }
        val runner = codeRunner ?: return "这台设备不能跑命令。"
        val cwd = args.str("cwd")?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: state.conversation(convId)?.grantedPaths?.map { File(it) }?.firstOrNull { it.isDirectory }
            ?: File(System.getProperty("user.home"))
        if (!cwd.isDirectory) return "没有这个文件夹：${cwd.path}"
        val stepId = newId()
        val danger = com.guixing.jixunying.engine.code.CodeCommands.dangerous(command)
        val step = ToolStep("run", command, id = stepId, ok = true, note = "跑这条命令（在 ${cwd.absolutePath}）", scopeDir = "")
        askToAct(convId, msgId, step, null, danger)?.let { return it }
        val code = state.settings.code
        if (args.str("background")?.trim()?.lowercase() == "true") {
            val id = startBackground(convId, command, cwd)
            updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) it.copy(ok = id != null, note = if (id != null) "在后台跑，编号 $id" else "后台开不起来") else it }) }
            return if (id == null) "后台开不起来。" else "已经在后台开起来了，编号 $id。用 read_output 看输出，用 stop_process 停掉。"
        }
        val r = runner.run(command, cwd, code.shell, code.commandTimeoutSec, 12_000)
        val (shown, _) = com.guixing.jixunying.engine.code.CodeRunnerRules.clip(r.output, 12_000)
        val status = when {
            r.timedOut -> "超时了（${code.commandTimeoutSec} 秒），进程连同子进程都杀掉了"
            r.exitCode == 0 -> "跑完了（退出码 0）"
            else -> "退出码 ${r.exitCode}"
        }
        updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) it.copy(ok = r.exitCode == 0, output = shown.take(20_000), note = status) else it }) }
        return "命令：$command\n在：${cwd.absolutePath}\n$status\n$shown"
    }

    /** 对话页删文件 / 文件夹：先整个备份（文件夹复制一份到 <数据目录>/code/<对话>/<编号>.dir），回答里能一键恢复。 */
    private suspend fun chatDelete(convId: String, msgId: String, args: JsonObject): String {
        val path = args.str("path").orEmpty().trim().removeSurrounding("\"").ifBlank { return "参数错误：缺少 path" }
        val f = File(path)
        if (!f.isAbsolute) return "path 要写完整路径：$path"
        if (!f.exists()) return "没有这个文件或文件夹：$path"
        if (f.path.startsWith("\\\\") || f.toPath().nameCount <= 1) return "这个路径不能删：$path"
        val stepId = newId()
        val isDir = f.isDirectory
        val count = if (isDir) withContext(Dispatchers.IO) { f.walkTopDown().count { it.isFile } } else 1
        val step = ToolStep("delete", f.absolutePath, id = stepId, ok = true, note = if (isDir) "删掉这个文件夹（里面 $count 个文件）" else "删掉这个文件",
            scopeDir = (f.absoluteFile.parentFile ?: f).absolutePath)
        askToAct(convId, msgId, step, f, dangerous = false)?.let { return it }
        val err = withContext(Dispatchers.IO) {
            runCatching {
                if (isDir) {
                    val bak = File(File(File(storage.root, "code"), convId), "$stepId.dir")
                    f.copyRecursively(bak, overwrite = true)
                    check(f.deleteRecursively()) { "有的文件删不掉（可能被别的程序占着）" }
                } else {
                    codeBackup(convId).save(stepId, f)
                    check(f.delete()) { "删不掉（可能被别的程序占着）" }
                }
            }.exceptionOrNull()
        }
        if (err != null) {
            updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) it.copy(ok = false, note = "没删成：${err.message}") else it }) }
            return "删不了 ${f.absolutePath}：${err.message}"
        }
        val change = com.guixing.jixunying.model.FileChange(stepId, f.absolutePath, 0, count)
        updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) it.copy(change = change) else it }) }
        return "删掉了：${f.absolutePath}${if (isDir) "（$count 个文件）" else ""}。删之前备份过，用户能一键恢复。"
    }

    /** 撤销对话页 AI 改的一处文件：按备份恢复，原来没有的删掉；删掉的文件 / 文件夹放回去。 */
    private suspend fun chatRevert(convId: String, changeId: String): CommandResult {
        val msg = snapshot(convId).firstOrNull { m -> m.tools.any { it.change?.id == changeId } } ?: return CommandResult(false, "找不到这处改动")
        val ch = msg.tools.first { it.change?.id == changeId }.change!!
        if (ch.review == "rejected") return CommandResult(message = "已经撤销过了")
        val dirBak = File(File(File(storage.root, "code"), convId), "$changeId.dir")
        val ok = withContext(Dispatchers.IO) {
            if (dirBak.isDirectory) runCatching { dirBak.copyRecursively(File(ch.path), overwrite = true) }.isSuccess
            else codeBackup(convId).restore(changeId, File(ch.path))
        }
        if (!ok) return CommandResult(false, "这处改动的备份不在了，没法恢复")
        updateMessage(convId, msg.id) { m -> m.copy(tools = m.tools.map { if (it.change?.id == changeId) it.copy(change = it.change.copy(review = "rejected")) else it }) }
        return CommandResult(message = if (ch.created) "撤销了：${File(ch.path).name} 是新建的，已经删掉" else "撤销了：${File(ch.path).name} 恢复成改之前的样子")
    }

    /** 按文件名找（和 Code 栏的 find_files 同一套规矩：跳过 .git、node_modules，新改的在前）。 */
    private suspend fun chatFind(convId: String, msgId: String, args: JsonObject): String {
        val pattern = args.str("pattern").orEmpty().trim().replace('\\', '/').ifBlank { return "参数错误：缺少 pattern" }
        val dir = File(args.str("path").orEmpty().trim().removeSurrounding("\"").ifBlank { return "参数错误：缺少 path（在哪个文件夹底下找）" })
        if (!dir.isDirectory) return "没有这个文件夹：${dir.path}"
        askToRead(convId, msgId, dir)?.let { return it }
        val glob = if ('/' in pattern) pattern else "**/$pattern"
        val m1 = runCatching { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$glob") }.getOrElse { return "通配写法不对：$pattern" }
        val m2 = if ('/' in pattern) null else runCatching { java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern") }.getOrNull()
        val found = mutableListOf<File>()
        withContext(Dispatchers.IO) {
            val q = ArrayDeque<File>().apply { add(dir) }
            var n = 0
            while (q.isNotEmpty() && n < 50_000) {
                val f = q.removeFirst()
                if (f.isDirectory) f.listFiles()?.filter { it.name != ".git" && it.name != "node_modules" }?.forEach { q.add(it) }
                else { n++; val r = dir.toPath().relativize(f.toPath()); if (m1.matches(r) || m2?.matches(r) == true) found += f }
            }
        }
        val list = found.sortedByDescending { it.lastModified() }.take(200)
        updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("doc", "找文件「$pattern」· ${found.size} 个", ok = found.isNotEmpty())) }
        return if (found.isEmpty()) "在 ${dir.path} 底下没找到「$pattern」。"
        else "找到 ${found.size} 个：\n" + list.joinToString("\n") { it.absolutePath } + if (found.size > 200) "\n（只列了最新的 200 个）" else ""
    }

    /** 按内容找（和 Code 栏的 grep 同一套：跳过二进制和超过 1MB 的文件）。 */
    private suspend fun chatGrep(convId: String, msgId: String, args: JsonObject): String {
        val pattern = args.str("pattern").orEmpty().ifBlank { return "参数错误：缺少 pattern" }
        val dir = File(args.str("path").orEmpty().trim().removeSurrounding("\"").ifBlank { return "参数错误：缺少 path（在哪个文件夹底下找）" })
        if (!dir.exists()) return "没有这个路径：${dir.path}"
        askToRead(convId, msgId, dir)?.let { return it }
        val re = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
        val skip = setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "zip", "jar", "class", "exe", "dll", "so", "pdf", "docx", "xlsx", "pptx", "mp3", "mp4", "ttf", "woff", "woff2")
        val hits = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            val q = ArrayDeque<File>().apply { add(dir) }
            var n = 0
            while (q.isNotEmpty() && hits.size < 60 && n < 3000) {
                val f = q.removeFirst()
                if (f.isDirectory) f.listFiles()?.filter { it.name != ".git" && it.name != "node_modules" }?.sortedBy { it.name }?.forEach { q.add(it) }
                else if (f.length() <= 1L * 1024 * 1024 && f.extension.lowercase() !in skip) {
                    n++
                    val text = runCatching { f.readText() }.getOrNull() ?: continue
                    var i = 0
                    for (line in text.lineSequence()) {
                        i++
                        if (hits.size >= 60) break
                        val l = line.take(2000)
                        if (re?.containsMatchIn(l) ?: l.contains(pattern, ignoreCase = true)) hits += "${f.absolutePath}:$i: ${line.trim().take(300)}"
                    }
                }
            }
        }
        updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("doc", "按内容找「${pattern.take(30)}」· ${hits.size} 处", ok = hits.isNotEmpty())) }
        return if (hits.isEmpty()) "在 ${dir.path} 底下没找到「$pattern」。" else hits.joinToString("\n") + if (hits.size >= 60) "\n（只列了前 60 条）" else ""
    }

    /** 读收录范围外的一个文件（先过 askToRead）：文档抽文字，文本照读。 */
    private suspend fun readAnyFile(convId: String, msgId: String, path: String, start: Int, seen: MutableList<Pair<String, String>>? = null): String {
        val f = File(path)
        if (!f.exists()) return "没有这个文件：$path（先用 list_folder 看看文件夹里有什么，或者用 find_files 找）"
        if (f.isDirectory) return "这是文件夹：$path。用 list_folder 看里面有什么。"
        askToRead(convId, msgId, f)?.let { return it }
        // 图片：能看图的模型在下一条消息里看到（和 Code 栏的 read_file 一样）
        if (DocExtract.isImage(f.name, "")) {
            if (seen == null) return "${f.name} 是图片，你这个模型看不了图片。如实告诉用户，可以换一个能看图的成员。"
            if (f.length() > 30L * 1024 * 1024) return "${f.name} 是图片，但太大了（${f.length()} 字节）"
            val bytes = withContext(Dispatchers.IO) { f.readBytes() }
            val shrunk = runCatching { shrinkImageToJpeg(bytes, 1600, 1_500_000) }.getOrNull()
            seen += f.name to (if (shrunk != null) "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(shrunk)
                else "data:${DocExtract.imageMime(f.name, "")};base64," + Base64.getEncoder().encodeToString(bytes))
            updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("doc", "看了图片《${f.name}》")) }
            return "${f.name} 是图片，已经附在下一条消息里给你看。"
        }
        if (f.length() > 50L * 1024 * 1024) return "${f.name} 太大（${f.length()} 字节），读不了"
        val (text, note) = withContext(Dispatchers.IO) { DocExtract.extract(f.name, f.readBytes()) }
        updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("doc", "读《${f.name}》", ok = text != null)) }
        if (text == null) return "《${f.name}》读不出文字：$note。如实告诉用户。"
        val s = start.coerceIn(0, text.length)
        val end = minOf(text.length, s + 10_000)
        return buildString {
            appendLine("《${f.name}》（${f.absolutePath}）共 ${text.length} 字，下面是第 ${s + 1}～$end 字：")
            appendLine(text.substring(s, end))
            if (end < text.length) append("（后面还有 ${text.length - end} 字；要接着读就再调 read_document，start=$end）")
        }
    }

    // ———————————————— 记账 ————————————————

    private fun recordUsage(p: ProviderConfig, model: String, u: Usage, kind: String, tag: UsageTag?, images: Int = 0, units: Double = 0.0, unit: String = "") {
        runCatching {
            ledger.add(UsageRecord(now(), kind, p.id, p.name, Thinking.platformOf(p), model, u.prompt, u.cached, u.completion, images,
                tag?.memberId.orEmpty(), tag?.convId.orEmpty(), units = units, unit = unit))
        }
    }

    /**
     * 第一次用上记账时，把以前聊天记录里已有的用量补记进来（成员回答带着 usage，画的图有模型标签），花费页一打开就有历史。
     * 那时没记记录员的后台活，补不回来。
     */
    private fun backfillLedger(before: Long) {
        val out = mutableListOf<UsageRecord>()
        // 成员在聊天里画的图，消息上没记是哪个画图模型：按现在自动挑的第一个算（估计）
        val guess = ImagePick.resolve(state).firstOrNull()?.let { c -> state.provider(c.providerId)?.let { it to c.modelId } }
        for (c in state.conversations) {
            for (m in storage.loadMessages(c.id)) {
                if (m.role != Role.AI || m.status != MsgStatus.DONE || m.createdAt >= before) continue
                // 标签是「模型（服务商名）」，服务商名自己可能带全角括号（「千问AI平台（阿里）」），后面可能带「· 快速」
                val model = m.modelLabel.substringBefore("（").trim()
                val p = state.providers.firstOrNull { model.isNotEmpty() && m.modelLabel.startsWith("$model（${it.name}）") }
                    ?: state.member(m.senderId)?.let { state.provider(it.providerId) }
                    ?: state.providers.firstOrNull { pp -> pp.models.any { it.id == model && it.imageGen } }
                val pname = p?.name ?: m.modelLabel.substringAfter("（", "").substringBeforeLast("）")
                val platform = p?.let(Thinking::platformOf).orEmpty()
                val images = m.attachments.count { it.kind == AttachmentKind.GENERATED_IMAGE }
                if (m.senderId == PAINTER_ID) {
                    if (images > 0 && model.isNotEmpty()) out += UsageRecord(m.createdAt, UsageKinds.IMAGE, p?.id.orEmpty(), pname, platform, model, images = images, convId = c.id, backfill = true)
                    continue
                }
                val u = m.usage
                if (u != null && model.isNotEmpty()) out += UsageRecord(m.createdAt, UsageKinds.CHAT, p?.id.orEmpty(), pname, platform, model, u.prompt, u.cached, u.completion,
                    memberId = m.senderId, convId = c.id, backfill = true)
                if (images > 0) out += UsageRecord(m.createdAt, UsageKinds.IMAGE, guess?.first?.id.orEmpty(), guess?.first?.name.orEmpty(),
                    guess?.first?.let(Thinking::platformOf).orEmpty(), guess?.second.orEmpty(), images = images, memberId = m.senderId, convId = c.id, backfill = true)
            }
        }
        ledger.addAll(out.sortedBy { it.at })
        ledger.backfilled = true
    }

    // ———————————————— 记忆 ————————————————

    private val memoLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** 记录员的活：同一个对话同时只跑一份，正在跑就跳过（下次答完会再看）。 */
    private suspend fun afterTurn(convId: String) {
        val lock = memoLocks.getOrPut(convId) { kotlinx.coroutines.sync.Mutex() }
        if (!lock.tryLock()) return
        try {
            guard(BgJob.COMPACT, convId) { compactIfLong(convId) }
            guard(BgJob.MEMORY, convId) { extractMemories(convId) }
        } finally {
            lock.unlock()
        }
    }

    private fun titleOf(convId: String) = state.conversation(convId)?.title.orEmpty()

    /** 后台活出了异常（Key 失效、欠费、网络……）：记下来、提示用户，不影响聊天。 */
    private suspend fun guard(job: BgJob, convId: String, block: suspend () -> Unit) {
        try {
            withContext(UsageTag(job.name.lowercase(), convId = convId)) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 立场档案用的是裁判的模型，出错提示要按裁判那家说
            val p = if (job == BgJob.STANCES) JudgePick.pick(state)?.first else Recorder.pick(state)?.first
            bg.failed(job, friendlyError(e, p), titleOf(convId))
        }
    }

    private fun userMsg(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

    private fun speakerName(m: Message) = when {
        m.role == Role.USER -> state.profile.name
        m.senderId == PAINTER_ID -> "画图助手"
        m.senderId == CHAIR_ID -> "会审主席"
        m.senderId == CODE_ID -> "Code 助手"
        else -> state.member(m.senderId)?.name ?: "已移除的成员"
    }

    private fun transcriptOf(msgs: List<Message>, maxPerMessage: Int): String = buildString {
        for (m in msgs) {
            append("【").append(speakerName(m)).append("】")
            append(stripThink(m.content).take(maxPerMessage))
            m.attachments.forEach { a -> append("\n[附件：").append(a.name).append("]") }
            append("\n\n")
        }
    }

    private fun msgCost(m: Message) = m.content.length + m.attachments.sumOf { minOf(it.textChars, 4000) } + 50

    /**
     * 没压缩的聊天超过设定字数（默认 2.4 万字）时，把前面的部分交给记录员压成摘要，最近约三分之一保留原文。
     * 摘要全群共用一份（前身是每个成员各压一遍，调用次数是成员数倍）。
     */
    internal suspend fun compactIfLong(convId: String, force: Boolean = false): Boolean {
        val ms = state.settings.memory
        val memo = storage.loadMemo(convId)
        val fresh = snapshot(convId).filter {
            it.status == MsgStatus.DONE && it.role != Role.SYSTEM && it.createdAt > memo.upToTime && (it.content.isNotBlank() || it.attachments.isNotEmpty())
        }
        if (!force && fresh.sumOf(::msgCost) < ms.compressAt) return false
        var kept = 0
        var cut = fresh.size
        while (cut > 0 && kept + msgCost(fresh[cut - 1]) <= ms.compressAt / 3) { cut--; kept += msgCost(fresh[cut]) }
        cut = minOf(cut, fresh.size - 4)
        if (cut <= 0) return false
        val fold = fresh.subList(0, cut)
        val (p, model) = Recorder.pick(state) ?: run { bg.failed(BgJob.COMPACT, BackgroundWatch.NO_RECORDER, titleOf(convId)); return false }
        val prompt = Recorder.summarizePrompt(memo.summary, transcriptOf(fold, 3000), state.profile.name)
        val r = withContext(Dispatchers.IO) { llm.chat(p, model, listOf(userMsg(prompt)), null, null) { _, _ -> } }
        val summary = stripThink(r.content).trim()
        if (summary.length < 20) {
            bg.failed(BgJob.COMPACT, "记录员没写出摘要：$model 可能不适合当记录员，可以在 设置 → 记忆 换一个", titleOf(convId))
            return false
        }
        val next = storage.loadMemo(convId).copy(summary = summary.take(8000), upToTime = fold.last().createdAt,
            summarizedCount = memo.summarizedCount + fold.size, updatedAt = now())
        storage.saveMemo(convId, next)
        updateState { s -> s.copy(conversations = s.conversations.map { if (it.id == convId) it.copy(summarized = next.summarizedCount) else it }) }
        bg.ok(BgJob.COMPACT)
        return true
    }

    /** 攒够 4 句用户的话，让记录员挑出值得长期记住的、关于用户本人的信息。 */
    internal suspend fun extractMemories(convId: String, force: Boolean = false) {
        val ms = state.settings.memory
        if (!ms.enabled || !ms.autoExtract) return
        val memo = storage.loadMemo(convId)
        val fresh = snapshot(convId).filter { it.status == MsgStatus.DONE && it.createdAt > memo.scannedUpToTime && it.content.isNotBlank() }
        if (fresh.isEmpty() || (!force && fresh.count { it.role == Role.USER } < 4)) return
        val (p, model) = Recorder.pick(state) ?: run { bg.failed(BgJob.MEMORY, BackgroundWatch.NO_RECORDER, titleOf(convId)); return }
        val existing = state.memories
        val prompt = Recorder.extractPrompt(existing, transcriptOf(fresh.takeLast(40), 1500), state.profile.name)
        val r = withContext(Dispatchers.IO) { llm.chat(p, model, listOf(userMsg(prompt)), null, null) { _, _ -> } }
        // 看不懂输出就不挪扫描位置，下次再试
        val items = Recorder.parseItems(r.content) ?: run { bg.failed(BgJob.MEMORY, BackgroundWatch.UNREADABLE, titleOf(convId), BackgroundWatch.rawOf(r)); return }
        bg.ok(BgJob.MEMORY)
        storage.saveMemo(convId, storage.loadMemo(convId).copy(scannedUpToTime = fresh.last().createdAt))
        val changed = applyExtracted(items.take(3), existing, state.conversation(convId)?.title.orEmpty())
        if (changed.isNotEmpty()) emit(Event.Notice("记住了：${changed.first().text}" + (if (changed.size > 1) " 等 ${changed.size} 条" else "") + "（设置 → 记忆 可以查看和修改）"))
    }

    private fun applyExtracted(items: List<Recorder.Extracted>, existing: List<MemoryItem>, source: String): List<MemoryItem> {
        val changed = mutableListOf<MemoryItem>()
        updateState { s ->
            val list = s.memories.toMutableList()
            for (e in items) {
                if (looksSensitive(e.text)) continue
                val replaced = e.replaces?.let { existing.getOrNull(it - 1) }?.let { old -> list.indexOfFirst { it.id == old.id } }?.takeIf { it >= 0 }
                val same = list.indexOfFirst { Recorder.similar(it.text, e.text) }
                when {
                    replaced != null -> list[replaced] = list[replaced].copy(text = e.text, kind = e.kind, updatedAt = now()).also(changed::add)
                    same >= 0 -> Unit
                    else -> list += MemoryItem(newId(), e.text, e.kind, source, now(), now()).also(changed::add)
                }
            }
            s.copy(memories = list)
        }
        return changed
    }

    /** 删掉的记忆记下编号和时间，手机电脑同步时删除也跟过去（MemorySync）。 */
    private fun tombstoned(s: AppState, ids: List<String>): List<MemoryTombstone> {
        val t = now()
        return s.memoryDeleted.filterNot { it.id in ids } + ids.map { MemoryTombstone(it, t) }
    }

    /** 密码、Key、证件号之类不进长期记忆。 */
    private fun looksSensitive(text: String): Boolean {
        val t = text.lowercase()
        return listOf("密码", "password", "api key", "apikey", "身份证", "银行卡", "验证码", "口令").any { it in t } ||
            Regex("sk-[a-z0-9]{12,}").containsMatchIn(t) || Regex("\\d{15,}").containsMatchIn(t)
    }

    private fun rememberNow(text: String, kind: String, source: String): String {
        val clean = text.trim().take(300)
        if (clean.isBlank()) return "参数错误：缺少 text"
        if (looksSensitive(clean)) return "这条看起来是密码、Key、证件号之类的敏感信息，没有记。请提醒用户敏感信息不要放进记忆。"
        var result = "已记住：$clean"
        updateState { s ->
            val i = s.memories.indexOfFirst { Recorder.similar(it.text, clean) }
            if (i >= 0) {
                result = "已经记过类似的，已更新为：$clean"
                s.copy(memories = s.memories.toMutableList().also { it[i] = it[i].copy(text = clean, kind = kind, updatedAt = now()) })
            } else s.copy(memories = s.memories + MemoryItem(newId(), clean, kind, source, now(), now()))
        }
        return result
    }

    private suspend fun tidyMemories(): CommandResult {
        val pinned = state.memories.filter { it.pinned }
        val items = state.memories.filterNot { it.pinned }
        if (items.size < 2) return CommandResult(message = "记忆不多，不用整理")
        val (p, model) = Recorder.pick(state) ?: return CommandResult(false, "没有能用的模型来整理（先在 模型服务 里配一个）")
        val r = withContext(Dispatchers.IO + UsageTag("memory")) { llm.chat(p, model, listOf(userMsg(Recorder.tidyPrompt(items))), null, null) { _, _ -> } }
        val out = Recorder.parseItems(r.content) ?: return CommandResult(false, "记录员的输出看不懂，没有改动")
        if (out.isEmpty()) return CommandResult(false, "记录员一条都没留，不像话，没有改动")
        val next = out.map { e ->
            val src = e.from.mapNotNull { items.getOrNull(it - 1) }
            MemoryItem(newId(), e.text, e.kind, src.firstOrNull()?.source ?: "整理", src.minOfOrNull { it.createdAt } ?: now(), now())
        }
        updateState { it.copy(memories = pinned + next, memoryDeleted = tombstoned(it, items.map { m -> m.id })) }
        return CommandResult(message = "整理好了：${items.size} 条变成 ${next.size} 条（置顶的没动）")
    }

    /** 搜所有对话的聊天内容，按命中多少、时间新旧排序。 */
    /** 搜所有对话的聊天内容：按字面（关键词）搜。开「按意思搜」时由 searchHistory 和向量的结果合起来排。 */
    private fun literalHistory(query: String, limit: Int): List<HistoryHit> {
        val terms = Recorder.terms(query)
        if (terms.isEmpty()) return emptyList()
        val need = if (terms.size >= 4) 2 else 1
        val hits = mutableListOf<Pair<Int, HistoryHit>>()
        for (conv in state.conversations) {
            for (m in snapshot(conv.id)) {
                if (m.status == MsgStatus.STREAMING || m.content.isBlank()) continue
                val sc = Recorder.score(m.content, terms)
                if (sc < need) continue
                hits += sc to HistoryHit(conv.id, conv.title, m.id, speakerName(m), Recorder.snippet(stripThink(m.content), terms), m.createdAt)
            }
        }
        return hits.sortedWith(compareByDescending<Pair<Int, HistoryHit>> { it.first }.thenByDescending { it.second.time })
            .take(limit).map { it.second }
    }

    /** @ 了、或者开头直接喊了名字（「阿麦，……」）、但不在这个对话里的成员。 */
    private fun outsidersCalled(text: String, conv: Conversation): List<Member> =
        (parseMentions(text, state.members) + Addressing.leading(text, state.members)).distinct().filter { it.id !in conv.memberIds }

    /** 找出文字里 @ 了哪些成员，按出现顺序；名字有包含关系时取最长的。 */
    fun parseMentions(text: String, members: List<Member>): List<Member> {
        val out = LinkedHashSet<Member>()
        var i = text.indexOf('@')
        while (i >= 0) {
            members.filter { text.startsWith(it.name, i + 1) }.maxByOrNull { it.name.length }?.let(out::add)
            i = text.indexOf('@', i + 1)
        }
        return out.toList()
    }

    /** 跑一轮：该回答的成员回答，被 @ 的接着说。返回这一轮所有 AI 的发言（微信助理要把它们发回去）。 */
    private suspend fun runTurn(convId: String, trigger: Message): List<Message> {
        val conv = state.conversation(convId) ?: return emptyList()
        val members = conv.memberIds.mapNotNull(state::member)
        if (members.isEmpty()) return emptyList()
        val everyone = Regex("@(所有人|全体成员|全体|大家|all)", RegexOption.IGNORE_CASE).containsMatchIn(trigger.content)
        val mentioned = parseMentions(trigger.content, members)
        // 没 @ 人、但直接喊了名字（「阿麦，画一张图」「让阿麦来」）：只让被叫到的回答
        val named = if (everyone || mentioned.isNotEmpty() || members.size < 2) emptyList() else addressed(convId, trigger.content, members)
        val targets = when {
            everyone -> members
            mentioned.isNotEmpty() -> mentioned
            named.isNotEmpty() -> named
            members.size == 1 -> members
            conv.replyMode == ReplyMode.MENTION_ONLY -> listOf(members.first())
            else -> members
        }
        // 会审模式：对大家提的问题（不是只叫了某几位）走「独立回答 → 匿名互评 → 主席汇总」，不接力
        val council = conv.council && targets.size > 1 && mentioned.isEmpty() && named.isEmpty()
        val independent = targets.size > 1 && (conv.replyMode != ReplyMode.RELAY || council)
        var produced: List<Message> = if (independent) {
            coroutineScope {
                targets.map { m -> async { reply(convId, m, cutoffId = trigger.id, independent = true, calledBy = null) } }.awaitAll()
            }.filterNotNull()
        } else {
            targets.mapNotNull { m -> reply(convId, m, null, independent = false, calledBy = null) }
        }
        val firstAnswers = produced
        val all = produced.toMutableList()
        val calledBy = mutableMapOf<String, String>()
        val councilMsgs = if (council) runCouncil(convId, trigger, firstAnswers) else emptyList()
        if (council) produced = emptyList()

        // AI 之间互相 @：被 @ 的接着说，最多接力 maxMentionChain 轮
        var depth = 0
        while (produced.isNotEmpty() && depth < state.settings.maxMentionChain) {
            depth++
            val next = mutableListOf<Message>()
            val currentMembers = state.conversation(convId)?.memberIds?.mapNotNull(state::member) ?: return all
            for (msg in produced) {
                val speaker = state.member(msg.senderId) ?: continue
                val called = parseMentions(msg.content, currentMembers).filter { it.id != speaker.id }
                for (m in called) reply(convId, m, null, independent = false, calledBy = speaker.name)?.let { next += it; calledBy[it.id] = speaker.name }
            }
            produced = next
            all += next
        }
        // 立场档案：群聊每轮答完，记录员在后台看一遍（不耽误下一句）
        if (members.size > 1 && all.isNotEmpty()) {
            val independentIds = if (independent) firstAnswers.map { it.id }.toSet() else emptySet()
            val round = all.toList()
            scope.launch { guard(BgJob.STANCES, convId) { judgeStances(convId, trigger, round, independentIds, calledBy.toMap()) } }
        }
        // 微信里：互评太长不发，发各自的回答和主席汇总
        return all + councilMsgs.filter { it.stage != "review" }
    }

    // ———————————————— 会审模式（1.5.0） ————————————————

    /**
     * 第一轮各自答完以后：匿名互评（每位答了的成员各评一次，同时跑）→ 主席汇总（裁判模型）。
     * 互评和汇总里的「回答甲」等最后换回真名再给人看；名次由程序算平均名次。
     */
    private suspend fun runCouncil(convId: String, ask: Message, answers: List<Message>): List<Message> {
        val done = answers.filter { it.status == MsgStatus.DONE && stripThink(it.content).isNotBlank() }
        if (done.size < 2) return emptyList()
        val conv = state.conversation(convId) ?: return emptyList()
        // 打乱顺序（按这句话的编号定，同一句话每次一样），代号甲乙丙
        val order = done.shuffled(kotlin.random.Random(ask.id.hashCode()))
        val texts = order.mapIndexed { i, m -> Council.letters[i] to stripThink(m.content) }
        val names = order.mapIndexed { i, m -> Council.letters[i] to speakerName(m) }.toMap()
        val reviewers = done.mapNotNull { state.member(it.senderId) }.distinctBy { it.id }
        val prompt = Council.reviewPrompt(ask.content, texts)
        val reviews = coroutineScope {
            reviewers.map { mb ->
                async {
                    val p = state.provider(mb.providerId)
                    val thinking = p?.let { if (llm.thinkingDropped(it, mb.modelId)) null else Thinking.params(Thinking.rule(it, mb.modelId), mb.thinking) }
                    val sys = Prompts.system(state, conv, mb, canSearch = false, canDraw = false, canSeeImages = false,
                        independentRound = false, calledBy = null, hasImageModel = false)
                    singleShot(convId, mb.id, p, mb.modelId, Prompts.modelLabel(state, mb) + "· 互评", "review", sys, prompt,
                        mb.temperature, thinking, UsageTag("council", mb.id, convId))
                }
            }.awaitAll()
        }.filterNotNull()
        val rankings = reviews.mapNotNull { Council.parseRanking(stripThink(it.content), order.size) }
        val avg = Council.averageRanks(rankings, order.size)
        val judge = com.guixing.jixunying.model.JudgePick.pick(state)
        val chairText = Council.chairPrompt(ask.content, texts, reviews.map { stripThink(it.content) }, avg)
        val chair = singleShot(convId, CHAIR_ID, judge?.first, judge?.second.orEmpty(),
            judge?.let { "${it.second}（${it.first.name}）· 主席" }.orEmpty(), "chair",
            "你是一位中立、严谨的主席，用中文回答，可以用 Markdown；这里显示不了 LaTeX 公式，数学式子直接用文字和符号写。", chairText, null, null, UsageTag("council", "", convId),
            missing = "没有能当主席的模型：到 设置 → 记忆 → 立场档案 选一个裁判（默认 DeepSeek-V4.1-Flash）")
        // 名次表程序写（不靠模型抄），跟在汇总后面；代号换回真名
        val rankTable = if (avg.isEmpty()) "" else "\n\n---\n**互评名次**（${rankings.size} 份互评的平均名次，越小越好）：" +
            avg.entries.sortedBy { it.value }.joinToString("；") { "${names[Council.letters[it.key]]} ${"%.1f".format(it.value)}" }
        val out = mutableListOf<Message>()
        for (r in reviews) updateMessage(convId, r.id) { it.copy(content = Council.reveal(it.content, names)) }?.let(out::add)
        if (chair != null) updateMessage(convId, chair.id) { it.copy(content = Council.reveal(it.content, names) + rankTable) }?.let(out::add)
        return out
    }

    /** 流式问一次模型，写进一条新消息（会审的互评、主席汇总用）。出错写在这条消息上，返回 null。 */
    private suspend fun singleShot(convId: String, senderId: String, provider: ProviderConfig?, modelId: String, label: String, stage: String,
                                   system: String, user: String, temperature: Double?, thinking: JsonObject?, tag: UsageTag,
                                   missing: String = "还没配置模型"): Message? {
        val msg = Message(newId(), convId, Role.AI, senderId, status = MsgStatus.STREAMING, createdAt = now(), modelLabel = label, stage = stage)
        addMessage(msg)
        if (provider == null || modelId.isBlank()) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = missing) }
            return null
        }
        return try {
            val r = withContext(tag) {
                llm.chat(provider, modelId, listOf(buildJsonObject { put("role", "system"); put("content", system) }, userMsg(user)),
                    temperature, null, thinking = thinking) { c, rs -> appendDelta(convId, msg.id, c, rs) }
            }
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.DONE, usage = r.usage) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateMessage(convId, msg.id) { it.copy(status = MsgStatus.STOPPED) } }
            throw e
        } catch (e: Throwable) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = friendlyError(e, provider)) }
            null
        }
    }

    /** 点名：没有 @ 时，看是不是直接喊了某几位成员的名字。开头直接喊的程序认；别的说法问记录员的模型，判断不了就当对大家说。 */
    private suspend fun addressed(convId: String, text: String, members: List<Member>): List<Member> {
        if (Addressing.namesIn(text, members).isEmpty()) return emptyList()
        Addressing.leading(text, members).takeIf { it.isNotEmpty() }?.let { return it }
        val title = titleOf(convId)
        val (p, model) = Recorder.pick(state) ?: run { bg.failed(BgJob.ADDRESSING, BackgroundWatch.NO_RECORDER, title); return emptyList() }
        return try {
            val r = withTimeoutOrNull(10_000) {
                withContext(Dispatchers.IO + UsageTag("addressing", convId = convId)) {
                    llm.chat(p, model, listOf(userMsg(Addressing.prompt(text, members, state.profile.name))), null, null) { _, _ -> }
                }
            }
            val who = r?.let { Addressing.parse(it.content, members) }
            when {
                r == null -> bg.failed(BgJob.ADDRESSING, "10 秒内没判断出来：记录员 $model 太慢，可以在 设置 → 记忆 换个快一点的", title)
                who == null -> bg.failed(BgJob.ADDRESSING, BackgroundWatch.UNREADABLE, title, BackgroundWatch.rawOf(r))
                else -> bg.ok(BgJob.ADDRESSING)
            }
            who.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bg.failed(BgJob.ADDRESSING, friendlyError(e, p), title)
            emptyList()
        }
    }

    // ———————————————— 立场档案 ————————————————

    private val stanceBook: MutableList<StanceTopic> by lazy { storage.loadStances() }
    private val stanceLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private fun topicsOf(convId: String) = synchronized(stanceBook) { stanceBook.filter { it.convId == convId } }

    /** 存盘，并更新对话上的议题数和变化时间（界面看到变了就重新取）。 */
    private fun saveStances(convIds: Set<String>) {
        val all = synchronized(stanceBook) { stanceBook.toList() }
        storage.saveStances(all)
        val counts = convIds.associateWith { id -> all.count { it.convId == id } }
        updateState { s -> s.copy(conversations = s.conversations.map { c -> counts[c.id]?.let { n -> c.copy(stanceTopics = n, stanceAt = now()) } ?: c }) }
    }

    /**
     * 每轮答完：记录员看一遍，开新议题，或者记下已有议题上谁改了口、为什么改。
     * 同一个对话按顺序来（上一轮记好了，下一轮才看得到）。
     */
    private suspend fun judgeStances(convId: String, ask: Message, round: List<Message>, independentIds: Set<String>, calledBy: Map<String, String>) {
        if (!state.settings.memory.stances) return
        val lock = stanceLocks.getOrPut(convId) { kotlinx.coroutines.sync.Mutex() }
        lock.withLock {
            val current = snapshot(convId).associateBy { it.id }
            val said = round.mapNotNull { current[it.id] }.filter {
                it.role == Role.AI && it.status == MsgStatus.DONE && it.senderId != PAINTER_ID && it.senderId != CHAIR_ID && it.stage.isEmpty() && stripThink(it.content).isNotBlank()
            }
            if (said.isEmpty()) return
            val open = topicsOf(convId).sortedBy { it.createdAt }.takeLast(3)
            val canOpen = said.count { it.id in independentIds } >= 2
            if (open.isEmpty() && !canOpen) return
            // 裁判或记录员
            val (p, model) = JudgePick.pick(state) ?: run { bg.failed(BgJob.STANCES, BackgroundWatch.NO_RECORDER, titleOf(convId)); return }
            fun nameOf(id: String) = state.member(id)?.name ?: "已移除的成员"
            val items = said.map { m -> StanceJudge.Said(m, nameOf(m.senderId), m.id in independentIds, calledBy[m.id], toolsNote(m)) }
            val members = stanceMembers(convId, open)
            val anonymous = state.settings.memory.judgeAnonymous
            val promptResult = StanceJudge.prompt(open, items, stripThink(ask.content), state.profile.name, ::nameOf, canOpen, anonymous, members)
            val r = withContext(Dispatchers.IO) { llm.chat(p, model, listOf(userMsg(promptResult.prompt)), null, null) { _, _ -> } }
            val v = StanceJudge.parse(r.content, promptResult.anon) ?: run { bg.failed(BgJob.STANCES, BackgroundWatch.UNREADABLE, titleOf(convId), BackgroundWatch.rawOf(r)); return }
            val newTopicId = applyStances(convId, ask, said, independentIds, open, v, canOpen)
            bg.ok(BgJob.STANCES)
            // AI 核实：开新议题后，裁判判一次谁对。另开一个协程，不占着这个对话的锁（下一轮的记录不用等它联网查完）
            if (newTopicId != null && state.settings.memory.aiVerify) scope.launch { verifyTopic(newTopicId) }
        }
    }

    /**
     * 给裁判看时排代号用的成员：对话里的成员按对话里的顺序（每一轮代号都一样），再加上议题里有表态、后来移出对话的。
     */
    private fun stanceMembers(convId: String, topics: List<StanceTopic>): List<Member> {
        val ids = state.conversation(convId)?.memberIds.orEmpty() + topics.flatMap { t -> t.entries.map { it.memberId } }
        return ids.distinct().mapNotNull { state.member(it) }
    }

    private fun toolsNote(m: Message): String {
        val used = m.tools.mapNotNull { t ->
            when (t.kind) {
                "search" -> if (t.ok) "联网搜索「${t.input.take(30)}」" else null
                "fetch" -> "读了网页"
                "doc" -> "查了本机文档"
                "history" -> "翻了以前的聊天"
                else -> null
            }
        }.distinct()
        return if (used.isEmpty()) "" else "回答前" + used.joinToString("、")
    }

    private fun applyStances(convId: String, ask: Message, said: List<Message>, independentIds: Set<String>, open: List<StanceTopic>,
                             v: StanceJudge.Verdict, canOpen: Boolean): String? {
        val speakers = said.map { it.senderId }.toSet()
        fun idOf(name: String): String? {
            val n = name.trim().removePrefix("@")
            if (n.isEmpty()) return null
            return state.members.filter { it.name.equals(n, ignoreCase = true) }.let { same -> same.firstOrNull { it.id in speakers } ?: same.firstOrNull() }?.id
        }
        val now = now()
        var touched = false
        var newTopicId: String? = null
        // 已有议题：照记录员的判断追加表态
        for ((idx, ups) in v.updates.groupBy { it.topic }) {
            val base = open.getOrNull(idx - 1) ?: continue
            val options = base.options.toMutableList()
            val added = mutableListOf<StanceEntry>()
            for (u in ups) {
                val mid = idOf(u.member)?.takeIf { it in speakers } ?: continue
                val msg = said.last { it.senderId == mid }
                if (base.entries.any { it.messageId == msg.id } || added.any { it.memberId == mid }) continue
                var key = u.stance
                if (key != StanceTopic.UNCLEAR && options.none { it.key == key }) {
                    if (u.newText.isBlank()) key = StanceTopic.UNCLEAR else options += StanceOption(key, u.newText)
                }
                val prev = base.latestOf(mid)
                // 记录员说「没变」就以它为准（有时同一个结论会被它写成新字母）
                val (finalKey, why) = when {
                    prev == null -> key to ""
                    u.why == Stances.HOLD || key == prev.option -> prev.option to Stances.HOLD
                    else -> key to u.why
                }
                val by = if (why.isEmpty() || why == Stances.HOLD) "" else idOf(u.by)?.takeIf { it != mid }.orEmpty()
                added += StanceEntry(mid, msg.id, ask.id, finalKey, first = false, why = why, by = by, reason = u.reason, doubted = v.userDoubt, time = msg.createdAt)
            }
            if (added.isEmpty()) continue
            synchronized(stanceBook) {
                val i = stanceBook.indexOfFirst { it.id == base.id }
                if (i >= 0) {
                    val cur = stanceBook[i]
                    stanceBook[i] = cur.copy(options = cur.options + options.filter { o -> cur.options.none { it.key == o.key } },
                        entries = cur.entries + added, updatedAt = now)
                    touched = true
                }
            }
        }
        // 新议题：每位成员的首答
        val nt = v.newTopic
        if (canOpen && nt != null) {
            val options = nt.options.distinctBy { it.first }.map { StanceOption(it.first, it.second) }
            val entries = nt.stances.mapNotNull { (name, key) ->
                val mid = idOf(name)?.takeIf { it in speakers } ?: return@mapNotNull null
                val msg = said.firstOrNull { it.senderId == mid && it.id in independentIds } ?: said.last { it.senderId == mid }
                val k = if (key == StanceTopic.UNCLEAR || options.any { it.key == key }) key else StanceTopic.UNCLEAR
                StanceEntry(mid, msg.id, ask.id, k, first = msg.id in independentIds, time = msg.createdAt)
            }.distinctBy { it.memberId }
            if (entries.size >= 2) {
                val id = newId()
                synchronized(stanceBook) { stanceBook += StanceTopic(id, convId, ask.id, nt.question, options, entries, createdAt = now, updatedAt = now) }
                touched = true
                newTopicId = id
            }
        }
        if (touched) saveStances(setOf(convId))
        return newTopicId
    }

    /** 正在核实的议题（同一道题不同时跑两次，免得重复花钱）。 */
    private val verifying: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 手填的余额：按填写以后这家的调用估算花了多少（CostReporter.manualEstimate），给出约剩多少。 */
    private fun withManual(b: com.guixing.jixunying.model.BalanceInfo, p: ProviderConfig, m: com.guixing.jixunying.model.ManualBalance): com.guixing.jixunying.model.BalanceInfo {
        val e = costs.manualEstimate(p.id, m)
        val left = m.amount - e.spent
        fun money(v: Double) = (if (m.currency == "USD") "$" else "¥") + "%.2f".format(v)
        fun tokens(n: Long) = if (n >= 10_000) "%.1f 万 token".format(n / 10_000.0) else "$n token"
        val text = buildString {
            append("手填 ").append(money(m.amount)).append("，之后估算用了 ").append(money(e.spent)).append("，约剩 ").append(money(left))
            if (m.packTokens > 0) append("；资源包 / 免费额度约剩 ").append(tokens(e.packLeft))
            if (e.unpriced > 0) append("（有 ${e.unpriced} 次调用价格表里没有，没算进去）")
        }
        return b.copy(ok = true, amount = left, currency = m.currency, text = text, manual = m, estimated = true)
    }

    /**
     * AI 核实一道题：出错、看不懂、没有能用的裁判，都记进后台出错提示（不悄悄停掉）。
     * 不管成没成，最后都碰一下议题的更新时间，界面上的「正在核实…」就能恢复。
     */
    private suspend fun verifyTopic(topicId: String) {
        if (!verifying.add(topicId)) return
        val convId = synchronized(stanceBook) { stanceBook.firstOrNull { it.id == topicId } }?.convId ?: run { verifying.remove(topicId); return }
        try {
            aiVerifyTopic(topicId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            bg.failed(BgJob.AI_VERIFY, friendlyError(e, JudgePick.pick(state)?.first), titleOf(convId))
        } finally {
            verifying.remove(topicId)
            synchronized(stanceBook) {
                val i = stanceBook.indexOfFirst { it.id == topicId }
                if (i >= 0) stanceBook[i] = stanceBook[i].copy(updatedAt = now())
            }
            saveStances(setOf(convId))
        }
    }

    /** AI 核实：开新议题后，裁判判一次谁对（能查就用 web_search）。 */
    private suspend fun aiVerifyTopic(topicId: String) {
        val topic = synchronized(stanceBook) { stanceBook.firstOrNull { it.id == topicId } } ?: return
        val (p, model) = JudgePick.pick(state) ?: run { bg.failed(BgJob.AI_VERIFY, BackgroundWatch.NO_RECORDER, titleOf(topic.convId)); return }
        val anonymous = state.settings.memory.judgeAnonymous
        val verifyResult = StanceJudge.verifyPrompt(topic, stanceMembers(topic.convId, listOf(topic)), anonymous)
        // 带工具的请求：web_search
        val tools = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "web_search")
                    put("description", "Search the web for current information")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Search query")
                            }
                        }
                        putJsonArray("required") { add("query") }
                    }
                }
            })
        }
        val tag = UsageTag("stances", "", topic.convId)
        var sources = emptyList<SearchSource>()
        val working = mutableListOf<JsonObject>()
        working += userMsg(verifyResult.prompt)

        var r: ChatResult
        withContext(tag + Dispatchers.IO) {
            r = llm.chat(p, model, working, null, tools, null, {}, null) { _, _ -> }
            // 如果有工具调用，执行工具并继续
            if (r.toolCalls.isNotEmpty()) {
                working += buildJsonObject {
                    put("role", "assistant")
                    put("content", r.content)
                    // 跟成员的工具循环一样：思考模式下把思考内容带回去（DeepSeek 等要求）
                    if (r.reasoning.isNotEmpty() && p.presetId !in setOf("openai", "anthropic", "gemini", "openrouter")) put("reasoning_content", r.reasoning)
                    putJsonArray("tool_calls") {
                        r.toolCalls.forEach { tc ->
                            add(buildJsonObject {
                                put("id", tc.id); put("type", tc.type)
                                putJsonObject("function") { put("name", tc.name); put("arguments", tc.arguments.ifBlank { "{}" }) }
                            })
                        }
                    }
                }
                for (tc in r.toolCalls) {
                    val result = if (tc.name == "web_search") {
                        val args = runCatching { AppJson.parseToJsonElement(tc.arguments).jsonObject }.getOrNull()
                        val query = args?.get("query")?.jsonPrimitive?.contentOrNull ?: "搜索参数错误"
                        if (query != "搜索参数错误") {
                            val sr = runCatching { search.search(query, searchSettings()) }.getOrNull()
                            if (sr != null && sr.isNotEmpty()) {
                                // 几次搜索接着编号，裁判在 sources 里报用了哪几条
                                val from = sources.size
                                sources = sources + sr
                                sr.withIndex().joinToString("\n\n") { (i, s) -> "[${from + i + 1}] ${s.title}\n${s.url}\n${s.snippet}" }
                            } else "搜索失败"
                        } else query
                    } else "未知工具"
                    working += buildJsonObject {
                        put("role", "tool"); put("tool_call_id", tc.id)
                        put("content", result)
                    }
                }
                // 再次调用让模型处理工具结果
                r = llm.chat(p, model, working, null, null, null, {}, null) { _, _ -> }
            }
        }
        val verdict = StanceJudge.parseVerify(r.content, topic.options.map { it.key }, verifyResult.anon)
            ?: run { bg.failed(BgJob.AI_VERIFY, "裁判的回答看不懂：$model 可能不适合当裁判，可以在 设置 → 记忆 → 立场档案 换一个", titleOf(topic.convId), BackgroundWatch.rawOf(r)); return }
        // 更新议题
        synchronized(stanceBook) {
            val i = stanceBook.indexOfFirst { it.id == topicId }
            if (i >= 0) {
                stanceBook[i] = stanceBook[i].copy(
                    aiVerdict = verdict.verdict,
                    aiReason = verdict.reason,
                    // 只存裁判说用到的；以前把搜到的全挂上，必应乱给的结果也当成了出处
                    aiSources = verdict.cited.mapNotNull { sources.getOrNull(it - 1) },
                    updatedAt = now()
                )
            }
        }
        saveStances(setOf(topic.convId))
        bg.ok(BgJob.AI_VERIFY)
    }

    /** 消息删了（或重新生成）：它的表态也去掉；议题一条表态都不剩就删掉。 */
    private fun dropStanceMessage(convId: String, messageId: String) {
        val changed = synchronized(stanceBook) {
            var any = false
            for (i in stanceBook.indices.reversed()) {
                val t = stanceBook[i]
                if (t.convId != convId || t.entries.none { it.messageId == messageId }) continue
                val left = t.entries.filterNot { it.messageId == messageId }
                if (left.isEmpty()) stanceBook.removeAt(i) else stanceBook[i] = t.copy(entries = left, updatedAt = now())
                any = true
            }
            any
        }
        if (changed) saveStances(setOf(convId))
    }

    // ———————————————— 外部渠道（微信助理） ————————————————

    /** 微信助理：电脑上由 Main.kt 设置，手机上由 JxyApp 设置（1.5.0 起手机也能接）。 */
    @Volatile var weixin: WeixinBridge? = null
        set(value) {
            field = value
            synchronized(stateLock) { state = state.copy(weixinCapable = value != null) }
            emit(Event.State(state))
        }

    fun setWeixinInfo(info: com.guixing.jixunying.model.WeixinInfo) {
        if (state.weixin == info) return
        synchronized(stateLock) { state = state.copy(weixin = info) }
        emit(Event.State(state))
    }

    /** 某个渠道（比如某个微信用户）最近的那个对话；没有就新建一个。 */
    fun channelConversation(channel: String, title: String, memberIds: List<String>, webSearch: Boolean): String {
        state.conversations.filter { it.channel == channel }.maxByOrNull { it.updatedAt }?.let { c ->
            // 对话里的成员都被删光了：补上现在指定回答的成员，不然以后微信里怎么问都答不上来
            if (c.memberIds.none { state.member(it) != null } && memberIds.isNotEmpty()) {
                updateState { s -> s.copy(conversations = s.conversations.map { if (it.id == c.id) it.copy(memberIds = memberIds) else it }) }
            }
            return c.id
        }
        return newChannelConversation(channel, title, memberIds, webSearch)
    }

    fun newChannelConversation(channel: String, title: String, memberIds: List<String>, webSearch: Boolean): String {
        val conv = Conversation(newId(), title, memberIds.filter { state.member(it) != null }, webSearch = webSearch,
            createdAt = now(), updatedAt = now(), channel = channel)
        convs[conv.id] = mutableListOf()
        updateState { it.copy(conversations = listOf(conv) + it.conversations) }
        emit(Event.Messages(conv.id, emptyList()))
        return conv.id
    }

    /**
     * 外部渠道来的一句话：放进对话、等这一轮答完，返回 AI 的回答（最终内容）。
     * 和界面上发消息走同一套（@ 拉人、工具、记忆都一样）；界面上能看到，也能点「停止」。
     */
    suspend fun channelTurn(convId: String, text: String, attachmentIds: List<String>): List<Message> {
        var conv = state.conversation(convId) ?: return emptyList()
        val invited = outsidersCalled(text, conv)
        if (invited.isNotEmpty()) {
            updateState { s -> s.copy(conversations = s.conversations.map { if (it.id == conv.id) it.copy(memberIds = it.memberIds + invited.map { m -> m.id }) else it }) }
            conv = state.conversation(convId) ?: return emptyList()
        }
        if (conv.memberIds.isEmpty()) return emptyList()
        val atts = attachmentIds.mapNotNull { storage.fileMeta(it) }
        val userMsg = Message(newId(), convId, Role.USER, USER_ID, text.trim(), attachments = atts, createdAt = now())
        addMessage(userMsg)
        touchConversation(convId, null)
        val job = scope.async { runTurn(convId, userMsg) }
        running.getOrPut(convId) { ConcurrentHashMap.newKeySet() }.add(job)
        job.invokeOnCompletion { running[convId]?.remove(job) }
        val produced = try { job.await() } catch (e: CancellationException) { if (!job.isCancelled) throw e; emptyList() }
        scope.launch { afterTurn(convId) }
        kickEmbedding()
        val ids = produced.map { it.id }.toSet()
        return snapshot(convId).filter { it.id in ids }
    }

    fun memberName(id: String) = state.member(id)?.name

    private suspend fun regenerate(convId: String, messageId: String, fromPhone: Boolean = false): CommandResult {
        val all = snapshot(convId)
        val idx = all.indexOfFirst { it.id == messageId }
        if (idx < 0) return CommandResult(false, "消息不存在")
        val old = all[idx]
        if (old.role != Role.AI) return CommandResult(false, "只能重新生成 AI 的回答")
        // 会审的互评和汇总靠整轮的回答，单独重答对不上：要重来就把问题再发一次
        if (old.stage.isNotEmpty() || old.senderId == CHAIR_ID) return CommandResult(false, "会审的互评和主席汇总不能单独重答，把问题再发一次就会重新会审")
        val list = messages(convId)
        synchronized(list) { list.removeAll { it.id == messageId } }
        emit(Event.MessageRemoved(convId, messageId))
        save(convId)
        dropStanceMessage(convId, messageId)
        val prev = all.getOrNull(idx - 1)
        launchFor(convId) { withContext(if (fromPhone) PhoneTurn() else kotlin.coroutines.EmptyCoroutineContext) {
            if (old.senderId == PAINTER_ID) {
                prev?.takeIf { it.role == Role.USER }?.let { drawDirect(convId, it) }
            } else if (old.senderId == VIDEO_ID) {
                // 重做：用那条消息的原话（去掉附上路径那一段），附件照旧当素材；当时拖进来的路径在原话后面列着，也带上
                prev?.takeIf { it.role == Role.USER }?.let { u ->
                    val marker = "附上的文件 / 文件夹（在这台电脑上"
                    val text = u.content.substringBefore(marker).trim()
                    val paths = if (marker in u.content) u.content.substringAfter(marker).lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() } else emptyList()
                    videoDirect(convId, u, text, paths)
                }
            } else {
                val m = state.member(old.senderId) ?: return@withContext
                reply(convId, m, cutoffId = prev?.id, independent = false, calledBy = null)
            }
        } }
        return CommandResult()
    }

    /** 自动模式下画图失败过的模型（比如这个 Key 没开通画图），半小时内排到后面。 */
    private val imageFailures = ConcurrentHashMap<String, Long>()

    private class Painted(val att: Attachment, val revisedPrompt: String?, val label: String)

    /**
     * 画一张图。设置里指定了模型就只用它；自动模式按候选顺序试（千问图像优先，见 ImagePick），
     * 一个失败换下一个，最多试三个。某家是 Key 失效或欠费，这家别的画图模型也不用试了，直接换下一家。
     */
    private suspend fun paintAuto(prompt: String, size: String? = null, only: ImageChoice? = null): Painted {
        val all = only?.let { listOf(it) } ?: ImagePick.resolve(state)
        if (all.isEmpty()) throw IllegalStateException(NO_IMAGE_MODEL)
        fun key(c: ImageChoice) = c.providerId + "|" + c.modelId
        val recentlyFailed = all.filter { (imageFailures[key(it)] ?: 0L) > now() - 30 * 60_000 }
        val order = all - recentlyFailed.toSet() + recentlyFailed
        val tried = mutableListOf<ImageChoice>()
        val deadProviders = mutableSetOf<String>()
        var first: Throwable? = null
        for (c in order) {
            if (tried.size >= 3) break
            if (c.providerId in deadProviders) continue
            val p = state.provider(c.providerId) ?: continue
            tried += c
            try {
                val (att, revised) = paint(p to c.modelId, prompt, size)
                imageFailures.remove(key(c))
                return Painted(att, revised, "${c.modelId}（${c.providerName}）")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                imageFailures[key(c)] = now()
                if (accountProblem(e)) {
                    deadProviders += c.providerId
                    all.filter { it.providerId == c.providerId }.forEach { imageFailures[key(it)] = now() }
                }
                if (first == null) first = e
            }
        }
        val e = first ?: IllegalStateException(NO_IMAGE_MODEL)
        if (tried.size <= 1) throw e
        throw IllegalStateException("试了 ${tried.joinToString("、") { it.modelId }}，都没画成。第一个的错误：${friendlyError(e)}", e)
    }

    /** Key 失效或欠费：整家都用不了，不是某个模型的问题。 */
    private fun accountProblem(e: Throwable): Boolean {
        if (e !is ApiException) return false
        val lower = e.body.lowercase()
        return e.status == 401 || e.status == 402 || "arrearage" in lower || "good standing" in lower || "insufficient" in lower ||
            "balance" in lower || "余额" in e.body || "欠费" in e.body || "invalid api key" in lower || "invalidapikey" in lower
    }

    private suspend fun drawDirect(convId: String, userMsg: Message) {
        val msg = Message(newId(), convId, Role.AI, PAINTER_ID, status = MsgStatus.STREAMING, createdAt = now())
        addMessage(msg)
        try {
            val r = withContext(UsageTag(UsageKinds.IMAGE, convId = convId)) { paintAuto(userMsg.content) }
            updateMessage(convId, msg.id) {
                it.copy(attachments = listOf(r.att), content = r.revisedPrompt?.let { rp -> "按描述画好了。\n\n> $rp" } ?: "按描述画好了。",
                    status = MsgStatus.DONE, modelLabel = r.label)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateMessage(convId, msg.id) { it.copy(status = MsgStatus.STOPPED) } }
            throw e
        } catch (e: Throwable) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = friendlyError(e)) }
        }
    }

    /**
     * 「直接做视频」：不经过 AI，把用户写的话当视频描述，这条消息附上的东西当素材（2026-10-21，照「直接画图」）。
     * 素材怎么用按最常见的猜：只有 1 张图 = 首帧，2 张 = 首尾帧；有视频 / 音频 / 文档时图片都当参考图，视频当参考视频（描述里写「改成」就是编辑、写「延长」就是接长），
     * 音频当参考音频，文档当文档素材。别的用法（比如多张图都当首帧以外的参考）在聊天里跟 AI 说。
     */
    private suspend fun videoDirect(convId: String, userMsg: Message, text: String, paths: List<String>) {
        val msg = Message(newId(), convId, Role.AI, VIDEO_ID, status = MsgStatus.STREAMING, createdAt = now(), modelLabel = "做视频")
        addMessage(msg)
        try {
            // 素材：消息里的附件（按名字）+ 拖进来的电脑文件（按路径，文件夹不算）
            val sources = userMsg.attachments.map { it.name to it.mime.ifBlank { VideoMedia.mimeOf(it.name) } } +
                paths.filter { File(it).isFile }.map { it to VideoMedia.mimeOf(it) }
            val uses = guessVideoUses(sources.map { it.second to it.first })
            val args = buildJsonObject {
                put("prompt", text.ifBlank { "让画面自然地动起来" })
                val media = sources.zip(uses).mapNotNull { (s, use) -> use?.let { it to s.first } }
                if (media.isNotEmpty()) putJsonArray("media") {
                    media.forEach { (use, source) -> add(buildJsonObject { put("use", use); put("source", source) }) }
                }
            }
            val r = withContext(UsageTag(UsageKinds.VIDEO, convId = convId)) { makeVideo(convId, msg.id, args) }
            val ok = r.startsWith("视频做好了（") && "收费），但是" !in r
            val sw = Regex("。(.+?)，要告诉用户").find(r)?.groupValues?.get(1)
            updateMessage(convId, msg.id) {
                if (ok) it.copy(content = "按描述做好了，点开就能播放。" + (sw?.let { s -> "\n\n$s。" } ?: ""), status = MsgStatus.DONE)
                else if (it.attachments.isNotEmpty()) it.copy(content = r.substringBefore("。请如实告诉用户"), status = MsgStatus.DONE)
                else it.copy(status = MsgStatus.ERROR, error = r.removeSuffix("请告诉用户。").removeSuffix("请如实告诉用户。").removeSuffix("请按要求改了再调，或者如实告诉用户。").trim())
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateMessage(convId, msg.id) { it.copy(status = MsgStatus.STOPPED) } }
            throw e
        } catch (e: Throwable) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = friendlyError(e)) }
        }
    }

    /** 「直接做视频」按附件的类型猜用途（mime 到 名字）：见 videoDirect。不认识的类型（比如压缩包）不当素材。 */
    private fun guessVideoUses(items: List<Pair<String, String>>): List<String?> {
        val kinds = items.map { (mime, name) ->
            val ext = name.substringAfterLast('.', "").lowercase()
            when {
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                ext in VideoMedia.docExts -> "doc"
                else -> null
            }
        }
        val images = kinds.count { it == "image" }
        val framesOk = kinds.none { it == "video" || it == "audio" || it == "doc" } && images in 1..2
        var seen = 0
        return kinds.map { k ->
            when (k) {
                "image" -> if (framesOk) (if (seen++ == 0) VideoMediaUse.FIRST else VideoMediaUse.LAST) else VideoMediaUse.IMAGE
                "video" -> VideoMediaUse.VIDEO
                "audio" -> VideoMediaUse.AUDIO
                "doc" -> VideoMediaUse.FILE
                else -> null
            }
        }
    }

    /**
     * 做一段视频（generate_video）：进度写在这一步上（传素材 / 排队中 / 生成中 / 下载中），做好了挂成「AI 做的文件」（点开用系统播放器放，
     * 手机遥控时先取过来，微信里发回去）。按成功生成的秒数记账，做好了只是下载失败的也记。
     * 素材（media）：这个对话里的图 / 视频 / 文件（按名字）、电脑上的文件（路径，读的规矩和 read_document 一样）、公开网址；
     * 设置里选的模型收不了这些素材时自动换能收的（VideoPick.resolve），发之前本地先查一遍（VideoMedia.check）。
     */
    private suspend fun makeVideo(convId: String, msgId: String, args: JsonObject): String {
        val prompt = args.str("prompt").orEmpty().ifBlank { return "参数错误：缺少 prompt" }
        val v = state.settings.videoGen
        if (VideoPick.resolve(state) == null) return "做视频没开，或者没有能用的服务商（要千问AI平台、阿里百炼或 MiniMax 中国版的 Key）。请告诉用户到 设置 → 画图和视频 里看看。"
        val wanted = videoMediaArgs(args["media"]).getOrElse { return "参数错误：${it.message}" }
        val inputs = mutableListOf<VideoInput>()
        for ((use, source) in wanted) inputs += videoInput(convId, msgId, use, source).getOrElse { return it.message.orEmpty() }
        val choice = VideoPick.resolve(state, inputs.map { it.use }.toSet()) ?: return "做视频的服务商不见了"
        val p = state.provider(choice.providerId) ?: return "做视频的服务商不见了"
        val hasVideoIn = inputs.any { it.use == VideoMediaUse.VIDEO }
        // 时长：AI 说了就用；带视频的万相默认「智能时长」（官方建议编辑 / 延长用 -1）；其余用设置里的
        val askedSecs = args.str("duration")?.trim()?.takeIf { it.isNotEmpty() }?.let { s -> if (s.startsWith("-")) -1 else s.filter { it.isDigit() }.toIntOrNull() }
        val duration = askedSecs ?: if (hasVideoIn && choice.model.vendor == VideoPick.DASHSCOPE) -1 else v.duration
        // 比例：带素材时默认按素材自适应
        val ratio = args.str("ratio")?.trim()?.takeIf { it.isNotEmpty() } ?: if (inputs.isNotEmpty()) "adaptive" else v.ratio
        VideoMedia.check(choice.model, inputs, duration)?.let { return "素材不合要求，没提交（不收费）：$it。请按要求改了再调，或者如实告诉用户。" }
        val stepId = newId()
        fun step(persist: Boolean = false, f: (ToolStep) -> ToolStep) =
            updateMessage(convId, msgId, persist) { m -> m.copy(tools = m.tools.map { if (it.id == stepId) f(it) else it }) }
        val what = inputs.joinToString("；") { "${VideoMediaUse.label(it.use)}：${it.name}" }
        updateMessage(convId, msgId, persist = false) {
            it.copy(tools = it.tools + ToolStep("video", (prompt.take(200) + if (what.isEmpty()) "" else "\n素材 · $what").take(600), id = stepId, note = "提交中"))
        }
        val tag = currentCoroutineContext()[UsageTag]
        /** 记账：万相「输入视频 + 输出视频」同一个单价，记一行；MiniMax 输出、输入视频、超出免费张数的图单价不同，分开记。 */
        fun bill(u: VideoGen.Usage, res: String) {
            val sec = com.guixing.jixunying.model.SpeechUnits.SECOND
            if (choice.model.vendor != VideoPick.MINIMAX) {
                recordUsage(p, VideoPick.billingModel(choice.model.id, res), Usage(), UsageKinds.VIDEO, tag, units = u.total, unit = sec)
                return
            }
            recordUsage(p, VideoPick.billingModel(choice.model.id, res), Usage(), UsageKinds.VIDEO, tag, units = u.output, unit = sec)
            if (u.input > 0) recordUsage(p, VideoPick.billingModel(choice.model.id, res, "输入视频"), Usage(), UsageKinds.VIDEO, tag, units = u.input, unit = sec)
            val extra = u.images - VideoPick.freeImages(choice.model.id)
            if (extra > 0) recordUsage(p, VideoPick.billingModel(choice.model.id, res, "图片"), Usage(), UsageKinds.VIDEO, tag, images = extra)
        }
        /** 做好了：记账、挂到这条回答上，返回「几秒 · 档位 · 模型」（带输入视频的写明另算了几秒）。 */
        fun keep(r: VideoGen.Result, extra: String = ""): String {
            bill(r.usage, r.resolution)
            val inPart = if (r.usage.input > 0) "（输入视频 ${VideoMedia.fmt(r.usage.input)} 秒也计费）" else ""
            val note = "${VideoMedia.fmt(r.seconds)} 秒 · ${r.resolution} · ${choice.model.id}$inPart"
            val name = "视频_${System.currentTimeMillis() % 1_000_000}.mp4"
            val att = Attachment(newId(), name, "video/mp4", r.bytes.size.toLong(), AttachmentKind.DOCUMENT, note = note,
                generated = true, savedPath = saveOfficeCopy(name, r.bytes))
            storage.putFile(att, r.bytes, null)
            updateMessage(convId, msgId) { m -> m.copy(attachments = m.attachments + att, tools = m.tools.map { if (it.id == stepId) it.copy(note = note + extra) else it }) }
            return "$note，文件名 $name"
        }
        var taskId: String? = null
        return try {
            val r = videos.generate(p, choice.model.id, prompt, v.resolution, duration, ratio, v.audio, inputs, onTask = { taskId = it }) { s -> step { it.copy(note = s) } }
            val note = keep(r)
            val sw = if (choice.switched.isEmpty()) "" else "。${choice.switched}，要告诉用户"
            "视频做好了（$note）$sw。已经显示给用户，点一下就能播放。简单说一句就行，不用再描述画面。"
        } catch (e: CancellationException) {
            // 用户点了停止：还在排队就取消（不收费）；已经在生成的取消不了，在后台接着等，做好了照样放回这条回答（反正要收钱）
            withContext(NonCancellable) {
                val id = taskId
                val status = if (id == null) null else withTimeoutOrNull(15_000) { videos.cancel(p, choice.model.id, id) }
                when {
                    id == null -> step(true) { it.copy(ok = false, note = "停了（还没提交，不收费）") }
                    status == "CANCELED" || status == "FAILED" -> step(true) { it.copy(ok = false, note = "停了：任务还在排队，已经取消，不收费") }
                    else -> {
                        step(true) { it.copy(note = "停了，但${choice.providerName}那边已经开始生成，取消不了（只能取消排队中的），做完照样按秒收费；做好了会放回这条回答下面") }
                        val res = VideoPick.resolutionFor(choice.model, v.resolution)
                        val secs = if (duration < 0) -1 else VideoPick.durationFor(choice.model, duration)
                        val images = inputs.count { it.use in setOf(VideoMediaUse.FIRST, VideoMediaUse.LAST, VideoMediaUse.IMAGE) }
                        scope.launch(tag ?: kotlin.coroutines.EmptyCoroutineContext) {
                            try {
                                keep(videos.await(p, choice.model.id, id, res, secs, images), "（点停止之后做完的）")
                            } catch (e2: VideoGen.DownloadFailed) {
                                bill(e2.usage, e2.resolution)
                                step(true) { it.copy(ok = false, note = "停了之后做完了（已按 ${VideoMedia.fmt(e2.seconds)} 秒收费），但是${e2.message}") }
                            } catch (e2: Throwable) {
                                step(true) { it.copy(ok = false, note = "停了之后没做成（不收费）：${friendlyError(e2)}") }
                            }
                        }
                    }
                }
            }
            throw e
        } catch (e: VideoGen.DownloadFailed) {
            bill(e.usage, e.resolution)
            step(true) { it.copy(ok = false, note = e.message.orEmpty()) }
            "视频做好了（已按 ${VideoMedia.fmt(e.seconds)} 秒收费），但是${e.message}。请如实告诉用户。"
        } catch (e: Throwable) {
            step(true) { it.copy(ok = false, note = friendlyError(e)) }
            "做视频失败：${friendlyError(e)}。请告诉用户。"
        }
    }

    /**
     * generate_video 的 media 参数：[{"use": "first_frame", "source": "图片_123.png"}, …]。
     * 有的模型会把数组写成字符串，也认；use 也认中文（首帧、尾帧、参考图、参考视频、参考音频、文档、网页）。
     */
    private fun videoMediaArgs(raw: JsonElement?): Result<List<Pair<String, String>>> = runCatching {
        if (raw == null || raw is JsonNull) return@runCatching emptyList()
        val arr = when (raw) {
            is JsonArray -> raw
            is JsonPrimitive -> raw.content.trim().takeIf { it.isNotEmpty() }?.let { Json.parse(it) as? JsonArray ?: error("media 要是数组") } ?: return@runCatching emptyList()
            else -> error("media 要是数组")
        }
        arr.map { el ->
            val o = el as? JsonObject ?: error("media 里每一项要写成 {\"use\": 用途, \"source\": 来源}")
            val rawUse = (o.str("use") ?: o.str("type") ?: o.str("role")).orEmpty().trim()
            val use = VideoMediaUse.all.firstOrNull { it.equals(rawUse, ignoreCase = true) || VideoMediaUse.label(it) == rawUse }
                ?: error("不认识的用途「$rawUse」，只能是 ${VideoMediaUse.all.joinToString("、")}")
            val source = (o.str("source") ?: o.str("url") ?: o.str("name") ?: o.str("path")).orEmpty().trim()
            if (source.isEmpty()) error("「${VideoMediaUse.label(use)}」没给来源（对话里的文件名、电脑上的路径或网址）")
            use to source
        }
    }

    /**
     * 找一份素材：http(s) 网址原样交给服务端；对话里的附件按名字找（从新到旧，图片_xxx.png、视频_xxx.mp4、用户贴进来的）；
     * 再不然当成电脑上的路径（读的规矩和 read_document 一样：微信、手机发起的不许读收录范围外的）。
     * 失败时 Result 里的 message 就是回给模型的话。
     */
    private suspend fun videoInput(convId: String, msgId: String, use: String, source: String): Result<VideoInput> {
        fun fail(s: String) = Result.failure<VideoInput>(IllegalArgumentException(s))
        if (source.startsWith("http://", true) || source.startsWith("https://", true))
            return Result.success(VideoInput(use, source.substringAfterLast('/').substringBefore('?').ifEmpty { source }, VideoMedia.mimeOf(source.substringBefore('?')), url = source))
        if (use == VideoMediaUse.LINK) return fail("网页素材要给公开网址（http / https 开头），「$source」不是。")
        val name = source.substringAfterLast('/').substringAfterLast('\\')
        val att = messages(convId).asReversed().asSequence().flatMap { it.attachments.asSequence() }
            .firstOrNull { it.name.equals(source, true) || it.name.equals(name, true) }
        if (att != null) {
            val bytes = withContext(Dispatchers.IO) { storage.fileBytes(att.id) } ?: return fail("对话里的《${att.name}》文件不见了，没法当素材。")
            val mime = att.mime.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: VideoMedia.mimeOf(att.name)
            return Result.success(VideoInput(use, att.name, mime, bytes, seconds = VideoMedia.durationOf(att.name, mime, bytes)))
        }
        val f = File(source)
        if (!f.isAbsolute || !f.isFile) {
            val names = messages(convId).flatMap { it.attachments }.map { it.name }.distinct().takeLast(20)
            return fail("找不到素材「$source」：不是这个对话里的文件名，也不是电脑上存在的完整路径。" +
                (if (names.isEmpty()) "这个对话里还没有图片、视频或文件。" else "这个对话里有：${names.joinToString("、")}。") +
                "电脑上的文件可以先用 find_files / list_folder 找到完整路径。")
        }
        askToRead(convId, msgId, f)?.let { return fail(it) }
        if (f.length() > 100L * 1024 * 1024) return fail("${f.name} 太大（${f.length() / 1024 / 1024} MB），素材单个最大 100 MB。")
        val bytes = withContext(Dispatchers.IO) { f.readBytes() }
        val mime = VideoMedia.mimeOf(f.name)
        updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("doc", "读了素材《${f.name}》")) }
        return Result.success(VideoInput(use, f.name, mime, bytes, seconds = VideoMedia.durationOf(f.name, mime, bytes)))
    }

    private suspend fun paint(target: Pair<ProviderConfig, String>, prompt: String, size: String? = null): Pair<Attachment, String?> {
        // 画成一张记一张（成员在聊天里画的记在成员账上；测试画的算测试）。画好了只是下载失败的也已经扣钱，照样记
        val tag = currentCoroutineContext()[UsageTag]
        fun bill() = recordUsage(target.first, target.second, Usage(), if (tag?.kind == UsageKinds.TEST) UsageKinds.TEST else UsageKinds.IMAGE, tag, images = 1)
        val r = try {
            images.generate(target.first, target.second, prompt, size ?: state.settings.imageGen.size)
        } catch (e: ImageDownloadFailed) {
            bill(); throw e
        }
        bill()
        val ext = r.mime.substringAfter('/').replace("jpeg", "jpg")
        val att = Attachment(newId(), "图片_${System.currentTimeMillis() % 1_000_000}.$ext", r.mime, r.bytes.size.toLong(), AttachmentKind.GENERATED_IMAGE, note = prompt.take(200))
        storage.putFile(att, r.bytes, null)
        return att to r.revisedPrompt
    }

    /** 让一位成员发言，返回它的消息（失败返回 null）。 */
    private suspend fun reply(convId: String, member: Member, cutoffId: String?, independent: Boolean, calledBy: String?): Message? {
        val conv = state.conversation(convId) ?: return null
        val provider = state.provider(member.providerId)
        val msg = Message(newId(), convId, Role.AI, member.id, status = MsgStatus.STREAMING, createdAt = now(),
            modelLabel = Prompts.modelLabel(state, member))
        addMessage(msg)

        if (provider == null || member.modelId.isBlank()) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = "「${member.name}」还没配置模型：到 设置→AI 成员 里给它选服务商和模型") }
            return null
        }
        if (!provider.enabled) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = "服务商「${provider.name}」已停用：到 设置→模型服务 把它的开关打开，「${member.name}」才能回答") }
            return null
        }
        if (provider.apiKey.isBlank() && !Presets.isChatGpt(provider) && !provider.baseUrl.contains("127.0.0.1") && !provider.baseUrl.contains("localhost")) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = "服务商「${provider.name}」还没填 API Key：到 设置→模型服务 填写") }
            return null
        }
        val model = provider.models.firstOrNull { it.id == member.modelId }
            ?: ModelInfo(member.modelId, vision = Presets.guessVision(member.modelId))
        // 快速 / 深度：按这家的参数切换思考；关不掉、开不了的不传，回答上也不标
        val thinking = if (llm.thinkingDropped(provider, model.id)) null else Thinking.params(Thinking.rule(provider, model.id), member.thinking)
        if (thinking != null) updateMessage(convId, msg.id, persist = false) { it.copy(modelLabel = it.modelLabel + "· " + Thinking.label(member.thinking)) }
        val hasImageModel = ImagePick.resolve(state).isNotEmpty()
        val hasVideo = VideoPick.resolve(state) != null
        val wantSearch = conv.webSearch
        // 用户这句话里贴了网址（比如抖音、小红书的分享链接）：没开「联网」也给读网页的工具（2026-10-23）
        val linkInAsk = snapshot(convId).lastOrNull { it.role == Role.USER }?.content?.contains("http") == true
        val toolsOk = model.tools && !llm.toolsDropped(provider, model.id)
        // 平台有官方内置搜索就用内置的（智谱、千问），否则用我们自己的搜索工具。
        // Kimi 的内置 $web_search 2026-10-20 下线：Kimi 成员也用我们的 web_search 工具，只是搜索走 Kimi 官方搜索接口（同一个 Key）
        val native = if (wantSearch && toolsOk) nativeSearchOf(provider, model.id) else NativeSearch.NONE
        val kimiSearch = provider.takeIf { wantSearch && state.settings.search.mode == com.guixing.jixunying.model.SearchMode.AUTO && isKimi(it) }
        val fnSearch = wantSearch && native == NativeSearch.NONE
        val memOn = state.settings.memory.enabled
        val docsOn = docsUsable()
        val officeOn = state.settings.office.enabled
        // 电脑上：任意文件 / 文件夹都能读（收录范围外的先问用户；微信、手机发起的不许）
        val filesOn = !isPhone
        val useTools = toolsOk && (wantSearch || hasImageModel || hasVideo || memOn || docsOn || officeOn || filesOn)

        try {
            val working = mutableListOf<JsonObject>()
            val sys = Prompts.system(state, conv, member, canSearch = wantSearch, canDraw = hasImageModel && useTools,
                canSeeImages = model.vision, independentRound = independent, calledBy = calledBy,
                nativeSearch = native != NativeSearch.NONE, hasImageModel = hasImageModel,
                memories = if (memOn) Recorder.forPrompt(state.memories) else emptyList(), canRemember = memOn && useTools,
                docCount = if (docsOn && useTools) docs.count else 0, deviceLabel = if (isPhone) "这台手机" else "这台电脑",
                canMakeFiles = officeOn && useTools, canReadFiles = filesOn && useTools, canVideo = hasVideo && useTools)
            working += buildJsonObject { put("role", "system"); put("content", sys) }
            working += buildHistory(convId, member, cutoffId, excludeId = msg.id, vision = model.vision)
            remindInstructions(working)

            val sources = mutableListOf<SearchSource>()
            if (wantSearch && !useTools) {
                // 模型不会调工具：替它搜一次
                val q = lastUserText(convId, cutoffId).take(120)
                if (q.isNotBlank()) {
                    val step = runSearch(q, sources, kimiSearch)
                    updateMessage(convId, msg.id, persist = false) { it.copy(tools = it.tools + step.first) }
                    if (step.second.isNotEmpty()) working.add(1, buildJsonObject { put("role", "system"); put("content", Prompts.searchDigest(q, step.second)) })
                }
            }

            // MCP 外部工具（2026-10-18）：电脑上对话栏也能用，和 Code 栏同一套
            val mcp = if (useTools && filesOn) runCatching { mcpTools() }.getOrDefault(emptyList()) else emptyList()
            val tools = if (useTools) JsonArray(toolDefs(fnSearch, fetch = wantSearch || linkInAsk,
                listen = (wantSearch || linkInAsk) && pageBrowser != null && speechTarget("", "", tts = false) != null, draw = hasImageModel, native = native, memory = memOn, docs = docsOn, office = officeOn, files = filesOn, video = hasVideo) +
                mcp.map { (n, _, t) -> buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") { put("name", n); put("description", ("（外部工具 ${t.name}）" + t.description).take(1000)); put("parameters", t.inputSchema) }
                } }) else null
            val extra = if (native == NativeSearch.QWEN) buildJsonObject {
                put("enable_search", true)
                putJsonObject("search_options") {
                    put("enable_source", true); put("enable_citation", true); put("citation_format", "[<number>]")
                }
            } else null
            val nativeLabel = when (native) {
                NativeSearch.ZHIPU -> "智谱官方联网搜索"
                NativeSearch.QWEN -> "千问官方联网搜索"
                NativeSearch.NONE -> ""
            }
            val onSources: (List<SearchSource>) -> Unit = { found ->
                if (found.isNotEmpty() && sources.isEmpty()) {
                    sources += found
                    updateMessage(convId, msg.id, persist = false) { it.copy(tools = it.tools + ToolStep("search", nativeLabel, found)) }
                }
            }
            var usage = Usage()
            var rounds = 0
            var toolUses = 0
            // 这一段里调模型、画图都记在这位成员的账上
            withContext(UsageTag(UsageKinds.CHAT, member.id, convId)) { while (true) {
                rounds++
                val before = snapshot(convId).firstOrNull { it.id == msg.id }?.content?.length ?: 0
                val r = llm.chat(provider, model.id, working, member.temperature, tools, extra, onSources, thinking) { c, rs -> appendDelta(convId, msg.id, c, rs) }
                usage = Usage(usage.prompt + r.usage.prompt, usage.completion + r.usage.completion, usage.cached + r.usage.cached, usage.millis + r.usage.millis)
                if (r.toolCalls.isEmpty() || rounds >= MAX_TOOL_USES) break
                // 这一轮说的是「我去查一下」「这页打不开，换个词再搜」之类的过程话，挪进推理过程，正文只留最后的回答
                updateMessage(convId, msg.id, persist = false) { m ->
                    val cut = before.coerceIn(0, m.content.length)
                    val said = m.content.substring(cut).trim()
                    if (said.isEmpty()) m else m.copy(content = m.content.substring(0, cut), reasoning = (m.reasoning + "\n\n" + said).trim())
                }
                working += buildJsonObject {
                    put("role", "assistant")
                    put("content", r.content)
                    if (r.reasoning.isNotEmpty() && provider.presetId !in setOf("openai", "anthropic", "gemini", "openrouter")) put("reasoning_content", r.reasoning)
                    putJsonArray("tool_calls") {
                        r.toolCalls.forEach { tc ->
                            add(buildJsonObject {
                                put("id", tc.id); put("type", tc.type)
                                putJsonObject("function") { put("name", tc.name); put("arguments", tc.arguments.ifBlank { "{}" }) }
                            })
                        }
                    }
                }
                val seen = mutableListOf<Pair<String, String>>()
                for (tc in r.toolCalls) {
                    // 一次回答最多用 MAX_TOOL_USES 次工具，超过就让它根据已有结果直接回答
                    val result = if (toolUses >= MAX_TOOL_USES)
                        "这次回答已经用了 $MAX_TOOL_USES 次工具，不能再用了。请直接根据前面的结果回答；没做完的如实说。"
                    else { toolUses++; runTool(convId, msg.id, tc, sources, kimiSearch, if (model.vision) seen else null) }
                    working += buildJsonObject {
                        put("role", "tool"); put("tool_call_id", tc.id)
                        put("content", result)
                    }
                }
                // read_document 读到的图片：工具结果里放不了图片，补一条 user 消息带上（和 Code 栏一样）
                if (seen.isNotEmpty()) working += buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "（这是刚才读到的图片：" + seen.joinToString("、") { it.first } + "）") })
                        seen.forEach { (_, url) -> add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", url) } }) }
                    }
                }
            } }
            // 模型不认切换思考的参数：这次去掉参数答完了，标签恢复原样，告诉用户一声（之后这个模型都按默认）
            val thinkingRefused = thinking != null && llm.thinkingDropped(provider, model.id)
            if (thinkingRefused) emit(Event.Notice("「${member.name}」用的 ${model.id} 不认「${Thinking.label(member.thinking)}」的参数，这次按它默认的方式回答了"))
            val done = updateMessage(convId, msg.id) {
                it.copy(content = cleanReply(it.content, member), status = MsgStatus.DONE, usage = usage,
                    modelLabel = if (thinkingRefused) Prompts.modelLabel(state, member) else it.modelLabel)
            }
            return done
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateMessage(convId, msg.id) { it.copy(status = MsgStatus.STOPPED) } }
            throw e
        } catch (e: Throwable) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = friendlyError(e, provider)) }
            return null
        }
    }

    private fun cleanReply(text: String, me: Member): String {
        var t = text.trim()
        // 模型有时会学聊天记录的格式，在开头写「【自己名字】」
        listOf("【${me.name}】", "${me.name}：", "${me.name}:").forEach { if (t.startsWith(it)) t = t.removePrefix(it).trimStart() }
        return t
    }

    private enum class NativeSearch { NONE, ZHIPU, QWEN }

    private fun isKimi(p: ProviderConfig): Boolean {
        val u = p.baseUrl.lowercase()
        return p.presetId.startsWith("moonshot") || "moonshot" in u || "api.kimi" in u
    }

    private fun nativeSearchOf(p: ProviderConfig, model: String): NativeSearch {
        if (state.settings.search.mode != com.guixing.jixunying.model.SearchMode.AUTO) return NativeSearch.NONE
        if (llm.nativeSearchDropped(p, model)) return NativeSearch.NONE
        val u = p.baseUrl.lowercase()
        return when {
            p.presetId == "zhipu" || p.presetId == "zai" || "bigmodel.cn" in u || "api.z.ai" in u -> NativeSearch.ZHIPU
            p.presetId.startsWith("dashscope") || p.presetId == "qianwen" || "dashscope" in u || "qianwenaiapi" in u || "maas.aliyuncs" in u -> NativeSearch.QWEN
            else -> NativeSearch.NONE
        }
    }

    private fun toolDefs(search: Boolean, fetch: Boolean, draw: Boolean, native: NativeSearch, memory: Boolean = false, docs: Boolean = false,
                         office: Boolean = false, files: Boolean = false, video: Boolean = false, listen: Boolean = false): JsonArray = buildJsonArray {
        when (native) {
            NativeSearch.ZHIPU -> add(buildJsonObject {
                put("type", "web_search")
                putJsonObject("web_search") {
                    put("enable", true); put("search_engine", "search_std"); put("search_result", true)
                }
            })
            else -> Unit
        }
        fun fn(name: String, desc: String, props: JsonObject, required: List<String>) = add(buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name); put("description", desc)
                putJsonObject("parameters") {
                    put("type", "object"); put("properties", props)
                    putJsonArray("required") { required.forEach { add(it) } }
                }
            }
        })
        fun prop(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
        if (search) {
            fn("web_search", "联网搜索。遇到时效性信息、没把握的事实时使用。返回带编号的搜索结果（标题、链接、摘要）。",
                buildJsonObject { put("query", prop("搜索关键词，简洁具体，中文问题用中文搜")) }, listOf("query"))
        }
        if (fetch) {
            fn("fetch_url", "打开一个网页，读取正文文字。搜索摘要不够详细、或用户给了链接时使用（抖音、小红书、快手、微博、B站的作品链接和分享短链接也能读：文案、作者、点赞、评论、图片）。",
                buildJsonObject { put("url", prop("完整网址，http 或 https 开头")) }, listOf("url"))
        }
        if (listen) {
            fn("listen_video", "听平台视频里说了什么，转成文字（抖音、小红书等作品链接，或者 fetch_url 结果里的视频地址）。用户想知道视频讲了什么、页面文字不够时用；按秒收费，最长听 10 分钟。",
                buildJsonObject { put("url", prop("作品链接或视频地址")) }, listOf("url"))
        }
        if (draw) {
            fn("generate_image", "根据文字描述画一张图，画好后会直接显示给用户。",
                buildJsonObject {
                    put("prompt", prop("画面描述：主体、风格、构图、光线、色彩，越具体越好"))
                    put("size", prop("可选，图片尺寸，如 1024x1024、1024x1792"))
                }, listOf("prompt"))
        }
        if (video) {
            val v = state.settings.videoGen
            val dashscope = VideoPick.candidates(state.providers).any { VideoPick.vendorOf(it) == VideoPick.DASHSCOPE }
            fn("generate_video", "做一段短视频（按秒收费，比画图贵；要一到几分钟），做好后会显示给用户、点开就能播放。只在用户明确要视频时用。" +
                "可以只用文字描述，也可以带素材（media）：用图片当首帧 / 首尾帧（图生视频）；用图、视频、音频当参考（参考生视频，提示词里用「图1」「视频1」「音频1」按顺序指代）；" +
                "给一段视频 + 提示词写明「编辑视频 / 去掉 / 替换 / 改成……」就是视频编辑，写明「向后延长 / 续写……」就是视频延长" +
                (if (dashscope) "；给一个文档（docx、pdf、pptx、xlsx、txt、md 等）或一个公开网页，按里面的内容做视频" else "") +
                "。首帧 / 尾帧不能和参考类混用。带输入视频的按「输入秒数 + 输出秒数」收费，比直接生成贵。程序会按素材自动挑能用的模型。",
                buildJsonObject {
                    put("prompt", prop("视频内容描述：主体、动作、场景、镜头运动、光线、风格、声音，越具体越好；编辑、延长时写清要怎么改、往哪边延长"))
                    put("duration", prop("可选，要做的秒数。用户没说就不填（默认 ${v.duration} 秒；带视频素材时默认智能时长，自动配合原视频）"))
                    put("ratio", prop("可选，画面比例：16:9、9:16、1:1、4:3、3:4、21:9，带素材时还可以 adaptive（跟素材一样）。用户没说就不填"))
                    put("media", buildJsonObject {
                        put("type", "array")
                        put("description", "可选，素材，按顺序。来源可以是这个对话里的文件名（AI 画的图片_xxx.png、做的视频_xxx.mp4、用户贴进来的文件）、" +
                            "电脑上文件的完整路径（不知道在哪就先用 find_files / list_folder 找），或者 http(s) 网址")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                put("use", buildJsonObject {
                                    put("type", "string")
                                    put("description", "用途：first_frame 首帧、last_frame 尾帧、reference_image 参考图、reference_video 参考视频（也用于编辑、延长）、" +
                                        "reference_audio 参考音频" + if (dashscope) "、file 文档、link 网页" else "")
                                    putJsonArray("enum") { (if (dashscope) VideoMediaUse.all else VideoMediaUse.all - setOf(VideoMediaUse.FILE, VideoMediaUse.LINK)).forEach { add(it) } }
                                })
                                put("source", prop("文件名、完整路径或网址"))
                            }
                            putJsonArray("required") { add("use"); add("source") }
                        }
                    })
                }, listOf("prompt"))
        }
        if (memory) {
            fn("remember", "把关于用户的、以后的对话也用得上的信息记进长期记忆（身份、职业、所在地、偏好、对回答方式的要求、长期项目）。" +
                "用户说「记住……」时一定要调用。不要记一次性的问题，不要记密码、Key、证件号。",
                buildJsonObject {
                    put("text", prop("要记的内容，一句完整的话，以用户为主语，例如「希望回答先给结论」"))
                    put("kind", prop("类别：关于我 / 偏好 / 要求 / 事实"))
                }, listOf("text"))
            fn("search_history", "搜索以前所有对话的聊天记录。用户提到「上次」「之前说过」「以前聊的」，或者需要回忆更早的原话时使用。返回匹配的片段（对话标题、日期、谁说的）。",
                buildJsonObject { put("query", prop("关键词，越具体越好")) }, listOf("query"))
        }
        if (files) {
            // 2026-10-17 用户定「和 Code 一样放开」：电脑上任意文件 / 文件夹都能读，收录范围外的程序先问用户
            fn("list_folder", "列出电脑上一个文件夹里有哪些文件和子文件夹（任意完整路径都行，比如用户拖进来的文件夹）。",
                buildJsonObject { put("path", prop("文件夹的完整路径")) }, listOf("path"))
            // 2026-10-17 对话栏 = Chat + Cowork：和 Code 栏同一套找文件、搜内容、新建 / 修改文件
            fn("find_files", "在一个文件夹底下按文件名找文件，比如 *.xlsx、**/合同*.docx、*.md；返回完整路径（新改的在前）。",
                buildJsonObject {
                    put("pattern", prop("文件名的通配写法：* 匹配一段名字，** 匹配任意几层目录"))
                    put("path", prop("在哪个文件夹底下找（完整路径）"))
                }, listOf("pattern", "path"))
            fn("search_in_files", "在一个文件夹底下按内容找：哪些文本文件里有这段文字，返回文件、行号和那一行。",
                buildJsonObject {
                    put("pattern", prop("要找的文字或正则表达式"))
                    put("path", prop("在哪个文件夹底下找（完整路径）"))
                }, listOf("pattern", "path"))
            fn("write_file", "在电脑上新建一个文本文件，或者整个重写一个已有的文本文件（Markdown、CSV、代码、配置、txt 等；Word / Excel / PPT 文件用专门做文件的工具）。改之前程序会备份，用户能一键撤销。",
                buildJsonObject {
                    put("path", prop("文件的完整路径"))
                    put("content", prop("文件的全部内容"))
                }, listOf("path", "content"))
            // 2026-10-17 用户截图：对话栏 AI 说「没有执行代码的工具」「没有删除文件」——Cowork 能跑代码，我们要比它更开放
            fn("run_command", "在电脑上跑一条命令（PowerShell；node、python、git 这些装了就能用），返回退出码和输出。用来跑代码、跑测试、处理文件。" +
                "开起来不会结束的（开发服务器之类）把 background 设成 true，之后用 read_output 看输出、stop_process 停掉。",
                buildJsonObject {
                    put("command", prop("要跑的命令"))
                    put("cwd", prop("可选，在哪个文件夹里跑（完整路径）；不写就是这个对话的第一个文件夹，没有就是用户文件夹"))
                    put("background", prop("可选，true = 在后台跑"))
                }, listOf("command"))
            fn("read_output", "看一个后台命令到现在的输出（最后一截），以及它还在不在跑。",
                buildJsonObject { put("id", prop("后台命令的编号")) }, listOf("id"))
            fn("update_todos", Todos.DESC, buildJsonObject { put("items", prop("整张清单，一行一项，开头 [x] / [>] / [ ]")) }, listOf("items"))
            fn("preview", "打开一个网页截一张图给你看（比如后台开的开发服务器），检查页面对不对。看不了图的话给你网页上的文字。",
                buildJsonObject { put("url", prop("网址")); put("width", prop("可选，浏览器宽度，默认 1280")) }, listOf("url"))
            fn("stop_process", "停掉一个后台命令。", buildJsonObject { put("id", prop("后台命令的编号")) }, listOf("id"))
            fn("delete_file", "删掉电脑上的一个文件或文件夹。删之前程序会整个备份，用户能一键恢复。",
                buildJsonObject { put("path", prop("要删的文件或文件夹的完整路径")) }, listOf("path"))
            fn("edit_file", "把电脑上一个已有文本文件里的一段文字换成另一段。old_text 在文件里必须正好出现一次。改之前程序会备份，用户能一键撤销。",
                buildJsonObject {
                    put("path", prop("文件的完整路径"))
                    put("old_text", prop("要换掉的原文（要唯一，写长一点，包括换行）"))
                    put("new_text", prop("换成什么（删掉就填空）"))
                }, listOf("path", "old_text", "new_text"))
        }
        if (docs || files) {
            if (docs) fn("search_documents", "搜索用户这台设备上的文档（PDF、Word、Excel、PPT、文本等），按文件名和内容匹配。" +
                "用户问到他自己的文件、资料、合同、报告、表格、笔记时用。关键词留空返回最近修改的文档。",
                buildJsonObject { put("query", prop("关键词：文件名里的词或内容里的词，可以留空")) }, emptyList())
            fn("read_document", (if (files) "读电脑上一个文件的文字内容（PDF、Word、Excel、PPT、代码、文本等，任意完整路径都行）。" else "读一个文档的文字内容。path 用 search_documents 返回的完整路径。") +
                "一次最多返回约 1 万字，没读完会提示 start 接着读。",
                buildJsonObject {
                    put("path", prop("文档的完整路径"))
                    put("start", prop("可选，从第几个字开始读，默认 0"))
                }, listOf("path"))
        }
        if (office) {
            fn("create_word", "做一个 Word 文件（.docx）交给用户，做好会显示在你的回答下面，点开就用 Word / WPS 打开。" +
                "正文用 Markdown 写：# 标题、## 小标题、- 列表、1. 编号、| 表格 |、**加粗**、> 引用；单独一行写 [分页] 就换页。" +
                "字体、字号、行距、页码、目录这些排版程序按用户的设置做，你只管把内容写完整、写好。",
                buildJsonObject {
                    put("filename", prop("文件名，不用写 .docx，比如「第三季度项目验收报告」"))
                    put("content", prop("正文（Markdown）"))
                    put("title", prop("可选，文档标题（第一页最上面居中）；不写就用正文开头的一级标题"))
                    put("style", prop("可选，排版样式：通用 / 正式 / 公文。用户没说就不填，按用户的设置"))
                    put("header", prop("可选，页眉文字，用户要求时才填"))
                    put("landscape", prop("可选，true = 横向（表格列很多时用）"))
                    put("toc", prop("可选，true / false：要不要目录；不填按设置（标题多时自动加）"))
                }, listOf("filename", "content"))
            fn("create_excel", "做一个 Excel 表格文件（.xlsx）交给用户，做好会显示在你的回答下面，点开就用 Excel / WPS 打开。" +
                "内容用 Markdown 表格写：每个「## 名字」下面的表格是一个工作表（名字就是工作表名）；表格上面一行字是表名，表格下面的字是备注。" +
                "数字直接写：12000、12,000、12.5%、¥1200、2026-10-10 会自动变成数字、百分比、金额、日期，能直接算；" +
                "要公式就写 =SUM(B2:B5) 这样，行号按表格算（表头是第 1 行，第一行数据是第 2 行）。表头样式、冻结、筛选、列宽程序自动做。",
                buildJsonObject {
                    put("filename", prop("文件名，不用写 .xlsx，比如「2026年销售汇总」"))
                    put("content", prop("表格内容（Markdown，可以有多个 ## 工作表）"))
                    put("total_row", prop("可选，true = 每张表最后加「合计」行（数字列自动求和）"))
                }, listOf("filename", "content"))
            fn("create_ppt", "做一个 PPT 演示文稿（.pptx）交给用户，做好会显示在你的回答下面，点开就用 PowerPoint / WPS 打开。" +
                "内容用 Markdown 写：开头「# 标题」是封面（下面一行字是副标题，比如部门和姓名），每个「## 」是一页；页里写要点（- 列表，最多两级）、编号、| 表格 |、> 引用；" +
                "「### 」是页里的小标题；只写「## 第一部分：xxx」不写内容就是章节页；最后一页可以只写「## 谢谢」。" +
                "每页写一行「备注：……」放讲稿（演讲时看的话），不会显示在幻灯片上。每页要点精炼（一页 3～6 条，每条一句话），配色、版式、字号、拆页程序自动做。",
                buildJsonObject {
                    put("filename", prop("文件名，不用写 .pptx，比如「第三季度工作汇报」"))
                    put("content", prop("内容（Markdown）"))
                    put("title", prop("可选，封面标题；不写就用开头的一级标题"))
                    put("subtitle", prop("可选，封面副标题"))
                    put("theme", prop("可选，配色：商务蓝 / 清新绿 / 活力橙 / 科技深色 / 简约黑白。用户没说就不填，按用户的设置"))
                }, listOf("filename", "content"))
        }
    }

    // ———————————————— 做 Word / Excel（1.5.0） ————————————————

    class OfficeFile(val att: Attachment, val bytes: ByteArray)

    private fun JsonObject.flag(key: String): Boolean? = when (str(key)?.trim()?.lowercase()) {
        "true", "1", "yes", "是", "要" -> true
        "false", "0", "no", "否", "不要" -> false
        else -> null
    }

    /** 文件名：去掉不能用的字符，补扩展名；设置里要日期就加上。 */
    private fun officeName(raw: String, ext: String, withDate: Boolean): String {
        var base = OfficeWriter.plain(raw).replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ").trim().trimEnd('.')
        if (base.lowercase().endsWith(".$ext")) base = base.dropLast(ext.length + 1).trim()
        base = base.take(60).ifEmpty { if (ext == "xlsx") "表格" else "文档" }
        if (withDate) base += "-" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        return "$base.$ext"
    }

    /** 另存一份带真名字的副本（点开就用 Word / WPS 打开）。电脑默认「文档\AI集训营」；手机存应用目录。重名加 (2)。 */
    private fun saveOfficeCopy(name: String, bytes: ByteArray): String = runCatching {
        val s = state.settings.office
        val dir = if (!isPhone && s.autoSave) (s.saveFolder.takeIf { it.isNotBlank() }?.let { File(it) } ?: defaultSaveFolder() ?: File(storage.root, "made"))
            else File(storage.root, "made")
        dir.mkdirs()
        var f = File(dir, name)
        var n = 2
        while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + "($n)." + name.substringAfterLast('.')); n++ }
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrDefault("")

    /** 按 AI 给的参数（create_word / create_excel / create_ppt，ext = docx / xlsx / pptx）做文件，存成附件。 */
    fun makeOfficeFile(ext: String, args: JsonObject): OfficeFile {
        val (name, made) = buildOffice(ext, args)
        val mime = when (ext) {
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        }
        val att = Attachment(newId(), name, mime, made.bytes.size.toLong(), AttachmentKind.DOCUMENT, textChars = made.text.length,
            note = made.summary, generated = true, savedPath = saveOfficeCopy(name, made.bytes))
        storage.putFile(att, made.bytes, made.text)
        return OfficeFile(att, made.bytes)
    }

    /** 做文件，返回（文件名, 文件）。 */
    private fun buildOffice(ext: String, args: JsonObject): Pair<String, OfficeWriter.Made> {
        val s = state.settings.office
        val content = args.str("content").orEmpty()
        val made = when (ext) {
            "xlsx" -> OfficeWriter.excel(content, s, totalRow = args.flag("total_row") == true)
            "pptx" -> PptWriter.ppt(content, s, PptWriter.PptOptions(
                title = args.str("title").orEmpty(),
                subtitle = args.str("subtitle").orEmpty(),
                theme = PptWriter.themeId(args.str("theme").orEmpty()),
            ))
            else -> OfficeWriter.word(content, s, OfficeWriter.WordOptions(
                title = args.str("title").orEmpty(),
                style = when (args.str("style")?.trim()?.lowercase()) {
                    "正式", "formal" -> "formal"
                    "公文", "红头", "红头文件", "official" -> "official"
                    "通用", "general" -> "general"
                    else -> ""
                },
                header = args.str("header").orEmpty(),
                landscape = args.flag("landscape") == true,
                toc = args.flag("toc"),
            ))
        }
        val firstHeading = Regex("""(?m)^#{1,6}\s+(.+)$""").find(content)?.groupValues?.get(1).orEmpty()
        val name = officeName(args.str("filename").orEmpty().ifBlank { args.str("title").orEmpty().ifBlank { firstHeading } }, ext, s.dateInName)
        return name to made
    }

    /** 「存成 Word」「表格存成 Excel」（像豆包的导出）：拿一条回答的正文做文件，用过的搜索来源附在 Word 最后。 */
    private fun exportMessage(c: Command.ExportMessage): CommandResult {
        val m = snapshot(c.convId).firstOrNull { it.id == c.messageId } ?: return CommandResult(false, "找不到这条消息")
        val body = stripThink(m.content)
        if (body.isBlank()) return CommandResult(false, "这条回答没有正文")
        val excel = c.format == "xlsx"
        val who = state.member(m.senderId)?.name.orEmpty()
        val convTitle = state.conversation(c.convId)?.title.orEmpty()
        val sources = m.tools.filter { it.kind == "search" }.flatMap { it.sources }.distinctBy { it.url }
        val content = if (excel || sources.isEmpty()) body
            else body + "\n\n## 参考来源\n\n" + sources.mapIndexed { i, x -> "${i + 1}. [${x.title.ifBlank { x.url }}](${x.url})" }.joinToString("\n")
        val args = buildJsonObject {
            put("filename", listOf(convTitle, who).filter { it.isNotBlank() }.joinToString("-"))
            put("content", content)
            if (excel) put("total_row", "false")
        }
        return try {
            val (name, made) = buildOffice(if (excel) "xlsx" else "docx", args)
            CommandResult(message = name, data = AppJson.encodeToString(com.guixing.jixunying.model.ExportedFile.serializer(),
                com.guixing.jixunying.model.ExportedFile(name, Base64.getEncoder().encodeToString(made.bytes))))
        } catch (e: Throwable) {
            CommandResult(false, if (excel) "这条回答里没有能存成 Excel 的表格" else "没做成：${e.message}")
        }
    }

    /** 语音：把文字读出来（docs/向量和语音设计说明.md）。结果 data 是 SpeechAudio 的 JSON（一段或多段 wav 的 base64）。 */
    private suspend fun speak(c: Command.Speak): CommandResult {
        val text = c.text.trim()
        if (text.isEmpty()) return CommandResult(false, "没有可读的文字")
        val t = speechTarget(c.providerId, c.modelId, tts = true)
            ?: return CommandResult(false, if (c.providerId.isNotBlank()) "没有这家服务商，或者它不支持读出来" else Speech.NO_TTS + "（去 设置 → 语音 里加一家的 Key）")
        // 设置里的音色是给设置里选的那一家挑的；那家用不了、自动换到别家时，别把小米的「冰糖」发给智谱
        val st = state.settings.speech
        val voice = c.voice.ifBlank { if (t.provider.id == st.ttsProviderId) st.ttsVoice else "" }
        return try {
            val spoken = speech.synthesize(t.provider, t.model, voice, st.ttsSpeed, text, prepared = c.prepared)
            val chars = spoken.sumOf { it.text.length }
            val label = "${t.model}（${t.provider.name}）· ${spoken.size} 段 · $chars 字"
            CommandResult(data = AppJson.encodeToString(SpeechAudio.serializer(), SpeechAudio(
                segments = spoken.map { Base64.getEncoder().encodeToString(it.bytes) },
                label = label, chars = chars)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            CommandResult(false, friendlyError(e, t.provider))
        }
    }

    /** 语音：读之前先切段（不联网、不花钱）。结果 data 是 SpeechPlan 的 JSON。 */
    private fun speakPlan(c: Command.SpeakPlan): CommandResult {
        val t = speechTarget(c.providerId, c.modelId, tts = true)
            ?: return CommandResult(false, if (c.providerId.isNotBlank()) "没有这家服务商，或者它不支持读出来" else Speech.NO_TTS + "（去 设置 → 语音 里加一家的 Key）")
        if (!SpeechModels.supportsTts(t.provider)) return CommandResult(false, Speech.NO_TTS)
        val chunks = speech.plan(t.provider, c.text)
        if (chunks.isEmpty()) return CommandResult(false, "这段文字里没有能读的内容（都是代码块或 Markdown 记号）")
        return CommandResult(data = AppJson.encodeToString(SpeechPlan.serializer(),
            SpeechPlan(t.provider.id, t.model, "${t.model}（${t.provider.name}）", chunks)))
    }

    /** 语音：把一段录音听写成文字。audioBase64 是 wav（16 位 PCM）。 */
    private suspend fun transcribe(c: Command.Transcribe): CommandResult {
        val wav = runCatching { Base64.getDecoder().decode(c.audioBase64) }.getOrNull()
        if (wav == null) return CommandResult(false, "这段录音不是有效的 base64")
        if (wav.isEmpty()) return CommandResult(false, "这段录音是空的")
        // 几乎没声音就不发：听写模型对着静音会编出一句话来（10-12 模拟器上小米把静音听成了一句英文），还白花钱
        SpeechText.parseWav(wav)?.let { w ->
            if (SpeechText.isSilent(w)) return CommandResult(false, Speech.SILENT_RECORDING)
        }
        val t = speechTarget(c.providerId, c.modelId, tts = false)
            ?: return CommandResult(false, if (c.providerId.isNotBlank()) "没有这家服务商，或者它不支持听写" else Speech.NO_ASR + "（去 设置 → 语音 里加一家的 Key）")
        return try {
            CommandResult(data = speech.transcribe(t.provider, t.model, wav))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            CommandResult(false, friendlyError(e, t.provider))
        }
    }

    private class SpeechTarget(val provider: ProviderConfig, val model: String)

    /**
     * 读出来 / 听写用哪家哪个模型：指令里指名了就用指名的；否则交给 SpeechPick——设置 → 语音 里选了且能用就用它，
     * 选的那家删了、停用了、没填 Key 就按顺序自动挑（和设置页显示的「自动」一致）。挑不出来返回 null。
     */
    private fun speechTarget(providerId: String, modelId: String, tts: Boolean): SpeechTarget? {
        if (providerId.isNotBlank()) {
            val p = state.provider(providerId) ?: return null
            val model = modelId.ifBlank {
                (if (tts) SpeechModels.ttsDefaultOf(p) else SpeechModels.asrDefaultOf(p)).orEmpty()
            }
            return SpeechTarget(p, model)
        }
        val choice = if (tts) SpeechPick.forTts(state) else SpeechPick.forAsr(state)
        val p = choice?.let { state.provider(it.providerId) } ?: return null
        return SpeechTarget(p, choice.modelId)
    }

    // ———————————————— 按意思搜（向量） ————————————————

    /** 一次补算要干的活：以前算过、还作数的段（连向量一起留着）+ 这次要算的段和文字。 */
    private class EmbedPlan {
        val entries = ArrayList<VecEntry>()
        val vectors = ArrayList<FloatArray>()
        val todo = ArrayList<VecEntry>()
        val texts = ArrayList<String>()
    }

    /** 搜聊天记录的结果：命中的片段 + 这次是不是只按字面搜的（工具步骤里写一句）。 */
    private class HistorySearch(val hits: List<HistoryHit>, val note: String)

    /** 搜文档的结果（同上）。 */
    private class DocumentSearch(val hits: List<DocHit>, val note: String)

    /**
     * 开关开着、挑得出向量模型时，在后台补算。新消息答完、文档扫完、设置改了都来叫一声。
     * 同一时间只跑一份（已经在算就不重复起）；开关关掉时调用方先 haltEmbedding。
     */
    fun kickEmbedding() {
        val sem = state.settings.semantic
        if (!sem.historyOn && !sem.docsOn) return
        if (EmbedPick.pick(state) == null) return
        if (embedJob?.isActive == true) return
        embedJob = scope.launch(Dispatchers.IO) { embedNow() }
    }

    /** 停掉正在算的那一份，并等它让开路（改设置、重新算之前用）。 */
    private suspend fun haltEmbedding() {
        val old = embedJob ?: return
        embedJob = null
        old.cancel()
        runCatching { old.join() }
    }

    /** 开关关掉了：进度上不再显示「正在算」（算好的向量留着，不删）。 */
    private fun clearEmbedRunning() = updateState { s ->
        s.copy(semanticProgress = SemanticProgress(s.semanticProgress.history.copy(running = false), s.semanticProgress.docs.copy(running = false)))
    }

    /** 补算一遍：聊天记录和文档各一份。一份出错不影响另一份，出错记进后台出错提示。 */
    private suspend fun embedNow() {
        val choice = EmbedPick.pick(state) ?: return
        var bad = false
        if (state.settings.semantic.historyOn) bad = !oneSection(VectorStore.HISTORY, choice)
        if (state.settings.semantic.docsOn) bad = !oneSection(VectorStore.DOCS, choice) || bad
        // 两份都算成了才算「成功一次」（以前的出错提示自己消失）
        if (!bad) bg.ok(BgJob.EMBED)
    }

    private suspend fun oneSection(kind: String, choice: EmbedChoice): Boolean = try {
        runSection(kind, choice)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val reason = friendlyError(e, state.provider(choice.providerId))
        bg.failed(BgJob.EMBED, reason, "")
        markProgress(kind, vectors.data(kind).entries.size, state.semanticProgress.of(kind).total, running = false, error = reason)
        false
    }

    private fun sectionOn(kind: String) =
        if (kind == VectorStore.HISTORY) state.settings.semantic.historyOn else state.settings.semantic.docsOn

    /**
     * 算一份：把还作数的旧段留着，其余的分批发去算；攒一阵（EMBED_SAVE_EVERY 段或 EMBED_SAVE_MS）写一次盘，
     * 算完、出错、被停下时也写（中途停了也存下算好的那些）。以前每批都整份重写，1 万段约 40MB，要重写一百多次。
     * 存的向量不是现在这个模型算的（换了服务商 / 模型 / 维度，或者自动挑换了一家）：不自动作废重算——
     * 那样会悄悄再花一遍钱（主会话审查 10-12）。只标「要重新算」（进度里 stale），搜索先只按字面，等用户点「重新算一遍」。
     */
    private suspend fun runSection(kind: String, choice: EmbedChoice) {
        val p = state.provider(choice.providerId) ?: throw IllegalStateException("向量服务商已经删掉了")
        val dim = if (EmbedModels.sendsDimensions(p)) EmbedModels.DIM else 0
        val old = vectors.data(kind)
        if (!old.isEmpty && !old.matches(choice.platform, choice.modelId, dim)) {
            markProgress(kind, old.entries.size, old.entries.size, running = false)
            return
        }
        val plan = if (kind == VectorStore.HISTORY) historyPlan() else docsPlan()
        val total = plan.entries.size + plan.todo.size
        markProgress(kind, plan.entries.size, total, running = true)
        if (plan.todo.isEmpty()) {
            // 没有要算的：但如果留下的比上次少了（消息删了、文档不在了），清单也要落一次盘把它们清掉
            if (plan.entries.size != old.entries.size) {
                vectors.write(kind, choice.platform, choice.modelId, if (dim > 0) dim else old.dim, plan.entries, plan.vectors, old.builtAt)
            }
            markProgress(kind, plan.entries.size, total, running = false)
            return
        }
        val batch = EmbedModels.batchOf(p)
        var i = 0
        var unsaved = plan.entries.size != old.entries.size // 删掉的段也要落盘
        var savedAt = System.currentTimeMillis()
        var savedCount = plan.entries.size
        fun save() {
            if (!unsaved || plan.vectors.isEmpty()) return
            vectors.write(kind, choice.platform, choice.modelId, plan.vectors.first().size, plan.entries, plan.vectors, now())
            unsaved = false
            savedAt = System.currentTimeMillis()
            savedCount = plan.entries.size
        }
        try {
            while (i < plan.todo.size) {
                // 开关中途关掉了就停：已经算好的留着（不删）
                if (!sectionOn(kind)) {
                    markProgress(kind, plan.entries.size, total, running = false)
                    return
                }
                val n = minOf(batch, plan.todo.size - i)
                val res = embeddings.embed(p, choice.modelId, plan.texts.subList(i, i + n))
                val d = res.vectors.first().size
                val was = plan.vectors.firstOrNull()?.size ?: 0
                if (was > 0 && was != d) throw IllegalStateException("向量维度变了（$was → $d），到「按意思搜」里点「重新算一遍」")
                plan.entries += plan.todo.subList(i, i + n)
                plan.vectors += res.vectors
                i += n
                unsaved = true
                if (plan.entries.size - savedCount >= EMBED_SAVE_EVERY || System.currentTimeMillis() - savedAt >= EMBED_SAVE_MS) save()
                markProgress(kind, plan.entries.size, total, running = true)
            }
        } finally {
            // 算完、出错、被停下（取消）都把算好的落盘：花过钱的别丢
            withContext(NonCancellable) { runCatching { save() } }
        }
        markProgress(kind, plan.entries.size, total, running = false)
    }

    /**
     * 聊天记录要算哪些段：一条消息一段；超过 500 字的按句号切成几段（相邻重叠 50 字，见 VectorText）。
     * 正在生成的不算（答完才进）；已经算过而且这段文字没变的留着；消息删了、改了，这里就丢掉重算。
     */
    private fun historyPlan(): EmbedPlan {
        val data = vectors.data(VectorStore.HISTORY)
        val old = HashMap<String, Int>()
        data.entries.forEachIndexed { i, e -> old[e.source.key()] = i }
        val plan = EmbedPlan()
        for (conv in state.conversations) {
            for (m in snapshot(conv.id)) {
                if (m.status != MsgStatus.DONE || m.role == Role.SYSTEM) continue
                VectorText.chunks(m.content).forEachIndexed { part, text ->
                    keepOrTodo(plan, old, data, VecSource(convId = conv.id, messageId = m.id, part = part), text)
                }
            }
        }
        return plan
    }

    /**
     * 文档要算哪些段：抽出来的文字按 500 字一段切（相邻重叠 50 字）。
     * 读不出文字的文档不算（按文件名找就够了）；文件改过（修改时间变了）或者不在了的，丢掉重算。
     */
    private fun docsPlan(): EmbedPlan {
        val data = vectors.data(VectorStore.DOCS)
        val old = HashMap<String, Int>()
        data.entries.forEachIndexed { i, e -> old[e.source.key()] = i }
        // 按文件归好：修改时间没变的文件，以前算的段整份留着，不用把文字从盘上再读一遍
        // （每答完一轮都会补算一次，以前每次都把所有文档的文字重读、重算指纹，主会话审查 10-12）
        val byPath = HashMap<String, MutableList<Int>>()
        data.entries.forEachIndexed { i, e -> if (e.source.path.isNotEmpty()) byPath.getOrPut(e.source.path) { ArrayList() } += i }
        val plan = EmbedPlan()
        for (e in docs.all()) {
            if (e.chars <= 0) continue
            val kept = byPath[e.path]
            // 段数也要对得上（算到一半被停下时只存了前几段，这种要读文字把剩下的补上）
            if (kept != null && kept.all { data.entries[it].source.mtime == e.mtime } &&
                data.entries[kept[0]].source.of == kept.size && kept.size > 0) {
                for (i in kept.sortedBy { data.entries[it].source.part }) {
                    plan.entries += data.entries[i]
                    plan.vectors += data.vectors[i]
                    old.remove(data.entries[i].source.key())
                }
                continue
            }
            val text = docs.text(e.path) ?: continue
            val parts = VectorText.chunks(text)
            parts.forEachIndexed { part, t ->
                keepOrTodo(plan, old, data, VecSource(path = e.path, part = part, mtime = e.mtime, of = parts.size), t)
            }
        }
        return plan
    }

    /** 这一段以前算过、文字和文件都没变就留着（连向量一起），否则记下来要算。 */
    private fun keepOrTodo(plan: EmbedPlan, old: HashMap<String, Int>, data: VecData, src: VecSource, text: String) {
        val h = hashOf(text)
        val i = old.remove(src.key())
        if (i != null && data.entries[i].hash == h && data.entries[i].source.mtime == src.mtime) {
            // 来历换成这次的（老数据里文档没记段数 of，补上，下次就能不读文字直接留）
            plan.entries += data.entries[i].copy(source = src)
            plan.vectors += data.vectors[i]
        } else {
            plan.todo += VecEntry(src, h)
            plan.texts += text
        }
    }

    private fun hashOf(text: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 进度写进 AppState 给界面看（手机遥控电脑时用 Command.SemanticStatus 取）。 */
    private fun markProgress(kind: String, done: Int, total: Int, running: Boolean, error: String = "") {
        val data = vectors.data(kind)
        val part = SemanticPart(done, total, running, error, data.platform, data.model, data.dim, data.builtAt, stale = isStale(kind, data))
        updateState { s ->
            s.copy(semanticProgress = if (kind == VectorStore.HISTORY) s.semanticProgress.copy(history = part) else s.semanticProgress.copy(docs = part))
        }
    }

    /** 存的向量不是现在挑中的模型算的（换了模型、Key没了）：界面要提示「重新算一遍」，搜索也先不用它。 */
    private fun isStale(kind: String, data: VecData): Boolean {
        if (data.isEmpty) return false
        val choice = EmbedPick.pick(state) ?: return true
        val p = state.provider(choice.providerId) ?: return true
        return !data.matches(choice.platform, choice.modelId, if (EmbedModels.sendsDimensions(p)) EmbedModels.DIM else 0)
    }

    /**
     * 这一份能不能真的按意思搜：开关开着、挑得出向量模型、这一份算过、而且是现在这个模型算的。
     * 不满足就返回 null，调用方只按字面搜（这就是「开关关掉、没算过、算出错时行为照旧」的地方）。
     */
    private fun semanticFor(kind: String): EmbedChoice? {
        if (!sectionOn(kind)) return null
        val choice = EmbedPick.pick(state) ?: return null
        val p = state.provider(choice.providerId) ?: return null
        if (!vectors.data(kind).matches(choice.platform, choice.modelId, if (EmbedModels.sendsDimensions(p)) EmbedModels.DIM else 0)) return null
        return choice
    }

    /** 把一句话算成向量（会记账，见 Embeddings.onBilled）。 */
    private suspend fun queryVector(choice: EmbedChoice, text: String): FloatArray {
        val p = state.provider(choice.providerId) ?: throw IllegalStateException("向量服务商已经删掉了")
        return embeddings.embed(p, choice.modelId, listOf(text)).vectors.first()
    }

    /** 按意思搜到的聊天记录：同一条消息只留最接近的那一段，片段就是那一段的话。 */
    private fun vectorHistoryHits(query: FloatArray, top: Int, minScore: Double): List<HistoryHit> {
        val byMsg = LinkedHashMap<String, HistoryHit>()
        for (hit in vectors.search(VectorStore.HISTORY, query, top)) {
            if (hit.score < minScore) break // 按相似度从高到低排的：低于门槛的都不算「意思相近」
            val s = hit.entry.source
            val key = "${s.convId}\u0000${s.messageId}"
            if (key in byMsg) continue
            val conv = state.conversation(s.convId) ?: continue
            val m = snapshot(conv.id).firstOrNull { it.id == s.messageId } ?: continue
            if (m.status != MsgStatus.DONE) continue
            val text = VectorText.chunks(m.content).getOrNull(s.part) ?: continue
            byMsg[key] = HistoryHit(conv.id, conv.title, m.id, speakerName(m), text.take(200), m.createdAt)
        }
        return byMsg.values.toList()
    }

    /** 按意思搜到的文档：一个文件只留一条，片段用最接近的那一段。 */
    private fun vectorDocHits(query: FloatArray, top: Int, minScore: Double): List<DocHit> {
        val out = ArrayList<DocHit>()
        val seen = HashSet<String>()
        for (hit in vectors.search(VectorStore.DOCS, query, top)) {
            if (hit.score < minScore) break
            val e = docs.entryAt(hit.entry.source.path) ?: continue
            if (!seen.add(e.path)) continue
            val text = docs.text(e.path) ?: continue
            val part = VectorText.chunks(text).getOrNull(hit.entry.source.part) ?: continue
            out += docs.hitOf(e, part.take(140))
        }
        return out
    }

    /**
     * 倒数排名融合（RRF，k = 60）：同一个东西在两份榜单里都有，就把名次的倒数加起来——
     * 两边都靠前的排最前，只在一份里靠后的不会挤掉另一份里靠前的。代表那一条用先传进来的那份榜单里的。
     */
    private fun <T> rrf(a: List<T>, b: List<T>, limit: Int, key: (T) -> String): List<T> {
        val score = HashMap<String, Double>()
        val first = LinkedHashMap<String, T>()
        for (list in listOf(a, b)) list.forEachIndexed { i, item ->
            val k = key(item)
            score[k] = (score[k] ?: 0.0) + 1.0 / (RRF_K + i + 1)
            if (k !in first) first[k] = item
        }
        return score.entries.sortedByDescending { it.value }.take(limit).mapNotNull { first[it.key] }
    }

    /**
     * 搜所有对话的聊天内容。开了「按意思搜」而且这一份已经算过时：把问题也算成向量，取最接近的几段，
     * 和按字面的结果合起来排；否则（没开、没算过、算向量出错）只按字面，note 里写明这次只按字面搜。
     */
    private suspend fun searchHistory(query: String, limit: Int): HistorySearch {
        val literal = literalHistory(query, limit)
        val choice = semanticFor(VectorStore.HISTORY) ?: return HistorySearch(literal, LITERAL_ONLY)
        return try {
            val vec = vectorHistoryHits(queryVector(choice, query), maxOf(limit * 3, 20), EmbedModels.minScore(choice.modelId))
            if (vec.isEmpty()) HistorySearch(literal, LITERAL_ONLY)
            else HistorySearch(rrf(literal, vec, limit) { it.messageId }, "这次按意思和字面合起来搜（意思最接近的 ${vec.size} 段）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            HistorySearch(literal, "$LITERAL_ONLY（按意思搜出错：${friendlyError(e, state.provider(choice.providerId))}）")
        }
    }

    /** 搜本机文档：关键词留空 = 最近改过的（这时不问意思）。开了「我的文档」按意思搜时和 searchHistory 一样合起来排。 */
    private suspend fun searchDocuments(query: String, limit: Int): DocumentSearch {
        if (query.isBlank()) return DocumentSearch(withContext(Dispatchers.IO) { docs.recent(limit) }, "")
        val literal = withContext(Dispatchers.IO) { docs.search(query, limit) }
        val choice = semanticFor(VectorStore.DOCS) ?: return DocumentSearch(literal, LITERAL_ONLY)
        return try {
            val vec = vectorDocHits(queryVector(choice, query), maxOf(limit * 3, 20), EmbedModels.minScore(choice.modelId))
            if (vec.isEmpty()) DocumentSearch(literal, LITERAL_ONLY)
            // 代表那一条用向量的（片段是最接近的那段话），名次两种算法照旧各自算
            else DocumentSearch(rrf(vec, literal, limit) { it.path }, "这次按意思和字面合起来搜（意思最接近的 ${vec.size} 个文件）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DocumentSearch(literal, "$LITERAL_ONLY（按意思搜出错：${friendlyError(e, state.provider(choice.providerId))}）")
        }
    }

    /**
     * 估算「把现有的聊天记录和文档都算成向量」要多少字、几段、大约多少钱。不联网、不花钱：
     * 只数本机已有的东西；token 按 1 个汉字约 1 个 token 粗估（EmbedModels.TOKEN_NOTE），钱按价格表算。
     */
    private fun semanticEstimate(): CommandResult {
        val choice = EmbedPick.pick(state)
        val p = choice?.let { state.provider(it.providerId) }
        val price = choice?.let { Prices.of(it.platform, it.modelId, state.settings.costs.overrides, p?.baseUrl.orEmpty()) }
        fun part(chars: Int, chunks: Int): SemanticEstimatePart {
            val tokens = (chars * EmbedModels.TOKENS_PER_CHAR).toLong()
            val unit = price?.tiers?.firstOrNull()?.input
            return SemanticEstimatePart(chars, chunks, tokens, if (unit == null) null else tokens * unit / 1_000_000.0)
        }
        var hChars = 0
        var hChunks = 0
        for (conv in state.conversations) {
            for (m in snapshot(conv.id)) {
                if (m.status != MsgStatus.DONE || m.role == Role.SYSTEM) continue
                for (c in VectorText.chunks(m.content)) { hChars += c.length; hChunks++ }
            }
        }
        var dChars = 0
        var dChunks = 0
        for (e in docs.all()) {
            if (e.chars <= 0) continue
            val t = docs.text(e.path) ?: continue
            for (c in VectorText.chunks(t)) { dChars += c.length; dChunks++ }
        }
        val res = SemanticEstimateResult(
            providerId = p?.id.orEmpty(), providerName = p?.name.orEmpty(), model = choice?.modelId.orEmpty(),
            label = if (choice == null) "" else "${choice.modelId}（${choice.providerName}）",
            priceText = price?.let { Prices.describe(it) } ?: "价格表里没有这个模型（钱算不出来，token 数是粗估的）",
            currency = price?.currency ?: "CNY",
            history = part(hChars, hChunks), docs = part(dChars, dChunks))
        return CommandResult(data = AppJson.encodeToString(SemanticEstimateResult.serializer(), res))
    }

    /** 整份作废重算（换了模型、维度时必须）。先把正在算的停下、等它让开路，再作废重算。 */
    private fun semanticRebuild(which: String): CommandResult {
        val s = which.trim().lowercase()
        val kinds = if (s == VectorStore.HISTORY || s == VectorStore.DOCS) listOf(s) else listOf(VectorStore.HISTORY, VectorStore.DOCS)
        scope.launch(Dispatchers.IO) {
            haltEmbedding()
            kinds.forEach { kind ->
                vectors.reset(kind)
                markProgress(kind, 0, 0, running = false)
            }
            kickEmbedding()
        }
        val what = kinds.joinToString("、") { if (it == VectorStore.HISTORY) "聊天记录" else "文档" }
        return CommandResult(message = if (state.settings.semantic.historyOn || state.settings.semantic.docsOn) "$what 的向量已作废，正在重新算" else "$what 的向量已清掉")
    }

    private companion object {
        /** 听平台视频最长听多少秒（按秒收费，也省时间）。 */
        const val LISTEN_MAX_SEC = 600

        /** 补全文件名时不进去翻的文件夹。 */
        val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", "out", "target", ".venv", "venv", "__pycache__", ".next", ".kotlin")
        /** 后台命令输出里的本机网址（开发服务器）。 */
        val LOCAL_URL = Regex("https?://(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1\\])(:\\d+)?[^\\s'\"<>]*")
        /** 没按意思搜时工具步骤里写的一句。 */
        const val LITERAL_ONLY = "这次只按字面搜"
        /** 倒数排名融合的 k（RRF 论文里的常用取值）。 */
        const val RRF_K = 60
        /** 补算时攒够这么多段、或者过了这么久，写一次盘（算完、停下时也写）。 */
        const val EMBED_SAVE_EVERY = 1000
        const val EMBED_SAVE_MS = 20_000L
    }

    /** 选了智谱搜索却没单独填 Key：用服务商里智谱开放平台的 Key（同一个账号，跟 Kimi 搜索一样不用再填）。 */
    private fun searchSettings(): com.guixing.jixunying.model.SearchSettings {
        val s = state.settings.search
        if (s.engine != com.guixing.jixunying.model.SearchEngine.ZHIPU || s.apiKeys[s.engine.name].orEmpty().isNotBlank()) return s
        val p = state.providers.firstOrNull { (it.presetId == "zhipu" || "open.bigmodel.cn" in it.baseUrl.lowercase()) && it.apiKey.isNotBlank() } ?: return s
        return s.copy(apiKeys = s.apiKeys + (s.engine.name to p.apiKey))
    }

    /** kimi 不为空：用这家 Kimi 的官方搜索接口；它出错就退回设置里的搜索引擎，并在步骤上注明。 */
    private suspend fun runSearch(query: String, sources: MutableList<SearchSource>, kimi: ProviderConfig? = null): Pair<ToolStep, List<SearchSource>> {
        var note = ""
        if (kimi != null) {
            try {
                val res = search.kimi(query, kimi.baseUrl, kimi.apiKey, state.settings.search)
                sources += res
                return ToolStep("search", query, res, ok = true) to res
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                note = "Kimi 官方搜索出错（${friendlyError(e, kimi)}），这次改用 ${WebSearch.engineName(state.settings.search.engine)}"
            }
        }
        return try {
            val res = search.search(query, searchSettings())
            sources += res
            ToolStep("search", query, res, ok = true, note = note) to res
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolStep("search", query, ok = false, note = listOf(note, friendlyError(e)).filter { it.isNotEmpty() }.joinToString("；")) to emptyList()
        }
    }

    private suspend fun runTool(convId: String, msgId: String, tc: ToolCall, sources: MutableList<SearchSource>, kimiSearch: ProviderConfig? = null,
                                seen: MutableList<Pair<String, String>>? = null): String {
        val args = runCatching { Json.parse(tc.arguments.ifBlank { "{}" }).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return when (tc.name) {
            "web_search" -> {
                val q = args.str("query").orEmpty().ifBlank { return "参数错误：缺少 query" }
                val start = sources.size
                val (step, res) = runSearch(q, sources, kimiSearch)
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + step) }
                if (!step.ok) "搜索失败：${step.note}。请如实告诉用户没搜到，不要编造。"
                else if (res.isEmpty()) "没有搜到结果。请如实告诉用户。"
                else res.mapIndexed { i, r -> "[${start + i + 1}] ${r.title}\n${r.url}\n${r.snippet}" }.joinToString("\n\n")
            }
            "listen_video" -> listenVideo(convId, msgId, args.str("url").orEmpty())
            "fetch_url" -> {
                val url = args.str("url").orEmpty()
                if (!url.startsWith("http")) return "参数错误：url 要以 http 开头"
                // 抖音、小红书、快手、微博、B站：用本机浏览器读（2026-10-23，普通请求只拿到登录页、验证码页）
                val platform = SocialLinks.platformOf(url)
                val browser = pageBrowser
                if (platform != null && browser != null) {
                    val (text, step) = try {
                        val page = pageSlots.withPermit { browser.snapshot(url, SocialLinks.EXTRACT_JS) }
                        if (SocialLinks.blocked(page)) {
                            ("${platform.name}没让看（页面是登录页、验证码或者风控拦截：${page.str("title").orEmpty().ifBlank { page.str("url").orEmpty() }}）。" +
                                "请如实告诉用户没读到，并说可以在电脑上 设置 → 联网搜索 → 读平台链接 里点「去登录${platform.name}」扫码登录后再试；" +
                                "B站拦截一般是因为电脑上网出口在国外。页面上的字：\n" + page.str("text").orEmpty().take(600)) to
                                ToolStep("fetch", url, listOf(SearchSource(platform.name, url)), ok = false, note = "${platform.name}要登录或被拦")
                        } else {
                            SocialLinks.videoOf(page)?.let { pageVideos[url] = it }
                            SocialLinks.format(platform, page) to ToolStep("fetch", url, listOf(SearchSource(platform.name + " · " + url.take(60), url)), ok = true)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        "${platform.name}的页面没打开：${friendlyError(e)}" to ToolStep("fetch", url, ok = false, note = friendlyError(e))
                    }
                    updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + step) }
                    return text
                }
                val (text, step) = try {
                    val t = runCatching { search.fetch(url, false) }.getOrElse { search.fetch(url, true) }
                    t to ToolStep("fetch", url, listOf(SearchSource(url.take(80), url)), ok = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    "网页打不开：${friendlyError(e)}" to ToolStep("fetch", url, ok = false, note = friendlyError(e))
                }
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + step) }
                text
            }
            "create_word", "create_excel", "create_ppt" -> {
                val ext = when (tc.name) { "create_excel" -> "xlsx"; "create_ppt" -> "pptx"; else -> "docx" }
                if (args.str("content").isNullOrBlank()) return "参数错误：缺少 content（文件内容）"
                try {
                    val f = makeOfficeFile(ext, args)
                    updateMessage(convId, msgId) { it.copy(attachments = it.attachments + f.att, tools = it.tools + ToolStep("file", f.att.name, note = "")) }
                    "已做好《${f.att.name}》（${f.att.note}），已经显示在你的回答下面，用户点开就能用。" +
                        (if (f.att.savedPath.isNotEmpty() && !isPhone) "电脑上另存在 ${f.att.savedPath}。" else "") +
                        "回答里简单说一下文件里有什么就行，不用把全文再贴一遍。"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    updateMessage(convId, msgId) { it.copy(tools = it.tools + ToolStep("file", args.str("filename").orEmpty(), ok = false, note = e.message ?: "出错了")) }
                    "文件没做成：${e.message}。改一下内容再试，或者告诉用户。"
                }
            }
            "generate_image" -> {
                val prompt = args.str("prompt").orEmpty().ifBlank { return "参数错误：缺少 prompt" }
                try {
                    val r = paintAuto(prompt, args.str("size"))
                    updateMessage(convId, msgId) { it.copy(attachments = it.attachments + r.att, tools = it.tools + ToolStep("image", prompt)) }
                    "图片已生成（用的是 ${r.label}），并已经显示给用户。"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    updateMessage(convId, msgId) { it.copy(tools = it.tools + ToolStep("image", prompt, ok = false, note = friendlyError(e))) }
                    "画图失败：${friendlyError(e)}。请告诉用户。"
                }
            }
            "generate_video" -> makeVideo(convId, msgId, args)
            "remember" -> {
                val text = args.str("text").orEmpty()
                val kind = args.str("kind")?.trim()?.takeIf { it in Recorder.kinds } ?: "关于我"
                val res = rememberNow(text, kind, state.conversation(convId)?.title.orEmpty())
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("memory", text.trim().take(80), ok = res.startsWith("已"))) }
                res
            }
            "search_history" -> {
                val q = args.str("query").orEmpty().ifBlank { return "参数错误：缺少 query" }
                val r = searchHistory(q, 8)
                val hits = r.hits
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("history", q, ok = hits.isNotEmpty(), note = r.note)) }
                if (hits.isEmpty()) "以前的聊天里没找到和「$q」相关的内容。如实告诉用户。"
                else hits.joinToString("\n\n") { h -> "[${h.convTitle} · ${dayOf(h.time)} · ${h.sender}] ${h.snippet}" }
            }
            "search_documents" -> {
                val q = args.str("query").orEmpty().trim()
                val r = searchDocuments(q, 10)
                val hits = r.hits
                updateMessage(convId, msgId, persist = false) {
                    it.copy(tools = it.tools + ToolStep("doc", if (q.isBlank()) "看了最近的文档" else "搜文档「$q」· ${hits.size} 个", ok = hits.isNotEmpty(), note = r.note))
                }
                if (hits.isEmpty()) "没找到相关的文档（文档库里一共 ${docs.count} 个文件）。如实告诉用户，可以建议换个关键词，或者到「我的文档」里看看收录了哪些文件夹。"
                else buildString {
                    hits.forEachIndexed { i, h ->
                        appendLine("${i + 1}. 《${h.name}》")
                        appendLine("   路径：${h.path}")
                        appendLine("   修改于 ${dayOf(h.mtime)}" + if (h.chars > 0) "，${h.chars} 字" else "，${h.note.ifBlank { "读不出文字" }}")
                        if (h.snippet.isNotBlank()) appendLine("   片段：${h.snippet}")
                    }
                    append("要看全文就用 read_document，path 填上面的路径。回答时说明出自哪个文件。")
                }
            }
            "find_files" -> chatFind(convId, msgId, args)
            "search_in_files" -> chatGrep(convId, msgId, args)
            "run_command" -> chatRun(convId, msgId, args)
            "update_todos" -> {
                val lines = Todos.normalize(args.str("items").orEmpty())
                if (lines.isEmpty()) return "清单是空的：一行一项，开头写 [x] / [>] / [ ]"
                updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.filter { it.kind != "todo" } + ToolStep("todo", "待办", id = newId(), output = lines, ok = true)) }
                Todos.reply(lines)
            }
            "preview" -> {
                val url = args.str("url").orEmpty().trim()
                if (!url.startsWith("http")) return "url 要以 http:// 或 https:// 开头"
                val width = args.str("width")?.trim()?.toIntOrNull()?.coerceIn(320, 2560) ?: 1280
                val png = if (seen != null) withContext(Dispatchers.IO) { com.guixing.jixunying.engine.code.Preview.screenshot(url, width) } else null
                if (png != null) {
                    val small = runCatching { shrinkImageToJpeg(png, 1600, 1_500_000) }.getOrNull()
                    seen!! += url to (if (small != null) "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(small) else "data:image/png;base64," + Base64.getEncoder().encodeToString(png))
                    updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("fetch", url, ok = true, note = "预览截图")) }
                    "$url 的截图已经附在下一条消息里给你看。"
                } else {
                    val t = runCatching { search.fetch(url, false) }.getOrElse { return "打不开 $url：${friendlyError(it)}" }
                    updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("fetch", url, ok = true)) }
                    "$url 的网页文字：\n" + t.take(12_000)
                }
            }
            "read_output" -> {
                val id = args.str("id").orEmpty().trim()
                val out = codeRunner?.backgroundOutput(id) ?: return "没有这个后台命令：$id"
                val alive = state.conversation(convId)?.background?.firstOrNull { it.id == id }?.running == true
                (if (alive) "（还在跑）\n" else "（已经结束了）\n") + out.takeLast(12_000)
            }
            "stop_process" -> {
                val id = args.str("id").orEmpty().trim()
                codeRunner?.stopBackground(id)
                markBg(convId, id) { it.copy(running = false) }
                "停掉了 $id"
            }
            "delete_file" -> chatDelete(convId, msgId, args)
            "write_file" -> chatWrite(convId, msgId, args, edit = false)
            "edit_file" -> chatWrite(convId, msgId, args, edit = true)
            "list_folder" -> {
                val p = args.str("path").orEmpty().trim().removeSurrounding("\"").ifBlank { return "参数错误：缺少 path" }
                val dir = File(p)
                if (!dir.isDirectory) return "没有这个文件夹：$p"
                askToRead(convId, msgId, dir)?.let { return it }
                val all = withContext(Dispatchers.IO) { dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }.orEmpty()
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("doc", "看了文件夹 ${dir.name} · ${all.size} 项")) }
                buildString {
                    appendLine("${dir.absolutePath} 里 ${all.size} 项：")
                    all.take(300).forEach { f -> appendLine(if (f.isDirectory) "${f.name}/" else "${f.name}（${f.length()} 字节）") }
                    if (all.size > 300) append("（还有 ${all.size - 300} 项没列出来）")
                }
            }
            "read_document" -> {
                val p = args.str("path").orEmpty().trim().removeSurrounding("\"").ifBlank { return "参数错误：缺少 path" }
                val e = docs.find(p) ?: return readAnyFile(convId, msgId, p, args.str("start")?.trim()?.toIntOrNull() ?: 0, seen)
                val text = withContext(Dispatchers.IO) { docs.text(e.path) }
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("doc", "读《${e.name}》", ok = text != null)) }
                if (text == null) "《${e.name}》读不出文字：${e.note.ifBlank { "可能是扫描件或老格式" }}。如实告诉用户。"
                else {
                    val s = (args.str("start")?.trim()?.toIntOrNull() ?: 0).coerceIn(0, text.length)
                    val end = minOf(text.length, s + 10_000)
                    buildString {
                        appendLine("《${e.name}》（${e.path}）共 ${text.length} 字，下面是第 ${s + 1}～$end 字：")
                        appendLine(text.substring(s, end))
                        if (end < text.length) append("（后面还有 ${text.length - end} 字；要接着读就再调 read_document，start=$end）")
                    }
                }
            }
            else -> if (tc.name.startsWith("mcp__")) {
                val (_, client, tool) = mcpTools().firstOrNull { it.first == tc.name } ?: return "没有这个外部工具：${tc.name}"
                val step = ToolStep("run", "外部工具 ${tool.name} ${args.toString().take(200)}", id = newId(), ok = true, note = "调用外部工具（MCP）")
                askToAct(convId, msgId, step, null, dangerous = false)?.let { return it }
                val out = runCatching { client.call(tool.name, args) }.getOrElse { "外部工具出错：${it.message?.take(300)}" }
                updateMessage(convId, msgId) { m -> m.copy(tools = m.tools.map { if (it.id == step.id) it.copy(output = out.take(20_000), note = "外部工具（MCP）返回了") else it }) }
                out.take(12_000)
            } else "没有这个工具：${tc.name}"
        }
    }

    private fun dayOf(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" }

    private fun lastUserText(convId: String, cutoffId: String?): String {
        val all = snapshot(convId)
        val end = cutoffId?.let { id -> all.indexOfFirst { it.id == id } + 1 }?.takeIf { it > 0 } ?: all.size
        return all.subList(0, end).lastOrNull { it.role == Role.USER }?.content.orEmpty()
    }

    /**
     * 旧对话里也照新写的自定义指令做（2026-10-23 用户：「旧对话看不到我新更新的个性化」）。
     * 指令本来就在 system 最后，但前面已经有一串没照指令写的回答时，模型会跟着自己以前的样子答：
     * 真模型量过（LiveInstructionTest），阿智 GLM-5.3-Flash 在旧对话里 5 次有 3 次没照做、新对话 5 次全照做。
     * 所以有过回答的对话，把用户自己写的指令原文再贴在最新一句话后面（不加别的要求）。
     * 贴在用户那句话里、不另起一条 system：有的接口不认对话中间的 system。
     */
    private fun remindInstructions(working: MutableList<JsonObject>) {
        val custom = state.settings.personal.instructions.trim()
        if (custom.isEmpty() || working.none { it["role"]?.jsonPrimitive?.contentOrNull == "assistant" }) return
        val i = working.indexOfLast { it["role"]?.jsonPrimitive?.contentOrNull == "user" }
        if (i < 0) return
        val note = "\n\n（我在设置里写的自定义指令：$custom）"
        val m = working[i]
        val content = m["content"]
        val next = when {
            content is JsonPrimitive -> JsonPrimitive(content.content + note)
            content is JsonArray -> JsonArray(content + buildJsonObject { put("type", "text"); put("text", note.trim()) })
            else -> return
        }
        working[i] = JsonObject(m + ("content" to next))
    }

    /**
     * 把聊天记录转成模型的 messages。别人的话标上「【名字】」，自己的话是 assistant。
     * 记录员压缩过的部分换成摘要放在最前面，只发摘要之后的原文。
     */
    private fun buildHistory(convId: String, me: Member, cutoffId: String?, excludeId: String, vision: Boolean): List<JsonObject> {
        val all = snapshot(convId)
        val memo = storage.loadMemo(convId)
        val end = cutoffId?.let { id -> all.indexOfFirst { it.id == id } + 1 }?.takeIf { it > 0 } ?: all.size
        // 还在生成中的回答（别人这一轮没说完的半句话、正在画的图）不算进上下文
        val usable = all.subList(0, end).filter {
            it.id != excludeId && it.role != Role.SYSTEM && it.status != MsgStatus.ERROR && it.status != MsgStatus.STREAMING &&
                (it.content.isNotBlank() || it.attachments.isNotEmpty()) && (memo.summary.isBlank() || it.createdAt > memo.upToTime)
        }
        val profile = state.profile
        val budget = 48_000
        var used = 0
        val picked = ArrayDeque<Message>()
        for (m in usable.asReversed()) {
            val cost = m.content.length + m.attachments.sumOf { minOf(it.textChars, 4000) } + 200
            if (used + cost > budget && picked.isNotEmpty()) break
            used += cost
            picked.addFirst(m)
        }
        val lastUserIdx = picked.indexOfLast { it.role == Role.USER }
        var imagesLeft = 6

        data class Part(val role: String, val text: String, val images: List<String>)
        val parts = mutableListOf<Part>()
        if (memo.summary.isNotBlank()) parts += Part("user",
            "【对话摘要】这个对话更早的部分已经由记录员压缩成下面的摘要（要查原话可以用 search_history）：\n\n" + memo.summary, emptyList())
        if (picked.size < usable.size) parts += Part("user", "（更早的聊天记录太长，已省略）", emptyList())
        picked.forEachIndexed { idx, m ->
            val mine = m.role == Role.AI && m.senderId == me.id
            val speaker = when {
                m.role == Role.USER -> profile.name
                m.senderId == PAINTER_ID -> "画图助手"
                m.senderId == CHAIR_ID -> "会审主席"
                m.senderId == CODE_ID -> "Code 助手"
                else -> state.member(m.senderId)?.name ?: "已移除的成员"
            } + if (m.stage == "review") "·互评" else ""
            val sb = StringBuilder()
            if (!mine) sb.append("【").append(speaker).append("】")
            sb.append(stripThink(m.content))
            val imgs = mutableListOf<String>()
            for (a in m.attachments) {
                when (a.kind) {
                    AttachmentKind.DOCUMENT -> {
                        val text = storage.fileText(a.id)
                        val limit = if (idx == lastUserIdx) 80_000 else 4_000
                        sb.append("\n\n[附件：").append(a.name).append("]")
                        if (text == null) sb.append("（读不出文字：").append(a.note).append("）")
                        else {
                            sb.append("\n```\n").append(text.take(limit))
                            if (text.length > limit) sb.append("\n…（共 ${text.length} 字，后面省略）")
                            sb.append("\n```")
                        }
                    }
                    AttachmentKind.IMAGE, AttachmentKind.GENERATED_IMAGE -> {
                        if (vision && !mine && imagesLeft > 0 && (m.role == Role.USER || a.kind == AttachmentKind.GENERATED_IMAGE)) {
                            imageDataUrl(a)?.let { imgs += it; imagesLeft-- }
                        }
                        val what = if (a.kind == AttachmentKind.GENERATED_IMAGE) "生成的图片" else "图片"
                        if (imgs.isEmpty() || !vision) sb.append("\n[$what：").append(a.name).append(if (vision) "]" else "，你看不到图片内容]")
                    }
                }
            }
            parts += Part(if (mine) "assistant" else "user", sb.toString(), imgs)
        }
        // 合并相邻同角色消息（有的接口不允许连续两条 user）
        val merged = mutableListOf<Part>()
        for (p in parts) {
            val last = merged.lastOrNull()
            if (last != null && last.role == p.role) merged[merged.size - 1] = Part(p.role, last.text + "\n\n" + p.text, last.images + p.images)
            else merged += p
        }
        while (merged.firstOrNull()?.role == "assistant") merged.removeAt(0)
        // 最后一条如果是自己的话（比如被 @ 时自己刚说过），补一句提示让它接着说
        if (merged.lastOrNull()?.role == "assistant") merged += Part("user", "（请继续）", emptyList())

        return merged.map { p ->
            buildJsonObject {
                put("role", p.role)
                if (p.images.isEmpty()) put("content", p.text)
                else putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", p.text) })
                    p.images.forEach { url -> add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", url) } }) }
                }
            }
        }
    }

    private fun stripThink(s: String) = s.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()

    /** 图片压到长边 1600 以内再发，省流量也省 token。 */
    private fun imageDataUrl(a: Attachment): String? {
        val bytes = storage.fileBytes(a.id) ?: return null
        val shrunk = runCatching { shrinkImageToJpeg(bytes, 1600, 1_500_000) }.getOrNull()
        val (data, mime) = if (shrunk != null) shrunk to "image/jpeg" else bytes to a.mime
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(data)
    }

    /** 报错原文里如果带出了 Key（比如 Key 里有换行，网络库报错会原样带出），打码。 */
    fun friendlyError(e: Throwable, p: ProviderConfig? = null): String {
        val key = p?.apiKey?.trim().orEmpty()
        val text = friendlyErrorRaw(e, p)
        return if (key.length >= 8) text.replace(key, maskKey(key)) else text
    }

    private fun friendlyErrorRaw(e: Throwable, p: ProviderConfig?): String {
        if (e is ImageDownloadFailed) return "图片画好了，但下载失败（这张已经扣费）：${e.body.take(200)}"
        if (e is SpeechDownloadFailed) return "声音合成好了，但下载失败（这次已经扣费）：${e.body.take(200)}"
        // 以前红框里直接是「…json.tmp -> …json」这种原始异常，看不懂（2026-10-22）
        if (e is java.nio.file.FileSystemException) return "读写文件没成功：${e.file ?: ""} 被别的程序占着，或者没有权限（${e.reason ?: e.javaClass.simpleName}）。等一下再试一次。"
        if (e is ApiException) {
            val body = e.body.take(300)
            val lower = body.lowercase()
            val hint = when {
                e.status == 401 || (e.status == 403 && "key" in lower) || "invalid api key" in lower || "login fail" in lower ||
                    "unauthorized" in lower || "authentication" in lower || "令牌" in body -> "API Key 不对或已失效"
                e.status == 402 || "insufficient" in lower || "余额" in body || "欠费" in body || "balance" in lower || "quota" in lower ||
                    "arrearage" in lower || "good standing" in lower -> "余额或额度不足"
                e.status == 404 -> "模型名或接口地址不对"
                e.status == 429 -> "请求太频繁或额度用完了，稍后再试"
                e.status >= 500 -> "服务商那边出错了，稍后再试"
                else -> "请求被拒绝"
            }
            return "$hint（HTTP ${e.status}）：$body"
        }
        val msg = e.message ?: e::class.simpleName ?: "未知错误"
        val net = e is java.net.ConnectException || e is java.net.UnknownHostException ||
            e::class.simpleName.orEmpty().contains("Timeout") || msg.contains("timed out", true)
        return if (net) {
            val proxyHint = if (p != null && !p.useProxy && Presets.byId(p.presetId).useProxy) "；这是海外服务商，记得在 设置→模型服务 里打开「走代理」" else
                if (p?.useProxy == true) "；检查代理 ${state.settings.proxy} 是否开着" else ""
            "网络连不上：$msg$proxyHint"
        } else msg
    }
}
