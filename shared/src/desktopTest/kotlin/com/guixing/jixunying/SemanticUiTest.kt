package com.guixing.jixunying

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.ImageBitmap
import com.guixing.jixunying.client.Backend
import com.guixing.jixunying.client.ClientStore
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.PriceTier
import com.guixing.jixunying.model.ModelPrice
import com.guixing.jixunying.model.SemanticEstimatePart
import com.guixing.jixunying.model.SemanticPart
import com.guixing.jixunying.model.SemanticProgress
import com.guixing.jixunying.model.SemanticSettings
import com.guixing.jixunying.model.Settings
import com.guixing.jixunying.ui.AppController
import com.guixing.jixunying.ui.PickedFile
import com.guixing.jixunying.ui.Platform
import com.guixing.jixunying.ui.SemanticUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设置 →「按意思搜」界面上那几句判断（ui/SemanticUi.kt）：开关前先估算、进度和进度条写到哪、
 * 哪些时候显示「重新算一遍」、换服务商 / 模型按新那家的价格重估。都是纯函数，不联网、不画界面。
 */
class SemanticUiTest {

    private val history = SemanticUi.HISTORY
    private val docs = SemanticUi.DOCS

    // ———————————————— 拨开关：先估算，点「开始算」才真的打开 ————————————————

    @Test
    fun turnOnAsksFirst() {
        // 有人能算：拨到「开」不是马上打开，而是先去弹估算
        val a = SemanticUi.switchAction(SemanticSettings(), history, want = true, usable = true)
        assertIs<SemanticUi.SwitchAction.Ask>(a)
        assertEquals(history, a.section, "弹的应该是刚拨的那一类")

        // 拨的是文档就弹文档那一类
        assertEquals(docs, (SemanticUi.switchAction(SemanticSettings(docsOn = false), docs, want = true, usable = true) as SemanticUi.SwitchAction.Ask).section)

        // 用户点了「开始算」才真的打开（只打开这一类，另一类不动）
        val after = SemanticUi.turnOn(SemanticSettings(), history)
        assertTrue(after.historyOn)
        assertFalse(after.docsOn)
        assertTrue(SemanticUi.turnOn(SemanticSettings(historyOn = true), docs).let { it.historyOn && it.docsOn })
    }

    @Test
    fun turnOffGoesStraight() {
        // 关掉不弹估算（不花钱），直接存
        val a = SemanticUi.switchAction(SemanticSettings(historyOn = true, docsOn = true), history, want = false, usable = true)
        assertIs<SemanticUi.SwitchAction.Save>(a)
        assertFalse(a.next.historyOn)
        assertTrue(a.next.docsOn, "关聊天记录不该动文档那一类")
    }

    @Test
    fun noProviderTurnsOnNothing() {
        // 一个能算的服务商都没有：开关不打开（页面上这时整页都是灰的）
        val a = SemanticUi.switchAction(SemanticSettings(), docs, want = true, usable = false)
        assertIs<SemanticUi.SwitchAction.Nothing>(a)
        // 关还是关得掉（用户想关，不能因为没服务商就关不了）
        assertIs<SemanticUi.SwitchAction.Save>(SemanticUi.switchAction(SemanticSettings(docsOn = true), docs, want = false, usable = false))
    }

    // ———————————————— 进度那一行 ————————————————

    @Test
    fun partLineShowsWhyItIsNotRunning() {
        val off = SemanticPart(done = 0, total = 0)
        assertEquals("没开", SemanticUi.partLine(off, on = false, usable = true))
        assertEquals("没有能算的服务商", SemanticUi.partLine(off, on = true, usable = false))
        assertEquals("还没算过", SemanticUi.partLine(off, on = true, usable = true))

        // 算过一部分、现在停着
        assertEquals("已算 1200 / 3400 段", SemanticUi.partLine(SemanticPart(done = 1200, total = 3400), on = true, usable = true))
        // 正在算
        val running = SemanticUi.partLine(SemanticPart(done = 12, total = 40, running = true), on = true, usable = true)
        assertTrue(running.startsWith("正在算…"), running)
        // 出错：写清楚原因
        val bad = SemanticUi.partLine(SemanticPart(error = "API Key 不对或已失效（HTTP 401）：{\"error\":…}"), on = true, usable = true)
        assertTrue(bad.startsWith("出错了："), bad)
        assertTrue(bad.contains("401"), bad)
    }

    @Test
    fun partLineShowsStale() {
        // 换了服务商 / 模型：显示 SemanticPart.text 那句（提醒要重新算一遍）
        val p = SemanticPart(done = 40, total = 40, model = "embedding-3", stale = true)
        assertEquals(p.text, SemanticUi.partLine(p, on = true, usable = true))
        assertTrue(p.text.contains("重新算一遍"), p.text)

        // 出错比 stale 先写（出错了就别再谈以前那个模型了）
        val both = p.copy(error = "连不上")
        assertEquals("出错了：连不上", SemanticUi.partLine(both, on = true, usable = true))
    }

    @Test
    fun percentAndRebuildButton() {
        assertEquals(35, SemanticUi.percent(SemanticPart(done = 1200, total = 3400)))
        assertEquals(0, SemanticUi.percent(SemanticPart()))
        assertEquals(100, SemanticUi.percent(SemanticPart(done = 40, total = 40)))
        // 数字对不上（清单变过）也不会超过 100 或者变成负的
        assertEquals(100, SemanticUi.percent(SemanticPart(done = 50, total = 40)))

        val doneAll = SemanticPart(done = 40, total = 40)
        assertTrue(SemanticUi.canRebuild(doneAll, on = true, usable = true))
        assertFalse(SemanticUi.canRebuild(doneAll, on = false, usable = true), "没开的时候不显示「重新算一遍」")
        assertFalse(SemanticUi.canRebuild(doneAll.copy(running = true), on = true, usable = true), "正在算的时候不给点")
        assertFalse(SemanticUi.canRebuild(SemanticPart(), on = true, usable = true), "什么都没算过就不用重算")
        // 出错、要重算的：都给一个「重新算一遍」让用户能重来
        assertTrue(SemanticUi.canRebuild(SemanticPart(error = "连不上"), on = true, usable = true))
        assertTrue(SemanticUi.canRebuild(SemanticPart(done = 40, total = 40, stale = true), on = true, usable = true))
    }

    @Test
    fun computedSectionsNeedRebuildConfirm() {
        assertEquals(emptyList(), SemanticUi.computed(SemanticProgress()))
        // 算过东西的才要先问「换了以后要重算」
        assertEquals(listOf(history), SemanticUi.computed(SemanticProgress(history = SemanticPart(done = 40, total = 40))))
        assertEquals(listOf(docs), SemanticUi.computed(SemanticProgress(docs = SemanticPart(builtAt = 123L))))
        // 标了 stale 的也算（以前算的还在，只是不能用）
        assertEquals(listOf(history, docs), SemanticUi.computed(SemanticProgress(
            history = SemanticPart(done = 5, total = 5),
            docs = SemanticPart(done = 5, total = 5, stale = true),
        )))
    }

    @Test
    fun rebuildScope() {
        assertEquals(history, SemanticUi.scopeOf(listOf(history)))
        assertEquals(docs, SemanticUi.scopeOf(listOf(docs)))
        // 两类都重算：留空，引擎当 all
        assertEquals("", SemanticUi.scopeOf(listOf(history, docs)))
    }

    // ———————————————— 估算 ————————————————

    @Test
    fun recostUsesNewPrice() {
        // 10 万个字 ≈ 7.5 万 token；智谱 embedding-3 是 0.5 元 / 百万 token
        val part = SemanticEstimatePart(chars = 100_000, chunks = 3400, tokens = 75_000, cost = 0.031)
        val price = ModelPrice("CNY", listOf(PriceTier(input = 0.5, output = 0.0)))
        val again = SemanticUi.recost(part, price)
        assertEquals(0.0375, again.cost!!, 1e-9)
        assertEquals(3400, again.chunks, "字数和段数和服务商无关，只有价钱变了")
        // 价格没查到：算不出钱
        assertNull(SemanticUi.recost(part, null).cost)
    }

    @Test
    fun costText() {
        // 价格表里有这个模型：写清楚「粗估」两个字
        assertEquals("粗估 0.0375 元", SemanticUi.costText(SemanticEstimatePart(tokens = 75_000, cost = 0.0375), "CNY"))
        // 价格没查到：照实说（别写 0）
        assertEquals("价格没查到，按它的账单为准", SemanticUi.costText(SemanticEstimatePart(tokens = 75_000, cost = null), "CNY"))
        // 便宜到四位小数看不出来：也不能写成「0 元」
        assertEquals("粗估不到 0.0001 元", SemanticUi.costText(SemanticEstimatePart(tokens = 60, cost = 0.00003), "CNY"))
        // 真的不要钱（限时免费这类）还是写 0
        assertEquals("粗估 0 元", SemanticUi.costText(SemanticEstimatePart(tokens = 60, cost = 0.0), "CNY"))
        assertEquals("粗估 0.0375 美元", SemanticUi.costText(SemanticEstimatePart(tokens = 75_000, cost = 0.0375), "USD"))
    }

    @Test
    fun charsText() {
        assertEquals("998 字", SemanticUi.charsText(998))
        assertEquals("9999 字", SemanticUi.charsText(9_999))
        assertEquals("1.2 万字", SemanticUi.charsText(12_345))
        assertEquals("1.2 万字", SemanticUi.charsText(12_000))
        assertEquals("12.3 万字", SemanticUi.charsText(123_456))
    }

    // ———————————————— 设置首页右边的小字 ————————————————

    @Test
    fun summary() {
        assertEquals("已关闭", SemanticUi.summary(SemanticSettings(), SemanticProgress()))
        assertEquals("聊天记录", SemanticUi.summary(SemanticSettings(historyOn = true), SemanticProgress()))
        assertEquals("文档", SemanticUi.summary(SemanticSettings(docsOn = true), SemanticProgress()))
        assertEquals("聊天记录、文档", SemanticUi.summary(SemanticSettings(historyOn = true, docsOn = true), SemanticProgress()))
        // 正在算：不管开关，先报进度
        assertEquals("正在算 35%", SemanticUi.summary(SemanticSettings(historyOn = true),
            SemanticProgress(history = SemanticPart(done = 35, total = 100, running = true))))
        // 两类一起算：合起来算一个百分比（20 + 40 = 60 / 200）
        assertEquals("正在算 30%", SemanticUi.summary(SemanticSettings(historyOn = true, docsOn = true),
            SemanticProgress(
                history = SemanticPart(done = 20, total = 100, running = true),
                docs = SemanticPart(done = 40, total = 100, running = true),
            )))
        // 还没数出总数（刚开工）也别写成 0/0
        assertEquals("正在算 0%", SemanticUi.summary(SemanticSettings(docsOn = true),
            SemanticProgress(docs = SemanticPart(done = 0, total = 0, running = true))))
    }

    // ———————————————— 换服务商：设置先落地，再重算 ————————————————

    /** 假后端：把发过来的指令记下来，不改真数据。 */
    private class FakeBackend : Backend {
        override val store = ClientStore()
        override val conn = kotlinx.coroutines.flow.MutableStateFlow<ConnState>(ConnState.Local)
        override val isHost = true
        val calls = CopyOnWriteArrayList<Command>()
        override suspend fun call(command: Command): CommandResult {
            calls += command
            return CommandResult()
        }
        override suspend fun upload(name: String, mime: String, bytes: ByteArray): Attachment? = null
        override suspend fun fileBytes(id: String): ByteArray? = null
    }

    private class FakePlatform : Platform {
        override val isDesktop = true
        override val deviceName = "测试机"
        override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> = emptyList()
        override fun decodeImage(bytes: ByteArray): ImageBitmap? = null
        override suspend fun saveFile(name: String, bytes: ByteArray): Boolean = false
        override fun openUrl(url: String) {}
    }

    /**
     * 换服务商 / 模型的时候：设置得先存下去，再发 SemanticRebuild。
     * 反过来的话引擎重算时还按旧那家挑，又拿旧那家跑一遍、白花一次钱。
     */
    @Test
    fun settingsSavedBeforeRebuild() = runBlocking {
        val b = FakeBackend()
        val scope = CoroutineScope(SupervisorJob() + uiThread)
        val ctl = AppController(Hub(b), b, scope, SnackbarHostState(), FakePlatform())
        try {
            withContext(uiThread) {
                ctl.updateSettingsThen({ it.copy(semantic = it.semantic.copy(providerId = "pz")) }) {
                    ctl.backend.call(Command.SemanticRebuild(SemanticUi.scopeOf(listOf(history, docs))))
                }
            }
            val end = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < end && b.calls.size < 2) delay(5)

            val kinds = b.calls.map { if (it is Command.SaveSettings) "save" else it::class.simpleName }
            assertEquals(listOf("save", "SemanticRebuild"), kinds, "顺序必须是先存设置再重算")
            val saved = b.calls.filterIsInstance<Command.SaveSettings>().first()
            assertEquals("pz", saved.settings.semantic.providerId)
            assertEquals(Settings.SCHEMA, saved.settings.schema, "存设置要带上自己的版本号（旧版手机不懂的那几项靠它）")
            // 两类都重算：scope 留空，引擎当 all
            assertEquals("", (b.calls.filterIsInstance<Command.SemanticRebuild>().first()).scope)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        /** 界面线程（一条就够，和真机上一样）。 */
        val uiThread = Executors.newSingleThreadExecutor { r -> Thread(r, "jxy-semantic-test-ui").apply { isDaemon = true } }.asCoroutineDispatcher()
    }
}
