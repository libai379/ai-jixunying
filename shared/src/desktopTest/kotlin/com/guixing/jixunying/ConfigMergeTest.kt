package com.guixing.jixunying

import com.guixing.jixunying.engine.ConfigMerge
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 电脑的配置同步到手机：同一个服务商、同一位成员不重复加；旧版本重复导入留下的，启动时合并。 */
class ConfigMergeTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("jxy-merge").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun ds(id: String, name: String, url: String = "https://api.deepseek.com", key: String = "sk-ds") =
        ProviderConfig(id, "deepseek", name, url, key, listOf(ModelInfo("deepseek-flash")))

    private fun mm(id: String, name: String, vararg models: String) =
        ProviderConfig(id, "minimax", name, "https://api.minimaxi.com/v1", "sk-mm", models.map { ModelInfo(it) })

    /** 电脑：深度求索、MiniMax（比手机多一个模型）、通义（手机上没有）。 */
    private fun pcState() = AppState(
        providers = listOf(
            ds("pa", "深度求索 DeepSeek"),
            mm("pb", "MiniMax 中国版", "MiniMax-M3", "MiniMax-M2.7"),
            ProviderConfig("pc", "qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-qw", listOf(ModelInfo("qwen3.8-max"))),
        ),
        members = listOf(
            Member("ma", "阿德", providerId = "pa", modelId = "deepseek-flash", bio = "电脑上的人设"),
            Member("mb", "阿米", providerId = "pb", modelId = "MiniMax-M3", bio = "电脑上写的阿米定位"),
            Member("mc", "阿千", providerId = "pc", modelId = "qwen3.8-max"),
        ),
    )

    /** 手机：自己建的同一个 DeepSeek / MiniMax 账号（名字、编号都不一样，地址末尾多个斜杠），同名同模型的阿德、阿米。 */
    private fun phoneState() = AppState(
        providers = listOf(ds("qa", "DeepSeek 4.1 Flash", url = "https://api.deepseek.com/"), mm("qb", "MiniMax M3", "MiniMax-M3")),
        members = listOf(
            Member("na", "阿德", providerId = "qa", modelId = "deepseek-flash", bio = "手机上的人设"),
            Member("nb", "阿米", providerId = "qb", modelId = "MiniMax-M3"),
        ),
        conversations = listOf(Conversation("c1", memberIds = listOf("na", "nb"))),
    )

    @Test
    fun importDoesNotDuplicateSameServicesAndMembers() = runBlocking {
        Storage(File(dir, "pc")).saveState(pcState())
        Storage(File(dir, "phone")).saveState(phoneState())
        val pc = Engine(Storage(File(dir, "pc")))
        val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
        val bundle = pc.call(Command.ExportConfig).data

        val first = phone.call(Command.ImportConfig(bundle))
        assertTrue(first.ok, first.message)
        val s = phone.state
        assertEquals(listOf("qa", "qb", "pc"), s.providers.map { it.id }, "只加手机上没有的通义")
        assertEquals(listOf("na", "nb", "mc"), s.members.map { it.id }, "阿德、阿米不重复，只加阿千")
        assertEquals("手机上的人设", s.member("na")!!.bio, "手机上已有的设定不被覆盖")
        assertEquals("电脑上写的阿米定位", s.member("nb")!!.bio, "手机上空着的补上")
        assertEquals(listOf("MiniMax-M3", "MiniMax-M2.7"), s.provider("qb")!!.models.map { it.id }, "电脑上多出来的模型补进来")
        assertEquals("新增 1 个服务商，新增 1 位成员，「MiniMax M3」多了 1 个模型，「阿米」补上了定位", first.message)

        val second = phone.call(Command.ImportConfig(bundle))
        assertEquals("电脑和这台设备的模型服务、成员已经一样，没有要导入的", second.message)
        assertEquals(3, phone.state.providers.size)
        assertEquals(3, phone.state.members.size)
    }

    @Test
    fun earlierDuplicatesAreMergedOnStartup() = runBlocking {
        // 旧版本导入后留下的样子：同一个账号两份，阿德、阿米各两份，聊天里用的是手机自己那份
        val p = phoneState()
        val dup = p.copy(
            providers = p.providers + listOf(ds("pa", "深度求索 DeepSeek"), mm("pb", "MiniMax 中国版", "MiniMax-M3", "MiniMax-M2.7")),
            members = p.members + listOf(
                Member("ma", "阿德", providerId = "pa", modelId = "deepseek-flash"),
                Member("mb", "阿米", providerId = "pb", modelId = "MiniMax-M3"),
            ),
            conversations = p.conversations + Conversation("c2", memberIds = listOf("mb")),
        )
        val st = Storage(File(dir, "phone"))
        st.saveState(dup)
        st.saveMessages("c2", listOf(Message("x1", "c2", Role.AI, "mb", "我是阿米")))

        val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
        val s = phone.state
        assertEquals(listOf("qa", "qb"), s.providers.map { it.id })
        assertEquals(listOf("na", "nb"), s.members.map { it.id })
        assertEquals(listOf("MiniMax-M3", "MiniMax-M2.7"), s.provider("qb")!!.models.map { it.id })
        assertEquals(listOf("nb"), s.conversation("c2")!!.memberIds, "会话里的成员换成保留的那位")
        phone.call(Command.LoadMessages("c2"))
        assertEquals("nb", phone.store.messages.value["c2"]!!.single().senderId, "聊天记录的发言人跟着换")

        // 再启动一次不再有变化
        val again = Engine(Storage(File(dir, "phone")), isPhone = true)
        assertEquals(s.providers, again.state.providers)
        assertEquals(s.members, again.state.members)
    }

    @Test
    fun differentAccountsOrModelsStaySeparate() {
        val s = AppState(
            providers = listOf(ds("a", "DeepSeek 工作号", key = "sk-1"), ds("b", "DeepSeek 自用号", key = "sk-2")),
            members = listOf(
                Member("m1", "阿德", providerId = "a", modelId = "deepseek-flash"),
                Member("m2", "阿德", providerId = "a", modelId = "deepseek-v4-pro"),
            ),
        )
        val (out, map, removed) = ConfigMerge.dedupe(s)
        assertEquals(0, removed)
        assertTrue(map.isEmpty())
        assertEquals(s, out)
    }
}
