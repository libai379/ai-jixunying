package com.guixing.jixunying

import com.guixing.jixunying.engine.Recorder
import com.guixing.jixunying.engine.noTimeLimit
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.TidyProgress
import com.guixing.jixunying.model.WireFrame
import com.guixing.jixunying.model.AppJson
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 整理记忆改成后台分批、不限时、有进度窗口（2026-11-05）。 */
class TidyTest {
    private fun m(id: String, text: String, topic: String = "", kind: String = "事实") = MemoryItem(id, text, kind, "", topic = topic)

    /** 分批：同一话题 / 没话题的同一类别放在同一批（装得下的话），意思相近的在同一批，一批不超过上限，批里照原来的先后。 */
    @Test
    fun batchesKeepTopicsAndLookalikesTogether() {
        // 每条用不同的字，免得被当成意思相近
        fun word(i: Int) = (0x4E00 + i * 7).toChar().toString() + (0x5E00 + i * 3).toChar()
        val items = buildList {
            add(m("c1", "用户喜欢喝美式咖啡", kind = "偏好"))
            for (i in 0 until 60) add(if (i % 2 == 0) m("p$i", "图片" + word(i), topic = "AI图片生成") else m("r$i", "要求" + word(i), kind = "要求"))
            for (i in 0 until 15) add(m("f$i", "事实" + word(100 + i)))
            add(m("c2", "用户喜欢喝美式咖啡。", kind = "偏好"))
        }
        val batches = Recorder.tidyBatches(items, max = 40)
        assertEquals(items.map { it.id }.toSet(), batches.flatten().map { it.id }.toSet(), "每条都在")
        assertEquals(items.size, batches.sumOf { it.size }, "没有重复")
        assertTrue(batches.all { it.size <= 40 }, batches.map { it.size }.toString())
        val order = items.withIndex().associate { it.value.id to it.index }
        assertTrue(batches.all { b -> b.zipWithNext().all { (x, y) -> order.getValue(x.id) < order.getValue(y.id) } }, "批里照原来的先后")
        fun batchOf(id: String) = batches.indexOfFirst { b -> b.any { it.id == id } }
        assertEquals(batchOf("c1"), batchOf("c2"), "意思相近的两条在同一批")
        for (prefix in listOf("p", "r", "f")) {
            val where = items.filter { it.id.startsWith(prefix) }.map { batchOf(it.id) }.toSet()
            assertEquals(1, where.size, "同一组（$prefix）放在同一批：$where")
        }
        assertEquals(listOf(32, 30, 15), batches.map { it.size }, "装得下就整组放进当前这批，装不下另起一批")

        // 一组超过上限才拆
        val big = (0 until 90).map { m("b$it", "图片" + word(it), topic = "AI图片生成") }
        assertEquals(listOf(40, 40, 10), Recorder.tidyBatches(big, max = 40).map { it.size })
    }

    /** 读整理结果：不截断（以前截在 200 字丢了后半截）；抄进来的「（11月3日记）」去掉；类别不认得的留空（落地时用原来那条的）。 */
    @Test
    fun parseTidyKeepsWholeTextAndStripsDates() {
        val long = "长".repeat(260)
        val plan = Recorder.parseTidy("""[{"text":"$long","kind":"偏好","from":[1,2]},""" +
            """{"text":"住北京（11月3日记）；在上海出差(2025年10月1日记)；报价按实价（11月3日记，11月5日更新）","kind":"随便","from":[3]},""" +
            """{"text":"按（11月3日记录的格式）来写","kind":"要求","from":[4]}]""")!!
        assertEquals(listOf(long, "住北京；在上海出差；报价按实价", "按（11月3日记录的格式）来写"), plan.merges.map { it.text })
        assertEquals(listOf("偏好", "", "要求"), plan.merges.map { it.kind })
    }

    /** 不限时：这一个请求的总时长、多久没数据都不限（连不上 15 秒照旧，不动）；不开的请求不碰全局默认。 */
    @Test
    fun noTimeLimitLiftsOnlyThisRequest() {
        val on = HttpRequestBuilder().apply { noTimeLimit(true) }.getCapabilityOrNull(HttpTimeoutCapability)
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, on?.requestTimeoutMillis)
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, on?.socketTimeoutMillis)
        assertNull(on?.connectTimeoutMillis)
        assertNull(HttpRequestBuilder().apply { noTimeLimit(false) }.getCapabilityOrNull(HttpTimeoutCapability))
    }

    /** 协议：进度小事件能来回；老手机发的心跳没有 caps（电脑就不给它发进度小事件）。 */
    @Test
    fun tidyEventAndHelloCaps() {
        val t = TidyProgress(running = true, total = 168, done = 40, batch = 2, batches = 5, written = 120, model = "mimo-v2.6-flash")
        val back = AppJson.decodeFromString(WireFrame.serializer(), AppJson.encodeToString(WireFrame.serializer(), WireFrame.Evt(Event.Tidy(t))))
        assertEquals(t, ((back as WireFrame.Evt).event as Event.Tidy).tidy)
        val old = AppJson.decodeFromString(WireFrame.serializer(), """{"@t":"com.guixing.jixunying.model.WireFrame.Hello","deviceName":"老手机","wantState":true}""")
        assertEquals(emptyList(), (old as WireFrame.Hello).caps)
        val neu = AppJson.decodeFromString(WireFrame.serializer(), AppJson.encodeToString(WireFrame.serializer(), WireFrame.Hello("新手机", caps = com.guixing.jixunying.model.PHONE_CAPS)))
        assertTrue("tidy" in (neu as WireFrame.Hello).caps)
    }
}
