package com.guixing.jixunying.engine

import com.guixing.jixunying.client.Backend
import com.guixing.jixunying.client.ClientStore
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.AttachmentKind
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.PairedDevice
import com.guixing.jixunying.model.Presets
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.ReplyMode
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SearchSource
import com.guixing.jixunying.model.ToolStep
import com.guixing.jixunying.model.USER_ID
import com.guixing.jixunying.model.Usage
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * 引擎：电脑和手机各有一个，都能单独用。数据、Key、模型调用都在本机。
 * 电脑上的引擎还会通过 RelayHost 接受配对手机的遥控。
 */
class Engine(private val storage: Storage, private val isPhone: Boolean = false) : Backend {
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
    }

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
            val r = withContext(Dispatchers.IO) {
                llm.chat(p, c.modelId, listOf(buildJsonObject { put("role", "user"); put("content", "用一句话（不超过 20 个字）介绍你是谁、什么模型。") }), null, null) { _, _ -> }
            }
            val said = r.content.ifBlank { r.reasoning }.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            val secs = "%.1f".format(r.usage.millis / 1000.0)
            if (said.isBlank()) CommandResult(false, "接口通了（$secs 秒），但模型「${c.modelId}」没有返回文字。检查一下模型名是否写对。")
            else CommandResult(message = "连通了（$secs 秒，模型 ${c.modelId}）。它说：" + said.take(120))
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
        is Command.SaveSettings -> {
            val before = state.settings.relay
            updateState { s ->
                val oldKeys = s.settings.search.apiKeys
                val keys = c.settings.search.apiKeys.mapValues { (k, v) -> if (isMaskedKey(v)) oldKeys[k].orEmpty() else v }
                s.copy(settings = c.settings.copy(search = c.settings.search.copy(apiKeys = keys)))
            }
            if (before.enabled != state.settings.relay.enabled || before.brokers != state.settings.relay.brokers) onRelaySettingsChanged?.invoke()
            CommandResult()
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
            CommandResult()
        }
        is Command.Regenerate -> regenerate(c.convId, c.messageId)
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
            val bundle = ConfigBundle(s.providers, s.members, s.profile, s.settings.search, s.settings.imageGen, s.settings.proxy)
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
        val conv = state.conversation(c.convId) ?: return CommandResult(false, "对话不存在")
        if (c.text.isBlank() && c.attachmentIds.isEmpty()) return CommandResult(false, "空消息")
        if (!c.drawImage && conv.memberIds.isEmpty()) return CommandResult(false, "这个对话还没有 AI 成员，点右上角加几位")
        val atts = c.attachmentIds.mapNotNull { storage.fileMeta(it) }
        val userMsg = Message(newId(), conv.id, Role.USER, USER_ID, c.text.trim(), attachments = atts, createdAt = now())
        addMessage(userMsg)
        touchConversation(conv.id, c.text.ifBlank { atts.firstOrNull()?.name.orEmpty() })
        launchFor(conv.id) {
            if (c.drawImage) drawDirect(conv.id, userMsg) else runTurn(conv.id, userMsg)
        }
        return CommandResult()
    }

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

    private suspend fun runTurn(convId: String, trigger: Message) {
        val conv = state.conversation(convId) ?: return
        val members = conv.memberIds.mapNotNull(state::member)
        if (members.isEmpty()) return
        val everyone = Regex("@(所有人|全体成员|全体|大家|all)", RegexOption.IGNORE_CASE).containsMatchIn(trigger.content)
        val mentioned = parseMentions(trigger.content, members)
        val targets = when {
            everyone -> members
            mentioned.isNotEmpty() -> mentioned
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

        // AI 之间互相 @：被 @ 的接着说，最多接力 maxMentionChain 轮
        var depth = 0
        while (produced.isNotEmpty() && depth < state.settings.maxMentionChain) {
            depth++
            val next = mutableListOf<Message>()
            val currentMembers = state.conversation(convId)?.memberIds?.mapNotNull(state::member) ?: return
            for (msg in produced) {
                val speaker = state.member(msg.senderId) ?: continue
                val called = parseMentions(msg.content, currentMembers).filter { it.id != speaker.id }
                for (m in called) reply(convId, m, null, independent = false, calledBy = speaker.name)?.let(next::add)
            }
            produced = next
        }
    }

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

    private fun imageGenTarget(): Pair<ProviderConfig, String>? {
        val ig = state.settings.imageGen
        val p = state.provider(ig.providerId) ?: return null
        if (ig.modelId.isBlank() || !p.enabled) return null
        return p to ig.modelId
    }

    private suspend fun drawDirect(convId: String, userMsg: Message) {
        val msg = Message(newId(), convId, Role.AI, PAINTER_ID, status = MsgStatus.STREAMING, createdAt = now())
        addMessage(msg)
        val target = imageGenTarget()
        if (target == null) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = "还没选画图模型：到 设置→画图 选一个（比如智谱 cogview、豆包 seedream、硅基流动 Kolors）") }
            return
        }
        try {
            val att = paint(target, userMsg.content)
            updateMessage(convId, msg.id) {
                it.copy(attachments = listOf(att.first), content = att.second?.let { r -> "按描述画好了。\n\n> $r" } ?: "按描述画好了。",
                    status = MsgStatus.DONE, modelLabel = "${target.second}（${target.first.name}）")
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { updateMessage(convId, msg.id) { it.copy(status = MsgStatus.STOPPED) } }
            throw e
        } catch (e: Throwable) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = friendlyError(e)) }
        }
    }

    private suspend fun paint(target: Pair<ProviderConfig, String>, prompt: String, size: String? = null): Pair<Attachment, String?> {
        val r = images.generate(target.first, target.second, prompt, size ?: state.settings.imageGen.size)
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
        if (provider.apiKey.isBlank() && !provider.baseUrl.contains("127.0.0.1") && !provider.baseUrl.contains("localhost")) {
            updateMessage(convId, msg.id) { it.copy(status = MsgStatus.ERROR, error = "服务商「${provider.name}」还没填 API Key：到 设置→模型服务 填写") }
            return null
        }
        val model = provider.models.firstOrNull { it.id == member.modelId }
            ?: ModelInfo(member.modelId, vision = Presets.guessVision(member.modelId))
        val drawTarget = imageGenTarget()
        val wantSearch = conv.webSearch
        val toolsOk = model.tools && !llm.toolsDropped(provider, model.id)
        // 平台有官方内置搜索就用内置的（Kimi、智谱、千问），否则用我们自己的搜索工具
        val native = if (wantSearch && toolsOk) nativeSearchOf(provider, model.id) else NativeSearch.NONE
        val fnSearch = wantSearch && native == NativeSearch.NONE
        val useTools = toolsOk && (wantSearch || drawTarget != null)

        try {
            val working = mutableListOf<JsonObject>()
            val sys = Prompts.system(state, conv, member, canSearch = wantSearch, canDraw = drawTarget != null && useTools,
                canSeeImages = model.vision, independentRound = independent, calledBy = calledBy,
                nativeSearch = native != NativeSearch.NONE)
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

            val tools = if (useTools) toolDefs(fnSearch, fetch = wantSearch, draw = drawTarget != null, native = native) else null
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
            while (true) {
                rounds++
                val before = snapshot(convId).firstOrNull { it.id == msg.id }?.content?.length ?: 0
                val r = llm.chat(provider, model.id, working, member.temperature, tools, extra, onSources) { c, rs -> appendDelta(convId, msg.id, c, rs) }
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
                    else { if (!tc.name.startsWith("$")) toolUses++; runTool(convId, msg.id, tc, sources, drawTarget) }
                    working += buildJsonObject {
                        put("role", "tool"); put("tool_call_id", tc.id)
                        if (tc.name.startsWith("$")) put("name", tc.name)
                        put("content", result)
                    }
                }
            }
            val done = updateMessage(convId, msg.id) {
                it.copy(content = cleanReply(it.content, member), status = MsgStatus.DONE, usage = usage)
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

    private fun toolDefs(search: Boolean, fetch: Boolean, draw: Boolean, native: NativeSearch): JsonArray = buildJsonArray {
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

    private suspend fun runTool(convId: String, msgId: String, tc: ToolCall, sources: MutableList<SearchSource>, drawTarget: Pair<ProviderConfig, String>?): String {
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
                val target = drawTarget ?: return "画图模型没有配置"
                val prompt = args.str("prompt").orEmpty().ifBlank { return "参数错误：缺少 prompt" }
                try {
                    val (att, _) = paint(target, prompt, args.str("size"))
                    updateMessage(convId, msgId) { it.copy(attachments = it.attachments + att, tools = it.tools + ToolStep("image", prompt)) }
                    "图片已生成，并已经显示给用户。"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    updateMessage(convId, msgId) { it.copy(tools = it.tools + ToolStep("image", prompt, ok = false, note = friendlyError(e))) }
                    "画图失败：${friendlyError(e)}。请告诉用户。"
                }
            }
            else -> "没有这个工具：${tc.name}"
        }
    }

    private fun lastUserText(convId: String, cutoffId: String?): String {
        val all = snapshot(convId)
        val end = cutoffId?.let { id -> all.indexOfFirst { it.id == id } + 1 }?.takeIf { it > 0 } ?: all.size
        return all.subList(0, end).lastOrNull { it.role == Role.USER }?.content.orEmpty()
    }

    /** 把聊天记录转成模型的 messages。别人的话标上「【名字】」，自己的话是 assistant。 */
    private fun buildHistory(convId: String, me: Member, cutoffId: String?, excludeId: String, vision: Boolean): List<JsonObject> {
        val all = snapshot(convId)
        val end = cutoffId?.let { id -> all.indexOfFirst { it.id == id } + 1 }?.takeIf { it > 0 } ?: all.size
        // 还在生成中的回答（别人这一轮没说完的半句话、正在画的图）不算进上下文
        val usable = all.subList(0, end).filter {
            it.id != excludeId && it.role != Role.SYSTEM && it.status != MsgStatus.ERROR && it.status != MsgStatus.STREAMING &&
                (it.content.isNotBlank() || it.attachments.isNotEmpty())
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

    fun friendlyError(e: Throwable, p: ProviderConfig? = null): String {
        if (e is ApiException) {
            val body = e.body.take(300)
            val lower = body.lowercase()
            val hint = when {
                e.status == 401 || (e.status == 403 && "key" in lower) || "invalid api key" in lower || "login fail" in lower ||
                    "unauthorized" in lower || "authentication" in lower || "令牌" in body -> "API Key 不对或已失效"
                e.status == 402 || "insufficient" in lower || "余额" in body || "balance" in lower || "quota" in lower -> "余额或额度不足"
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
