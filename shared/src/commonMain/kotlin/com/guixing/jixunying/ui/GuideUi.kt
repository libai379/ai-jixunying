package com.guixing.jixunying.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.guixing.jixunying.model.APP_VERSION
import com.guixing.jixunying.model.AppState
import kotlinx.coroutines.delay

/**
 * 新手引导和新版介绍（2026-11-06 用户：「整个 APP 要做一个引导……新安装的，或者说是新版本更新的，都可以做一个引导和介绍。
 * 引导和介绍可以跳过，也可以下一步，也可以从『设置』>『关于』里边再次去查看。万一哪个功能找不到了，可以有迹可循」）。
 * - 新装的（这台设备没看过、也还没配过模型、没聊过）：从头讲一遍「功能介绍」。
 * - 更新的：只讲这一版新加的（按「主.次」记，修订版不重复弹）；最后一页能接着看完整介绍。
 * 看过没看过记在这台设备上（Platform.setPref），每台设备各看各的。
 */
object Guide {
    class Page(val emoji: String, val title: String, val body: String, val where: String = "")

    const val PREF = "guide.seen"

    /** 「1.20.3」→「1.20」：修订版不重复弹新版介绍。 */
    fun versionKey(v: String = APP_VERSION) = v.split('.').take(2).joinToString(".")

    val tour = listOf(
        Page("👋", "欢迎使用 AI集训营", "把国内外的大模型拉进一个应用：单聊像豆包，群聊里几个 AI 能互相 @、互相纠错。\n电脑和手机都能单独用，手机还能在任何地方遥控电脑。"),
        Page("🔑", "先接上模型", "选一家模型服务，填上它的 API Key；再建一位 AI 成员，给它起名字、选模型。之后新对话里就能找它聊。",
            "设置 → 模型服务；设置 → AI 成员"),
        Page("💬", "开始聊天", "侧栏上的「＋」开新对话，选一位或几位成员。\n群聊能选独立作答、接力讨论、只回答被 @ 的，还有会审：各自回答、匿名互评、主席汇总。\n顶上右边成员头像那里是对话设置。"),
        Page("⌨️", "输入框旁的按钮", "「＋」：发图片、发文件、@ 成员、直接画图、直接做视频。\n地球：联网开关。\n书本 / 大脑：用哪种记忆，点一下就换。\n话筒：说一段话变成文字。\n电脑上还能直接把文件拖进来。"),
        Page("🧠", "记忆", "AI 会记住关于你的事，所有成员共用，能看、能改、能整理。\n两种用法：完整记忆把关于你的整份带上；联想记忆按这会儿聊的挑相关的，久不用的往后排。\n回答下面点「换…再答一次」，两种放一起比。",
            "设置 → 记忆"),
        Page("🎨", "画图、P 图、视频、文件", "说「画一张……」就画；发一张图说要改哪儿就 P；要视频、要 Word / Excel / PPT 也直接说。用哪家的模型、多少钱，在设置里看。",
            "设置 → 画图和视频；设置 → Word / Excel / PPT"),
        Page("📱", "手机和电脑一起用", "电脑上出一个二维码，手机扫一下就配对好了；之后手机在哪儿都能用电脑上的 AI 和文件。也能把助手接进微信，在微信里找它。",
            "电脑：设置 → 联机；手机：设置 → 连接电脑；设置 → 微信"),
        Page("🛠", "Code 栏和狼人杀", "侧栏顶上切到「Code」：对着一个项目文件夹，让 AI 写代码、跑命令。\n切到「狼人杀」：看 AI 们打一局，或者你上桌一起玩。"),
        Page("🧭", "找不到功能时", "这份介绍随时能再看。每次更新以后，会先讲这一版新加了什么。", "设置 → 关于 → 功能介绍"),
    )

    /** 每一版新加的（按「主.次」）。没写的版本更新后不弹。 */
    val whatsNew: Map<String, List<Page>> = mapOf(
        "1.20" to listOf(
            Page("🧠", "两种记忆，随时切换", "同一份记忆，两种想起来的方式：\n完整记忆：关于你的整份带上（原来的做法）。\n联想记忆：按这会儿聊的挑相关的带上；很久没用到的往后排，聊到了再浮上来。记忆内容一个字不改。",
                "输入框旁的书本 / 大脑按钮；默认在 设置 → 记忆"),
            Page("🔁", "换一种记忆再答一次", "回答下面点「换联想记忆再答一次」或「换完整记忆再答一次」，原来那条留着，新的排在后面，两条都标上用的哪种，放一起比哪种好。"),
            Page("🧭", "新手引导", "新装的会从头讲一遍各功能在哪儿；以后每次更新先讲新加了什么。跳过了也没关系，随时能再看。", "设置 → 关于 → 功能介绍"),
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

/** 开着的时候盖在整个界面上（Dialog），点外面不关，只能「跳过」或看完。 */
@Composable
fun GuideDialog(mode: String, onClose: () -> Unit, onTour: () -> Unit) {
    // 这一版没写新版介绍就讲功能介绍（标题也跟着换，别说成「新加了什么」）
    val news = if (mode == "new") Guide.whatsNew[Guide.versionKey()].orEmpty() else emptyList()
    val isNew = news.isNotEmpty()
    val pages = news.ifEmpty { Guide.tour }
    var i by remember(mode) { mutableIntStateOf(0) }
    var forward by remember { mutableIntStateOf(1) }
    val last = i == pages.lastIndex
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true, usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = 460.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isNew) "AI集训营 ${APP_VERSION} 新加了什么" else "功能介绍", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle,
                        modifier = Modifier.weight(1f))
                    Text("${i + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
                }
                Spacer(Modifier.height(10.dp))
                // 一页一页滑过去：下一步从右边进、上一步从左边进，带一点回弹
                AnimatedContent(i, transitionSpec = {
                    val dir = forward
                    (slideInHorizontally(spring(dampingRatio = 0.75f, stiffness = 380f)) { w -> dir * w / 2 } + fadeIn())
                        .togetherWith(slideOutHorizontally { w -> -dir * w / 3 } + fadeOut())
                }, label = "guide") { k ->
                    val p = pages[k]
                    Column(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp).verticalScroll(rememberScrollState())) {
                        BouncyEmoji(p.emoji, k)
                        Spacer(Modifier.height(12.dp))
                        Text(p.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(p.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
                        if (p.where.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text("在哪儿：${p.where}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                // 第几页：当前那颗点拉长，弹簧过去
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    pages.indices.forEach { k ->
                        val w by animateDpAsState(if (k == i) 22.dp else 7.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "dot")
                        Box(Modifier.padding(horizontal = 3.dp).height(7.dp).width(w).clip(CircleShape)
                            .background(if (k == i) MaterialTheme.colorScheme.primary else Ext.c.border).clickable { forward = if (k > i) 1 else -1; i = k })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!last) TextButton(onClick = onClose) { Text("跳过", color = Ext.c.subtle) }
                    Spacer(Modifier.weight(1f))
                    if (i > 0) { OutlinedButton(onClick = { forward = -1; i-- }) { Text("上一步") }; Spacer(Modifier.width(8.dp)) }
                    if (last && isNew) { OutlinedButton(onClick = onTour) { Text("看完整介绍") }; Spacer(Modifier.width(8.dp)) }
                    SpringButton(if (last) (if (isNew) "知道了" else "开始使用") else "下一步") {
                        if (last) onClose() else { forward = 1; i++ }
                    }
                }
            }
        }
    }
}

/** 每翻到一页，大图标从小弹出来。 */
@Composable
private fun BouncyEmoji(emoji: String, key: Int) {
    var shown by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key) { delay(60); shown = 1 }
    val s by animateFloatAsState(if (shown == 1) 1f else 0.4f, spring(dampingRatio = 0.38f, stiffness = 320f), label = "emoji")
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 34.sp, modifier = Modifier.graphicsLayer { scaleX = s; scaleY = s })
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
