package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.Today
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.USER_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test

/**
 * AI 反应得过来吗（2026-11-02 用户：「我生日是农历九月二十四……他没有立刻反应过来，就是今天……问的不是生日，问的是其他的呢？」）：
 * 把「手里有的事实和眼前这句话连起来」的几类情形，在几位真模型上各跑一遍，改提示词前后对比。人读全文，keywords 只是粗算。
 * JXY_LIVE_AWARE=数据文件夹（只复制 state.json 和 chatgpt/auth.json，auth 里的刷新令牌清空，绝不会顶掉用户的登录）；
 * JXY_LIVE_AWARE_OUT=结果文件；JXY_LIVE_AWARE_MEMBERS=阿米,阿德（默认这两位）；JXY_LIVE_AWARE_ONLY=A1,B2 只跑这几条；JXY_LIVE_AWARE_RUNS=每条跑几遍（默认 1）。
 * 花一点模型的钱（每次两三千 token；模型自己要搜的话有搜索费）。
 */
class LiveAwarenessTest {
    private val now: ZonedDateTime = ZonedDateTime.now(Today.ZONE)
    private val today: LocalDate = now.toLocalDate()
    private fun l(n: Long) = Today.lunarText(today.plusDays(n))
    private fun s(n: Long) = Today.md(today.plusDays(n))
    private fun minutesAgo(m: Long) = System.currentTimeMillis() - m * 60_000

    private class Case(
        val id: String, val text: String, val keywords: List<String> = emptyList(),
        val memories: List<Pair<String, Long>> = emptyList(),
        /** 聊天记录：是不是用户、说的话、多少分钟以前。 */
        val history: List<Triple<Boolean, String, Long>> = emptyList(),
        /** 期望 remember 工具写下的内容里有这些字。 */
        val rememberHas: List<String> = emptyList(),
    )

    private fun cases(): List<Case> {
        val nextWed = today.with(java.time.DayOfWeek.MONDAY).plusWeeks(1).plusDays(2)
        val spring = (0L..400L).map { today.plusDays(it) }.first { "春节" in Today.festivalsOf(it) }
        val springDays = java.time.temporal.ChronoUnit.DAYS.between(today, spring)
        val lunarNow = Today.lunarOf(today)
        val nextYearSame = Today.fromLunar(lunarNow.year + 1, kotlin.math.abs(lunarNow.month), lunarNow.day)!!
        val oldBirth = Today.fromLunar(1995, kotlin.math.abs(lunarNow.month), lunarNow.day)!!
        val ny = ZonedDateTime.now(ZoneId.of("America/New_York")).hour
        return listOf(
            // 甲：用户说了一个日期，没问，AI 要自己对上今天
            Case("A1生日农历今天", "我生日是农历${l(0)}", listOf("今天", "生日快乐")),
            Case("A2生日公历今天", "我生日是${s(0)}", listOf("今天", "生日快乐")),
            Case("A3老婆生日农历明天", "我老婆生日是农历${l(1)}", listOf("明天")),
            Case("A4纪念日农历五天后", "我们结婚纪念日是农历${l(5)}", listOf("5 天", "5天", "五天", s(5), "下周")),
            Case("A5交标书明天", "我${s(1)}要交标书", listOf("明天")),
            Case("A6妈生日刚过", "我妈生日是${s(-5)}", listOf("前", "刚过", "过了", "上周")),
            // 乙：直接问日历
            Case("B1今天农历", "今天农历几号", listOf(l(0), l(0).replace("二十", "廿"))),
            Case("B2节气", "下一个节气是什么，哪天", listOf(Today.lunarOf(today).nextJieQi.name)),
            Case("B3下周三", "下周三是几号", listOf(Today.md(nextWed), "${nextWed.dayOfMonth}号", "${nextWed.dayOfMonth}日")),
            Case("B4离春节", "离春节还有多少天", listOf("$springDays")),
            Case("B5明年农历生日", "我生日农历${l(0)}，明年是公历几号", listOf(Today.md(nextYearSame))),
            Case("B6农历几天后星期几", "农历${l(4)}是星期几", listOf(Today.weekName(today.plusDays(4)), "星期" + "一二三四五六日"[today.plusDays(4).dayOfWeek.value - 1])),
            Case("B7九五年农历换公历", "1995年农历${l(0)}是公历哪天", listOf(Today.md(oldBirth))),
            Case("B8明天上班吗", "明天要上班吗", listOf(if (Today.workNote(today.plusDays(1)) == "工作日") "要" else "不")),
            Case("B9纽约几点", "纽约现在几点", listOf("$ny", if (ny > 12) "${ny - 12}" else "$ny")),
            // 丙：聊天记录里的时间
            Case("C1今晚面试", "有点紧张", listOf("面试"), history = listOf(Triple(true, "我今晚八点有个面试", 180L), Triple(false, "加油，到时候放松点。", 179L))),
            Case("C2三天前出差", "我回来了", listOf("杭州", "出差"), history = listOf(Triple(true, "我明天去杭州出差，得走几天", 3 * 24 * 60L), Triple(false, "好，路上顺利。", 3 * 24 * 60L - 1))),
            Case("C3昨天生日", "昨天那顿吃撑了", listOf("生日"), history = listOf(Triple(true, "今天我生日，晚上去吃火锅", 24 * 60L), Triple(false, "生日快乐！吃好喝好。", 24 * 60L - 1))),
            // 丁：记忆里的事对上眼前
            Case("D1记忆生日今天", "中午吃啥好", listOf("生日"), memories = listOf("用户的生日是农历${l(0)}。" to 30 * 24 * 60L)),
            Case("D2记忆生日公历后天", "这周末有什么安排建议吗", listOf("生日"), memories = listOf("用户女儿的生日是${s(2)}。" to 60 * 24 * 60L)),
            Case("D3记忆过敏", "推荐点下午茶小吃", listOf("花生", "过敏"), memories = listOf("用户对花生过敏。" to 90 * 24 * 60L)),
            Case("D4记忆城市", "今天冷不冷", listOf("杭州"), memories = listOf("用户住在杭州。" to 90 * 24 * 60L)),
            Case("D5旧记忆过期", "下周二有空吗，约个饭", memories = listOf("用户下周二要去上海开会。" to 21 * 24 * 60L)),
            // 戊：记下来的话以后还看得懂（「明天」要写成具体日期）
            Case("E1记住明天", "我明天要去杭州出差，记一下", rememberHas = listOf(s(1))),
            Case("E2记住下周三", "下周三我要交报告，记住", rememberHas = listOf(Today.md(nextWed))),
            // 己：做不到的事别答应
            Case("F1提醒", "明天早上八点提醒我开会"),
            // 庚：对照，日子没关系的时候别硬提（中午吃什么、写代码；没有记忆时别猜用户在哪；几点要答得出）
            Case("Z1没关系的闲聊", "中午吃啥好"),
            Case("Z2写代码", "用 Python 写个冒泡排序，只要代码"),
            Case("Z3不知道在哪", "今天冷不冷"),
            Case("Z4现在几点", "现在几点了", listOf(now.format(java.time.format.DateTimeFormatter.ofPattern("H:")) , now.format(java.time.format.DateTimeFormatter.ofPattern("HH:")), (if (now.hour > 12) "${now.hour - 12}点" else "${now.hour}点"))),
        )
    }

    @Test
    fun awareness() = runBlocking<Unit> {
        val src = System.getenv("JXY_LIVE_AWARE") ?: return@runBlocking
        val out = File(System.getenv("JXY_LIVE_AWARE_OUT") ?: return@runBlocking)
        val members = (System.getenv("JXY_LIVE_AWARE_MEMBERS") ?: "阿米,阿德").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val only = System.getenv("JXY_LIVE_AWARE_ONLY")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val runs = System.getenv("JXY_LIVE_AWARE_RUNS")?.toIntOrNull() ?: 1
        val todo = cases().filter { c -> only.isEmpty() || only.any { c.id.startsWith(it) } }
        out.parentFile?.mkdirs()
        val log = StringBuffer("今天 ${Today.describe(today)} 现在 $now\n")
        val score = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
        // 每位成员一条线同时跑，一条线内一条接一条（别把一家打爆）
        coroutineScope {
            members.map { name ->
                async(Dispatchers.IO) {
                    for (c in todo) for (run in 1..runs) {
                        val r = runCatching { runCase(File(src), name, c) }.getOrElse { "出错：$it" to false }
                        log.append("\n[${c.id}][$name][$run] ${c.text}\n${r.first}\n")
                        if (c.keywords.isNotEmpty() || c.rememberHas.isNotEmpty()) score.getOrPut(c.id) { Collections_sync() }.add(name + if (r.second) "对" else "错")
                        out.writeText(log.toString())
                    }
                }
            }.awaitAll()
        }
        log.append("\n==== 粗算 ====\n")
        todo.forEach { c -> score[c.id]?.let { log.append(c.id).append("：").append(it.sorted().joinToString(" ")).append("\n") } }
        out.writeText(log.toString())
    }

    /**
     * 提示词缓存：同一个对话隔一分多钟连着问两句，看第二句有多少走了缓存（JXY_LIVE_AWARE_CACHE=成员1,成员2）。2026-11-02 量过：设定里带着分钟时第二句只有 0～17% 走缓存，拿掉以后 92%～99%。
     */
    @Test
    fun promptCache() = runBlocking<Unit> {
        val src = System.getenv("JXY_LIVE_AWARE") ?: return@runBlocking
        val names = System.getenv("JXY_LIVE_AWARE_CACHE")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@runBlocking
        val out = File(System.getenv("JXY_LIVE_AWARE_OUT") ?: return@runBlocking)
        val log = StringBuffer("缓存对比" + "\n")
        coroutineScope {
            names.map { name ->
                async(Dispatchers.IO) {
                    val tmp = kotlin.io.path.createTempDirectory("jxy-live-cache").toFile()
                    try {
                        File(src, "state.json").copyTo(File(tmp, "state.json"))
                        val st = Storage(tmp)
                        var state = st.loadState()!!
                        val m = state.members.first { it.name == name }
                        val convId = "cache"
                        state = state.copy(
                            conversations = listOf(Conversation(convId, "测试", listOf(m.id), webSearch = false, createdAt = minutesAgo(5000), updatedAt = minutesAgo(1))),
                            memories = emptyList(),
                            settings = state.settings.copy(memory = state.settings.memory.copy(enabled = false, autoExtract = false, stances = false, aiVerify = false),
                                semantic = state.settings.semantic.copy(historyOn = false, docsOn = false), relay = state.settings.relay.copy(enabled = false)),
                        )
                        st.saveState(state)
                        // 一段够长的聊天（缓存一般要 1024 token 以上才生效）
                        val filler = (1..30).flatMap { i ->
                            listOf(Message("u$i", convId, Role.USER, USER_ID, "第 $i 个问题：讲讲长江第 $i 段的情况。" + "长江的水文和航运情况。".repeat(12), createdAt = minutesAgo(500L - i * 2)),
                                Message("a$i", convId, Role.AI, m.id, "第 $i 段的情况是这样的。" + "这一段水流平稳，航运繁忙，沿岸有不少城市。".repeat(12), createdAt = minutesAgo(499L - i * 2), modelLabel = m.modelId))
                        }
                        st.saveMessages(convId, filler)
                        val e = Engine(st, isPhone = true)
                        e.call(Command.LoadMessages(convId))
                        suspend fun ask(text: String): String {
                            val before = e.store.messages.value[convId].orEmpty().size
                            e.call(Command.SendMessage(convId, text))
                            withTimeoutOrNull(120_000) {
                                while (true) {
                                    delay(300)
                                    val l = e.store.messages.value[convId].orEmpty()
                                    if (l.size >= before + 2 && l.last().role == Role.AI && l.last().status != MsgStatus.STREAMING) break
                                }
                            }
                            val a = e.store.messages.value[convId].orEmpty().last()
                            return if (a.status == MsgStatus.ERROR) "出错 " + a.error.take(120) else "输入 ${a.usage?.prompt} 缓存 ${a.usage?.cached} 用时 ${a.usage?.millis} 毫秒"
                        }
                        val first = ask("一句话说说长江有多长")
                        delay(75_000)
                        val second = ask("再一句话说说黄河有多长")
                        delay(75_000)
                        val third = ask("再一句话说说珠江有多长")
                        log.append("$name（${m.modelId}）第一句：$first；第二句：$second；第三句：$third\n")
                    } finally {
                        tmp.deleteRecursively()
                    }
                }
            }.awaitAll()
        }
        out.parentFile?.mkdirs()
        out.writeText(log.toString())
    }

    @Suppress("FunctionName")
    private fun Collections_sync(): MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private suspend fun runCase(src: File, memberName: String, c: Case): Pair<String, Boolean> {
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-aware").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            // ChatGPT 订阅的成员要用登录：只带访问令牌，刷新令牌清空（副本刷新会把用户的登录顶掉）
            File(src, "chatgpt/auth.json").takeIf { it.isFile }?.let { f ->
                val o = Json.parseToJsonElement(f.readText()).jsonObject
                val safe = JsonObject(o + ("refreshToken" to JsonPrimitive("")))
                File(tmp, "chatgpt").mkdirs()
                File(tmp, "chatgpt/auth.json").writeText(safe.toString())
            }
            val st = Storage(tmp)
            var state = st.loadState()!!
            val m = state.members.first { it.name == memberName }
            val convId = "aware"
            state = state.copy(
                conversations = listOf(Conversation(convId, "测试", listOf(m.id), webSearch = true, createdAt = minutesAgo(5000), updatedAt = minutesAgo(1))),
                memories = c.memories.mapIndexed { i, (text, ago) -> MemoryItem("mem$i", text, "事实", "聊天", minutesAgo(ago), minutesAgo(ago)) },
                settings = state.settings.copy(
                    memory = state.settings.memory.copy(enabled = true, autoExtract = false, stances = false, aiVerify = false),
                    semantic = state.settings.semantic.copy(historyOn = false, docsOn = false),
                    relay = state.settings.relay.copy(enabled = false),
                ),
            )
            st.saveState(state)
            st.saveMessages(convId, c.history.mapIndexed { i, (user, text, ago) ->
                Message("h$i", convId, if (user) Role.USER else Role.AI, if (user) USER_ID else m.id, text, createdAt = minutesAgo(ago), modelLabel = m.modelId)
            })
            // 当手机跑：不给读写电脑文件的工具（基线时阿麦自己去翻了文件夹，文件名发给了模型；测试不该碰用户的文件）
            val e = Engine(st, isPhone = true)
            e.call(Command.LoadMessages(convId))
            val t0 = System.currentTimeMillis()
            e.call(Command.SendMessage(convId, c.text))
            var ai: Message? = null
            withTimeoutOrNull(240_000) {
                while (true) {
                    delay(300)
                    ai = e.store.messages.value[convId].orEmpty().lastOrNull()?.takeIf { it.role == Role.AI && it.id !in c.history.indices.map { i -> "h$i" } && it.status != MsgStatus.STREAMING }
                    if (ai != null) break
                }
            }
            val a = ai ?: return "（240 秒没答完）" to false
            val body = a.content.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
            val steps = a.tools.joinToString("；") { it.kind + "「" + it.input.take(60) + "」" }
            val hit = (c.keywords.isNotEmpty() && c.keywords.any { it in body }) ||
                (c.rememberHas.isNotEmpty() && a.tools.any { t -> t.kind == "memory" && c.rememberHas.any { it in t.input } })
            val cost = a.usage?.let { "输入 ${it.prompt} 缓存 ${it.cached} 输出 ${it.completion}" }.orEmpty()
            return (if (a.status == MsgStatus.ERROR) "错误：" + a.error.take(200) + "\n" else "") +
                (if (steps.isNotEmpty()) "〔工具：$steps〕\n" else "") + body + "\n（${System.currentTimeMillis() - t0} 毫秒，$cost）" to hit
        } finally {
            tmp.deleteRecursively()
        }
    }
}
