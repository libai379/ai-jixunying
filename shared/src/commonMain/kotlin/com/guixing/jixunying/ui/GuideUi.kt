package com.guixing.jixunying.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guixing.jixunying.model.APP_VERSION
import com.guixing.jixunying.model.AppState
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 新手引导和新版介绍（2026-11-06）。
 * 第一版是一张张卡片左右滑，用户：「我想要的是：打开页面之后跟它标出来，用一个箭头、一个圆圈标出来，然后在一个文字框解释这是什么……
 * 你要真正跳到应用的某个界面，每一个界面，然后详细地去解释的时候，我觉得更生动一点」。
 * 现在每一步先真的跳到那个界面（设置的某一页、对话页、手机上打开侧栏），等界面画好，把要讲的按钮挖出来圈住、外面压暗，旁边文字框加箭头指过去。
 * 界面上要被圈的东西用 Modifier.guideTarget("名字") 报自己的位置（GuideTargets）；这一步的东西不在屏幕上（比如还没有对话，没有输入框）就只出文字框。
 * - 新装的（没配模型、没聊过）：从头讲一遍「功能介绍」；更新的：只讲这一版新加的（按「主.次」记，修订版不重复弹）。
 * - 能跳过、上一步、下一步；设置 → 关于 → 功能介绍 随时再看。看过没看过记在这台设备上。
 */
object Guide {
    /** 一步：要圈的东西（null = 不圈，只出文字框）、标题、说明、在哪儿；先跳到哪个界面；手机上要不要打开侧栏。 */
    class Page(val target: String?, val emoji: String, val title: String, val body: String, val where: String = "",
               val drawer: Boolean = false, val go: (AppController) -> Unit = {})

    // 第一版卡片式引导用的是 guide.seen；11-06 改成在真界面上圈出来，换个键名，看过旧版的也再看一次新的
    const val PREF = "guide.seen.v2"

    /** 「1.20.3」→「1.20」：修订版不重复弹新版介绍。 */
    fun versionKey(v: String = APP_VERSION) = v.split('.').take(2).joinToString(".")

    private fun settings(tab: SettingsTab): (AppController) -> Unit = { c -> c.openSettings(tab); c.settingsHome = false }

    /** 回到对话页，打开最近的一个对话（有的话），好圈输入框旁的按钮。 */
    private val chat: (AppController) -> Unit = { c ->
        c.settingsTab = null; c.page = null; c.sidebarTab = SidebarTab.CHAT
        if (c.currentConvId == null) c.backend.store.state.value.conversations.filter { !it.isCode && !it.archived }
            .maxByOrNull { it.updatedAt }?.let { c.openConversation(it.id) }
    }

    val tour = listOf(
        Page(null, "👋", "欢迎使用 AI集训营", "把国内外的大模型拉进一个应用：单聊像豆包，群聊里几个 AI 能互相 @、互相纠错。电脑和手机都能单独用，手机还能在任何地方遥控电脑。\n接下来带你在真界面上走一遍。", go = chat),
        Page("pageaction", "🔑", "第一步：接上模型", "点这里选一家模型服务，填上它的 API Key。国内外三十多家都有预设，填好会自动拉出模型清单。", "设置 → 模型服务", go = settings(SettingsTab.PROVIDERS)),
        Page("pageaction", "🤖", "第二步：建 AI 成员", "点这里添加成员。每位成员 = 一个模型 + 名字和定位。成员下面的小字写着这个模型的脾气，聊天首选智谱。", "设置 → AI 成员", go = settings(SettingsTab.MEMBERS)),
        Page("newchat", "💬", "开新对话", "点这里开新对话：选一位就是单聊，选几位就是群聊。", "侧栏上方的「＋」", drawer = true, go = chat),
        Page("convset", "⚙️", "对话设置", "点成员头像这一块：换成员、改群聊方式（独立作答、接力讨论、只回答被 @ 的、会审），还能改这个对话的记忆话题。", go = chat),
        Page("plus", "➕", "发图、发文件、直接画图", "这里能发图片和文件给 AI 看，也能 @ 成员、直接画图、直接做视频。电脑上还能把文件直接拖进来。", go = chat),
        Page("web", "🌐", "联网", "打开后，AI 遇到新闻、价格、政策这类要查的，会先上网搜再答，句末标出处。", go = chat),
        Page("memory", "🧠", "两种记忆，点一下就换", "书本是完整记忆：关于你的整份带上。大脑是联想记忆：按这会儿聊的挑相关的，久不用的往后排。回答下面能「换一种记忆再答一次」，两条放一起比。", go = chat),
        Page("mic", "🎙️", "说话变文字", "点一下开始说，再点一下停，认出来的字填进输入框，你改一改再发。", go = chat),
        Page("sidetabs", "🛠️", "Code 栏和狼人杀", "切到「Code」：对着一个项目文件夹让 AI 写代码、跑命令。切到「狼人杀」：看 AI 们打一局，或者你上桌一起玩。", "侧栏顶上", drawer = true, go = chat),
        Page("memmode", "📒", "记忆在这里管", "AI 记住的关于你的事都在这一页：能看、能改、能整理，也在这里定新对话默认用哪种记忆。", "设置 → 记忆", go = settings(SettingsTab.MEMORY)),
        Page("pageheader", "📱", "手机和电脑一起用", "电脑上这一页出二维码，手机扫一下就配好了；之后手机在哪儿都能用电脑上的 AI 和文件。微信也能接进来。", "电脑：设置 → 联机；手机：设置 → 连接电脑", go = settings(SettingsTab.DEVICES)),
        Page("aboutguide", "🧭", "找不到功能时", "这份介绍随时能在这里再看一遍。每次更新以后，会先带你看这一版新加了什么。", "设置 → 关于 → 功能介绍", go = settings(SettingsTab.ABOUT)),
    )

    /** 每一版新加的（按「主.次」）。没写的版本更新后不弹。 */
    val whatsNew: Map<String, List<Page>> = mapOf(
        "1.20" to listOf(
            Page("memory", "🧠", "两种记忆，随时切换", "同一份记忆，两种想起来的方式。书本 = 完整记忆，关于你的整份带上；大脑 = 联想记忆，按这会儿聊的挑相关的，很久没用到的往后排，聊到了再浮上来。点一下就换。", go = chat),
            Page(null, "🔁", "换一种记忆再答一次", "每条回答下面多了「换联想记忆再答一次」「换完整记忆再答一次」：原来那条留着，新的排在后面，两条都标上用的哪种，放一起比。", go = chat),
            Page("memmode", "📒", "默认用哪种，在这里定", "新对话默认用哪种记忆，在这一页选；每个对话里还能随时切。", "设置 → 记忆", go = settings(SettingsTab.MEMORY)),
            Page("pageaction", "🤖", "每个模型的脾气", "成员下面多了一行小字，写着这个模型测出来的毛病；聊天首选智谱，它最干净。", "设置 → AI 成员", go = settings(SettingsTab.MEMBERS)),
            Page("aboutguide", "🧭", "随时再看", "跳过了也没关系，完整的功能介绍在这里。", "设置 → 关于 → 功能介绍", go = settings(SettingsTab.ABOUT)),
        ),
    )

    /** 这台设备该弹哪种："tour" 从头讲、"new" 新版介绍、null 不弹。 */
    fun due(seen: String?, state: AppState, version: String = APP_VERSION): String? {
        val key = versionKey(version)
        if (seen == key) return null
        // 没看过引导的老用户（引导是 1.20 才有的）：已经配过模型、聊过天，就不从头讲，只讲新版
        val used = state.providers.isNotEmpty() || state.conversations.isNotEmpty()
        return when {
            seen == null && !used -> "tour"
            whatsNew.containsKey(key) -> "new"
            else -> null
        }
    }
}

/** 界面上能被引导圈住的东西的位置（相对整个窗口）。 */
object GuideTargets {
    val rects = mutableStateMapOf<String, Rect>()
    /** 引导这一步要圈的（它在滚动区域里、不在屏幕上时自己滚过来）。 */
    var active by mutableStateOf<String?>(null)
}

/** 报这个东西在屏幕上的位置，引导要圈它时用；离开屏幕就撤掉。引导轮到它时，在滚动区域里的话自己滚到看得见。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.guideTarget(key: String): Modifier = composed {
    DisposableEffect(key) { onDispose { GuideTargets.rects.remove(key) } }
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(GuideTargets.active == key) { if (GuideTargets.active == key) runCatching { bring.bringIntoView() } }
    Modifier.bringIntoViewRequester(bring).onGloballyPositioned { c -> GuideTargets.rects[key] = c.boundsInRoot() }
}

/**
 * 盖在整个界面上（放在根 Box 里，坐标和 boundsInRoot 一致）：外面压暗，圈住的地方挖空，外圈一圈呼吸的光；
 * 文字框放在圈的上面或下面（看圈在屏幕上半还是下半），带一条弯箭头指过去。点暗处不关，只能「跳过」或看完。
 */
@Composable
fun GuideOverlay(ctl: AppController, mode: String, onClose: () -> Unit) {
    val news = if (mode == "new") Guide.whatsNew[Guide.versionKey()].orEmpty() else emptyList()
    val isNew = news.isNotEmpty()
    val pages = news.ifEmpty { Guide.tour }
    var i by remember(mode) { mutableIntStateOf(ctl.guideStart.coerceIn(0, pages.lastIndex)) }
    var ready by remember { mutableStateOf(false) }
    val page = pages[i.coerceIn(0, pages.lastIndex)]
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 860.dp
        // 先跳到那个界面（手机上要圈侧栏里的就先打开侧栏），要圈的东西不在屏幕里就滚过去，等它画好再圈
        LaunchedEffect(i, mode) {
            ready = false
            page.go(ctl)
            ctl.guideDrawer = narrow && page.drawer
            GuideTargets.active = page.target
            delay(if (narrow && page.drawer) 700 else 480)
            ready = true
        }
        DisposableEffect(Unit) { onDispose { GuideTargets.active = null } }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val rect = if (ready) page.target?.let { GuideTargets.rects[it] }?.takeIf { it.width > 0f && it.bottom > 0f && it.top < h } else null
        val pad = with(density) { 8.dp.toPx() }
        // 小按钮用圆圈；宽的一块（标题、一排选项）用贴边的圆角框，不然圆圈大到盖住半个屏幕
        val round = rect != null && maxOf(rect.width, rect.height) <= with(density) { 120.dp.toPx() } && rect.width / rect.height in 0.4f..2.6f
        val tl = rect?.let { if (round) it.center.x - maxOf(it.width, it.height) / 2 - pad else it.left - pad } ?: (w / 2)
        val tt = rect?.let { if (round) it.center.y - maxOf(it.width, it.height) / 2 - pad else it.top - pad } ?: (h / 2)
        val trr = rect?.let { if (round) it.center.x + maxOf(it.width, it.height) / 2 + pad else it.right + pad } ?: (w / 2)
        val tb = rect?.let { if (round) it.center.y + maxOf(it.width, it.height) / 2 + pad else it.bottom + pad } ?: (h / 2)
        // 圈在步骤之间弹过去
        val sp = spring<Float>(dampingRatio = 0.68f, stiffness = 260f)
        val l by animateFloatAsState(tl, sp, label = "l")
        val t by animateFloatAsState(tt, sp, label = "t")
        val r by animateFloatAsState(trr, sp, label = "r")
        val b by animateFloatAsState(tb, sp, label = "b")
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "p")
        val corner = with(density) { 16.dp.toPx() }

        // 压暗 + 挖空 + 光圈；点暗处什么也不做（挡住下面的界面）
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).pointerInput(Unit) { detectTapGestures { } }) {
            drawRect(Color.Black.copy(alpha = 0.58f))
            if (rect != null && r - l > 2f) {
                val glow = pulse * 16.dp.toPx()
                if (round) {
                    val c = Offset((l + r) / 2, (t + b) / 2)
                    val rad = (r - l) / 2
                    drawCircle(Color.Black, rad, c, blendMode = BlendMode.Clear)
                    drawCircle(primary, rad, c, style = Stroke(3.dp.toPx()))
                    drawCircle(primary.copy(alpha = (1f - pulse) * 0.7f), rad + glow, c, style = Stroke(2.dp.toPx()))
                } else {
                    val cr = androidx.compose.ui.geometry.CornerRadius(corner, corner)
                    drawRoundRect(Color.Black, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), cr, blendMode = BlendMode.Clear)
                    drawRoundRect(primary, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), cr, style = Stroke(3.dp.toPx()))
                    drawRoundRect(primary.copy(alpha = (1f - pulse) * 0.7f), Offset(l - glow, t - glow), androidx.compose.ui.geometry.Size(r - l + 2 * glow, b - t + 2 * glow),
                        androidx.compose.ui.geometry.CornerRadius(corner + glow, corner + glow), style = Stroke(2.dp.toPx()))
                }
            }
        }

        // 文字框：宽最多 360dp；圈在上半屏就放圈下面，在下半屏就放圈上面；没有圈就居中
        val margin = with(density) { 16.dp.toPx() }
        val bw = minOf(w - 2 * margin, with(density) { 360.dp.toPx() })
        var bh by remember { mutableIntStateOf(0) }
        val gap = with(density) { 52.dp.toPx() }
        val cxT = (tl + trr) / 2
        val below = rect != null && (tt + tb) / 2 < h / 2
        val bx = if (rect == null || w - margin - bw < margin) (w - bw) / 2 else (cxT - bw / 2).coerceIn(margin, w - margin - bw)
        val by = when {
            rect == null -> (h - bh) / 2
            below -> minOf(tb + gap, h - margin - bh).coerceAtLeast(0f)
            else -> maxOf(tt - gap - bh, margin)
        }
        // 弯箭头：从文字框靠近圈的那条边，指到圈边上
        if (rect != null && bh > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val sx = if (bw > 48.dp.toPx()) cxT.coerceIn(bx + 24.dp.toPx(), bx + bw - 24.dp.toPx()) else bx + bw / 2
                val sy = if (below) by - 4.dp.toPx() else by + bh + 4.dp.toPx()
                val (ex, ey) = if (round) {
                    val c = Offset((l + r) / 2, (t + b) / 2)
                    val ang = atan2(c.y - sy, c.x - sx)
                    (c.x - cos(ang) * ((r - l) / 2 + 6.dp.toPx())) to (c.y - sin(ang) * ((r - l) / 2 + 6.dp.toPx()))
                } else (if (r - l > 32.dp.toPx()) sx.coerceIn(l + 16.dp.toPx(), r - 16.dp.toPx()) else (l + r) / 2) to (if (below) b + 6.dp.toPx() else t - 6.dp.toPx())
                if (hypot(ex - sx, ey - sy) > 12.dp.toPx()) {
                    val bend = 26.dp.toPx() * (if (sx < ex) 1 else -1)
                    val mx = (sx + ex) / 2 + bend
                    val my = (sy + ey) / 2
                    drawPath(Path().apply { moveTo(sx, sy); quadraticTo(mx, my, ex, ey) }, Color.White, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                    val a2 = atan2(ey - my, ex - mx)
                    val len = 11.dp.toPx()
                    drawLine(Color.White, Offset(ex, ey), Offset(ex - len * cos(a2 - 0.5f), ey - len * sin(a2 - 0.5f)), 2.5.dp.toPx(), StrokeCap.Round)
                    drawLine(Color.White, Offset(ex, ey), Offset(ex - len * cos(a2 + 0.5f), ey - len * sin(a2 + 0.5f)), 2.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        // 每换一步，文字框弹一下
        var popped by remember(i) { mutableStateOf(false) }
        LaunchedEffect(i, ready) { if (ready) popped = true }
        val pop by animateFloatAsState(if (popped) 1f else 0.85f, spring(dampingRatio = 0.5f, stiffness = 380f), label = "pop")
        Surface(
            Modifier.offset { IntOffset(bx.roundToInt(), by.roundToInt()) }.width(with(density) { bw.toDp() })
                .onSizeChanged { bh = it.height }.graphicsLayer { scaleX = pop; scaleY = pop; alpha = if (ready) 1f else 0.6f },
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row {
                    Text(if (isNew) "${APP_VERSION} 新加了什么" else "功能介绍", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle, modifier = Modifier.weight(1f))
                    Text("${i + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(page.emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(page.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(page.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 21.sp)
                if (page.where.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("在哪儿：${page.where}", style = MaterialTheme.typography.labelMedium, color = primary)
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    if (i < pages.lastIndex) TextButton(onClick = onClose) { Text("跳过", color = Ext.c.subtle) }
                    Spacer(Modifier.weight(1f))
                    if (i > 0) { OutlinedButton(onClick = { i-- }) { Text("上一步") }; Spacer(Modifier.width(8.dp)) }
                    if (i == pages.lastIndex && isNew) { OutlinedButton(onClick = { ctl.guideStart = 0; ctl.guide = "tour" }) { Text("完整介绍") }; Spacer(Modifier.width(8.dp)) }
                    SpringButton(if (i == pages.lastIndex) (if (isNew) "知道了" else "开始使用") else "下一步") {
                        if (i == pages.lastIndex) onClose() else i++
                    }
                }
            }
        }
    }
}

/** 按下去缩一下、松手弹回来的主按钮。 */
@Composable
fun SpringButton(label: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "btn")
    Button(onClick = onClick, interactionSource = press, modifier = Modifier.graphicsLayer { scaleX = s; scaleY = s }) { Text(label) }
}
