package com.guixing.jixunying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.BalanceInfo
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CostLine
import com.guixing.jixunying.model.CostReport
import com.guixing.jixunying.model.ModelPrice
import com.guixing.jixunying.model.PriceTier
import com.guixing.jixunying.model.SpeechUnits
import kotlinx.serialization.builtins.ListSerializer

private val PERIODS = listOf("today" to "今天", "week" to "本周", "month" to "本月", "all" to "全部")

/** 上次查到的余额（离开页面再回来还在，不用每次都查）。本机和遥控的电脑各记各的。 */
private val lastBalances = mutableMapOf<Boolean, Pair<Long, List<BalanceInfo>>>()

/**
 * 花费页：按各家官方价格和每次调用的用量估算花了多少（不是账单）。
 * 合计、按天、按成员、按模型（能改单价）、按用途；能查余额的平台查余额。
 */
@Composable
fun CostsScreen(ctl: AppController, wide: Boolean, openDrawer: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    var period by remember { mutableStateOf("month") }
    var tick by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val report by produceState<CostReport?>(null, period, tick, state.settings.costs, ctl.backend) {
        error = null
        val r = ctl.backend.call(Command.GetCosts(period))
        val got = if (r.ok) runCatching { AppJson.decodeFromString(CostReport.serializer(), r.data) }.getOrNull() else null
        // 遥控的电脑没响应、或者是不认这个指令的老版本：说清楚，别一直转圈
        if (got == null) error = r.message.ifBlank { "没算出来（电脑上的 AI集训营 可能是老版本，更新一下）" }
        value = got
    }
    var editing by remember { mutableStateOf<CostLine?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (!wide) Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "菜单") }
            Text("花费", style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 20.dp)) {
            Column(Modifier.widthIn(max = 860.dp)) {
                PageHeader("花费", "按各家官方价格和每次调用的用量估算，不是账单；联网搜索这类按次另收的钱没算。价格变了可以在下面「按模型」里自己改。") {
                    OutlinedButton(onClick = { tick++ }) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("刷新")
                    }
                }
                Segmented(PERIODS, period) { period = it }
                Spacer(Modifier.height(12.dp))
                val r = report
                val err = error
                if (r == null && err != null) {
                    TestResultBox(false, err) { tick++ }
                    TextButton({ tick++ }) { Text("再试一次") }
                } else if (r == null) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                        Text("正在算…", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
                    }
                } else {
                    TotalCard(r)
                    if (r.daily.size >= 2) { Spacer(Modifier.height(12.dp)); DailyChart(r) }
                    if (r.total.calls > 0) {
                        Spacer(Modifier.height(12.dp))
                        Breakdown("按成员", "谁花得多；「记录员（后台）」是压缩聊天、挑记忆、点名、立场档案", r.byMember, r.usdRate) { line ->
                            val m = state.member(line.key)
                            if (m != null) MemberAvatar(m, 26.dp) else MemberAvatar(null, 26.dp, emoji = when (line.key) { "painter" -> "🎨"; "recorder" -> "📝"; else -> "⚙️" })
                        }
                        Spacer(Modifier.height(12.dp))
                        ModelBreakdown(r, onEdit = { editing = it })
                        Spacer(Modifier.height(12.dp))
                        Breakdown("按用途", null, r.byKind, r.usdRate, icon = null)
                    }
                }
                Spacer(Modifier.height(12.dp))
                BalancesCard(ctl)
                Spacer(Modifier.height(12.dp))
                // 用了按美元收费的服务（账本里有美元花费、或者加了海外服务商）才说「正在用」；只用国内几家时说明用不上
                val usesUsd = (report?.total?.usd ?: 0.0) > 0 || state.providers.any { it.enabled && com.guixing.jixunying.model.Presets.byId(it.presetId).group == com.guixing.jixunying.model.Presets.GROUP_GLOBAL }
                RateCard(ctl, state.settings.costs, usesUsd)
            }
        }
    }
    editing?.let { line -> PriceDialog(ctl, line, state.settings.costs.overrides[line.priceKey]) { editing = null } }
}

// —— 小组件 ——

@Composable
private fun Segmented(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.clip(shape).border(1.dp, Ext.c.border, shape)) {
        options.forEachIndexed { i, (key, label) ->
            if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(Ext.c.border))
            val sel = key == value
            Box(Modifier.height(36.dp).background(if (sel) primary.copy(alpha = 0.12f) else Color.Transparent).clickable { onPick(key) }
                .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) primary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** ¥12.34；不到一分钱的写「不到 1 分」，免得一排 ¥0.00 看着像没算。 */
fun yuan(v: Double): String = when {
    v <= 0.0 -> "¥0"
    v < 0.01 -> "不到 1 分"
    v < 100 -> "¥" + ((kotlin.math.round(v * 100) / 100).toString().let { if (it.substringAfter('.', "").length == 1) it + "0" else it })
    else -> "¥" + kotlin.math.round(v).toLong()
}

private fun usdText(v: Double): String = when {
    v <= 0.0 -> "$0"
    v < 0.01 -> "不到 1 美分"
    else -> "$" + (kotlin.math.round(v * 100) / 100).toString().let { if (it.substringAfter('.', "").length == 1) it + "0" else it }
}

/** 12345 → 1.2 万；12345678 → 1235 万。 */
fun tokens(n: Long): String = when {
    n < 10_000 -> n.toString()
    n < 100_000_000 -> ((kotlin.math.round(n / 1000.0) / 10.0).toString()).removeSuffix(".0") + " 万"
    else -> ((kotlin.math.round(n / 10_000_000.0) / 10.0).toString()).removeSuffix(".0") + " 亿"
}

/** 一行的钱；全是没价格 / 没用量的调用时写「—」，不写容易误会的 ¥0。 */
private fun lineMoney(l: CostLine, rate: Double) =
    if (l.calls > 0 && l.unpriced + l.noUsage >= l.calls && l.total(rate) == 0.0) "—" else yuan(l.total(rate))

/** "2026-10-08" → "10月8日" */
private fun dayLabel(key: String): String {
    val parts = key.split('-')
    return if (parts.size == 3) "${parts[1].toInt()}月${parts[2].toInt()}日" else key
}

/**
 * 语音的行后面跟一句累计：读出来是「共 N 字」（MiniMax 一个汉字算 2 个字符，照它的算法标出来），
 * 听写是「共 M 分 S 秒」。别的行没有（unit 是空的）。
 */
private fun unitsText(l: CostLine): String? {
    if (l.units <= 0.0) return null
    return when (l.unit) {
        // MiniMax 的单价说明里已经写了「1 个汉字算 2 个字符」，这里不再重复
        SpeechUnits.CHAR -> if (l.label.trim().lowercase().startsWith("speech-")) "共 ${l.units.toInt()} 字符"
        else "共 ${l.units.toInt()} 字"
        SpeechUnits.SECOND -> {
            val total = l.units.toInt()
            if (total < 60) "共 $total 秒" else "共 ${total / 60} 分 ${total % 60} 秒"
        }
        else -> null
    }
}

@Composable
private fun TotalCard(r: CostReport) {
    val t = r.total
    SectionCard {
        Text(PERIODS.first { it.first == r.period }.second + "估算花费", style = MaterialTheme.typography.labelLarge, color = Ext.c.subtle)
        Text(lineMoney(t, r.usdRate), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
        if (t.usd > 0) Text("其中海外服务 ${usdText(t.usd)}，按 1 美元 = ${r.usdRate} 元折算", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
        Spacer(Modifier.height(6.dp))
        Text(buildString {
            append("调用 ${t.calls} 次")
            if (t.images > 0) append("（含画图 ${t.images} 张）")
            append(" · 输入 ${tokens(t.prompt)} token")
            if (t.cached > 0) append("（命中缓存 ${tokens(t.cached)}）")
            append(" · 输出 ${tokens(t.completion)} token")
        }, style = MaterialTheme.typography.bodySmall)
        val gaps = buildList {
            if (t.noUsage > 0) add("${t.noUsage} 次服务商没返回用量")
            if (t.unpriced > 0) add("${t.unpriced} 次的模型价格表里没有")
        }
        if (gaps.isNotEmpty()) Text(gaps.joinToString("，") + "，这些没算进去（没价格的可以在「按模型」里填单价）。",
            style = MaterialTheme.typography.bodySmall, color = Ext.c.warning, modifier = Modifier.padding(top = 4.dp))
        if (t.calls == 0) Text("这段时间还没有调用。", style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp))
        if (r.firstAt > 0) Text("从 ${dateText(r.firstAt)} 开始记账" + (if (r.backfillUntil > 0) "；${dateText(r.backfillUntil)} 以前的是从聊天记录补记的，只有成员回答和画图（那时没记记录员的后台活）" else "") + "。",
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
    }
}

/** 每天花多少：一种颜色的细柱，点哪天看哪天的数（默认最后一天）。 */
@Composable
private fun DailyChart(r: CostReport) {
    val days = r.daily
    var picked by remember(days) { mutableStateOf(days.lastIndex) }
    val max = days.maxOf { it.total(r.usdRate) }.takeIf { it > 0 } ?: 1.0
    val bar = MaterialTheme.colorScheme.primary
    SectionCard {
        val d = days[picked.coerceIn(0, days.lastIndex)]
        Row(verticalAlignment = Alignment.Bottom) {
            Text("每天", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${dayLabel(d.key)} · ${lineMoney(d, r.usdRate)} · ${d.calls} 次", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            days.forEachIndexed { i, day ->
                val frac = (day.total(r.usdRate) / max).toFloat().coerceIn(0f, 1f)
                Box(Modifier.weight(1f).fillMaxHeight().clickable { picked = i }, contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(maxOf(frac, 0.02f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (i == picked) bar else bar.copy(alpha = 0.45f)))
                }
            }
        }
        HorizontalDivider(color = Ext.c.border)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(dayLabel(days.first().key), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.weight(1f))
            Text(dayLabel(days.last().key), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
        }
    }
}

/** 一组占比：名字、同色横条（按金额比例）、金额、次数。 */
@Composable
private fun Breakdown(title: String, hint: String?, lines: List<CostLine>, rate: Double, icon: (@Composable (CostLine) -> Unit)?) {
    val max = lines.maxOfOrNull { it.total(rate) }?.takeIf { it > 0 } ?: 1.0
    val bar = MaterialTheme.colorScheme.primary
    SectionCard {
        FieldLabel(title, hint)
        lines.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { icon(l); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(lineMoney(l, rate), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Ext.c.border.copy(alpha = 0.5f))) {
                        Box(Modifier.fillMaxWidth((l.total(rate) / max).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(bar))
                    }
                    Text(listOfNotNull(
                        "${l.calls} 次",
                        if (l.images > 0) "${l.images} 张图" else null,
                        if (l.prompt + l.completion > 0) "输入 ${tokens(l.prompt)} · 输出 ${tokens(l.completion)}" else null,
                        if (l.unpriced > 0) "${l.unpriced} 次没价格" else null,
                        if (l.noUsage > 0) "${l.noUsage} 次没返回用量" else null,
                    ).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelBreakdown(r: CostReport, onEdit: (CostLine) -> Unit) {
    SectionCard {
        FieldLabel("按模型", "后面是算钱用的单价；价格不对点「改单价」")
        r.byModel.forEachIndexed { i, l ->
            if (i > 0) HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(l.sub, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                }
                Text(lineMoney(l, r.usdRate), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (l.overridden) "你改过的单价：" else "") + l.priceText + (unitsText(l)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (l.unpriced > 0) Ext.c.warning else Ext.c.subtle, modifier = Modifier.weight(1f).padding(top = 2.dp))
                Text("改单价", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onEdit(l) }.padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun BalancesCard(ctl: AppController) {
    var busy by remember { mutableStateOf(false) }
    var result by remember(ctl.remoteMode) { mutableStateOf(lastBalances[ctl.remoteMode]) }
    var failed by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<BalanceInfo?>(null) }
    val platform = LocalPlatform.current
    fun query() {
        busy = true; failed = null
        ctl.run(Command.GetBalances, quiet = true) { r ->
            busy = false
            if (!r.ok) failed = r.message.ifBlank { "没查成（电脑上的 AI集训营 可能是老版本）" }
            if (r.ok) runCatching { AppJson.decodeFromString(ListSerializer(BalanceInfo.serializer()), r.data) }.getOrNull()?.let {
                val got = nowMillis() to it
                result = got; lastBalances[ctl.remoteMode] = got
            }
        }
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("余额", style = MaterialTheme.typography.titleSmall)
                Text(result?.let { "${formatTime(it.first)}查的" } ?: "用你填的 Key 去各家查；没有查余额接口的平台，可以照控制台手动填，之后按花费估算",
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
            Button(enabled = !busy, onClick = { query() }) { Text(if (busy) "正在查…" else if (result == null) "查余额" else "再查一次") }
        }
        failed?.let { TestResultBox(false, it) { failed = null } }
        result?.second?.forEach { b ->
            HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.provider, style = MaterialTheme.typography.bodyMedium)
                        if (b.unofficial) { Spacer(Modifier.width(6.dp)); Pill("非公开接口", Ext.c.subtle) }
                        if (b.estimated) { Spacer(Modifier.width(6.dp)); Pill("手填 · 估算", Ext.c.subtle) }
                    }
                    Text(b.text, style = MaterialTheme.typography.bodySmall,
                        color = when { b.ok -> MaterialTheme.colorScheme.onSurface; b.supported -> MaterialTheme.colorScheme.error; else -> Ext.c.subtle })
                    b.manual?.let { m ->
                        Text(if (b.estimated) "${formatTime(m.at)} 填的；控制台上的数变了就重新填一次" else "接口查到了，手填的（${formatTime(m.at)}）没用上",
                            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if ((!b.ok || b.estimated) && b.consoleUrl.isNotBlank()) TextButton({ platform.openUrl(b.consoleUrl) }) { Text("去控制台 ›", style = MaterialTheme.typography.labelMedium) }
                    if (!b.ok || b.manual != null) TextButton({ editing = b }) { Text(if (b.manual != null) "改手填" else "手填余额", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
    editing?.let { b -> ManualBalanceDialog(ctl, b, onSaved = { editing = null; query() }, onClose = { editing = null }) }
}

/**
 * 手动填余额（用户 2026-10-12：「手动输入也要计算，如何计算去官网找说明」）。
 * 填控制台上现在的可用余额；智谱、千问这类先扣资源包 / 免费额度的，再填还剩多少 token。保存时记下时间，之后按花费估算。
 */
@Composable
private fun ManualBalanceDialog(ctl: AppController, b: BalanceInfo, onSaved: () -> Unit, onClose: () -> Unit) {
    val state by ctl.backend.store.state.collectAsState()
    val rule = state.provider(b.providerId)?.let { com.guixing.jixunying.model.BillingRules.of(com.guixing.jixunying.model.Thinking.platformOf(it)) }
    val old = b.manual
    var amount by remember { mutableStateOf(old?.amount?.toString().orEmpty()) }
    var usd by remember { mutableStateOf((old?.currency ?: b.currency) == "USD") }
    var packWan by remember { mutableStateOf(old?.packTokens?.takeIf { it > 0 }?.let { (it / 10_000.0).toString() }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val amountNum = amount.trim().toDoubleOrNull()
    val packNum = if (packWan.isBlank()) 0.0 else packWan.trim().toDoubleOrNull()
    val platform = LocalPlatform.current
    AppDialog("手填余额 · ${b.provider}", onClose, width = 480.dp, actions = {
        if (old != null) TextButton({ ctl.run(Command.SaveManualBalance(b.providerId, null), quiet = true) { onSaved() } }) { Text("清除手填") }
        Spacer(Modifier.weight(1f))
        TextButton(onClose) { Text("取消") }
        Button(enabled = amountNum != null && packNum != null && packNum >= 0, onClick = {
            val m = com.guixing.jixunying.model.ManualBalance(amountNum!!, if (usd) "USD" else "CNY", at = 0, packTokens = (packNum!! * 10_000).toLong())
            ctl.run(Command.SaveManualBalance(b.providerId, m), quiet = true) { r -> if (r.ok) onSaved() else error = r.message.ifBlank { "没存上（电脑上的 AI集训营 可能是老版本）" } }
        }) { Text("保存") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text("照控制台上现在的「可用余额」填。保存时记下现在的时间，之后按这家的调用和价格表估算还剩多少（只是估算，以控制台为准）。",
                style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("CNY" to "人民币", "USD" to "美元"), if (usd) "USD" else "CNY") { usd = it == "USD" }
            Spacer(Modifier.height(10.dp))
            FieldLabel("可用余额（${if (usd) "美元" else "元"}）")
            AppTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            FieldLabel("资源包 / 免费额度还剩多少万 token（没有就空着）", if (rule?.packFirst == true) "这家先扣这个，扣完才扣钱" else null)
            AppTextField(packWan, { packWan = it.filter { c -> c.isDigit() || c == '.' } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            if (rule != null) {
                Spacer(Modifier.height(10.dp))
                Text("怎么扣钱（官方说明）：" + rule.text, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
                TextButton({ platform.openUrl(rule.source) }, contentPadding = PaddingValues(0.dp)) { Text("看官方原文 ›", style = MaterialTheme.typography.labelSmall) }
            }
            error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * 美元汇率（2026-10-14 改）：海外服务（OpenAI、Gemini、海外版 MiniMax / Kimi 等）按美元计价，花费合计时按它折成人民币。
 * 默认自动更新：引擎联网取最新的（engine/UsdRate，最多 12 小时一次）；用户自己填了数就改成手动，点「改回自动」恢复。
 */
@Composable
private fun RateCard(ctl: AppController, costs: com.guixing.jixunying.model.CostSettings, usesUsd: Boolean) {
    val rate = costs.usdRate
    // 别处（比如手机）改了汇率、或者自动更新了，这里跟着变
    var text by remember(rate) { mutableStateOf(rate.toString()) }
    var busy by remember { mutableStateOf(false) }
    val bad = text.toDoubleOrNull()?.let { it <= 0 } ?: true
    // 手动模式下才自动保存输入框；自动模式下输入框是只读显示
    if (!costs.usdAuto) AutoSave(text, rate.toString()) { v ->
        v.toDoubleOrNull()?.takeIf { it > 0 }?.let { r -> ctl.run(Command.SaveCosts(ctl.backend.store.state.value.settings.costs.copy(usdRate = r, usdAuto = false)), quiet = true) }
    }
    SectionCard {
        FieldLabel("美元汇率", "海外服务按美元计价，合计花费时按这个折成人民币")
        if (!usesUsd) {
            Text("你现在用的几家都按人民币收费，这个汇率暂时用不上；以后加了按美元收费的海外服务（OpenAI、Gemini、海外版 MiniMax / Kimi 等）才用它折算。",
                style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("1 美元 = ", style = MaterialTheme.typography.bodyMedium)
            if (costs.usdAuto) {
                Text(rate.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            } else {
                AppTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, modifier = Modifier.width(110.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Text(" 元", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = {
                busy = true
                ctl.run(Command.RefreshUsdRate, quiet = true) { r ->
                    busy = false
                    ctl.toast(if (r.ok) "汇率更新了：${r.message}" else r.message)
                }
            }) { Text(if (busy) "正在取…" else if (costs.usdAuto) "现在更新" else "改回自动", style = MaterialTheme.typography.labelMedium) }
        }
        Text(
            when {
                !costs.usdAuto -> "手动填的，不会自动更新。点「改回自动」联网取最新汇率。"
                costs.usdRateAt > 0 -> "自动更新：${formatTime(costs.usdRateAt)} 从 ${costs.usdRateFrom} 取的，之后每 12 小时取一次。"
                else -> "自动更新：还没取到（要联网）；取到之前先按 $rate 算。"
            },
            style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 4.dp),
        )
        if (costs.usdAuto) {
            TextButton({ ctl.run(Command.SaveCosts(ctl.backend.store.state.value.settings.costs.copy(usdAuto = false)), quiet = true) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("改成自己填", style = MaterialTheme.typography.labelSmall)
            }
        } else if (bad) {
            Text("填一个大于 0 的数，比如 6.7", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** 改单价：按 token（输入 / 命中缓存 / 输出，每百万）或按张；能恢复官方价。 */
@Composable
private fun PriceDialog(ctl: AppController, line: CostLine, current: ModelPrice?, onClose: () -> Unit) {
    val official = current == null
    // 预填现在算钱用的单价（改过的就是改过的，没改过就是官方价），币种也照它，免得把美元价当人民币填
    val base = current ?: line.price
    val isImage = (base?.perImage != null && base.tiers.isEmpty()) || (line.images > 0 && line.prompt + line.completion == 0L)
    var usd by remember { mutableStateOf(base?.currency == "USD") }
    var perImage by remember { mutableStateOf(base?.perImage?.toString().orEmpty()) }
    val tier = base?.tiers?.firstOrNull()
    var input by remember { mutableStateOf(tier?.input?.toString().orEmpty()) }
    var cached by remember { mutableStateOf(tier?.cached?.toString().orEmpty()) }
    var output by remember { mutableStateOf(tier?.output?.toString().orEmpty()) }
    fun num(s: String) = s.trim().toDoubleOrNull()
    val valid = if (isImage) num(perImage) != null else num(input) != null && num(output) != null
    AppDialog("改单价 · ${line.label}", onClose, width = 480.dp, actions = {
        if (!official) TextButton({
            val costs = ctl.backend.store.state.value.settings.costs
            ctl.run(Command.SaveCosts(costs.copy(overrides = costs.overrides - line.priceKey)), quiet = true); onClose()
        }) { Text(if (line.hasOfficial) "恢复官方价" else "清除自填单价") }
        Spacer(Modifier.weight(1f))
        TextButton(onClose) { Text("取消") }
        Button(enabled = valid, onClick = {
            val p = if (isImage) ModelPrice(if (usd) "USD" else "CNY", perImage = num(perImage))
                else ModelPrice(if (usd) "USD" else "CNY", listOf(PriceTier(input = num(input)!!, cached = num(cached), output = num(output)!!)))
            val costs = ctl.backend.store.state.value.settings.costs
            ctl.run(Command.SaveCosts(costs.copy(overrides = costs.overrides + (line.priceKey to p))), quiet = true)
            onClose()
        }) { Text("保存") }
    }) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Text("现在用的：" + line.priceText, style = MaterialTheme.typography.bodySmall, color = Ext.c.subtle)
            Spacer(Modifier.height(10.dp))
            Segmented(listOf("CNY" to "人民币", "USD" to "美元"), if (usd) "USD" else "CNY") { usd = it == "USD" }
            Spacer(Modifier.height(10.dp))
            val unit = if (usd) "美元" else "元"
            if (isImage) {
                FieldLabel("每张多少$unit")
                AppTextField(perImage, { perImage = it.filter { c -> c.isDigit() || c == '.' } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            } else {
                FieldLabel("输入（没命中缓存），$unit / 百万 token")
                AppTextField(input, { input = it.filter { c -> c.isDigit() || c == '.' } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                FieldLabel("命中缓存的输入（可不填，按输入价算）")
                AppTextField(cached, { cached = it.filter { c -> c.isDigit() || c == '.' } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                FieldLabel("输出（含思考），$unit / 百万 token")
                AppTextField(output, { output = it.filter { c -> c.isDigit() || c == '.' } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("改了以后按一个价算（官方按输入长度分档、分高峰空闲的，会变成不分）。", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
