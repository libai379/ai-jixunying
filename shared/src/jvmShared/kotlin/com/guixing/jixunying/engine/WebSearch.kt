package com.guixing.jixunying.engine

import com.guixing.jixunying.model.SearchEngine
import com.guixing.jixunying.model.SearchSettings
import com.guixing.jixunying.model.SearchSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 联网搜索。默认用免费的必应网页（不要 Key，国内直连），
 * 有 Key 时可以换成博查 / Tavily / 智谱 / Brave 这些正规搜索 API，更稳。
 */
class WebSearch(private val proxy: () -> String?) {

    suspend fun search(query: String, s: SearchSettings): List<SearchSource> {
        val px = if (s.useProxy) proxy() else null
        val n = s.maxResults.coerceIn(3, 10)
        val key = s.apiKeys[s.engine.name].orEmpty().trim()
        if (s.engine != SearchEngine.BING_FREE && key.isEmpty()) {
            throw IllegalStateException("搜索引擎 ${engineName(s.engine)} 还没填 Key，请到 设置→联网搜索 填写，或换回免费的必应")
        }
        return when (s.engine) {
            SearchEngine.BING_FREE -> free(query, n, px)
            SearchEngine.BOCHA -> bocha(query, n, key, px)
            SearchEngine.TAVILY -> tavily(query, n, key, px)
            SearchEngine.ZHIPU -> zhipu(query, n, key, px)
            SearchEngine.BRAVE -> brave(query, n, key, px)
        }
    }

    /**
     * Kimi 官方搜索接口（POST {base}/tools/search，用 Kimi 的 Key，Basic ¥0.01/次）。
     * 替代 2026-10-20 下线的内置 $web_search，出处见 docs/参考资料.md「Kimi 联网搜索」。
     */
    suspend fun kimi(q: String, baseUrl: String, key: String, s: SearchSettings): List<SearchSource> {
        val o = postJson(baseUrl.trimEnd('/') + "/tools/search", key, if (s.useProxy) proxy() else null, buildJsonObject {
            put("text_query", q); put("limit", s.maxResults.coerceIn(3, 10)); put("timeout_seconds", 20)
        })
        return (o["search_results"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            SearchSource(it.str("title").orEmpty(), it.str("url").orEmpty(), it.str("snippet").orEmpty().take(500))
        }.filter { it.url.startsWith("http") }
    }

    /**
     * 免费搜索：必应和 DuckDuckGo 一起搜，按和搜索词对得上的程度挑。
     * 2026-10-21 实测：不带登录的必应对中文题常返回完全无关的结果（搜珠峰高度出来工商银行股票、二手车），
     * 不是空的，所以以前「必应没结果才换 DuckDuckGo」永远换不过去；cn.bing.com/search 还会被跳回首页、一条没有。
     */
    private suspend fun free(q: String, n: Int, px: String?): List<SearchSource> = coroutineScope {
        // 国内不走代理时 DuckDuckGo 连不上（手机常这样），各给 12 秒，不让一边拖住
        val b = async { withTimeoutOrNull(12_000) { runCatching { bing(q, n, px) }.getOrNull() }.orEmpty() }
        val d = async { withTimeoutOrNull(12_000) { duck(q, n, proxy()) }.orEmpty() }
        Relevance.pick(q, listOf(d.await(), b.await()), n)
    }

    private suspend fun bing(q: String, n: Int, px: String?): List<SearchSource> {
        // www + mkt=zh-CN：不带 mkt 时链接是 bing.com/ck/a 跳转，cn.bing.com 会被跳回首页
        val html = Http.client(px).get("https://www.bing.com/search") {
            parameter("q", q)
            parameter("count", n + 4)
            parameter("mkt", "zh-CN")
            header("User-Agent", Http.UA)
            header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        }.bodyAsText()
        val out = mutableListOf<SearchSource>()
        val blocks = Regex("""<li class="b_algo"[\s\S]*?</li>""").findAll(html)
        for (b in blocks) {
            val block = b.value
            val a = Regex("""<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""").find(block) ?: continue
            val url = Html.unescape(a.groupValues[1])
            if (!url.startsWith("http")) continue
            val title = Html.toText(a.groupValues[2])
            val snip = Regex("""<p[^>]*>([\s\S]*?)</p>""").find(block)?.groupValues?.get(1)
                ?: Regex("""class="b_lineclamp\d?"[^>]*>([\s\S]*?)</""").find(block)?.groupValues?.get(1) ?: ""
            out += SearchSource(title, url, Html.toText(snip).take(300))
            if (out.size >= n) break
        }
        return out
    }

    private suspend fun duck(q: String, n: Int, px: String?): List<SearchSource> = runCatching {
        // 2026-10-21 实测：GET 回 202 拦截页、一条没有；照网页表单 POST 才有结果
        val html = Http.client(px).post("https://html.duckduckgo.com/html/") {
            setBody(FormDataContent(Parameters.build { append("q", q); append("kl", "cn-zh") }))
            header("User-Agent", Http.UA)
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        }.bodyAsText()
        val links = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""").findAll(html).toList()
        val snips = Regex("""class="result__snippet"[^>]*>([\s\S]*?)</a>""").findAll(html).toList()
        links.take(n).mapIndexed { i, m ->
            var url = Html.unescape(m.groupValues[1])
            Regex("""uddg=([^&]+)""").find(url)?.let { url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            SearchSource(Html.toText(m.groupValues[2]), url, snips.getOrNull(i)?.let { Html.toText(it.groupValues[1]) }.orEmpty())
        }
    }.getOrDefault(emptyList())

    private suspend fun postJson(url: String, key: String, px: String?, body: JsonObject): JsonObject {
        val resp = Http.client(px).post(url) {
            header("Authorization", "Bearer $key")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        return Json.parse(text).jsonObject
    }

    private suspend fun bocha(q: String, n: Int, key: String, px: String?): List<SearchSource> {
        val o = postJson("https://api.bochaai.com/v1/web-search", key, px, buildJsonObject {
            put("query", q); put("summary", true); put("count", n)
        })
        val arr = ((o["data"] as? JsonObject)?.get("webPages") as? JsonObject)?.get("value") as? JsonArray
        return arr.orEmpty().map { it.jsonObject }.map {
            SearchSource(it.str("name").orEmpty(), it.str("url").orEmpty(), (it.str("summary") ?: it.str("snippet")).orEmpty().take(500))
        }
    }

    private suspend fun tavily(q: String, n: Int, key: String, px: String?): List<SearchSource> {
        val o = postJson("https://api.tavily.com/search", key, px, buildJsonObject {
            put("query", q); put("max_results", n); put("search_depth", "basic")
        })
        return (o["results"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            SearchSource(it.str("title").orEmpty(), it.str("url").orEmpty(), it.str("content").orEmpty().take(500))
        }
    }

    private suspend fun zhipu(q: String, n: Int, key: String, px: String?): List<SearchSource> {
        val o = postJson("https://open.bigmodel.cn/api/paas/v4/web_search", key, px, buildJsonObject {
            put("search_query", q); put("search_engine", "search_std"); put("count", n)
        })
        return (o["search_result"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
            SearchSource(it.str("title").orEmpty(), it.str("link").orEmpty(), it.str("content").orEmpty().take(500))
        }
    }

    private suspend fun brave(q: String, n: Int, key: String, px: String?): List<SearchSource> {
        val resp = Http.client(px).get("https://api.search.brave.com/res/v1/web/search") {
            parameter("q", q); parameter("count", n)
            header("X-Subscription-Token", key)
            header("Accept", "application/json")
        }
        val text = resp.bodyAsText()
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, text)
        val results = (Json.parse(text).jsonObject["web"] as? JsonObject)?.get("results") as? JsonArray
        return results.orEmpty().map { it.jsonObject }.map {
            SearchSource(it.str("title").orEmpty(), it.str("url").orEmpty(), Html.toText(it.str("description").orEmpty()))
        }
    }

    /** 读一个网页的正文（粗略去标签），给模型看详情用。 */
    suspend fun fetch(url: String, useProxy: Boolean): String {
        val resp = Http.client(if (useProxy) proxy() else null).get(url) {
            header("User-Agent", Http.UA)
            header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        }
        if (resp.status.value !in 200..299) throw ApiException(resp.status.value, "网页打不开")
        val text = resp.bodyAsText()
        val ct = resp.headers["Content-Type"].orEmpty()
        return (if (ct.contains("html") || text.trimStart().startsWith("<")) Html.toText(Html.mainPart(text)) else text).take(12_000)
    }

    companion object {
        fun engineName(e: SearchEngine) = when (e) {
            SearchEngine.BING_FREE -> "必应（免费，无需 Key）"
            SearchEngine.BOCHA -> "博查 Bocha"
            SearchEngine.TAVILY -> "Tavily"
            SearchEngine.ZHIPU -> "智谱 Web Search"
            SearchEngine.BRAVE -> "Brave Search"
        }
    }
}

/** 搜索结果和搜索词对不对得上：英文数字按词、中文按相邻两字，算搜索词里有几成出现在标题或摘要里。 */
object Relevance {
    private fun terms(q: String): Set<String> {
        val out = mutableSetOf<String>()
        Regex("""[A-Za-z0-9][A-Za-z0-9.+#-]*""").findAll(q).forEach { if (it.value.length >= 2) out += it.value.lowercase() }
        Regex("""[一-鿿]+""").findAll(q).forEach { m ->
            val s = m.value
            if (s.length == 1) out += s else for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
        }
        return out
    }

    fun score(q: String, s: SearchSource): Double {
        val t = terms(q)
        if (t.isEmpty()) return 1.0
        val text = (s.title + " " + s.snippet).lowercase()
        return t.count { it in text }.toDouble() / t.size
    }

    /** 几个引擎的结果按顺序轮流取、同一网址只留一个，扔掉对不上的（不到两成）；按分数从高到低。 */
    fun pick(q: String, lists: List<List<SearchSource>>, n: Int, min: Double = 0.2): List<SearchSource> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<Pair<SearchSource, Double>>()
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) for (l in lists) {
            val s = l.getOrNull(i) ?: continue
            if (seen.add(s.url.trimEnd('/'))) merged += s to score(q, s)
        }
        return merged.filter { it.second >= min }.sortedByDescending { it.second }.take(n).map { it.first }
    }
}

object Html {
    fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")
        .replace("&ensp;", " ").replace("&#0183;", "·").replace("&middot;", "·")

    /** 取正文：在 article / main / 正文类 div 里挑文字最多的一块；都太短就用整页。 */
    fun mainPart(html: String): String {
        val candidates = Regex("""<(article|main)\b[\s\S]*?</\1>""", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
        val best = candidates.maxByOrNull { toText(it).length }
        return if (best != null && toText(best).length >= 300) best else html
    }

    fun toText(html: String): String {
        var s = html
        s = s.replace(Regex("""<(script|style|noscript|svg|nav|footer|header)[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""<!--[\s\S]*?-->"""), " ")
        s = s.replace(Regex("""<(br|p|div|li|h[1-6]|tr)[^>]*>""", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("""<[^>]+>"""), "")
        s = unescape(s)
        s = s.replace(Regex("""[ \t ]+"""), " ").replace(Regex("""\n\s*\n+"""), "\n").trim()
        return s
    }
}
