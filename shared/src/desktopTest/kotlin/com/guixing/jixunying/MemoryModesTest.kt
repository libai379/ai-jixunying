package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Recorder
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.MemoryUse
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 两种记忆（2026-11-06，用户看了抖音「小凌」识海拆解后定：同一份记忆、两种想起来的方式、能随时切、能换一种再答一次比较）。
 */
class MemoryModesTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private fun mem(id: String, text: String, kind: String = "事实", topic: String = "", pinned: Boolean = false) =
        MemoryItem(id, text, kind, createdAt = now - 10 * day, updatedAt = now - 10 * day, topic = topic, pinned = pinned)

    private val items = listOf(
        mem("me", "我叫老李，在抖音直播卖沉香手串", "关于我"),
        mem("rule", "回答不要用「--」这种分隔符", "要求"),
        mem("price", "西马沉香手串进货价每串 380 元，直播间卖 699"),
        mem("cat", "家里养了一只猫叫团子"),
        mem("kt", "AI集训营是 Kotlin 写的，两端共用引擎", topic = "AI集训营"),
        mem("dalagan", "达拉干沉香手串这批是老料，油线粗"),
    )

    @Test
    fun picksWhatThisMomentIsAbout() {
        val r = Recorder.recall(items, emptyMap(), "", "西马沉香手串直播怎么定价", now)
        val ids = r.items.map { it.id }
        assertTrue("me" in ids && "rule" in ids, "身份和回答要求常驻：$ids")
        assertTrue("price" in ids, "对得上的要挑上：$ids")
        assertFalse("cat" in ids || "kt" in ids, "不相干的不带：$ids")
        assertEquals(listOf("price", "dalagan").filter { it in ids }, r.activated.filter { it != "me" && it != "rule" }, "常驻的不算被想起")
        // 别的话题的也能被想起（完整记忆只带这个对话话题的）
        val r2 = Recorder.recall(items, emptyMap(), "", "AI集训营 的引擎是用什么写的", now)
        assertTrue("kt" in r2.items.map { it.id })
    }

    @Test
    fun usedOftenAndRecentlyComesFirstButFades() {
        val two = listOf(mem("a", "沉香手串甲款的卖点"), mem("b", "沉香手串乙款的卖点"))
        // 这句话更像在说甲款；乙款最近常被想起，能压过一点点相关度的差距
        val q = "沉香手串甲款卖点"
        assertEquals("a", Recorder.recall(two, emptyMap(), "", q, now, recallChars = 12).items.single().id, "都没用过：更相关的在前")
        val fresh = mapOf("b" to MemoryUse(now - day, 6))
        assertEquals("b", Recorder.recall(two, fresh, "", q, now, recallChars = 12).items.single().id, "最近常用的浮上来")
        val stale = mapOf("b" to MemoryUse(now - 400 * day, 6))
        assertEquals("a", Recorder.recall(two, stale, "", q, now, recallChars = 12).items.single().id, "一年多没用就沉下去了")
    }

    // ———— 走引擎：联想记忆进设定、换一种再答、跨话题查重 ————

    private val systems = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-memmode").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                post("/v1/chat/completions") {
                    val req = Json.parseToJsonElement(call.receiveText()).jsonObject
                    val msgs = req["messages"]!!.jsonArray.map { it.jsonObject }
                    val hasTools = req["tools"] != null
                    val sys = msgs.firstOrNull { it["role"]?.jsonPrimitive?.content == "system" }?.let { (it["content"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    if (hasTools) systems += sys
                    val task = msgs.lastOrNull { it["role"]?.jsonPrimitive?.content == "user" }?.let { (it["content"] as? JsonPrimitive)?.contentOrNull }.orEmpty()
                    val results = msgs.count { it["role"]?.jsonPrimitive?.content == "tool" }
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        fun chunk(delta: String) { write("data: {\"choices\":[{\"index\":0,\"delta\":$delta}]}\n\n"); flush() }
                        fun say(t: String) = chunk(JsonObject(mapOf("content" to JsonPrimitive(t))).toString())
                        fun callArgs(name: String, args: Map<String, String>) {
                            val fn = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to JsonPrimitive(JsonObject(args.mapValues { JsonPrimitive(it.value) }).toString())))
                            val c = JsonObject(mapOf("index" to JsonPrimitive(0), "id" to JsonPrimitive("c$results"), "type" to JsonPrimitive("function"), "function" to fn))
                            chunk(JsonObject(mapOf("tool_calls" to JsonArray(listOf(c)))).toString())
                            write("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")
                        }
                        when {
                            !hasTools -> say("[]")
                            "记一下" in task && results == 0 -> callArgs("remember", mapOf("text" to "AI集训营是 Kotlin 写的，两端共用引擎", "kind" to "事实", "topic" to "编程"))
                            else -> say("好的。")
                        }
                    }
                }
            }
        }
        fake.start(wait = false)
    }

    @AfterTest
    fun cleanup() { fake.stop(500, 1_000); dir.deleteRecursively() }

    private suspend fun await(check: () -> Boolean) = withTimeout(30_000) { while (!check()) delay(30) }

    private suspend fun setup(): Pair<Engine, String> {
        val e = Engine(Storage(File(dir, "data")))
        e.call(Command.SaveProvider(ProviderConfig("p1", "custom", "假服务商", "http://127.0.0.1:$port/v1", "sk-test", listOf(ModelInfo("m")))))
        e.call(Command.SaveMember(Member("a1", "阿测", providerId = "p1", modelId = "m")))
        val s = e.state.settings
        e.call(Command.SaveSettings(s.copy(memory = s.memory.copy(autoExtract = false, stances = false, aiVerify = false))))
        items.forEach { e.call(Command.SaveMemory(it)) }
        val conv = e.call(Command.CreateConversation(listOf("a1"))).data
        return e to conv
    }

    @Test
    fun recallModeAnswerAgainAndDedupe() = runBlocking {
        val (e, conv) = setup()
        e.call(Command.UpdateConversation(e.state.conversation(conv)!!.copy(memoryMode = "recall")))
        e.call(Command.SendMessage(conv, "西马沉香手串直播怎么定价"))
        await { e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }?.status == MsgStatus.DONE }
        val first = e.store.messages.value[conv].orEmpty().last { it.role == Role.AI }
        assertEquals("recall", first.memoryMode)
        val s1 = systems.last()
        assertTrue("这会儿想起来的长期记忆" in s1 && "进货价" in s1, s1.substringAfter("长期记忆").take(400))
        assertFalse("团子" in s1, "不相干的不带")
        assertTrue((e.state.memoryUse["price"]?.count ?: 0) == 1, "被想起的记一笔：${e.state.memoryUse}")

        // 换完整记忆再答一次：原来那条留着，新的一条用完整记忆
        assertTrue(e.call(Command.AnswerAgain(conv, first.id, "full")).ok)
        await { e.store.messages.value[conv].orEmpty().count { it.role == Role.AI && it.status == MsgStatus.DONE } == 2 }
        val ais = e.store.messages.value[conv].orEmpty().filter { it.role == Role.AI }
        assertEquals(listOf("recall", "full"), ais.map { it.memoryMode })
        val s2 = systems.last()
        assertTrue("长期记忆】（以前的对话里记下的" in s2 && "团子" in s2, "完整记忆整份带上")

        // 联想记忆下 AI 换个话题名把别的话题里早有的再记一遍：跨话题查重，不多一条
        val before = e.state.memories.size
        e.call(Command.SendMessage(conv, "记一下"))
        await { e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }?.let { it.status == MsgStatus.DONE && it.content.isNotBlank() } == true }
        assertEquals(before, e.state.memories.size, e.state.memories.map { it.topic + ":" + it.text }.toString())
    }
}
