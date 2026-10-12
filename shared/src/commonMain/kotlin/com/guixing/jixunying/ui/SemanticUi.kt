package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.EmbedModels
import com.guixing.jixunying.model.EmbedPick
import com.guixing.jixunying.model.ImagePick
import com.guixing.jixunying.model.ModelPrice
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.SemanticEstimatePart
import com.guixing.jixunying.model.SemanticEstimateResult
import com.guixing.jixunying.model.SemanticPart
import com.guixing.jixunying.model.SemanticProgress
import com.guixing.jixunying.model.SemanticSettings
import com.guixing.jixunying.model.Thinking

/**
 * 设置 →「按意思搜」（docs/向量和语音设计说明.md 第 3、7、8 节，1.7.0）。
 *
 * 和 设置 →「语音」不一样：那一页读写的是「这台设备自己的」服务商和 Key（手机上遥控电脑时也用手机自己的），
 * 这一页读写的是**数据在哪台设备**（ctl.backend）：手机遥控电脑时，搜的是电脑上的聊天记录和文档，
 * 这里改的开关、选的服务商、看到的进度也都是电脑上的（设计说明第 7 节）。
 *
 * 界面上的几句判断都放在 SemanticUi 里（纯函数，单独测，不掺界面）。
 */
object SemanticUi {

    /** 两类的名字（引擎那边叫 history / docs，见 engine/VectorStore.kt）。 */
    const val HISTORY = "history"
    const val DOCS = "docs"

    /** 拨开关要干什么。 */
    enum class Intent {
        /** 关掉：立刻关（算好的向量留着，不删）。 */
        OFF,
        /** 打开：先弹估算，用户点了「开始算」才真的打开。 */
        ASK,
        /** 打开但一个能算的服务商都没有：不开。 */
        CANT,
    }

    /**
     * 按意思搜要花钱：打开之前先给用户看一眼估算（设计说明第 7 节第 1 条），点「开始算」才真的打开开关。
     * 没有能用的服务商时不开（页面上这时整页都是灰的，写着去哪儿加）。
     */
    fun intent(want: Boolean, usable: Boolean): Intent = when {
        !want -> Intent.OFF
        usable -> Intent.ASK
        else -> Intent.CANT
    }

    /** 打开哪一类。 */
    fun turnOn(sem: SemanticSettings, section: String): SemanticSettings =
        if (section == SemanticUi.HISTORY) sem.copy(historyOn = true) else sem.copy(docsOn = true)

    /** 拨开关要干的事：关掉就存、打开先弹估算、没有服务商就什么都不做。 */
    sealed interface SwitchAction {
        data class Save(val next: SemanticSettings) : SwitchAction
        data class Ask(val section: String) : SwitchAction
        data object Nothing : SwitchAction
    }

    /**
     * 界面上一拨开关就走这里（开关「先估算、点「开始算」才真的打开」的判断就在这一段，
     * 抽出来是为了能单独测：见 SemanticUiTest）。
     */
    fun switchAction(sem: SemanticSettings, section: String, want: Boolean, usable: Boolean): SwitchAction =
        when (intent(want, usable)) {
            Intent.OFF -> SwitchAction.Save(if (section == SemanticUi.HISTORY) sem.copy(historyOn = false) else sem.copy(docsOn = false))
            Intent.ASK -> SwitchAction.Ask(section)
            Intent.CANT -> SwitchAction.Nothing
        }

    /** 设置首页右边的小字：都没开写「已关闭」，正在算写「正在算 35%」，开着列出开了的那几样。 */
    fun summary(sem: SemanticSettings, p: SemanticProgress): String {
        val running = listOf(p.history, p.docs).filter { it.running }
        if (running.isNotEmpty()) {
            val done = running.sumOf { it.done }
            val total = running.sumOf { it.total }
            return "正在算 ${if (total > 0) done * 100 / total else 0}%"
        }
        val on = buildList {
            if (sem.historyOn) add("聊天记录")
            if (sem.docsOn) add("文档")
        }
        return if (on.isEmpty()) "已关闭" else on.joinToString("、")
    }

    /** 这一类进度那一行的话。 */
    fun partLine(part: SemanticPart, on: Boolean, usable: Boolean): String = when {
        part.error.isNotBlank() -> "出错了：${part.error}"
        // 换了服务商 / 模型：以前算的不能用，等用户点「重新算一遍」
        part.stale -> part.text
        part.running -> "正在算…" + if (part.total > 0) "（已算 ${part.done} / ${part.total} 段）" else ""
        !on -> "没开"
        !usable -> "没有能算的服务商"
        part.total == 0 -> "还没算过"
        else -> "已算 ${part.done} / ${part.total} 段"
    }

    /** 进度条走到哪（0～100）。 */
    fun percent(part: SemanticPart): Int =
        if (part.total <= 0) 0 else ((part.done.toLong() * 100) / part.total).toInt().coerceIn(0, 100)

    /** 这一栏要不要显示「重新算一遍」：算过、要重算、或者上次出错了。正在算的时候不给点。 */
    fun canRebuild(part: SemanticPart, on: Boolean, usable: Boolean): Boolean =
        on && usable && !part.running && (part.total > 0 || part.done > 0 || part.stale || part.error.isNotBlank())

    /** 算过东西的那几类（history / docs）：换服务商、模型之前要问的就是这几类。 */
    fun computed(p: SemanticProgress): List<String> = buildList {
        if (p.history.builtAt > 0 || p.history.done > 0 || p.history.stale) add(SemanticUi.HISTORY)
        if (p.docs.builtAt > 0 || p.docs.done > 0 || p.docs.stale) add(SemanticUi.DOCS)
    }

    /**
     * 按另一家的价格重算一遍钱：多少字、几段、多少 token 和服务商无关，只有单价不同——
     * 换服务商 / 模型时估算弹窗里显示的就该是新那家的价（引擎返回的是旧那家的）。
     */
    fun recost(part: SemanticEstimatePart, price: ModelPrice?): SemanticEstimatePart =
        part.copy(cost = price?.tiers?.firstOrNull()?.input?.let { part.tokens * it / 1_000_000.0 })

    /** 给 SemanticRebuild 用的 scope：只有一类就报这一类，两类都重算就留空（引擎当 all 处理）。 */
    fun scopeOf(sections: List<String>): String = if (sections.size == 1) sections.single() else ""

    /** 多少个字：上万就写成「1.2 万字」。 */
    fun charsText(n: Int): String = if (n >= 10_000) "${(n / 1000) / 10.0} 万字" else "$n 字"

    /** 估算弹窗里那一行钱：粗估多少（价格表里没有这个模型就照实说，别写个 0 骗人）。 */
    fun costText(part: SemanticEstimatePart, currency: String): String {
        val unit = if (currency == "USD") "美元" else "元"
        val cost = part.cost ?: return "价格没查到，按它的账单为准"
        val shown = Prices.fmt(cost)
        // 太便宜了、四位数看不出来（比如 0.00003 元）：别写成「0 元」，像是不要钱
        return if (cost > 0 && shown == "0") "粗估不到 0.0001 $unit" else "粗估 $shown $unit"
    }
}

private fun sectionName(s: String) = if (s == SemanticUi.HISTORY) "聊天记录" else "文档"

@Composable
fun SemanticPage(ctl: AppController) {
    val state by ctl.backend.store.state.collectAsState()
    val sem = state.settings.semantic
    val progress = state.semanticProgress
    val cands = remember(state.providers) { state.providers.filter { EmbedModels.supports(it) && ImagePick.usable(it) } }
    val choice = remember(state.providers, sem) { EmbedPick.pick(state) }
    val auto = EmbedPick.isAuto(state)
    val usable = choice != null
    val custom = cands.firstOrNull { it.presetId == "custom" }
    // 「自定义」没有默认模型，要用户自己填；别的家都有默认的
    val customInUse = custom != null && (choice?.platform == "custom" || (auto && sem.model.isNotBlank()) || sem.providerId == custom.id && (choice == null || choice.platform == "custom"))
    val dim = if (usable) 1f else 0.45f

    fun save(next: SemanticSettings) = ctl.updateSettings { it.copy(semantic = next) }

    var askEstimate by remember { mutableStateOf(if (ctl.debugDialog == "estimate") SemanticUi.HISTORY else null) }
    var rebuild by remember { mutableStateOf<List<String>?>(if (ctl.debugDialog == "rebuild") listOf(SemanticUi.HISTORY) else null) }
    var pendingChange by remember {
        mutableStateOf<Pair<SemanticSettings, List<String>>?>(
            if (ctl.debugDialog == "change") sem.copy(providerId = "") to listOf(SemanticUi.HISTORY, SemanticUi.DOCS) else null,
        )
    }

    // 换服务商 / 模型：已经算过东西的先问一句（换了以后以前算的不能用了）；没算过就直接换
    fun apply(next: SemanticSettings) {
        val done = SemanticUi.computed(progress)
        if (done.isEmpty()) save(next) else pendingChange = next to done
    }

    PageHeader(
        "按意思搜",
        "换个说法也能搜到以前聊过的、文档里写过的。要先把聊天记录、文档用下面选的服务商「算一遍」，按它的价格收费；算好的存在这台设备上。" +
            if (ctl.remoteMode) " 现在遥控的是电脑，这一页改的、算的都是电脑上的。" else "",
    )

    if (!usable) {
        SectionCard {
            Text("还没有能算的服务商", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "算「按意思搜」用的是智谱（embedding-3）和千问（text-embedding-v4）的接口，用「模型服务」里已经填好的 Key，不用另外申请。" +
                    "别的服务商（硅基流动、本机 Ollama 这类 OpenAI 兼容的地址）可以加一个「自定义」，模型名自己填。到「模型服务」加上智谱或千问就行。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { ctl.openSettings(SettingsTab.PROVIDERS) }) { Text("去添加服务商") }
        }
        Spacer(Modifier.height(12.dp))
    }

    // ———— 用哪家算 ————
    SectionCard(Modifier.alpha(dim)) {
        FieldLabel("用哪家算", "算一遍按它的价格收费，打开下面的开关之前会先给你看要花多少钱")
        RadioRow(auto, "自动（推荐）", "按 智谱 → 千问 → 自定义 的顺序挑已经启用、填了 Key 的那家。") {
            apply(sem.copy(providerId = "", model = ""))
        }
        cands.forEach { p ->
            val model = if (p.presetId == "custom") sem.model else EmbedModels.defaultOf(p).orEmpty()
            val desc = listOfNotNull(
                model.takeIf { it.isNotBlank() },
                EmbedPick.noteOf(p, model).takeIf { p.presetId != "custom" || model.isNotBlank() },
            ).joinToString(" · ")
            RadioRow(!auto && sem.providerId == p.id, p.name, desc.ifBlank { "还没填模型名" }) {
                // 自定义那家的模型名留着，别的一家有自己的默认
                apply(sem.copy(providerId = p.id, model = if (p.presetId == "custom") sem.model else ""))
            }
        }
        if (auto) {
            Text(
                if (choice == null) "现在没有能用的服务商。"
                else "现在会用 ${choice.modelId}（${choice.note}）",
                style = MaterialTheme.typography.labelSmall,
                color = if (choice == null) MaterialTheme.colorScheme.error else Ext.c.subtle,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (customInUse && custom != null) {
            Spacer(Modifier.height(10.dp))
            FieldLabel("用哪个模型", "「${custom.name}」是一家 OpenAI 兼容的地址，模型名要自己填")
            var model by remember(sem.model) { mutableStateOf(sem.model) }
            AppTextField(model, { model = it }, placeholder = "比如 bge-m3")
            AutoSave(model, sem.model) { v -> save(sem.copy(model = v.trim())) }
            AutoSaveHint()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "算的时候会把聊天记录、文档里的文字发给上面这家服务商。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle,
        )
    }
    Spacer(Modifier.height(12.dp))

    // ———— 两类的开关和进度 ————
    // 打开之前先弹估算（点「开始算」才真的打开）：判断在 SemanticUi.switchAction 里
    fun toggle(section: String, want: Boolean) {
        when (val a = SemanticUi.switchAction(sem, section, want, usable)) {
            is SemanticUi.SwitchAction.Save -> save(a.next)
            is SemanticUi.SwitchAction.Ask -> askEstimate = a.section
            SemanticUi.SwitchAction.Nothing -> {}
        }
    }

    SemanticSwitchCard(
        title = "聊天记录按意思搜",
        desc = "搜聊天记录时换个说法也能找到。打开后会把聊过的文字发给上面选的服务商算一遍。",
        part = progress.history, on = sem.historyOn, usable = usable, dim = dim,
        onToggle = { toggle(SemanticUi.HISTORY, it) },
        onRebuild = { rebuild = listOf(SemanticUi.HISTORY) },
    )
    Spacer(Modifier.height(12.dp))
    SemanticSwitchCard(
        title = "我的文档按意思搜",
        desc = "搜「我的文档」时换个说法也能找到。打开后会把文档里的文字发给上面选的服务商算一遍。",
        part = progress.docs, on = sem.docsOn, usable = usable, dim = dim,
        onToggle = { toggle(SemanticUi.DOCS, it) },
        onRebuild = { rebuild = listOf(SemanticUi.DOCS) },
    )

    // ———— 弹窗 ————

    // 打开某一类之前先看估算：点「开始算」才真的打开
    askEstimate?.let { section ->
        EstimateDialog(
            ctl = ctl,
            title = "先看要花多少钱",
            sections = listOf(section),
            intro = "打开以后，这台设备上现有的${sectionName(section)}都会「算一遍」存下来，按它的价格收费；以后有新内容会在后台慢慢补算。",
            confirmText = "开始算",
            cancelText = "先不开",
            onConfirm = { askEstimate = null; save(SemanticUi.turnOn(sem, section)) },
            onDismiss = { askEstimate = null },
        )
    }

    // 重新算一遍：换了服务商或模型以后要重算，会再花一次钱
    rebuild?.let { kinds ->
        EstimateDialog(
            ctl = ctl,
            title = "重新算一遍",
            sections = kinds,
            intro = "换了服务商或模型以后要重算；重算会再花一次钱。",
            confirmText = "重新算一遍",
            cancelText = "先不算",
            onConfirm = { rebuild = null; ctl.run(Command.SemanticRebuild(SemanticUi.scopeOf(kinds))) },
            onDismiss = { rebuild = null },
        )
    }

    // 换服务商 / 模型：算过东西的先问（按新那家的价格估一遍），确认了才存设置、再重算
    pendingChange?.let { (next, kinds) ->
        val nextChoice = EmbedPick.pick(state.copy(settings = state.settings.copy(semantic = next)))
        val np = nextChoice?.let { state.provider(it.providerId) }
        val nprice = nextChoice?.let { Prices.of(it.platform, it.modelId, state.settings.costs.overrides, np?.baseUrl.orEmpty()) }
        EstimateDialog(
            ctl = ctl,
            title = "换了以后要重新算一遍",
            sections = kinds,
            intro = "换了服务商或模型，以前算好的东西和新的对不上、不能再用了，要重新算一遍；重算会再花一次钱。",
            confirmText = "换了，重新算",
            cancelText = "先不换",
            label = nextChoice?.let { "${it.modelId}（${it.providerName}）" }.orEmpty(),
            price = nprice,
            priceHow = nprice?.let { Prices.describe(it) }.orEmpty(),
            onConfirm = {
                pendingChange = null
                // 设置先落地，再重算：不然重算又拿旧的那家跑一遍，白花一次钱
                ctl.updateSettingsThen({ it.copy(semantic = next) }) {
                    ctl.backend.call(Command.SemanticRebuild(SemanticUi.scopeOf(kinds)))
                }
            },
            onDismiss = { pendingChange = null },
        )
    }
}

/** 一类（聊天记录 / 我的文档）：开关 + 进度 + 重新算一遍。 */
@Composable
private fun SemanticSwitchCard(
    title: String,
    desc: String,
    part: SemanticPart,
    on: Boolean,
    usable: Boolean,
    dim: Float,
    onToggle: (Boolean) -> Unit,
    onRebuild: () -> Unit,
) {
    SectionCard(Modifier.alpha(dim)) {
        SwitchRow(title, desc, on, onToggle)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    SemanticUi.partLine(part, on, usable),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (part.error.isNotBlank()) MaterialTheme.colorScheme.error else Ext.c.subtle,
                )
                // 要重新算的时候不画进度条：那条杠满着会让人以为还能用（那句话已经说清现在只能按字面搜）
                if (on && part.total > 0 && !part.stale) {
                    Spacer(Modifier.height(6.dp))
                    ProgressBar(SemanticUi.percent(part) / 100f)
                }
            }
            if (SemanticUi.canRebuild(part, on, usable)) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRebuild) { Text("重新算一遍") }
            }
        }
    }
}

/** 进度条（没用 material3 那个：这里要跟卡片的底色配，深色下也要看得清）。 */
@Composable
private fun ProgressBar(fraction: Float) {
    val shape = RoundedCornerShape(50)
    Box(Modifier.fillMaxWidth().height(6.dp).clip(shape).background(Ext.c.border)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(shape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/**
 * 先估一估，再问用户要不要开始（教训 30：弹窗里的结果、出错都显示在弹窗里，别指望外面那条提示——
 * 会被弹窗挡住）。
 */
@Composable
private fun EstimateDialog(
    ctl: AppController,
    title: String,
    sections: List<String>,
    intro: String,
    confirmText: String,
    cancelText: String,
    label: String = "",
    price: ModelPrice? = null,
    priceHow: String = "",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var result by remember { mutableStateOf<SemanticEstimateResult?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val r = ctl.backend.call(Command.SemanticEstimate)
        val est = if (r.ok) runCatching { AppJson.decodeFromString(SemanticEstimateResult.serializer(), r.data) }.getOrNull() else null
        if (est == null) error = r.message.ifBlank { "估不出来（引擎没有返回结果）" } else result = est
        loading = false
    }
    AppDialog(title, onDismiss, width = 520.dp, actions = {
        TextButton(onClick = onDismiss, enabled = !loading) { Text(cancelText) }
        // 没估出来就不让往下走（不知道要花多少钱）；出错和结果都显示在弹窗里
        Button(onClick = onConfirm, enabled = result != null) { Text(confirmText) }
    }) {
        Text(intro, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在数有多少字…", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            }
            error.isNotBlank() -> Text("估不出来：$error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            result != null -> {
                val est = result!!
                // label 不空 = 换服务商之前的估算：引擎算的是旧那家的价，要按新那家（price 可能是 null：新那家价格没查到）重算，
                // 不能退回去显示旧那家的价和钱（主会话审查 10-12）
                val forNext = label.isNotBlank()
                val who = label.ifBlank { est.label }
                if (forNext) {
                    Text("会用 ${who}算，" + (if (price != null) "按 ${priceHow.ifBlank { "它的价格" }}收费。" else "这家的价格没查到。"),
                        style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                } else if (est.priceText.isNotBlank()) {
                    Text("会用 ${who}算，价格：${est.priceText}", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                }
                Spacer(Modifier.height(10.dp))
                sections.forEach { s ->
                    val raw = if (s == SemanticUi.HISTORY) est.history else est.docs
                    val part = if (forNext) SemanticUi.recost(raw, price) else raw
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sectionName(s), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${SemanticUi.charsText(raw.chars)} · ${raw.chunks} 段", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        SemanticUi.costText(part, price?.currency ?: est.currency),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = if (part.cost == null) Ext.c.subtle else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(est.tokenNote, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
        }
    }
}
