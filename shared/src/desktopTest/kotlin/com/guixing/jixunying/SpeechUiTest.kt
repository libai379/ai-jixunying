package com.guixing.jixunying

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.ImageBitmap
import com.guixing.jixunying.client.Backend
import com.guixing.jixunying.client.ClientStore
import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import com.guixing.jixunying.model.SpeechAudio
import com.guixing.jixunying.model.SpeechPlan
import com.guixing.jixunying.ui.AppController
import com.guixing.jixunying.ui.PickedFile
import com.guixing.jixunying.ui.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「读出来」的界面部分（ui/SpeechUi.kt 的 SpeechPlayer）：先 SpeakPlan 再一段一段 Speak，
 * 放这一段的同时提前合成下一段（最多提前一段），点「停止」后面的段就不再发；回答完自动读的排队。
 * 不联网、不真的出声：假后端按段回结果，假放音器把「放了哪几段」记下来。
 *
 * 界面上的动作（点读出来 / 点停止 / 消息变了）都在同一条「界面线程」上做，和真机上一样；
 * 测试这边用 runBlocking 等条件。
 */
class SpeechUiTest {

    /** 假后端：SpeakPlan 按「；」切段，Speak 回一段 wav（内容就是 seg:段文字，方便断言）。 */
    private class FakeBackend : Backend {
        override val store = ClientStore()
        override val conn = MutableStateFlow<ConnState>(ConnState.Local)
        override val isHost = true
        val calls = CopyOnWriteArrayList<Command>()

        /** 让 SpeakPlan 失败（弹这句话）。 */
        @Volatile var planFail: String? = null

        override suspend fun call(command: Command): CommandResult = when (command) {
            is Command.SpeakPlan -> {
                calls += command
                val fail = planFail
                if (fail != null) CommandResult(ok = false, message = fail)
                else CommandResult(data = AppJson.encodeToString(SpeechPlan.serializer(),
                    SpeechPlan("p1", "mimo-v2.5-tts", "mimo-v2.5-tts（小米 MiMo）", command.text.split("；").filter { it.isNotBlank() })))
            }
            is Command.Speak -> {
                calls += command
                val wav = kotlin.io.encoding.Base64.encode("seg:${command.text}".toByteArray())
                CommandResult(data = AppJson.encodeToString(SpeechAudio.serializer(),
                    SpeechAudio(segments = listOf(wav), label = "mimo-v2.5-tts（小米 MiMo）")))
            }
            else -> CommandResult()
        }

        override suspend fun upload(name: String, mime: String, bytes: ByteArray): Attachment? = null
        override suspend fun fileBytes(id: String): ByteArray? = null

        fun speaks() = calls.filterIsInstance<Command.Speak>().map { it.text }
        fun plans() = calls.filterIsInstance<Command.SpeakPlan>().map { it.text }
    }

    /** 假放音器：记下放了哪几段，每段「放」一会儿；第一段开始放的时候放行 playingFirst。 */
    private class FakePlatform(private val playMs: Long) : Platform {
        override val isDesktop = true
        override val deviceName = "测试机"
        override val canPlayAudio = true
        val played = CopyOnWriteArrayList<String>()
        val playingFirst = CompletableDeferred<Unit>()

        override suspend fun playWav(bytes: ByteArray) {
            played += String(bytes)
            playingFirst.complete(Unit)
            if (playMs > 0) delay(playMs)
        }

        override suspend fun pickFiles(imagesOnly: Boolean): List<PickedFile> = emptyList()
        override fun decodeImage(bytes: ByteArray): ImageBitmap? = null
        override suspend fun saveFile(name: String, bytes: ByteArray): Boolean = false
        override fun openUrl(url: String) {}
    }

    /**
     * 控制器跑在自己的「界面线程」上（读出来在后台协程里干，和真机上一样；
     * 弹提示条那句会一直挂着——测试里没有真的提示条去接管它——所以收工前把这个 scope 撤销掉）。
     */
    private fun playerTest(playMs: Long = 0, planFail: String? = null, body: suspend (FakeBackend, FakePlatform, AppController) -> Unit) = runBlocking {
        val b = FakeBackend().also { it.planFail = planFail }
        val p = FakePlatform(playMs)
        val scope = CoroutineScope(SupervisorJob() + uiThread)
        val ctl = AppController(Hub(b), b, scope, SnackbarHostState(), p)
        try {
            body(b, p, ctl)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun onUi(block: suspend () -> Unit) = withContext(uiThread) { block() }

    private suspend fun waitUntil(what: String, timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            delay(5)
        }
        kotlin.test.fail("等不到：$what（等了 ${timeoutMs / 1000} 秒）")
    }

    /** 第一段开始放了（放的活儿在后台协程里，这里等它一下）。 */
    private suspend fun firstPlaying(p: FakePlatform) = withTimeout(5000) { p.playingFirst.await() }

    private fun ai(id: String, content: String, status: MsgStatus = MsgStatus.DONE) =
        Message(id, "c1", Role.AI, "m1", content, status = status)

    @Test
    fun readsInOrderWithPlan() = playerTest { b, p, ctl ->
        onUi { ctl.speech.read("m1", "一；二；三") }
        waitUntil("三段都放完") { p.played.size == 3 && ctl.speech.readingId == null }

        assertEquals(listOf("一；二；三"), b.plans())
        // 每段都带着 SpeakPlan 挑中的服务商和模型，prepared = true（引擎不再清理一遍 Markdown）
        val speaks = b.calls.filterIsInstance<Command.Speak>()
        assertEquals(listOf("一", "二", "三"), speaks.map { it.text })
        assertTrue(speaks.all { it.providerId == "p1" && it.modelId == "mimo-v2.5-tts" && it.prepared })
        // 按顺序一段一段放
        assertEquals(listOf("seg:一", "seg:二", "seg:三"), p.played)
        assertFalse(ctl.speech.preparing)
    }

    @Test
    fun stopStopsTheRest() = playerTest(playMs = 300) { b, p, ctl ->
        onUi { ctl.speech.read("m1", "一；二；三") }
        firstPlaying(p)
        assertTrue(ctl.speech.isReading("m1"))
        onUi { ctl.speech.stop() }
        delay(300) // 让被取消的那条协程有机会乱来

        // 最多提前合成一段：停的时候最多已经发了前两段，第三段绝不再发
        assertTrue(b.speaks().size <= 2, "停之后还在发段：${b.speaks()}")
        assertTrue("三" !in b.speaks(), "点了停止还在往下合成（会接着花钱）：${b.speaks()}")
        assertNull(ctl.speech.readingId)
        assertFalse(ctl.speech.preparing)
    }

    @Test
    fun tapAgainStops() = playerTest(playMs = 300) { b, p, ctl ->
        onUi { ctl.speech.toggle("m1", "一；二；三") }
        firstPlaying(p)
        assertTrue(ctl.speech.isReading("m1"))
        // 同一条再点一下 = 停止
        onUi { ctl.speech.toggle("m1", "一；二；三") }
        delay(300)

        assertFalse(ctl.speech.isReading("m1"))
        assertNull(ctl.speech.readingId)
        assertTrue("三" !in b.speaks(), "点了停止还在往下合成：${b.speaks()}")
    }

    @Test
    fun readingAnotherStopsPrevious() = playerTest(playMs = 300) { b, p, ctl ->
        onUi { ctl.speech.read("m1", "甲；乙；丙") }
        firstPlaying(p)
        onUi { ctl.speech.read("m2", "子；丑；寅") }
        waitUntil("读 m2 时叫得出 m2 的名字") { ctl.speech.isReading("m2") }
        waitUntil("m2 读完了") { b.speaks().contains("寅") }

        assertTrue("丙" !in b.speaks(), "上一条剩下的段还在合成：${b.speaks()}")
        assertEquals(listOf("甲；乙；丙", "子；丑；寅"), b.plans())
    }

    @Test
    fun planFailureShowsEngineMessage() = playerTest(planFail = "小米 MiMo 的 Key 不对：去 设置 → 语音 换一家") { b, _, ctl ->
        onUi { ctl.speech.read("m1", "一；二") }
        waitUntil("弹出引擎给的原话") { ctl.snackbar.currentSnackbarData != null }

        assertEquals("小米 MiMo 的 Key 不对：去 设置 → 语音 换一家", ctl.snackbar.currentSnackbarData?.visuals?.message)
        assertEquals(0, b.calls.count { it is Command.Speak }, "计划都没成，不该往下合成")
        assertNull(ctl.speech.readingId)
        assertFalse(ctl.speech.preparing)
    }

    @Test
    fun autoReadQueuesAndStopClears() = playerTest(playMs = 300) { b, p, ctl ->
        val a1 = ai("a1", "一；二")
        val a2 = ai("a2", "三；四")

        // 打开对话时已经答完的旧回答不读
        onUi { ctl.speech.onMessages("c1", listOf(a1, a2), enabled = true) }
        delay(120)
        assertEquals(emptyList(), b.plans())

        // a1 答完 → 开始读；a2 跟着答完 → 排队
        onUi { ctl.speech.onMessages("c1", listOf(a1.copy(status = MsgStatus.STREAMING), a2.copy(status = MsgStatus.STREAMING)), enabled = true) }
        onUi { ctl.speech.onMessages("c1", listOf(a1, a2.copy(status = MsgStatus.STREAMING)), enabled = true) }
        onUi { ctl.speech.onMessages("c1", listOf(a1, a2), enabled = true) }
        firstPlaying(p)
        assertTrue(ctl.speech.isReading("a1"))

        onUi { ctl.speech.stop() }
        delay(300)
        // 点「停止」把排着队的也清了：第二条不读
        assertEquals(listOf("一；二"), b.plans())
        assertNull(ctl.speech.readingId)
    }

    @Test
    fun autoReadOffDoesNothing() = playerTest { b, _, ctl ->
        val a1 = ai("a1", "一；二", MsgStatus.STREAMING)
        onUi { ctl.speech.onMessages("c1", listOf(a1), enabled = false) }
        onUi { ctl.speech.onMessages("c1", listOf(ai("a1", "一；二")), enabled = false) }
        delay(120)

        assertEquals(emptyList(), b.plans())
        assertNull(ctl.speech.readingId)
    }

    private companion object {
        /** 界面线程（一条就够，和真机上一样：界面上的动作和读出来的协程在同一条线上）。 */
        val uiThread = Executors.newSingleThreadExecutor { r -> Thread(r, "jxy-speech-test-ui").apply { isDaemon = true } }.asCoroutineDispatcher()
    }
}
