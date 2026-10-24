package com.guixing.jixunying

import com.guixing.jixunying.engine.EdgeBrowser
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MsgStatus
import com.guixing.jixunying.model.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test

/**
 * 真跑一遍「发平台链接问视频讲了什么」（2026-10-23）：JXY_LIVE_PLATFORM=数据文件夹（复制它的 state.json，不动原数据），
 * JXY_LIVE_PLATFORM_URL=作品链接，JXY_LIVE_PLATFORM_MEMBER=成员名（默认第一位），JXY_BROWSER_PROFILE=浏览器资料夹。
 * 真 Edge、真模型、真听写；只打印 AI 用了哪些工具和回答。
 */
class LivePlatformChatTest {
    @Test
    fun askAboutVideo() = runBlocking<Unit> {
        val src = System.getenv("JXY_LIVE_PLATFORM") ?: return@runBlocking
        val url = System.getenv("JXY_LIVE_PLATFORM_URL") ?: error("要 JXY_LIVE_PLATFORM_URL")
        val profile = System.getenv("JXY_BROWSER_PROFILE")?.let(::File) ?: kotlin.io.path.createTempDirectory("jxy-browser").toFile()
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-platform").toFile()
        val browser = EdgeBrowser(EdgeBrowser.findBrowser() ?: error("找不到 Edge"), profile)
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp), browserFactory = { browser })
            val name = System.getenv("JXY_LIVE_PLATFORM_MEMBER")
            val m = e.state.members.firstOrNull { name == null || it.name == name } ?: error("没有成员 $name")
            val conv = e.call(Command.CreateConversation(listOf(m.id))).data
            val t0 = System.currentTimeMillis()
            e.call(Command.SendMessage(conv, "这个视频讲了什么？说说里面的人说了哪些话。$url"))
            fun ai() = e.store.messages.value[conv].orEmpty().lastOrNull { it.role == Role.AI }
            withTimeout(300_000) { while (ai()?.status != MsgStatus.DONE && ai()?.status != MsgStatus.ERROR) delay(200) }
            val a = ai()!!
            println("成员：${m.name}（${a.modelLabel}），${(System.currentTimeMillis() - t0) / 1000} 秒，状态 ${a.status} ${a.error}")
            a.tools.forEach { println("工具：${it.kind} ok=${it.ok} ${it.input.take(80)} ${it.note}") }
            println("回答：\n" + a.content)
        } finally {
            browser.close()
            tmp.deleteRecursively()
        }
    }
}
