package com.guixing.jixunying.ui

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.HostFeatures
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 整段对话导出成 Markdown 文件（2026-10-24，照 Cherry Studio「导出话题」）：标题、成员、每条消息（谁、什么时候、正文），
 * 附件只写文件名；推理过程不导出。记录没加载过的先取一次。
 */
fun exportConversationMarkdown(ctl: AppController, state: AppState, c: Conversation) {
    ctl.scope.launch {
        if (!ctl.backend.store.hasMessages(c.id)) ctl.backend.call(Command.LoadMessages(c.id))
        val msgs = ctl.backend.store.messages.value[c.id].orEmpty()
        if (msgs.isEmpty()) { ctl.toast("这个对话还没有消息"); return@launch }
        val md = conversationMarkdown(state, c, msgs)
        val name = c.title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40).ifBlank { "对话" } + ".md"
        if (ctl.platform.saveFile(name, md.toByteArray())) ctl.toast("已导出《$name》")
    }
}

fun conversationMarkdown(state: AppState, c: Conversation, msgs: List<Message>): String = buildString {
    append("# ").append(c.title).append("\n\n")
    val names = c.memberIds.mapNotNull { state.member(it)?.name }
    if (names.isNotEmpty()) append("成员：").append(names.joinToString("、")).append("\n\n")
    for (m in msgs) {
        val who = if (m.role == Role.USER) state.profile.name.ifBlank { "我" } else state.member(m.senderId)?.name ?: "AI"
        append("## ").append(who).append(" · ").append(formatTime(m.createdAt)).append("\n\n")
        append(splitThink(m.content).first.trim()).append("\n\n")
        m.attachments.forEach { a -> append("- 附件：").append(a.name).append("\n") }
        if (m.attachments.isNotEmpty()) append("\n")
    }
}

/** 正在发给电脑的对话（同一个对话别连点两次发两份）。 */
private val sendingToComputer = mutableSetOf<String>()

/** 一段多大：和电脑传文件给手机一样（经公共中转加密后约 6 片）。 */
private const val EXPORT_PART = 512 * 1024

/** 导出编号里的时间（北京时间，到秒，免得同一分钟发两次撞名）：2026-11-5-1540-12。 */
private fun exportStamp(now: Long): String {
    val bj = now + 8 * 3_600_000L
    val minOfDay = bj.mod(86_400_000L) / 60_000
    val sec = bj.mod(60_000L) / 1000
    fun two(n: Long) = n.toString().padStart(2, '0')
    return dateText(now).replace("年", "-").replace("月", "-").removeSuffix("日") + "-" + two(minOfDay / 60) + two(minOfDay % 60) + "-" + two(sec)
}

/** 发给电脑的结果：error 不为空就是没发完。 */
class SentToComputer(val folder: String, val messages: Int, val files: Int, val skipped: List<String>, val error: String? = null)

/**
 * 把一个对话发给电脑（核心，界面和测试共用）：附件原文件、对话.json（全部原始记录）、对话.md，一个文件分段发（每段 512KB），
 * 一段没成功再试两次。local 是这台手机自己的引擎，computer 是配对的电脑（经加密中转）。
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
suspend fun sendConversationFiles(local: com.guixing.jixunying.client.Backend, computer: com.guixing.jixunying.client.Backend,
                                  state: AppState, c: Conversation, deviceName: String, now: Long = nowMillis()): SentToComputer {
    if (!local.store.hasMessages(c.id)) local.call(Command.LoadMessages(c.id))
    val msgs = local.store.messages.value[c.id].orEmpty()
    if (msgs.isEmpty()) return SentToComputer("", 0, 0, emptyList(), "这个对话还没有消息")
    val exportId = exportStamp(now) + "-" + c.title.replace(Regex("""\s+"""), " ").trim().take(30)
    // 附件起个不重名的文件名（序号在前，看得出先后）
    val atts = msgs.flatMap { it.attachments + it.quoted }.distinctBy { it.id }
    val fileNames = LinkedHashMap<String, String>()
    atts.forEachIndexed { i, a -> fileNames[a.id] = "附件-" + (i + 1).toString().padStart(2, '0') + "-" + a.name.ifBlank { a.id } }
    val members = (c.memberIds + msgs.map { it.senderId }).distinct().mapNotNull { state.member(it) }
    val json = buildJsonObject {
        put("说明", "AI集训营 手机发给电脑的对话：conversation 是对话设定，members 是成员（不含 Key），messages 是全部原始消息（content 里 <think> 那段是推理过程），attachmentFiles 是附件编号对应这个文件夹里的文件名")
        put("device", deviceName)
        put("profileName", state.profile.name)
        put("exportedAt", now)
        put("conversation", AppJson.encodeToJsonElement(Conversation.serializer(), c))
        putJsonArray("members") {
            members.forEach { m ->
                add(buildJsonObject {
                    put("id", m.id); put("name", m.name); put("modelId", m.modelId)
                    put("provider", state.provider(m.providerId)?.name ?: m.providerId)
                    put("thinking", m.thinking.name)
                })
            }
        }
        putJsonObject("attachmentFiles") { fileNames.forEach { (id, n) -> put(id, n) } }
        put("messages", AppJson.encodeToJsonElement(ListSerializer(Message.serializer()), msgs))
    }
    var folder = ""
    val skipped = mutableListOf<String>()

    suspend fun send(name: String, bytes: ByteArray, end: Boolean): String? {
        var offset = 0
        do {
            val n = minOf(EXPORT_PART, bytes.size - offset)
            val last = offset + n >= bytes.size
            val part = Command.SaveDeviceExportPart(exportId, name, offset.toLong(), kotlin.io.encoding.Base64.encode(bytes, offset, offset + n), last, end && last)
            var r = computer.call(part)
            var tries = 1
            while (!r.ok && tries < 3) { tries++; r = computer.call(part) }
            if (!r.ok) return r.message.ifBlank { "电脑没回话" }
            folder = r.message
            offset += n
        } while (offset < bytes.size)
        return null
    }

    var sentFiles = 0
    for (a in atts) {
        val bytes = local.fileBytes(a.id)
        if (bytes == null) { skipped += a.name + "（手机上已经没有这个文件）"; continue }
        send(fileNames.getValue(a.id), bytes, end = false)?.let { why -> return SentToComputer(folder, msgs.size, sentFiles, skipped, "${a.name}：$why") }
        sentFiles++
    }
    send("对话.json", AppJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), json).encodeToByteArray(), end = false)
        ?.let { why -> return SentToComputer(folder, msgs.size, sentFiles, skipped, why) }
    send("对话.md", conversationMarkdown(state, c, msgs).encodeToByteArray(), end = true)
        ?.let { why -> return SentToComputer(folder, msgs.size, sentFiles, skipped, why) }
    return SentToComputer(folder, msgs.size, sentFiles, skipped)
}

/**
 * 手机把整个对话发给配对的电脑（2026-11-05 用户：「三星的，你加一个吧」——想让电脑这边看手机里和阿雯的聊天，普通聊天两边不同步）。
 * 发三样：对话.md（好读）、对话.json（全部原始记录：推理过程、工具步骤、出错信息、成员和模型都在，不含任何 Key）、附件原文件（语音、图片、视频……）。
 * 电脑存在 <数据目录>\对话导出\手机-<设备名>-<时间>-<标题>\，到了电脑上会弹一句。
 * 电脑太老（状态里没报 HostFeatures.CONV_EXPORT）就不发，直接说，不让人干等。
 */
fun sendConversationToComputer(ctl: AppController, state: AppState, c: Conversation) {
    val remote = ctl.hub.remote.value ?: run { ctl.toast("还没配对电脑：设置 → 连接电脑"); return }
    when (val conn = remote.conn.value) {
        is ConnState.Connected -> Unit
        is ConnState.Failed -> { ctl.toast("没连上电脑：${conn.reason}"); return }
        else -> { ctl.toast("正在找电脑，还没连上，等几秒再试"); return }
    }
    if (HostFeatures.CONV_EXPORT !in remote.store.state.value.features) {
        ctl.toast("电脑上的 AI集训营 太旧，收不了对话；先把电脑更新了"); return
    }
    if (!sendingToComputer.add(c.id)) { ctl.toast("这个对话正在发"); return }
    ctl.scope.launch {
        try {
            ctl.toast("正在发给电脑…")
            val r = sendConversationFiles(ctl.hub.local, remote, state, c, ctl.platform.deviceName)
            ctl.toast(when {
                r.error != null -> "没发完：${r.error}"
                else -> "发给电脑了（${r.messages} 条消息、${r.files} 个附件），存在电脑的 ${r.folder}" +
                    if (r.skipped.isEmpty()) "" else "；没发的：" + r.skipped.joinToString("、")
            })
        } finally {
            sendingToComputer.remove(c.id)
        }
    }
}
