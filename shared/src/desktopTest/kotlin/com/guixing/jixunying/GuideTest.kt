package com.guixing.jixunying

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.ui.Guide
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 新手引导什么时候弹（2026-11-06 用户：「新安装的，或者说是新版本更新的，都可以做一个引导和介绍……可以跳过……从『设置』>『关于』里边再次去查看」）。
 * 2026-11-07 完整引导：分章节、弹窗里一栏一栏讲（用户：「除了那种特别简单的功能，我觉得所有功能都要来一个更好的、更完整的引导」）。
 */
class GuideTest {
    private val fresh = AppState()
    private val used = AppState(conversations = listOf(Conversation("c1")))

    @Test
    fun whenItShows() {
        assertEquals("tour", Guide.due(null, fresh, "1.20.0"), "新装的从头讲")
        assertEquals("new", Guide.due(null, used, "1.20.0"), "老用户更新上来没看过引导：只讲新版")
        assertEquals("new", Guide.due("1.19", used, "1.20.0"))
        assertNull(Guide.due("1.20", used, "1.20.3"), "修订版不重复弹")
        assertNull(Guide.due("1.20", used, "1.21.0"), "下一版没写新版介绍就不弹")
        assertEquals("1.20", Guide.versionKey("1.20.3"))
    }

    @Test
    fun pagesSayWhereThingsAre() {
        assertTrue(Guide.tour.size >= 5)
        assertTrue(Guide.tour.last().where.contains("设置 → 关于"), "最后一页告诉用户去哪儿再看")
        assertTrue(Guide.whatsNew.getValue("1.20").any { "记忆" in it.title })
    }

    /** 用户点名要讲的：加模型、加成员、画图视频怎么选、自动选、联网、语音、Office、文档、按意思搜、立场档案、会审、Code、狼人杀、微信、联机、更新、花费。 */
    @Test
    fun chaptersCoverWhatTheUserAskedFor() {
        val all = Guide.chapters.flatMap { it.pages }
        val text = all.joinToString("\n") { it.title + " " + it.body }
        listOf("添加服务商", "API Key", "拉取模型列表", "添加成员", "定位", "思考", "画图模型", "自动（推荐）", "做视频", "联网", "读出来", "话筒",
            "Word", "我的文档", "按意思搜", "立场档案", "会审", "Code", "狼人杀", "微信", "扫码配对", "检查更新", "花了多少钱").forEach {
            assertTrue(it in text, "引导里没讲到「$it」")
        }
        // 新手必看：欢迎 + 必看的三章 + 告诉他别的章在哪儿
        assertEquals(listOf("models", "members", "chat"), Guide.chapters.filter { it.essential }.map { it.id })
        assertEquals(1 + Guide.chapters.filter { it.essential }.sumOf { it.pages.size } + 1, Guide.tour.size)
        assertEquals(Guide.chapters.size, Guide.chapters.map { it.id }.toSet().size, "章节编号不能重")
        assertEquals(Guide.chapter("image")!!.pages, Guide.pagesFor("ch:image"))
        assertEquals("功能介绍 · 画图和视频", Guide.labelFor("ch:image"))
        assertEquals(Guide.tour, Guide.pagesFor("tour"))
        // 弹窗里的那几步：只有引导会打开的三个弹窗
        assertEquals(setOf("provider", "member", "convset"), all.mapNotNull { it.dialog }.toSet())
    }

    /** 每一步要圈的东西，界面代码里真有人用 Modifier.guideTarget("…") 报位置（名字写错就圈不到，只剩文字框）。 */
    @Test
    fun everyTargetIsTaggedInTheUi() {
        val src = listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin")).first { it.isDirectory }
        val tagged = src.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""guideTarget\("([^"]+)"\)""").findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        val wanted = (Guide.chapters.flatMap { it.pages } + Guide.tour + Guide.whatsNew.values.flatten()).mapNotNull { it.target }.toSet()
        // guide = "qdocs" 这种是经 QuickCard 的参数传进去的
        val viaParam = Regex("""guide = "([^"]+)"""").findAll(File(src, "com/guixing/jixunying/ui/Sidebar.kt").readText()).map { it.groupValues[1] }.toSet()
        val missing = wanted - tagged - viaParam
        assertTrue(missing.isEmpty(), "这些要圈的没人报位置：$missing")
    }
}
