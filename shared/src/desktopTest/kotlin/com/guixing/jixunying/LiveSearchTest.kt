package com.guixing.jixunying

import com.guixing.jixunying.engine.WebSearch
import com.guixing.jixunying.model.SearchSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** 真联网的检查，平时跳过；设环境变量 JXY_LIVE=1 才跑。 */
class LiveSearchTest {
    @Test
    fun bingFreeReturnsResults() = runBlocking {
        if (System.getenv("JXY_LIVE") != "1") return@runBlocking
        val ws = WebSearch { "127.0.0.1:10809" }
        val res = ws.search("DeepSeek 最新模型 发布", SearchSettings())
        res.forEach { println("${it.title} | ${it.url} | ${it.snippet.take(60)}") }
        assertTrue(res.size >= 3, "必应只返回了 ${res.size} 条")
        val page = ws.fetch(res.first().url, false)
        println("正文前 200 字：" + page.take(200))
        assertTrue(page.length > 100)
    }
}
