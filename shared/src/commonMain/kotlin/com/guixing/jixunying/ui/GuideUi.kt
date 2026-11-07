package com.guixing.jixunying.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
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
 * - 新装的（没配模型、没聊过）：从头讲「新手必看」；更新的：只讲这一版新加的（按「主.次」记，修订版不重复弹）。
 * - 能跳过、上一步、下一步；设置 → 关于 → 功能介绍 随时再看，每一章能单独打开。看过没看过记在这台设备上。
 *
 * 2026-11-07 完整引导（用户 11-06：「我觉得引导还是太简单了。我看你只做了最近的几个新功能……怎么去加模型、怎么去加成员、里边有什么功能、
 * 怎么切换生图的或者说生视频的模型、怎么去让它自动选等等……除了那种特别简单的功能，我觉得所有功能都要来一个更好的、更完整的引导」）：
 * 分成章节（新用户必看 3 章 + 进阶 8 章）；添加服务商、添加成员、对话设置这几个弹窗由引导自己打开，弹窗里一栏一栏圈出来
 * （弹窗是另一层，主界面的遮罩画不进去：要圈的那一栏自己画一圈，弹窗顶上放同样的说明框，见 GuideDialogCard）。
 * 同一天改动画（用户 11-06：「我点下一步，这个动画它落下去，隔了一段时间才起来……有点生硬」）：不再先缩下去等界面，
 * 内容立刻淡入淡出，圈先滑到新位置、文字框跟着移过去；同一页上的不等，换页面最多等到新目标位置稳下来。
 */
object Guide {
    /** 一步：要圈的东西（null = 不圈，只出文字框）、标题、说明、在哪儿；手机上要不要打开侧栏；要打开哪个弹窗；先跳到哪个界面。 */
    class Page(val target: String?, val emoji: String, val title: String, val body: String, val where: String = "",
               val drawer: Boolean = false, val dialog: String? = null, val go: (AppController) -> Unit = {})

    /** 一章：编号、名字、一句话讲什么、是不是新用户必看。 */
    class Chapter(val id: String, val title: String, val desc: String, val essential: Boolean, val pages: List<Page>)

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

    /** 对话页，记下最后一条答完的 AI 回答，圈它下面那排按钮。 */
    private val lastAnswer: (AppController) -> Unit = { c ->
        chat(c)
        c.guideAnswerId = c.currentConvId?.let { c.backend.store.messages.value[it] }
            ?.lastOrNull { it.role != Role.USER && it.status == MsgStatus.DONE && it.content.isNotBlank() }?.id
    }

    /** 侧栏切到狼人杀（主区域是开局页）。 */
    private val wolf: (AppController) -> Unit = { c ->
        c.settingsTab = null; c.page = null; c.sidebarTab = SidebarTab.WOLF; c.wolfView = ""; c.wolfShowLast = false
    }

    private val welcome = Page(null, "👋", "欢迎使用 AI集训营",
        "把国内外的大模型拉进一个应用：单聊像豆包，群聊里几个 AI 能互相 @、互相纠错。电脑和手机都能单独用，手机还能在任何地方遥控电脑。\n" +
            "接下来在真界面上带你走一遍：先接上模型、建 AI 成员，再开始聊天。每一步都可以跟着点一点。", go = chat)

    private val moreChapters = Page("aboutguide", "🧭", "还有更多",
        "画图和视频、联网搜索、文档和 Office、记忆、语音、手机和微信、Code 栏、狼人杀、花费……每一章都能在这里单独打开看。更新以后也会先带你看这一版新加了什么。",
        "设置 → 关于 → 功能介绍", go = settings(SettingsTab.ABOUT))

    val chapters: List<Chapter> = listOf(
        Chapter("models", "接上模型", "选一家模型服务、填 Key、拉出模型", true, listOf(
            Page("pageaction", "🔑", "添加服务商",
                "AI集训营自己不带模型，用的是各家的模型服务。点「添加服务商」接上一家：国内的 DeepSeek、智谱、Kimi、通义千问、豆包，国外的 OpenAI、Claude、Gemini 等都有预设。",
                "设置 → 模型服务", go = settings(SettingsTab.PROVIDERS)),
            Page("pv.preset", "🏷️", "先选一家",
                "点这里展开列表，能搜名字。选好以后名称和接口地址自动填好，一般不用改。", "添加服务商 → 服务商",
                dialog = "provider", go = settings(SettingsTab.PROVIDERS)),
            Page("pv.key", "🗝️", "填 API Key",
                "Key 是这家平台给你的一串密码，按用量扣费。没有的话点右边「去获取 Key」，到它的官网注册、创建一个 Key，复制回来粘贴到这里。" +
                    "Key 只存在这台设备上。选「ChatGPT 订阅」的不用填 Key，用 ChatGPT 账号登录。", "添加服务商 → API Key",
                dialog = "provider", go = settings(SettingsTab.PROVIDERS)),
            Page("pv.proxy", "🌏", "海外的要走代理",
                "OpenAI、Claude、Gemini 这类海外服务商在国内连不上，要打开「走代理」，选海外的一般会自动打开；代理地址在 设置 → 外观 里填。国内的不用开。",
                "添加服务商 → 走代理", dialog = "provider", go = settings(SettingsTab.PROVIDERS)),
            Page("pv.fetch", "🔄", "拉取模型列表",
                "填好 Key 点这里，这家能用的模型就都列出来了。列表里没有的，也能在最下面手动加模型名。", "添加服务商 → 拉取模型列表",
                dialog = "provider", go = settings(SettingsTab.PROVIDERS)),
            Page("pv.models", "🧩", "聊天还是画图",
                "每个模型标好是「聊天」还是「画图」，能看图的勾上「能看图」。聊天模型拿来当 AI 成员；画图模型只用来出图，聊天里说「画一张……」会自动用上。",
                "添加服务商 → 模型", dialog = "provider", go = settings(SettingsTab.PROVIDERS)),
            Page("pv.test", "✅", "测试连通，再保存",
                "点「测试连通」让它答一句，通了再点「保存」。以后在列表里随时能编辑、停用。", "添加服务商 → 底下的按钮",
                dialog = "provider", go = settings(SettingsTab.PROVIDERS)),
        )),
        Chapter("members", "建 AI 成员", "选模型、起名字、写定位、定思考深浅", true, listOf(
            Page("pageaction", "🤖", "添加成员",
                "每位成员 = 一个模型 + 名字和定位。点「添加成员」建一位。同一个模型可以建好几位，定位不同，说话就不一样。",
                "设置 → AI 成员", go = settings(SettingsTab.MEMBERS)),
            Page("mb.templates", "📋", "从模板快速加",
                "不想自己写，就点这里的模板，名字和定位都带好了，再选个模型就能用。", "AI 成员 → 从模板快速添加", go = settings(SettingsTab.MEMBERS)),
            Page("mb.name", "✏️", "起名字", "起个好记的名字，再挑个头像和颜色。群聊里用「@名字」点它回答。", "添加成员 → 名字",
                dialog = "member", go = settings(SettingsTab.MEMBERS)),
            Page("mb.bio", "🎭", "定位 / 人设",
                "写它是谁、怎么说话，比如「资深律师，回答严谨，会说明法律依据和风险」。它照这个回答，群里其他成员也知道它是干什么的。", "添加成员 → 定位 / 人设",
                dialog = "member", go = settings(SettingsTab.MEMBERS)),
            Page("mb.model", "🧠", "选服务商和模型",
                "先选服务商，再选模型；模型名也能直接输入。这里只列聊天模型。", "添加成员 → 服务商、模型",
                dialog = "member", go = settings(SettingsTab.MEMBERS)),
            Page("mb.thinking", "💭", "思考深浅",
                "默认 = 模型自己定；快速 = 不先想、直接答，回得快；深度 = 想透再答，适合难题。这个模型做不到的那项会灰掉。", "添加成员 → 思考",
                dialog = "member", go = settings(SettingsTab.MEMBERS)),
            Page("mb.first", "⭐", "看小字挑模型",
                "成员下面的小字写着这个模型测出来的毛病，带 ★ 的是聊天首选。哪里不满意，点右边的铅笔随时改。", "设置 → AI 成员",
                go = settings(SettingsTab.MEMBERS)),
        )),
        Chapter("chat", "开始聊天", "单聊群聊、对话设置、发图发文件、联网、记忆、说话", true, listOf(
            Page("newchat", "💬", "开新对话", "点「＋」开新对话：选一位成员就是单聊，选几位就是群聊。", "侧栏上方的「＋」", drawer = true, go = chat),
            Page("convset", "⚙️", "对话设置", "点顶上成员这一块，打开这个对话的设置：换成员、改标题、群聊怎么回答、联网、记忆话题。", "对话顶上", go = chat),
            Page("cs.mode", "🗣️", "群聊怎么回答",
                "选了两位以上才有：独立作答（推荐）= 各答各的、互相看不到，避免跟风附和；接力讨论 = 依次发言，后面的看得到前面的；只回答被 @ 的 = 没 @ 人时只让第一位答，省 token。",
                "对话设置 → 没人被 @ 时怎么回答", dialog = "convset", go = chat),
            Page("cs.council", "⚖️", "会审模式",
                "重要、有争议的问题打开它：大家先各自回答，再匿名互评、排名次，最后由中立的主席写最终答案，写清分歧和少数派意见。每个问题要多调几次模型。",
                "对话设置 → 会审模式", dialog = "convset", go = chat),
            Page("plus", "➕", "发图、发文件、直接画图",
                "「＋」里有：图片、文件（PDF、Word、Excel……）发给 AI 看；「@ 成员」点名谁来答；「直接画图」「直接做视频」不经过 AI，你写的话直接当画面描述。电脑上还能把文件直接拖进来。",
                "输入框左边的「＋」", go = chat),
            Page("web", "🌐", "联网", "打开后，AI 遇到新闻、价格、政策这类要查的，会先上网搜再答，句末标出处。", "输入框旁", go = chat),
            Page("memory", "🧠", "两种记忆，点一下就换",
                "书本是完整记忆：关于你的整份带上。大脑是联想记忆：按这会儿聊的挑相关的，久不用的往后排。回答下面能「换一种记忆再答一次」，两条放一起比。",
                "输入框旁", go = chat),
            Page("mic", "🎙️", "说话变文字", "点一下开始说，再点一下停，认出来的字填进输入框，你改一改再发。", "输入框旁的话筒", go = chat),
            Page("msgactions", "🔊", "回答下面的按钮",
                "每条回答下面能复制、读出来、重新回答、换一种记忆再答一次；开着 Office 导出时还能存成 Word，有表格的能存成 Excel。", "每条回答下面", go = lastAnswer),
            Page("filemode", "🛡️", "AI 动手前问不问（电脑上）",
                "AI 改文件、删东西、跑命令之前要不要问你：「自动」让检查员先看、拿不准再问；「动手先问我」每次都问；「全部放开」都不问，只有危险命令问一次。改之前都会备份，能撤销。",
                "电脑上输入框旁", go = chat),
        )),
        Chapter("image", "画图和视频", "怎么选画图 / 视频模型、自动选、直接画", false, listOf(
            Page("pageheader", "🎨", "不用专门设置",
                "在任何对话里说「画一张……」「做一段……的视频」，AI 会自己调用画图 / 视频模型；发一张图说要改哪里，它能直接 P 图。画图和聊天用同一家的 Key。",
                "设置 → 画图和视频", go = settings(SettingsTab.IMAGE)),
            Page("img.pick", "🖼️", "用哪个画图模型",
                "选「自动（推荐）」就按画质排好的顺序用，失败了（比如余额用完）自动换下一家；也能点某一个，固定用它。列表来自你接上的服务商，没有的话先到「模型服务」接一家能画图的。",
                "画图和视频 → 用哪个画图模型", go = settings(SettingsTab.IMAGE)),
            Page("img.retry", "🔁", "画不好自己换模型再试",
                "画得不好、P 得不好时，AI 会自己换一个模型再做。这里限定每次回答最多再花多少钱。", "画图和视频 → AI 换模型再试", go = settings(SettingsTab.IMAGE)),
            Page("vid.on", "🎬", "做视频",
                "先打开「让 AI 做视频」，再选用哪家、哪个模型、清晰度和默认时长。视频按秒收费，做一段要一到几分钟；要先接上千问AI平台、阿里百炼或 MiniMax 中国版。",
                "画图和视频 → 让 AI 做视频", go = settings(SettingsTab.IMAGE)),
            Page("plus", "🖌️", "不经过 AI 直接画",
                "输入框「＋」里的「直接画图」「直接做视频」：你写的话直接当画面描述，只画这一张，不让 AI 改写。", "输入框左边的「＋」", go = chat),
        )),
        Chapter("search", "联网搜索", "用谁的搜索、每个对话怎么开", false, listOf(
            Page("search.mode", "🔎", "联网方式",
                "默认「自动（推荐）」：智谱、千问用平台自带的搜索，Kimi、DeepSeek 用自家的官方搜索，出错了自动换下面选的引擎。也能让所有模型统一用下面选的搜索引擎，有的引擎要填 Key。",
                "设置 → 联网搜索", go = settings(SettingsTab.SEARCH)),
            Page("web", "🌐", "每个对话自己开关", "每个对话的「联网」开着，AI 才会在这个对话里上网查。", "输入框旁", go = chat),
        )),
        Chapter("docs", "文档和 Office", "读你的文档、按意思搜、做成 Word / Excel / PPT", false, listOf(
            Page("qdocs", "📂", "我的文档",
                "AI 能搜、能读这台设备上的文档（PDF、Word、Excel、PPT、文本）。聊天里直接问「我那份合同里付款日期是哪天」就行；也能点这里找到文件，点「问 AI」。",
                "侧栏「我的文档」", drawer = true, go = chat),
            Page("pageheader", "🧭", "按意思搜",
                "换个说法也能搜到以前聊过的、文档里写过的。要先用选的服务商把聊天记录和文档「算一遍」，按它的价格收费，打开之前会先告诉你要花多少。",
                "设置 → 按意思搜", go = settings(SettingsTab.SEMANTIC)),
            Page("pageheader", "📄", "做成 Word / Excel / PPT",
                "说「做成 Word」「整理成表格发我」「做个汇报 PPT」就行，做好的文件显示在回答下面，点一下打开。这一页定 Word 的样式：通用、正式、公文。",
                "设置 → Word / Excel / PPT", go = settings(SettingsTab.OFFICE)),
        )),
        Chapter("memory", "记忆和立场档案", "AI 记住你什么、个性化、谁说得准", false, listOf(
            Page("memmode", "📒", "记忆在这里管",
                "AI 记住的关于你的事都在这一页：能看、能改、能整理，也在这里定新对话默认用哪种记忆。", "设置 → 记忆", go = settings(SettingsTab.MEMORY)),
            Page("pageheader", "🙋", "个性化", "写下你的身份、偏好和要它一直照着做的规矩，所有成员回答都照着来。", "设置 → 个性化",
                go = settings(SettingsTab.PROFILE)),
            Page("qstances", "🚩", "立场档案",
                "群聊独立作答时，记录员记下每位成员一开始怎么说、后来谁改了口、为什么改。你标一下谁说对了，就能看出谁首答准、谁容易被带偏。",
                "侧栏「立场档案」", drawer = true, go = chat),
        )),
        Chapter("voice", "语音", "读出来、听写用哪家", false, listOf(
            Page("pageheader", "🔊", "读出来和听写",
                "回答下面的「读出来」把回答念给你听，输入框旁的话筒把你说的话变成字。用哪家在这一页选，默认「自动」挑你已经接上的；还能打开「回答完自动读」。",
                "设置 → 语音", go = settings(SettingsTab.SPEECH)),
        )),
        Chapter("devices", "手机、电脑和微信", "配对、本机和电脑来回切、接微信", false, listOf(
            Page("devpair", "📱", "手机和电脑配对",
                "电脑上 设置 → 手机联机 有二维码，手机在 设置 → 连接电脑 里点「扫码配对」扫一下就配好了，不用同一个 Wi-Fi。配好以后两边自动同步模型服务、成员、记忆和微信聊天记录。",
                "电脑：设置 → 手机联机；手机：设置 → 连接电脑", go = settings(SettingsTab.DEVICES)),
            Page("devmenu", "🔀", "本机和电脑来回切",
                "手机上，侧栏顶上这个按钮点一下就在「本机」和「电脑」之间切：切到电脑就是遥控电脑上的 AI集训营，用电脑上的模型和文件；电脑没开时会自动回到本机。电脑上点它打开「手机联机」。",
                "侧栏顶上", drawer = true, go = chat),
            Page("wx.bind", "💚", "接上微信",
                "点「绑定微信」出二维码，用手机微信扫一下，以后在微信里给助理发文字、图片、文件、语音，AI 成员回答。电脑开着电脑答，电脑关了由手机接着答。",
                "设置 → 微信", go = settings(SettingsTab.WEIXIN)),
            Page("wx.who", "👥", "谁来回答微信",
                "选一位就是单聊，选几位就是群聊，每位的回答各发一条。在应用里的微信对话里换成员，也会记到这里。", "设置 → 微信", go = settings(SettingsTab.WEIXIN)),
        )),
        Chapter("code", "Code 栏和狼人杀", "让 AI 写代码、看 AI 打狼人杀", false, listOf(
            Page("sidetabs", "🛠️", "侧栏顶上的几栏", "「对话」是平常聊天；「Code」对着一个项目文件夹让 AI 写代码（手机上切到电脑才有）；「狼人杀」看 AI 们打一局，或者你上桌一起玩。",
                "侧栏顶上", drawer = true, go = chat),
            Page("pageheader", "💻", "Code 栏",
                "侧栏切到「Code」，选一个项目文件夹，让 AI 读代码、改文件、跑命令。这一页设命令用哪个外壳、外部的 Claude Code / Codex 在哪儿；想在手机上用，要在电脑上的这一页打开「允许手机用 Code 栏」。",
                "设置 → Code 栏", go = settings(SettingsTab.CODE)),
            Page("wolfmodes", "🐺", "狼人杀",
                "「我当主持人」全是 AI 打，你看得到所有身份，可以暂停、一步一步来、白天插话；「我上桌玩」你（和朋友）坐几个座位跟 AI 一起打。",
                "侧栏「狼人杀」", go = wolf),
        )),
        Chapter("more", "花费、更新和外观", "花了多少钱、检查更新、主题和代理、账号", false, listOf(
            Page("costs", "💰", "花了多少钱",
                "按各家官方价格估算每次调用花了多少，按天、按模型都能看；不是账单，价格变了能自己改。", "侧栏最底下「花费」", drawer = true, go = chat),
            Page("update", "⬆️", "检查更新", "有新版本时在这里更新。手机先从国内镜像下，不行再走 GitHub，还不行可以请电脑代下。", "设置 → 关于",
                go = settings(SettingsTab.ABOUT)),
            Page("pageheader", "🎨", "外观和代理",
                "主题、背景、液态玻璃在这里改，每台设备各管各的。海外服务商用的代理地址也在这一页。", "设置 → 外观", go = settings(SettingsTab.APPEARANCE)),
            Page("account", "👤", "账号和备份", "账号不用注册：用 GitHub 登录，换设备时用备份码带过去。", "侧栏最底下左边", drawer = true, go = chat),
        )),
    )

    /** 新手必看：欢迎 + 必看的几章 + 别的章在哪儿。 */
    val tour: List<Page> = listOf(welcome) + chapters.filter { it.essential }.flatMap { it.pages } + moreChapters

    fun chapter(id: String): Chapter? = chapters.firstOrNull { it.id == id }

    /** 每一版新加的（按「主.次」）。没写的版本更新后不弹。 */
    val whatsNew: Map<String, List<Page>> = mapOf(
        "1.20" to listOf(
            Page("memory", "🧠", "两种记忆，随时切换", "同一份记忆，两种想起来的方式。书本 = 完整记忆，关于你的整份带上；大脑 = 联想记忆，按这会儿聊的挑相关的，很久没用到的往后排，聊到了再浮上来。点一下就换。", go = chat),
            Page(null, "🔁", "换一种记忆再答一次", "每条回答下面多了「换联想记忆再答一次」「换完整记忆再答一次」：原来那条留着，新的排在后面，两条都标上用的哪种，放一起比。", go = chat),
            Page("memmode", "📒", "默认用哪种，在这里定", "新对话默认用哪种记忆，在这一页选；每个对话里还能随时切。", "设置 → 记忆", go = settings(SettingsTab.MEMORY)),
            Page("pageaction", "🤖", "每个模型的脾气", "成员下面多了一行小字，写着这个模型测出来的毛病；聊天首选智谱，它最干净。", "设置 → AI 成员", go = settings(SettingsTab.MEMBERS)),
            Page("aboutguide", "🧭", "随时再看", "跳过了也没关系，完整的功能介绍在这里，每一章都能单独看。", "设置 → 关于 → 功能介绍", go = settings(SettingsTab.ABOUT)),
        ),
    )

    /** 这次要走的几步："new" 这一版新加的、"ch:编号" 某一章、其他（"tour"）新手必看。 */
    fun pagesFor(mode: String): List<Page> = when {
        mode == "new" -> whatsNew[versionKey()].orEmpty().ifEmpty { tour }
        mode.startsWith("ch:") -> chapter(mode.removePrefix("ch:"))?.pages ?: tour
        else -> tour
    }

    /** 文字框左上角的小字：在看哪一部分。 */
    fun labelFor(mode: String): String = when {
        // 新版介绍按「主.次」写的：修订版（1.20.2）看到的是 1.20 加的，标「1.20 新加了什么」
        mode == "new" && whatsNew.containsKey(versionKey()) -> "${versionKey()} 新加了什么"
        mode.startsWith("ch:") -> chapter(mode.removePrefix("ch:"))?.let { "功能介绍 · ${it.title}" } ?: "功能介绍"
        else -> "新手必看"
    }

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

/** 界面上能被引导圈住的东西的位置（相对它所在的窗口：主界面的是整个窗口，弹窗里的是弹窗那一层）。 */
object GuideTargets {
    val rects = mutableStateMapOf<String, Rect>()
    /** 引导这一步要圈的（它在滚动区域里、不在屏幕上时自己滚过来）。 */
    var active by mutableStateOf<String?>(null)
    /** 这一步要圈的在弹窗里：主界面的遮罩画不进弹窗，由它自己画一圈（见 guideTarget）。 */
    var inDialog by mutableStateOf(false)
}

/** 引导正在走的那一步，给弹窗顶上的说明框用（主界面上的文字框被弹窗挡住了）。 */
object GuideRun {
    var page by mutableStateOf<Guide.Page?>(null)
    var label by mutableStateOf("")
    var index by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var prev: () -> Unit = {}
    var next: () -> Unit = {}
    var skip: () -> Unit = {}
}

/**
 * 报这个东西在屏幕上的位置，引导要圈它时用；离开屏幕就撤掉。引导轮到它时，在滚动区域里的话自己滚到看得见。
 * 在弹窗里时（GuideTargets.inDialog）自己画一圈会呼吸的框。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.guideTarget(key: String): Modifier = composed {
    DisposableEffect(key) { onDispose { GuideTargets.rects.remove(key) } }
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val on = GuideTargets.active == key
    // 刚打开的弹窗、刚换的页面还没排好版时滚不动（11-07 截图：弹窗下面的「模型」那一栏没滚上来）：等一下再滚，多试两次
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        repeat(3) { n -> delay(if (n == 0) 30L else 150L); runCatching { bring.bringIntoView() } }
    }
    val ring = on && GuideTargets.inDialog
    val primary = MaterialTheme.colorScheme.primary
    val pulse: State<Float>? = if (ring) rememberInfiniteTransition(label = "ring")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "r") else null
    Modifier.bringIntoViewRequester(bring).onGloballyPositioned { c -> GuideTargets.rects[key] = c.boundsInRoot() }
        .then(if (pulse == null) Modifier else Modifier.drawWithContent {
            drawContent()
            val p = pulse.value
            // 上下往里收一点（贴着弹窗滚动区上下边的那一栏，画在外面会被裁掉一截；滚动区只裁上下），左右往外放一点（不压字）
            val ox = 6.dp.toPx()
            val oy = -2.dp.toPx()
            val g = p * 6.dp.toPx()
            val cr = 12.dp.toPx()
            drawRoundRect(primary, Offset(-ox, -oy), Size(size.width + 2 * ox, size.height + 2 * oy), CornerRadius(cr, cr), style = Stroke(2.5.dp.toPx()))
            drawRoundRect(primary.copy(alpha = (1f - p) * 0.6f), Offset(-ox - g, -oy - g), Size(size.width + 2 * (ox + g), size.height + 2 * (oy + g)),
                CornerRadius(cr + g, cr + g), style = Stroke(2.dp.toPx()))
        })
}

/**
 * 盖在整个界面上（放在根 Box 里，坐标和 boundsInRoot 一致）：外面压暗，圈住的地方挖空，外圈一圈呼吸的光；
 * 文字框放在圈的上面或下面（看圈在屏幕上半还是下半），带一条弯箭头指过去。点暗处不关，只能「跳过」或看完。
 * 换步：文字框里的内容马上淡入淡出；圈先停在原处，等新目标画好、位置稳了滑过去，文字框慢半拍跟过去（11-07 改，以前先缩下去再弹回来）。
 */
@Composable
fun GuideOverlay(ctl: AppController, mode: String, onClose: () -> Unit) {
    val pages = remember(mode) { Guide.pagesFor(mode) }
    val label = remember(mode) { Guide.labelFor(mode) }
    val isNew = mode == "new" && Guide.whatsNew.containsKey(Guide.versionKey())
    var i by remember(mode) { mutableIntStateOf(ctl.guideStart.coerceIn(0, pages.lastIndex)) }
    val page = pages[i.coerceIn(0, pages.lastIndex)]
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    /** 现在圈着的目标（换步时先圈着上一个，等新的画好再换）。 */
    var shown by remember { mutableStateOf<String?>(null) }
    /** 上一次圈到的位置：换界面时旧的那个没了，圈先停在原地，等新的出来再滑过去；这一步不圈时就在原地淡掉，不缩回中间。 */
    var lastRect by remember { mutableStateOf<Rect?>(null) }
    var settling by remember { mutableStateOf(true) }
    val goNext: () -> Unit = { if (i >= pages.lastIndex) onClose() else i++ }
    val goPrev: () -> Unit = { if (i > 0) i-- }
    DisposableEffect(Unit) { onDispose { GuideTargets.active = null; GuideTargets.inDialog = false; GuideRun.page = null } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 860.dp
        // 先跳到那个界面（手机上要圈侧栏里的就先打开侧栏，要讲弹窗里的就打开弹窗），要圈的东西不在屏幕里就滚过去，等它位置稳了再圈
        LaunchedEffect(i, mode) {
            settling = true
            page.go(ctl)
            ctl.guideDialog = page.dialog
            ctl.guideDrawer = narrow && page.drawer
            GuideTargets.inDialog = page.dialog != null
            GuideTargets.active = page.target
            GuideRun.page = page; GuideRun.label = label; GuideRun.index = i; GuideRun.total = pages.size
            GuideRun.prev = goPrev; GuideRun.next = goNext; GuideRun.skip = onClose
            val key = page.target?.takeIf { page.dialog == null }
            if (key != null) {
                // 同一页上的两下就稳了；换页面、开侧栏、滚过去要等一会儿，最多 0.8 秒（开侧栏 1 秒）
                val until = nowMillis() + if (narrow && page.drawer) 1_000 else 800
                var before: Rect? = null
                var same = 0
                while (nowMillis() < until) {
                    val r = GuideTargets.rects[key]
                    if (r != null && r == before) { if (++same >= 2) break } else same = 0
                    before = r
                    delay(40)
                }
            }
            shown = key
            settling = false
        }
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val live = shown?.let { GuideTargets.rects[it] }?.takeIf { it.width > 0f && it.bottom > 0f && it.top < h }
        SideEffect { if (live != null && live != lastRect) lastRect = live }
        // 圈住的：新目标；还在等新目标时圈着原来的位置；这一步不圈就淡掉
        val rect = live ?: if (settling) lastRect else null
        // 比大半屏还高的（一长张卡片）：只圈开头那一段，文字框放到下面（11-07 截图：圈整张卡，文字框压住了卡片开头的「自动（推荐）」）
        val geo = (rect ?: lastRect)?.let { g ->
            if (g.height <= h * 0.45f) g else { val top = maxOf(g.top, 0f); Rect(g.left, top, g.right, top + h * 0.3f) }
        }
        val holeAlpha by animateFloatAsState(if (rect != null) 1f else 0f, tween(220), label = "hole")
        val pad = with(density) { 8.dp.toPx() }
        // 小按钮用圆圈；宽的一块（标题、一排选项）用贴边的圆角框，不然圆圈大到盖住半个屏幕
        // 接近方的才用圆圈（11-07：侧栏「我的文档」那种扁卡片套圆圈，圈大到压住旁边的搜索框）
        val round = geo != null && maxOf(geo.width, geo.height) <= with(density) { 120.dp.toPx() } && geo.width / geo.height in 0.6f..1.7f
        val tl = geo?.let { if (round) it.center.x - maxOf(it.width, it.height) / 2 - pad else it.left - pad } ?: (w / 2)
        val tt = geo?.let { if (round) it.center.y - maxOf(it.width, it.height) / 2 - pad else it.top - pad } ?: (h / 2)
        val trr = geo?.let { if (round) it.center.x + maxOf(it.width, it.height) / 2 + pad else it.right + pad } ?: (w / 2)
        val tb = geo?.let { if (round) it.center.y + maxOf(it.width, it.height) / 2 + pad else it.bottom + pad } ?: (h / 2)
        // 圈滑过去（快一点），文字框慢半拍跟上
        val ringSpring = spring<Float>(dampingRatio = 0.82f, stiffness = 320f)
        val l by animateFloatAsState(tl, ringSpring, label = "l")
        val t by animateFloatAsState(tt, ringSpring, label = "t")
        val r by animateFloatAsState(trr, ringSpring, label = "r")
        val b by animateFloatAsState(tb, ringSpring, label = "b")
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Restart), label = "p")
        val corner = with(density) { 16.dp.toPx() }

        // 压暗 + 挖空 + 光圈；点暗处什么也不做（挡住下面的界面）
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).pointerInput(Unit) { detectTapGestures { } }) {
            drawRect(Color.Black.copy(alpha = 0.58f))
            if (holeAlpha > 0.01f && r - l > 2f) {
                val glow = pulse * 16.dp.toPx()
                // DstOut 按透明度挖：淡入淡出时洞也跟着慢慢开合（Clear 只能一下子挖穿）
                if (round) {
                    val c = Offset((l + r) / 2, (t + b) / 2)
                    val rad = (r - l) / 2
                    drawCircle(Color.Black.copy(alpha = holeAlpha), rad, c, blendMode = BlendMode.DstOut)
                    drawCircle(primary.copy(alpha = holeAlpha), rad, c, style = Stroke(3.dp.toPx()))
                    drawCircle(primary.copy(alpha = (1f - pulse) * 0.7f * holeAlpha), rad + glow, c, style = Stroke(2.dp.toPx()))
                } else {
                    val cr = CornerRadius(corner, corner)
                    drawRoundRect(Color.Black.copy(alpha = holeAlpha), Offset(l, t), Size(r - l, b - t), cr, blendMode = BlendMode.DstOut)
                    drawRoundRect(primary.copy(alpha = holeAlpha), Offset(l, t), Size(r - l, b - t), cr, style = Stroke(3.dp.toPx()))
                    drawRoundRect(primary.copy(alpha = (1f - pulse) * 0.7f * holeAlpha), Offset(l - glow, t - glow), Size(r - l + 2 * glow, b - t + 2 * glow),
                        CornerRadius(corner + glow, corner + glow), style = Stroke(2.dp.toPx()))
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
        val bxT = if (rect == null || w - margin - bw < margin) (w - bw) / 2 else (cxT - bw / 2).coerceIn(margin, w - margin - bw)
        val byT = when {
            rect == null -> (h - bh) / 2
            below -> minOf(tb + gap, h - margin - bh).coerceAtLeast(0f)
            else -> maxOf(tt - gap - bh, margin)
        }
        // 第一次量出高度以前直接放到位，之后都滑过去
        val boxSpec: AnimationSpec<Float> = if (bh == 0) snap() else spring(dampingRatio = 0.9f, stiffness = 170f)
        val bx by animateFloatAsState(bxT, boxSpec, label = "bx")
        val by by animateFloatAsState(byT, boxSpec, label = "by")
        // 弯箭头：从文字框靠近圈的那条边，指到圈边上（跟着圈和文字框一起动）
        if (holeAlpha > 0.01f && bh > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val ink = Color.White.copy(alpha = holeAlpha)
                val sx = if (bw > 48.dp.toPx()) ((l + r) / 2).coerceIn(bx + 24.dp.toPx(), bx + bw - 24.dp.toPx()) else bx + bw / 2
                val up = by > (t + b) / 2
                val sy = if (up) by - 4.dp.toPx() else by + bh + 4.dp.toPx()
                val (ex, ey) = if (round) {
                    val c = Offset((l + r) / 2, (t + b) / 2)
                    val ang = atan2(c.y - sy, c.x - sx)
                    (c.x - cos(ang) * ((r - l) / 2 + 6.dp.toPx())) to (c.y - sin(ang) * ((r - l) / 2 + 6.dp.toPx()))
                } else (if (r - l > 32.dp.toPx()) sx.coerceIn(l + 16.dp.toPx(), r - 16.dp.toPx()) else (l + r) / 2) to (if (up) b + 6.dp.toPx() else t - 6.dp.toPx())
                if (hypot(ex - sx, ey - sy) > 12.dp.toPx()) {
                    val bend = 26.dp.toPx() * (if (sx < ex) 1 else -1)
                    val mx = (sx + ex) / 2 + bend
                    val my = (sy + ey) / 2
                    drawPath(Path().apply { moveTo(sx, sy); quadraticTo(mx, my, ex, ey) }, ink, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                    val a2 = atan2(ey - my, ex - mx)
                    val len = 11.dp.toPx()
                    drawLine(ink, Offset(ex, ey), Offset(ex - len * cos(a2 - 0.5f), ey - len * sin(a2 - 0.5f)), 2.5.dp.toPx(), StrokeCap.Round)
                    drawLine(ink, Offset(ex, ey), Offset(ex - len * cos(a2 + 0.5f), ey - len * sin(a2 + 0.5f)), 2.5.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        Surface(
            Modifier.offset { IntOffset(bx.roundToInt(), by.roundToInt()) }.width(with(density) { bw.toDp() }).onSizeChanged { bh = it.height },
            shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle, modifier = Modifier.weight(1f))
                    Text("${i + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium, color = Ext.c.subtle)
                }
                Spacer(Modifier.height(6.dp))
                GuidePageText(page)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (i < pages.lastIndex) TextButton(onClick = onClose) { Text("跳过", color = Ext.c.subtle) }
                    Spacer(Modifier.weight(1f))
                    if (i > 0) { OutlinedButton(onClick = goPrev) { Text("上一步") }; Spacer(Modifier.width(8.dp)) }
                    if (i == pages.lastIndex && isNew) { OutlinedButton(onClick = { ctl.guideStart = 0; ctl.guide = "tour" }) { Text("完整介绍") }; Spacer(Modifier.width(8.dp)) }
                    SpringButton(when {
                        i < pages.lastIndex -> "下一步"
                        isNew -> "知道了"
                        mode.startsWith("ch:") -> "看完了"
                        else -> "开始使用"
                    }, goNext)
                }
            }
        }
    }
}

/** 一步的图标、标题、说明、在哪儿；换步时淡出旧的、淡入新的，框的高度跟着平滑变。 */
@Composable
private fun GuidePageText(page: Guide.Page, small: Boolean = false) {
    AnimatedContent(page, transitionSpec = {
        (fadeIn(tween(220, delayMillis = 70)) togetherWith fadeOut(tween(90))).using(SizeTransform(clip = false))
    }, label = "guidePage") { p ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.emoji, fontSize = if (small) 18.sp else 22.sp)
                Spacer(Modifier.width(8.dp))
                Text(p.title, style = if (small) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(p.body, style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = if (small) 19.sp else 21.sp)
            if (p.where.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("在哪儿：${p.where}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * 弹窗顶上的引导说明框（AppDialog 里放着）：引导走到弹窗里的那几步时出来，和主界面上的文字框一样能上一步、下一步、跳过。
 * 主界面的文字框在弹窗下面被挡住了，所以弹窗里再放一个；要讲的那一栏自己画圈（guideTarget）。
 */
@Composable
fun GuideDialogCard() {
    val p = GuideRun.page ?: return
    if (p.dialog == null) return
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        Modifier.fillMaxWidth().padding(bottom = 10.dp), shape = RoundedCornerShape(14.dp),
        color = primary.copy(alpha = 0.08f), border = BorderStroke(1.dp, primary.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row {
                Text(GuideRun.label, style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle, modifier = Modifier.weight(1f))
                Text("${GuideRun.index + 1} / ${GuideRun.total}", style = MaterialTheme.typography.labelSmall, color = Ext.c.subtle)
            }
            Spacer(Modifier.height(4.dp))
            GuidePageText(p, small = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { GuideRun.skip() }) { Text("跳过", color = Ext.c.subtle) }
                Spacer(Modifier.weight(1f))
                if (GuideRun.index > 0) { OutlinedButton(onClick = { GuideRun.prev() }) { Text("上一步") }; Spacer(Modifier.width(8.dp)) }
                SpringButton(if (GuideRun.index < GuideRun.total - 1) "下一步" else "看完了") { GuideRun.next() }
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
