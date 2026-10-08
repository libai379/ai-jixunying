package com.guixing.jixunying.engine

import com.guixing.jixunying.client.Backend
import com.guixing.jixunying.client.ClientStore
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.BgJob
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.ImageChoice
import com.guixing.jixunying.model.ImagePick
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.PairedDevice
import com.guixing.jixunying.model.Presets
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.ReplyMode
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SearchSource
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.ConfigBundle
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val PAINTER_ID = "tool:image"

/** 一次回答最多调几次工具（搜索、读网页、画图）。 */
const val MAX_TOOL_USES = 8

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
    private val search = WebSearch { state.settings.proxy.trim().ifEmpty { null } }
    private val images = ImageGen { proxyFor(it.useProxy) }

    /** 账本：每次调用模型、每张图记一条，花费页用（见 Ledger.kt、model/Costs.kt）。 */
    private val ledger = Ledger(java.io.File(storage.root, "usage"))

    /** 花费页：按账本和价格表估算；能查余额的平台查余额。 */
    private val costs = CostReporter(ledger, { state })
    private val balances = Balances { proxyFor(it.useProxy) }

    /** 记录员后台活出错时记下来、提示用户（以前悄悄停掉）。 */
    private val bg = BackgroundWatch({ state }, { f -> updateState(f) }, { emit(Event.Notice(it, error = true)) })

    /** 本机文档库（AI 能搜、能读）。 */
    private val docs = DocLibrary(java.io.File(storage.root, "docindex").apply { mkdirs() })

    /** 联机设置变了（开关、中转列表）时通知外面重启联机服务。 */
    var onRelaySettingsChanged: (() -> Unit)? = null

    /** 上一个配对密钥：手机补发的配对请求可能还用旧的，留 3 分钟。 */
    @Volatile private var previousSecret: Pair<String, Long>? = null

    init {
        var s = state
        if (s.hostId.isEmpty()) s = s.copy(hostId = com.guixing.jixunying.relay.Crypto.randomHex(8))
        if (s.pairingSecret.isEmpty()) s = s.copy(pairingSecret = newSecret())
        // 手机上一般没有本地 HTTP 代理（用的是 VPN 类软件），默认直连
        if (isPhone && storage.loadState() == null) s = s.copy(settings = s.settings.copy(proxy = ""))
        // 以前重复导入留下的同一个服务商 / 同一位成员，启动时合并掉
        val (deduped, memberMap, removed) = ConfigMerge.dedupe(s)
        if (removed > 0) s = deduped
        if (s != state) { state = s; storage.saveState(s) }
        if (memberMap.isNotEmpty()) remapSenders(memberMap)
        emit(Event.State(state))
        storage.brokenNotes.forEach { emit(Event.Notice(it, error = true)) }
        llm.onUsage = { p, model, u, tag -> recordUsage(p, model, u, tag?.kind ?: UsageKinds.OTHER, tag) }
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
    private fun newSecret() = Base64.getEncoder().encodeToString(com.guixing.jixunying.relay.Crypto.randomBytes(16))

    private fun updateState(transform: (AppState) -> AppState): AppState {
        val s = synchronized(stateLock) {
            state = transform(state)
            storage.saveState(state)
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

    private fun messages(convId: String): MutableList<Message> = convs.getOrPut(convId) { storage.loadMessages(convId) }

    private fun snapshot(convId: String): List<Message> = messages(convId).let { synchronized(it) { it.toList() } }

    private fun save(convId: String) = storage.saveMessages(convId, snapshot(convId))

    private fun addMessage(m: Message) {
        val list = messages(m.convId)
        synchronized(list) { list.add(m) }
        emit(Event.MessageUpsert(m))
        save(m.convId)
    }

    private fun updateMessage(convId: String, id: String, persist: Boolean = true, transform: (Message) -> Message): Message? {
        val list = messages(convId)
        val updated = synchronized(list) {
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) return null
            transform(list[i]).also { list[i] = it }
        }
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
            updateState { it.copy(settings = it.settings.copy(costs = c.costs)) }
            CommandResult()
        }
        is Command.GetBalances -> {
            val list = coroutineScope {
                state.providers.filter { it.enabled }.map { p -> async(Dispatchers.IO) { balances.query(p, now()) } }.awaitAll()
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
            updateState { s ->
                val oldKeys = s.settings.search.apiKeys
                val keys = c.settings.search.apiKeys.mapValues { (k, v) -> if (isMaskedKey(v)) oldKeys[k].orEmpty() else v }
                // 花费设置走 SaveCosts 单独存，这里保留原样（旧版手机发来的设置里没有这块）
                s.copy(settings = c.settings.copy(search = c.settings.search.copy(apiKeys = keys), costs = s.settings.costs))
            }
            if (before.enabled != state.settings.relay.enabled || before.brokers != state.settings.relay.brokers) onRelaySettingsChanged?.invoke()
            // 关掉了的后台活不会再跑，它以前的出错提示也就不会「成功一次自己消失」：直接清掉
            state.settings.memory.let { m ->
                if (!m.stances) bg.ok(BgJob.STANCES)
                if (!m.enabled || !m.autoExtract) bg.ok(BgJob.MEMORY)
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
            val atts = snapshot(c.id).flatMap { m -> m.attachments.map { it.id } }
            convs.remove(c.id)
            storage.deleteConversation(c.id, atts)
            if (synchronized(stanceBook) { stanceBook.removeAll { it.convId == c.id } }) storage.saveStances(synchronized(stanceBook) { stanceBook.toList() })
            updateState { s -> s.copy(conversations = s.conversations.filterNot { it.id == c.id }) }
            emit(Event.ConversationRemoved(c.id))
            CommandResult()
        }
        is Command.LoadMessages -> {
            emit(Event.Messages(c.convId, snapshot(c.convId)))
            CommandResult()
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
        is Command.Regenerate -> regenerate(c.convId, c.messageId)
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
            updateState { s -> s.copy(memories = s.memories.filterNot { it.id == c.id }) }
            CommandResult()
        }
        is Command.TidyMemories -> tidyMemories()
        is Command.SearchHistory -> CommandResult(data = AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.HistoryHit.serializer()), searchHistory(c.query, c.limit)))
        is Command.WeixinLogin -> weixin?.login() ?: CommandResult(false, "微信助理只能接在电脑上：到电脑上的 设置 → 微信 扫码绑定")
        is Command.WeixinVerify -> weixin?.verify(c.code) ?: CommandResult(false, "这台设备没有接微信助理")
        is Command.WeixinLogout -> weixin?.logout() ?: CommandResult(false, "这台设备没有接微信助理")
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

    private fun launchFor(convId: String, block: suspend CoroutineScope.() -> Unit) {
        val set = running.getOrPut(convId) { ConcurrentHashMap.newKeySet() }
        val job = scope.launch(block = block)
        set.add(job)
        job.invokeOnCompletion { set.remove(job) }
    }

    private fun stop(convId: String) {
        running[convId]?.toList()?.forEach { it.cancel() }
    }

    private suspend fun send(c: Command.SendMessage): CommandResult {
        var conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
        if (c.text.isBlank() && c.attachmentIds.isEmpty()) return CommandResult(false, "空消息")
        // @ 了（或者开头直接喊了）不在这个对话里的成员：直接拉进来让他回答（以前会悄悄换成别人答）
        val invited = if (c.drawImage) emptyList() else outsidersCalled(c.text, conv)
        if (invited.isNotEmpty()) {
            updateState { s -> s.copy(conversations = s.conversations.map { if (it.id == conv.id) it.copy(memberIds = it.memberIds + invited.map { m -> m.id }) else it }) }
            conv = state.conversation(conv.id) ?: return CommandResult(false, "对话不存在")
            emit(Event.Notice("已把「${invited.joinToString("、") { it.name }}」拉进这个对话"))
        }
        if (!c.drawImage && conv.memberIds.isEmpty()) return CommandResult(false, "这个对话还没有 AI 成员，点右上角加几位")
        val atts = c.attachmentIds.mapNotNull { storage.fileMeta(it) }
        val userMsg = Message(newId(), conv.id, Role.USER, USER_ID, c.text.trim(), attachments = atts, createdAt = now())
        addMessage(userMsg)
        touchConversation(conv.id, c.text.ifBlank { atts.firstOrNull()?.name.orEmpty() })
        launchFor(conv.id) {
            if (c.drawImage) drawDirect(conv.id, userMsg) else {
                runTurn(conv.id, userMsg)
                // 答完以后在后台：聊天太长就压缩，攒够了就挑出值得长期记住的
                scope.launch { afterTurn(conv.id) }
            }
        }
        return CommandResult()
    }

    // ———————————————— 记账 ————————————————

    private fun recordUsage(p: ProviderConfig, model: String, u: Usage, kind: String, tag: UsageTag?, images: Int = 0) {
        runCatching {
            ledger.add(UsageRecord(now(), kind, p.id, p.name, Thinking.platformOf(p), model, u.prompt, u.cached, u.completion, images,
                tag?.memberId.orEmpty(), tag?.convId.orEmpty()))
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
            bg.failed(job, friendlyError(e, Recorder.pick(state)?.first), titleOf(convId))
        }
    }

    private fun userMsg(text: String) = buildJsonObject { put("role", "user"); put("content", text) }

    private fun speakerName(m: Message) = when {
        m.role == Role.USER -> state.profile.name
        m.senderId == PAINTER_ID -> "画图助手"
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
        val items = Recorder.parseItems(r.content) ?: run { bg.failed(BgJob.MEMORY, BackgroundWatch.UNREADABLE, titleOf(convId)); return }
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
        updateState { it.copy(memories = pinned + next) }
        return CommandResult(message = "整理好了：${items.size} 条变成 ${next.size} 条（置顶的没动）")
    }

    /** 搜所有对话的聊天内容，按命中多少、时间新旧排序。 */
    fun searchHistory(query: String, limit: Int): List<com.guixing.jixunying.model.HistoryHit> {
        val terms = Recorder.terms(query)
        if (terms.isEmpty()) return emptyList()
        val need = if (terms.size >= 4) 2 else 1
        val hits = mutableListOf<Pair<Int, com.guixing.jixunying.model.HistoryHit>>()
        for (conv in state.conversations) {
            for (m in snapshot(conv.id)) {
                if (m.status == MsgStatus.STREAMING || m.content.isBlank()) continue
                val sc = Recorder.score(m.content, terms)
                if (sc < need) continue
                hits += sc to com.guixing.jixunying.model.HistoryHit(conv.id, conv.title, m.id, speakerName(m), Recorder.snippet(stripThink(m.content), terms), m.createdAt)
            }
        }
        return hits.sortedWith(compareByDescending<Pair<Int, com.guixing.jixunying.model.HistoryHit>> { it.first }.thenByDescending { it.second.time })
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
        val independent = targets.size > 1 && conv.replyMode != ReplyMode.RELAY
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
        return all
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
                who == null -> bg.failed(BgJob.ADDRESSING, BackgroundWatch.UNREADABLE, title)
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
                it.role == Role.AI && it.status == MsgStatus.DONE && it.senderId != PAINTER_ID && stripThink(it.content).isNotBlank()
            }
            if (said.isEmpty()) return
            val open = topicsOf(convId).sortedBy { it.createdAt }.takeLast(3)
            val canOpen = said.count { it.id in independentIds } >= 2
            if (open.isEmpty() && !canOpen) return
            val (p, model) = Recorder.pick(state) ?: run { bg.failed(BgJob.STANCES, BackgroundWatch.NO_RECORDER, titleOf(convId)); return }
            fun nameOf(id: String) = state.member(id)?.name ?: "已移除的成员"
            val items = said.map { m -> StanceJudge.Said(m, nameOf(m.senderId), m.id in independentIds, calledBy[m.id], toolsNote(m)) }
            val prompt = StanceJudge.prompt(open, items, stripThink(ask.content), state.profile.name, ::nameOf, canOpen)
            val r = withContext(Dispatchers.IO) { llm.chat(p, model, listOf(userMsg(prompt)), null, null) { _, _ -> } }
            val v = StanceJudge.parse(r.content) ?: run { bg.failed(BgJob.STANCES, BackgroundWatch.UNREADABLE, titleOf(convId)); return }
            applyStances(convId, ask, said, independentIds, open, v, canOpen)
            bg.ok(BgJob.STANCES)
        }
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
                             v: StanceJudge.Verdict, canOpen: Boolean) {
        val speakers = said.map { it.senderId }.toSet()
        fun idOf(name: String): String? {
            val n = name.trim().removePrefix("@")
            if (n.isEmpty()) return null
            return state.members.filter { it.name.equals(n, ignoreCase = true) }.let { same -> same.firstOrNull { it.id in speakers } ?: same.firstOrNull() }?.id
        }
        val now = now()
        var touched = false
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
                synchronized(stanceBook) { stanceBook += StanceTopic(newId(), convId, ask.id, nt.question, options, entries, createdAt = now, updatedAt = now) }
                touched = true
            }
        }
        if (touched) saveStances(setOf(convId))
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

    /** 微信助理接在这台电脑上时由 Main.kt 设置。 */
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
        val ids = produced.map { it.id }.toSet()
        return snapshot(convId).filter { it.id in ids }
    }

    fun memberName(id: String) = state.member(id)?.name

    private suspend fun regenerate(convId: String, messageId: String): CommandResult {
        val all = snapshot(convId)
        val idx = all.indexOfFirst { it.id == messageId }
        if (idx < 0) return CommandResult(false, "消息不存在")
        val old = all[idx]
        if (old.role != Role.AI) return CommandResult(false, "只能重新生成 AI 的回答")
        val list = messages(convId)
        synchronized(list) { list.removeAll { it.id == messageId } }
        emit(Event.MessageRemoved(convId, messageId))
        save(convId)
        dropStanceMessage(convId, messageId)
        val prev = all.getOrNull(idx - 1)
        launchFor(convId) {
            if (old.senderId == PAINTER_ID) {
                prev?.takeIf { it.role == Role.USER }?.let { drawDirect(convId, it) }
            } else {
                val m = state.member(old.senderId) ?: return@launchFor
                reply(convId, m, cutoffId = prev?.id, independent = false, calledBy = null)
            }
        }
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
        if (provider.apiKey.isBlank() && !provider.baseUrl.contains("127.0.0.1") && !provider.baseUrl.contains("localhost")) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = "服务商「${provider.name}」还没填 API Key：到 设置→模型服务 填写") }
            return null
        }
        val model = provider.models.firstOrNull { it.id == member.modelId }
            ?: ModelInfo(member.modelId, vision = Presets.guessVision(member.modelId))
        // 快速 / 深度：按这家的参数切换思考；关不掉、开不了的不传，回答上也不标
        val thinking = if (llm.thinkingDropped(provider, model.id)) null else Thinking.params(Thinking.rule(provider, model.id), member.thinking)
        if (thinking != null) updateMessage(convId, msg.id, persist = false) { it.copy(modelLabel = it.modelLabel + "· " + Thinking.label(member.thinking)) }
        val hasImageModel = ImagePick.resolve(state).isNotEmpty()
        val wantSearch = conv.webSearch
        val toolsOk = model.tools && !llm.toolsDropped(provider, model.id)
        // 平台有官方内置搜索就用内置的（Kimi、智谱、千问），否则用我们自己的搜索工具
        val native = if (wantSearch && toolsOk) nativeSearchOf(provider, model.id) else NativeSearch.NONE
        val fnSearch = wantSearch && native == NativeSearch.NONE
        val memOn = state.settings.memory.enabled
        val docsOn = docsUsable()
        val useTools = toolsOk && (wantSearch || hasImageModel || memOn || docsOn)

        try {
            val working = mutableListOf<JsonObject>()
            val sys = Prompts.system(state, conv, member, canSearch = wantSearch, canDraw = hasImageModel && useTools,
                canSeeImages = model.vision, independentRound = independent, calledBy = calledBy,
                nativeSearch = native != NativeSearch.NONE, hasImageModel = hasImageModel,
                memories = if (memOn) Recorder.forPrompt(state.memories) else emptyList(), canRemember = memOn && useTools,
                docCount = if (docsOn && useTools) docs.count else 0, deviceLabel = if (isPhone) "这台手机" else "这台电脑")
            working += buildJsonObject { put("role", "system"); put("content", sys) }
            working += buildHistory(convId, member, cutoffId, excludeId = msg.id, vision = model.vision)

            val sources = mutableListOf<SearchSource>()
            if (wantSearch && !useTools) {
                // 模型不会调工具：替它搜一次
                val q = lastUserText(convId, cutoffId).take(120)
                if (q.isNotBlank()) {
                    val step = runSearch(q, sources)
                    updateMessage(convId, msg.id, persist = false) { it.copy(tools = it.tools + step.first) }
                    if (step.second.isNotEmpty()) working.add(1, buildJsonObject { put("role", "system"); put("content", Prompts.searchDigest(q, step.second)) })
                }
            }

            val tools = if (useTools) toolDefs(fnSearch, fetch = wantSearch, draw = hasImageModel, native = native, memory = memOn, docs = docsOn) else null
            val extra = if (native == NativeSearch.QWEN) buildJsonObject {
                put("enable_search", true)
                putJsonObject("search_options") {
                    put("enable_source", true); put("enable_citation", true); put("citation_format", "[<number>]")
                }
            } else null
            val nativeLabel = when (native) {
                NativeSearch.KIMI -> "Kimi 官方联网搜索"
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
                if (r.toolCalls.isEmpty() || rounds >= 8) break
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
                for (tc in r.toolCalls) {
                    // 一次回答最多用 8 次工具（Kimi 官方搜索不算），超过就让它根据已有结果直接回答
                    val result = if (!tc.name.startsWith("$") && toolUses >= MAX_TOOL_USES)
                        "这次回答已经用了 $MAX_TOOL_USES 次工具，不能再搜了。请直接根据前面的结果回答；没查到的就如实说。"
                    else { if (!tc.name.startsWith("$")) toolUses++; runTool(convId, msg.id, tc, sources) }
                    working += buildJsonObject {
                        put("role", "tool"); put("tool_call_id", tc.id)
                        if (tc.name.startsWith("$")) put("name", tc.name)
                        put("content", result)
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

    private enum class NativeSearch { NONE, KIMI, ZHIPU, QWEN }

    private fun nativeSearchOf(p: ProviderConfig, model: String): NativeSearch {
        if (state.settings.search.mode != com.guixing.jixunying.model.SearchMode.AUTO) return NativeSearch.NONE
        if (llm.nativeSearchDropped(p, model)) return NativeSearch.NONE
        val u = p.baseUrl.lowercase()
        return when {
            p.presetId.startsWith("moonshot") || "moonshot" in u || "api.kimi" in u -> NativeSearch.KIMI
            p.presetId == "zhipu" || p.presetId == "zai" || "bigmodel.cn" in u || "api.z.ai" in u -> NativeSearch.ZHIPU
            p.presetId.startsWith("dashscope") || p.presetId == "qianwen" || "dashscope" in u || "qianwenaiapi" in u || "maas.aliyuncs" in u -> NativeSearch.QWEN
            else -> NativeSearch.NONE
        }
    }

    private fun toolDefs(search: Boolean, fetch: Boolean, draw: Boolean, native: NativeSearch, memory: Boolean = false, docs: Boolean = false): JsonArray = buildJsonArray {
        when (native) {
            NativeSearch.KIMI -> add(buildJsonObject {
                put("type", "builtin_function")
                putJsonObject("function") { put("name", "\$web_search") }
            })
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
            fn("fetch_url", "打开一个网页，读取正文文字。搜索摘要不够详细、或用户给了链接时使用。",
                buildJsonObject { put("url", prop("完整网址，http 或 https 开头")) }, listOf("url"))
        }
        if (draw) {
            fn("generate_image", "根据文字描述画一张图，画好后会直接显示给用户。",
                buildJsonObject {
                    put("prompt", prop("画面描述：主体、风格、构图、光线、色彩，越具体越好"))
                    put("size", prop("可选，图片尺寸，如 1024x1024、1024x1792"))
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
        if (docs) {
            fn("search_documents", "搜索用户这台设备上的文档（PDF、Word、Excel、PPT、文本等），按文件名和内容匹配。" +
                "用户问到他自己的文件、资料、合同、报告、表格、笔记时用。关键词留空返回最近修改的文档。",
                buildJsonObject { put("query", prop("关键词：文件名里的词或内容里的词，可以留空")) }, emptyList())
            fn("read_document", "读一个文档的文字内容。path 用 search_documents 返回的完整路径。一次最多返回约 1 万字，没读完会提示 start 接着读。",
                buildJsonObject {
                    put("path", prop("文档的完整路径"))
                    put("start", prop("可选，从第几个字开始读，默认 0"))
                }, listOf("path"))
        }
    }

    private suspend fun runSearch(query: String, sources: MutableList<SearchSource>): Pair<ToolStep, List<SearchSource>> = try {
        val res = search.search(query, state.settings.search)
        sources += res
        ToolStep("search", query, res, ok = true) to res
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ToolStep("search", query, ok = false, note = friendlyError(e)) to emptyList()
    }

    private suspend fun runTool(convId: String, msgId: String, tc: ToolCall, sources: MutableList<SearchSource>): String {
        val args = runCatching { Json.parse(tc.arguments.ifBlank { "{}" }).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return when (tc.name) {
            // Kimi 官方搜索：搜索在 Kimi 服务器上做，客户端只要把参数原样交回去
            "\$web_search" -> {
                if (sources.isEmpty()) updateMessage(convId, msgId, persist = false) { m ->
                    if (m.tools.any { it.input == "Kimi 官方联网搜索" }) m else m.copy(tools = m.tools + ToolStep("search", "Kimi 官方联网搜索"))
                }
                tc.arguments
            }
            "web_search" -> {
                val q = args.str("query").orEmpty().ifBlank { return "参数错误：缺少 query" }
                val start = sources.size
                val (step, res) = runSearch(q, sources)
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + step) }
                if (!step.ok) "搜索失败：${step.note}。请如实告诉用户没搜到，不要编造。"
                else if (res.isEmpty()) "没有搜到结果。请如实告诉用户。"
                else res.mapIndexed { i, r -> "[${start + i + 1}] ${r.title}\n${r.url}\n${r.snippet}" }.joinToString("\n\n")
            }
            "fetch_url" -> {
                val url = args.str("url").orEmpty()
                if (!url.startsWith("http")) return "参数错误：url 要以 http 开头"
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
            "remember" -> {
                val text = args.str("text").orEmpty()
                val kind = args.str("kind")?.trim()?.takeIf { it in Recorder.kinds } ?: "关于我"
                val res = rememberNow(text, kind, state.conversation(convId)?.title.orEmpty())
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("memory", text.trim().take(80), ok = res.startsWith("已"))) }
                res
            }
            "search_history" -> {
                val q = args.str("query").orEmpty().ifBlank { return "参数错误：缺少 query" }
                val hits = searchHistory(q, 8)
                updateMessage(convId, msgId, persist = false) { it.copy(tools = it.tools + ToolStep("history", q, ok = hits.isNotEmpty())) }
                if (hits.isEmpty()) "以前的聊天里没找到和「$q」相关的内容。如实告诉用户。"
                else hits.joinToString("\n\n") { h -> "[${h.convTitle} · ${dayOf(h.time)} · ${h.sender}] ${h.snippet}" }
            }
            "search_documents" -> {
                val q = args.str("query").orEmpty().trim()
                val hits = withContext(Dispatchers.IO) { if (q.isBlank()) docs.recent(10) else docs.search(q, 10) }
                updateMessage(convId, msgId, persist = false) {
                    it.copy(tools = it.tools + ToolStep("doc", if (q.isBlank()) "看了最近的文档" else "搜文档「$q」· ${hits.size} 个", ok = hits.isNotEmpty()))
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
            "read_document" -> {
                val p = args.str("path").orEmpty().ifBlank { return "参数错误：缺少 path" }
                val e = docs.find(p) ?: return "文档库里没有「$p」。只能读 search_documents 找到的文件，先搜一下。"
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
            else -> "没有这个工具：${tc.name}"
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
                else -> state.member(m.senderId)?.name ?: "已移除的成员"
            }
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
