package com.guixing.jixunying

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Conversation
import com.guixing.jixunying.ui.Guide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 新手引导什么时候弹（2026-11-06 用户：「新安装的，或者说是新版本更新的，都可以做一个引导和介绍……可以跳过……从『设置』>『关于』里边再次去查看」）。
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
}
