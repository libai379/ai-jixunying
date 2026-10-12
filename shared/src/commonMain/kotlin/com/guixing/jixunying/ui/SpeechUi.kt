package com.guixing.jixunying.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.client.Backend
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.ImagePick
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SpeechAudio
import com.guixing.jixunying.model.SpeechModels
import com.guixing.jixunying.model.SpeechPick
import com.guixing.jixunying.model.SpeechPlan
import com.guixing.jixunying.model.SpeechSettings
import com.guixing.jixunying.model.Thinking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 读出来（docs/向量和语音设计说明.md 第 5 节，2026-10-12）。
 *
 * 每次都走本机引擎 ctl.hub.local：手机遥控电脑时，读出来和听写也用手机自己的服务商和 Key，
 * 音频不进中转（设计说明第 5 节的最后一条），所以这里不用 ctl.run / ctl.backend。
 *
 * 一条回答：先发 SpeakPlan（引擎不联网，只挑服务商、把文字切好段），再一段一段发
 * Speak(段, providerId, modelId, prepared = true)，放这一段的同时先合成下一段（最多提前一段）。
 * 点「停止」= 取消整个协程：正在放的停住，后面的段也不再发（不会再花钱）。
 */
class SpeechPlayer(private val ctl: AppController) {

    /** 正在读哪条消息；没在读就是 null。 */
    var readingId by mutableStateOf<String?>(null)
        private set

    /** 在等第一段合成（按钮旁边显示「合成中…」）。 */
    var preparing by mutableStateOf(false)
        private set

    fun isReading(messageId: String) = readingId == messageId

    /** 点一下「读出来」/「停止」：同一条再点一下就是停。 */
    fun toggle(messageId: String, text: String) {
        if (readingId == messageId) stop() else read(messageId, text)
    }

    /** 手动读一条：正在读的、排着队的都停掉，换成这条。 */
    fun read(messageId: String, text: String) {
        stop()
        queue += messageId to text
        start()
    }

    /** 停下来：正在放的停住，后面排着的都不读了。 */
    fun stop() {
        queue.clear()
        job?.cancel()
        job = null
        readingId = null
        preparing = false
    }

    // ———————————————— 回答完自动读 ————————————————

    private var watched = ""
    private val known = HashMap<String, MsgStatus>()

    /**
     * 聊天界面的消息变了就叫一下（ChatScreen 里盯着当前打开的这个对话）。
     * 只有「本来在流式回答、这会儿答完了」和「刚出现就已经答完了」的会读；打开对话时已有的旧回答不读
     * （第一次看到某个对话时只记下有什么）。几位成员接连答完就排着队一条一条读。
     */
    fun onMessages(convId: String, messages: List<Message>, enabled: Boolean) {
        if (convId != watched) {
            watched = convId
            known.clear()
        }
        if (known.isEmpty()) {
            messages.forEach { known[it.id] = it.status } // 刚打开这个对话：已有的回答不读
            return
        }
        for (m in messages) {
            val before = known[m.id]
            known[m.id] = m.status
            if (!enabled || m.role != Role.AI || m.status != MsgStatus.DONE) continue
            if (before == MsgStatus.DONE || before == MsgStatus.ERROR || before == MsgStatus.STOPPED) continue
            val text = splitThink(m.content).first.trim()
            if (text.isBlank()) continue
            if (readingId == m.id || queue.any { it.first == m.id }) continue
            queue += m.id to text
        }
        if (queue.isNotEmpty()) start()
    }

    // ———————————————— 一条一条读 ————————————————

    private val queue = ArrayDeque<Pair<String, String>>()
    private var job: Job? = null

    private fun start() {
        if (job?.isActive == true) return
        job = ctl.scope.launch {
            while (true) {
                val next = queue.removeFirstOrNull() ?: break
                readOne(next.first, next.second)
            }
            readingId = null
            preparing = false
        }
    }

    private suspend fun readOne(messageId: String, text: String) {
        if (!ctl.platform.canPlayAudio) {
            fail("这台设备不能放声音，读不出来")
            return
        }
        val local = ctl.hub.local
        readingId = messageId
        preparing = true
        try {
            val r = local.call(Command.SpeakPlan(text))
            val plan = if (r.ok) runCatching { AppJson.decodeFromString(SpeechPlan.serializer(), r.data) }.getOrNull() else null
            if (plan == null || plan.chunks.isEmpty()) {
                fail(r.message.ifBlank { "这段文字读不出来（引擎没有返回内容）" })
                return
            }
            speak(local, plan)
        } catch (e: CancellationException) {
            throw e // 点了「停止」
        } catch (e: Throwable) {
            fail("读出来失败了：${reason(e)}")
        } finally {
            if (readingId == messageId) {
                readingId = null
                preparing = false
            }
        }
    }

    private suspend fun speak(local: Backend, plan: SpeechPlan) {
        coroutineScope {
            var pending: Deferred<Result<List<ByteArray>>>? = null
            // 段号和这一段文字都按每一轮取好再交给协程（提前合成的那一段跑起来时，轮次可能已经变了）
            for ((step, text) in plan.chunks.withIndex()) {
                val cur = pending ?: async { synth(local, plan, text) }
                // 一边放这一段，一边先把下一段合成起来（最多提前一段，点了「停止」就不会再往下花钱）
                pending = plan.chunks.getOrNull(step + 1)?.let { next -> async { synth(local, plan, next) } }
                val audio = cur.await()
                if (step == 0) preparing = false
                val parts = audio.getOrElse { e ->
                    // 提前合成的下一段也不要了（不然 coroutineScope 要等它跑完才返回）
                    pending?.cancel()
                    fail("用 ${plan.label.ifBlank { "选中的服务商" }} 读出来失败了：${reason(e)}")
                    return@coroutineScope
                }
                // 一段文字引擎可能切成几个 wav，按顺序放完
                for (b in parts) {
                    try {
                        ctl.platform.playWav(b)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        pending?.cancel()
                        fail("放不出来：${reason(e)}")
                        return@coroutineScope
                    }
                }
            }
        }
    }

    /** 让引擎合成一段，返回一段或多段 wav。用 Result 而不是抛异常：async 里抛会把旁边正在放的那段也取消掉。 */
    private suspend fun synth(local: Backend, plan: SpeechPlan, text: String): Result<List<ByteArray>> = try {
        val r = local.call(Command.Speak(text, plan.providerId, plan.modelId, prepared = true))
        if (!r.ok) {
            Result.failure(IllegalStateException(r.message.ifBlank { "服务商没有返回音频" }))
        } else {
            val audio = AppJson.decodeFromString(SpeechAudio.serializer(), r.data)
            val parts = audio.segments.mapNotNull { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) Result.failure(IllegalStateException("服务商没有返回音频")) else Result.success(parts)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun fail(text: String) {
        queue.clear()
        readingId = null
        preparing = false
        ctl.toast(text)
    }

    private fun reason(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "出错了"
}

// ———————————————— 设置 → 语音 ————————————————

/**
 * 这一页读写的是「这台设备自己的」设置（ctl.hub.local，见 AppController.updateLocalSettings）：
 * 电脑上本机就是引擎本身，和别的设置页一样；手机上遥控电脑时，读出来和听写也用手机上的服务商。
 */
@Composable
fun SpeechPage(ctl: AppController) {
    val platform = LocalPlatform.current
    val state by ctl.hub.local.store.state.collectAsState()
    val sp = state.settings.speech
    val ttsCands = remember(state.providers) { state.providers.filter { SpeechModels.supportsTts(it) && ImagePick.usable(it) } }
    val asrCands = remember(state.providers) { state.providers.filter { SpeechModels.supportsAsr(it) && ImagePick.usable(it) } }
    val ttsAuto = SpeechPick.isAuto(state, tts = true)
    val asrAuto = SpeechPick.isAuto(state, tts = false)
    val ttsChosen = SpeechPick.forTts(state)
    val asrChosen = SpeechPick.forAsr(state)
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    fun save(f: (SpeechSettings) -> SpeechSettings) = ctl.updateLocalSettings { it.copy(speech = f(it.speech)) }

    PageHeader(
        "语音",
        "读出来、听写会把文字或录音发给下面选的服务商，按它的价格收费。" +
            if (!platform.isDesktop) "这里是这台手机自己的设置：遥控电脑时，读出来和听写也用手机上的服务商。" else "",
    )

    if (ttsCands.isEmpty()) {
        SectionCard {
            Text("还没有能读出来的服务商", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("读出来、听写用的就是「模型服务」里已经填了 Key 的那几家，不用另外申请：小米（mimo）、MiniMax、智谱、千问。到「模型服务」加上任意一家就行。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { ctl.openSettings(SettingsTab.PROVIDERS) }) { Text("去添加服务商") }
        }
        return
    }

    // ———— 读出来 ————
    SectionCard {
        FieldLabel("读出来用哪家", "回答下面的「读出来」按钮用它")
        RadioRow(ttsAuto, "自动（推荐）", "按 小米 → MiniMax → 智谱 → 千问 的顺序挑已经启用、填了 Key 的那家。") {
            save { it.copy(ttsProviderId = "", ttsModel = "") }
        }
        ttsCands.forEach { p ->
            val model = SpeechModels.ttsDefaultOf(p).orEmpty()
            RadioRow(!ttsAuto && sp.ttsProviderId == p.id, p.name, "$model · ${SpeechPick.noteOf(p, model)}") {
                // 换了一家：上一家的音色（比如小米的「冰糖」）这家不认，清掉用这家的默认音色
                save { it.copy(ttsProviderId = p.id, ttsModel = "", ttsVoice = if (it.ttsProviderId == p.id) it.ttsVoice else "") }
            }
        }
        if (ttsAuto) {
            val c = ttsChosen
            Text(
                if (c == null) "现在没有能用的服务商：去「模型服务」添加一家并填上 Key。"
                else "现在会用 ${c.modelId}（${c.note}）",
                style = MaterialTheme.typography.labelSmall,
                color = if (c == null) MaterialTheme.colorScheme.error else Ext.c.subtle,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 指定了服务商才能挑模型；自动挑的时候模型由自动挑中的那家决定
        if (!ttsAuto) {
            val p = state.provider(sp.ttsProviderId)
            val list = p?.let { SpeechModels.ttsListOf(it) }.orEmpty()
            if (p != null && list.size > 1) {
                Spacer(Modifier.height(8.dp))
                FieldLabel("用哪个模型")
                val cur = sp.ttsModel.ifBlank { SpeechModels.ttsDefaultOf(p).orEmpty() }
                list.forEach { m -> RadioRow(cur == m, m, SpeechPick.noteOf(p, m)) { save { it.copy(ttsModel = m) } } }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    // ———— 音色和语速 ————
    val modelInUse = if (ttsAuto) ttsChosen?.modelId.orEmpty()
    else sp.ttsModel.ifBlank { state.provider(sp.ttsProviderId)?.let { SpeechModels.ttsDefaultOf(it) }.orEmpty() }
    val platformInUse = if (ttsAuto) ttsChosen?.let { state.provider(it.providerId) }?.let { Thinking.platformOf(it) }.orEmpty()
    else state.provider(sp.ttsProviderId)?.let { Thinking.platformOf(it) }.orEmpty()
    // 自动挑的时候用各家的默认音色：引擎只把设置里的音色发给设置里指定的那一家（换到别家时别家不认），
    // 所以自动模式下不给选，免得选了没用（主会话审查 10-12）
    if (ttsAuto) SectionCard {
        FieldLabel("音色")
        Text("自动挑的时候用各家的默认音色${ttsChosen?.let { "（现在是 ${SpeechModels.defaultVoice(it.modelId).ifBlank { "这家的默认" }}）" }.orEmpty()}。要换音色，先在上面指定一家。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
    } else SectionCard {
        FieldLabel("音色", "不填就用这家的默认音色")
        val voices = SpeechModels.voices(modelInUse)
        val current = sp.ttsVoice.ifBlank { SpeechModels.defaultVoice(modelInUse) }
        if (voices.isNotEmpty()) {
            SelectBox(current.ifBlank { "这家的默认音色" }, Modifier.fillMaxWidth()) { close ->
                voices.forEach { (id, name) ->
                    DropdownMenuItem(
                        { Text(if (id == current) "✓ $name" else name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = { save { it.copy(ttsVoice = id) }; close() },
                    )
                }
                if (sp.ttsVoice.isNotBlank()) DropdownMenuItem(
                    { Text("用这家的默认音色", style = MaterialTheme.typography.bodyMedium, color = Ext.c.subtle) },
                    onClick = { save { it.copy(ttsVoice = "") }; close() },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 表里没列全的那两家（MiniMax、千问）官方只给了例子，编号可以自己填
        var voice by remember(sp.ttsVoice) { mutableStateOf(sp.ttsVoice) }
        AppTextField(voice, { voice = it }, placeholder = "也可以自己填音色编号（MiniMax、千问的控制台里有）")
        AutoSave(voice, sp.ttsVoice) { v -> save { it.copy(ttsVoice = v.trim()) } }
        AutoSaveHint()
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        val canSpeed = SpeechModels.supportsSpeed(platformInUse)
        var speed by remember(sp.ttsSpeed) { mutableStateOf(sp.ttsSpeed) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("语速", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (canSpeed) Text(speedText(speed), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        // 这家不认语速就不放滑块（灰掉的滑块看着像坏了，主会话审查 10-12），只写一句为什么
        if (canSpeed) {
            Slider(
                value = speed.toFloat(),
                onValueChange = { speed = (it * 10).roundToInt() / 10.0 },
                valueRange = 0.5f..2f,
                steps = 14,
            )
            AutoSave(speed, sp.ttsSpeed) { v -> save { it.copy(ttsSpeed = v) } }
        }
        Text(
            if (canSpeed) "0.5～2.0，1.0 是原速"
            else "${if (ttsAuto) "现在自动挑中的这家" else "这家"}的接口不能调语速，用原速读（只有 MiniMax 和智谱的接口有语速这个参数）。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    SectionCard {
        SwitchRow("回答完自动读", "AI 答完一条就自动念出来。好几位成员一起答就排着队一条一条读，点「停止」都不读了。", sp.autoRead) { on ->
            save { it.copy(autoRead = on) }
        }
    }
    if (platform.canPlayAudio) {
        Spacer(Modifier.height(12.dp))
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("试听", style = MaterialTheme.typography.titleSmall)
                    Text("念一句「你好，我是 AI集训营。」，听听这家读出来是什么效果。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                }
                Spacer(Modifier.width(10.dp))
                Button(enabled = !testing, onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val r = ctl.hub.local.call(Command.Speak("你好，我是 AI集训营。"))
                        val audio = if (r.ok) runCatching { AppJson.decodeFromString(SpeechAudio.serializer(), r.data) }.getOrNull() else null
                        val bytes = audio?.segments?.firstOrNull()?.let { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() }
                        if (bytes == null) {
                            testing = false
                            result = false to r.message.ifBlank { "没读出来（引擎没有返回音频）" }
                            return@launch
                        }
                        try {
                            platform.playWav(bytes)
                            result = true to "已经读出来了（${audio.label}）"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            result = false to "放不出来：${e.message ?: e::class.simpleName}"
                        } finally {
                            testing = false
                        }
                    }
                }) { Text(if (testing) "正在读…" else "试听") }
            }
            result?.let { (ok, text) -> TestResultBox(ok, text) { result = null } }
        }
    }

    // ———— 听写 ————
    Spacer(Modifier.height(12.dp))
    SectionCard {
        FieldLabel("听写用哪家", "输入框旁边的话筒按钮认出来的字用它")
        RadioRow(asrAuto, "自动（推荐）", "按 小米 → MiniMax → 智谱 的顺序挑已经启用、填了 Key 的那家。") {
            save { it.copy(asrProviderId = "", asrModel = "") }
        }
        asrCands.forEach { p ->
            val m = SpeechModels.asrModelOf(p).orEmpty()
            RadioRow(!asrAuto && sp.asrProviderId == p.id, p.name, "$m · ${SpeechPick.noteOf(p, m)}") {
                save { it.copy(asrProviderId = p.id, asrModel = "") }
            }
        }
        if (asrAuto) {
            val c = asrChosen
            Text(
                if (c == null) "现在没有能用的服务商：去「模型服务」添加一家并填上 Key。"
                else "现在会用 ${c.modelId}（${c.note}）",
                style = MaterialTheme.typography.labelSmall,
                color = if (c == null) MaterialTheme.colorScheme.error else Ext.c.subtle,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "每家只有一个听写模型，不用选。千问的听写还没接（官方标着「OpenAI 兼容」，但接口文档打不开、价格也没查到）。" +
                (if (!platform.isDesktop) "手机上第一次点话筒会问麦克风权限。" else ""),
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
        )
    }
}

/** 语速显示成「1.0×」。 */
private fun speedText(v: Double): String {
    val r = (v * 10).roundToInt() / 10.0
    return if (r == r.toInt().toDouble()) "${r.toInt()}.0×" else "$r×"
}

