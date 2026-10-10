package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.StanceJudge
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.model.StanceEntry
import com.guixing.jixunying.model.StanceOption
import com.guixing.jixunying.model.StanceTopic
import com.guixing.jixunying.model.Stances
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 立场档案的裁判：隐名、读裁判的结论、统计口径、旧版手机发来的设置。用跟代号不一样的真名字测，换错了才看得出来。 */
class StanceJudgeTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("jxy-stance").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val de = Member("m1", "阿德", modelId = "deepseek-flash")
    private val mai = Member("m2", "阿麦", modelId = "MiniMax-M3")
    private val mi = Member("m3", "阿米", modelId = "mimo-v2.6-flash")
    private val members = listOf(de, mai, mi)

    @Test
    fun anonymizeOnlyNamesInQuestionAndOnePass() {
        // 问题问的就是 DeepSeek 和 MiniMax：问题不能被改，回答里的这两个词也不换（换了题目就变了）
        val anon = StanceJudge.Anon(members, "DeepSeek 和 MiniMax 哪家的 API 更便宜？")
        assertEquals("DeepSeek 和 MiniMax 哪家的 API 更便宜？", anon.names("DeepSeek 和 MiniMax 哪家的 API 更便宜？"))
        assertEquals("我觉得 DeepSeek 更便宜，成员乙说得不对", anon.all("我觉得 DeepSeek 更便宜，阿麦说得不对"))
        // 问题没提到的模型名照样换：成员自己报家门认得出是谁
        assertEquals("我是模型丙", anon.all("我是MiMo"))
        // 代号按成员顺序给，一次换完
        assertEquals("成员甲、成员乙、成员丙", anon.names("阿德、阿麦、阿米"))
        assertEquals("成员甲", anon.code("m1"))
        assertEquals("成员乙", anon.codeOfName("@阿麦"))
        // 换回真名：名字字段和整段话
        assertEquals("阿麦", anon.nameOf("成员乙"))
        assertEquals("阿麦", anon.nameOf("乙"))
        assertNull(anon.nameOf("阿麦"))
        assertEquals("阿德说得对，阿米算错了", anon.back("成员甲说得对，成员丙算错了"))
    }

    @Test
    fun nameThatLooksLikeACodeIsNotReplacedTwice() {
        // 成员名字恰好是「甲」：第一遍换成「成员乙」后不能再被换一遍
        val odd = listOf(Member("x1", "乙"), Member("x2", "甲"))
        val anon = StanceJudge.Anon(odd)
        assertEquals("成员甲说完成员乙说", anon.names("乙说完甲说"))
        assertEquals("乙说完甲说", anon.back("成员甲说完成员乙说"))
    }

    @Test
    fun recorderPromptAndParseUseCodesBothWays() {
        val ask = Message("u1", "c1", Role.USER, "user", "1.5 和 1.12 哪个大？")
        val said = listOf(
            StanceJudge.Said(Message("a1", "c1", Role.AI, "m1", "我是 DeepSeek，1.5 大"), "阿德", true, null, ""),
            StanceJudge.Said(Message("a2", "c1", Role.AI, "m2", "阿德说得对"), "阿麦", false, "阿德", ""),
        )
        val r = StanceJudge.prompt(emptyList(), said, ask.content, "我", { id -> members.first { it.id == id }.name }, true, true, members)
        assertFalse("阿德" in r.prompt || "阿麦" in r.prompt, "裁判不该看到真名：${r.prompt}")
        assertTrue("【成员甲】" in r.prompt && "【成员乙】" in r.prompt)
        assertTrue("我是 模型甲" in r.prompt, "成员报的模型名要换掉")
        assertTrue("被成员甲 @ 后接着说" in r.prompt, "接力的标签不能丢")
        val v = StanceJudge.parse(
            """{"newTopic":{"question":"成员甲问的数谁大","options":[{"key":"A","text":"成员甲说的 1.5 大"},{"key":"B","text":"1.12 大"}],
              |"stances":[{"member":"成员甲","stance":"A"},{"member":"乙","stance":"A"}]}}""".trimMargin(), r.anon)!!
        val nt = v.newTopic!!
        assertEquals("阿德问的数谁大", nt.question)
        assertEquals("阿德说的 1.5 大", nt.options.first().second)
        assertEquals(listOf("阿德" to "A", "阿麦" to "A"), nt.stances)
    }

    @Test
    fun verifyAcceptsOnlyRealOptions() {
        val keys = listOf("A", "B")
        val anon = StanceJudge.Anon(members)
        assertEquals("A", StanceJudge.parseVerify("""{"verdict":"a","reason":"成员乙算对了"}""", keys, anon)!!.verdict)
        assertEquals("阿麦算对了", StanceJudge.parseVerify("""{"verdict":"A","reason":"成员乙算对了"}""", keys, anon)!!.reason)
        assertEquals("B", StanceJudge.parseVerify("""{"verdict":"立场B","reason":""}""", keys)!!.verdict)
        assertEquals(StanceTopic.NONE, StanceJudge.parseVerify("""{"verdict":"none","reason":""}""", keys)!!.verdict)
        assertEquals(StanceTopic.UNCLEAR, StanceJudge.parseVerify("""{"verdict":"unclear","reason":""}""", keys)!!.verdict)
        assertNull(StanceJudge.parseVerify("""{"verdict":"A对","reason":""}""", keys), "不是立场 key 的当看不懂")
        assertNull(StanceJudge.parseVerify("""{"verdict":"C","reason":""}""", keys), "这道题没有 C")
    }

    @Test
    fun unclearOrInvalidAiVerdictIsNotScored() {
        fun topic(ai: String) = StanceTopic(
            "t", "c1", question = "q", options = listOf(StanceOption("A", "a"), StanceOption("B", "b")),
            entries = listOf(StanceEntry("m1", "a1", option = StanceTopic.UNCLEAR, first = true, time = 1),
                StanceEntry("m2", "a2", option = "A", first = true, time = 1)),
            aiVerdict = ai,
        )
        // 裁判判断不了：谁都不计分（以前首答也是「?」的会被算成答对）
        val unclear = Stances.cards(listOf(topic(StanceTopic.UNCLEAR)), listOf("m1", "m2"), countAi = true)
        assertEquals(0, unclear[0].judged)
        assertEquals(0, unclear[1].judged)
        // 判了个这道题没有的立场：也不计分
        assertEquals(0, Stances.card(listOf(topic("D")), "m2", countAi = true).judged)
        // 正常判 A：阿麦首答对
        val ok = Stances.card(listOf(topic("A")), "m2", countAi = true)
        assertEquals(1, ok.judged)
        assertEquals(1, ok.firstRight)
        assertFalse(topic(StanceTopic.UNCLEAR).aiCounts())
        assertTrue(topic(StanceTopic.NONE).aiCounts())
    }

    @Test
    fun oldPhoneSettingsDoNotResetJudge() = runBlocking {
        val e = Engine(Storage(File(dir, "pc")))
        assertEquals(Settings.SCHEMA, e.state.settings.schema, "启动后状态里带着这一版的设置版本")
        val mine = e.state.settings.memory.copy(judgeProviderId = "p1", judgeModelId = "deepseek-flash", aiVerify = false, judgeAnonymous = false, countAiVerdict = false)
        assertTrue(e.call(Command.SaveSettings(e.state.settings.copy(memory = mine))).ok)
        // 旧版手机：不认识这几项和 schema，发来的 JSON 里就没有它们
        val newJson = AppJson.encodeToJsonElement(Command.serializer(), Command.SaveSettings(e.state.settings.copy(maxMentionChain = 5))).jsonObject
        fun JsonObject.drop(vararg keys: String) = JsonObject(this - keys.toSet())
        val settings = newJson.getValue("settings").jsonObject
        val oldMemory = settings.getValue("memory").jsonObject.drop("judgeProviderId", "judgeModelId", "judgeAnonymous", "aiVerify", "countAiVerdict")
        val oldJson = JsonObject(newJson + ("settings" to JsonObject(settings.drop("schema") + ("memory" to oldMemory))))
        val fromOldPhone = AppJson.decodeFromJsonElement(Command.serializer(), oldJson)
        assertTrue(e.call(fromOldPhone).ok)
        assertEquals(5, e.state.settings.maxMentionChain, "旧版手机能改的照样改")
        assertEquals(mine.judgeModelId, e.state.settings.memory.judgeModelId, "旧版手机不认识的不能被冲回默认值")
        assertFalse(e.state.settings.memory.aiVerify)
        assertFalse(e.state.settings.memory.judgeAnonymous)
        // 新版发来的照常生效
        assertTrue(e.call(Command.SaveSettings(e.state.settings.copy(memory = e.state.settings.memory.copy(aiVerify = true)))).ok)
        assertTrue(e.state.settings.memory.aiVerify)
        assertEquals(JsonPrimitive(Settings.SCHEMA), AppJson.encodeToJsonElement(Settings.serializer(), e.state.settings).jsonObject["schema"])
    }
}
