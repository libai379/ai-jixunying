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
    val report by produceState<CostReport?>(null, period, tick, state.settings.costs, ctl.backend) {
        val r = ctl.backend.call(Command.GetCosts(period))
        value = if (r.ok) runCatching { AppJson.decodeFromString(CostReport.serializer(), r.data) }.getOrNull() else null
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
                if (r == null) {
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
                RateCard(ctl, state.settings.costs.usdRate)
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

private fun usdText(v: Double) = "$" + (kotlin.math.round(v * 100) / 100).toString()

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
                Text((if (l.overridden) "你改过的单价：" else "") + l.priceText, style = MaterialTheme.typography.labelSmall,
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
    val platform = LocalPlatform.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("余额", style = MaterialTheme.typography.titleSmall)
                Text(result?.let { "${formatTime(it.first)}查的" } ?: "用你填的 Key 去各家查；有的平台没有查余额的接口，给你控制台链接",
                    style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
            Button(enabled = !busy, onClick = {
                busy = true
                ctl.run(Command.GetBalances, quiet = true) { r ->
                    busy = false
                    if (r.ok) runCatching { AppJson.decodeFromString(ListSerializer(BalanceInfo.serializer()), r.data) }.getOrNull()?.let {
                        val got = nowMillis() to it
                        result = got; lastBalances[ctl.remoteMode] = got
                    }
                }
            }) { Text(if (busy) "正在查…" else if (result == null) "查余额" else "再查一次") }
        }
        result?.second?.forEach { b ->
            HorizontalDivider(color = Ext.c.border, modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.provider, style = MaterialTheme.typography.bodyMedium)
                        if (b.unofficial) { Spacer(Modifier.width(6.dp)); Pill("非公开接口", Ext.c.subtle) }
                    }
                    Text(b.text, style = MaterialTheme.typography.bodySmall,
                        color = when { b.ok -> MaterialTheme.colorScheme.onSurface; b.supported -> MaterialTheme.colorScheme.error; else -> Ext.c.subtle })
                }
                if (!b.ok && b.consoleUrl.isNotBlank()) TextButton({ platform.openUrl(b.consoleUrl) }) { Text("去控制台 ›", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun RateCard(ctl: AppController, rate: Double) {
    var text by remember { mutableStateOf(rate.toString()) }
    AutoSave(text, rate.toString()) { v -> v.toDoubleOrNull()?.takeIf { it > 0 }?.let { r -> ctl.updateSettings { it.copy(costs = it.costs.copy(usdRate = r)) } } }
    SectionCard {
        FieldLabel("美元汇率", "海外服务按美元计价，合计时按这个折成人民币")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("1 美元 = ", style = MaterialTheme.typography.bodyMedium)
            AppTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, modifier = Modifier.width(110.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text(" 元", style = MaterialTheme.typography.bodyMedium)
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
            ctl.updateSettings { it.copy(costs = it.costs.copy(overrides = it.costs.overrides - line.priceKey)) }; onClose()
        }) { Text("恢复官方价") }
        Spacer(Modifier.weight(1f))
        TextButton(onClose) { Text("取消") }
        Button(enabled = valid, onClick = {
            val p = if (isImage) ModelPrice(if (usd) "USD" else "CNY", perImage = num(perImage))
                else ModelPrice(if (usd) "USD" else "CNY", listOf(PriceTier(input = num(input)!!, cached = num(cached), output = num(output)!!)))
            ctl.updateSettings { it.copy(costs = it.costs.copy(overrides = it.costs.overrides + (line.priceKey to p))) }
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
