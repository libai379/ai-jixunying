package com.guixing.jixunying

import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.Thinking
import com.guixing.jixunying.model.ThinkingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 快速 / 深度的参数表：照各家官方文档（2026-10-08 查），写错一个字段名要么白传、要么 400。 */
class ThinkingTest {
    private fun p(preset: String, url: String = "https://example.com/v1") = ProviderConfig("x", preset, preset, url)
    private fun fast(preset: String, model: String) = Thinking.params(Thinking.rule(p(preset), model), ThinkingMode.FAST)?.toString()
    private fun deep(preset: String, model: String) = Thinking.params(Thinking.rule(p(preset), model), ThinkingMode.DEEP)?.toString()

    @Test
    fun fourMembers() {
        // 阿德 DeepSeek-V4.1-Flash：默认开思考（强度 high），能关，最深 max
        assertEquals("""{"thinking":{"type":"disabled"}}""", fast("deepseek", "deepseek-flash"))
        assertEquals("""{"thinking":{"type":"enabled"},"reasoning_effort":"max"}""", deep("deepseek", "deepseek-flash"))
        // 阿麦 MiniMax-M3：只认 adaptive / disabled，绝不能传 enabled（400）；默认就是开
        assertEquals("""{"thinking":{"type":"disabled"}}""", fast("minimax", "MiniMax-M3"))
        assertNull(deep("minimax", "MiniMax-M3"), "默认就会想，不传")
        // 阿智 GLM-5.3-Flash：关不掉（传 disabled 报错），快速 = 强度 low；默认已是 max
        assertEquals("""{"thinking":{"type":"enabled"},"reasoning_effort":"low"}""", fast("zhipu", "GLM-5.3-Flash"))
        assertTrue(Thinking.rule(p("zhipu"), "GLM-5.3-Flash").fastOnlyLess)
        assertNull(deep("zhipu", "GLM-5.3-Flash"))
        // 阿米 mimo-v2.6-flash：默认开，能关，没有强度
        assertEquals("""{"thinking":{"type":"disabled"}}""", fast("mimo", "mimo-v2.6-flash"))
        assertEquals("""{"thinking":{"type":"enabled"}}""", deep("mimo", "mimo-v2.6-flash"))
    }

    @Test
    fun othersAndUnknown() {
        // 千问：enable_thinking
        assertEquals("""{"enable_thinking":false}""", fast("qianwen", "qwen3.8-max"))
        assertEquals("""{"enable_thinking":true}""", deep("dashscope", "qwen3-max"))
        assertFalse(Thinking.rule(p("dashscope"), "qwen3-max").thinksByDefault!!, "qwen3-max 默认不想")
        // Kimi K3 关不掉，快速 = 强度 low；K2.6 能关
        assertEquals("""{"reasoning_effort":"low"}""", fast("moonshot", "kimi-k3"))
        assertEquals("""{"thinking":{"type":"disabled"}}""", fast("moonshot", "kimi-k2.6"))
        // 火山方舟豆包：能关；关的时候不能带 reasoning_effort；画图模型 seedream 不算
        assertEquals("""{"thinking":{"type":"disabled"}}""", fast("ark", "doubao-seed-2-1-pro-260628"))
        assertNull(deep("ark", "doubao-seed-2-1-pro-260628"), "默认已是最高档")
        assertFalse(Thinking.rule(p("ark"), "doubao-seedream-5-0-260128").known)
        // MiniMax M2.x 关不掉
        assertFalse(Thinking.available(Thinking.rule(p("minimax"), "MiniMax-M2.7"), ThinkingMode.FAST))
        // 自定义服务商填的官方地址也认得出
        assertEquals("deepseek", Thinking.platformOf(p("custom", "https://api.deepseek.com")))
        // 没查到的：两样都不能选、什么都不传
        val unknown = Thinking.rule(p("custom", "http://127.0.0.1:1234/v1"), "my-model")
        assertFalse(unknown.known)
        assertFalse(Thinking.available(unknown, ThinkingMode.FAST) || Thinking.available(unknown, ThinkingMode.DEEP))
        assertNull(Thinking.params(unknown, ThinkingMode.FAST))
    }
}
