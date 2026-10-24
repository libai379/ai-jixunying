package com.guixing.jixunying

import com.guixing.jixunying.engine.SocialLinks
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 真开 Edge 读平台页面（2026-10-23）：JXY_LIVE_BROWSER=网址1,网址2…；JXY_BROWSER_PROFILE=资料夹（默认临时的，不带登录）。
 * 只打印读到了什么，不断言（平台随时改版）。
 */
class LiveBrowserTest {
    @Test
    fun readPages() = runBlocking<Unit> {
        val urls = System.getenv("JXY_LIVE_BROWSER")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@runBlocking
        val profile = System.getenv("JXY_BROWSER_PROFILE")?.let(::File) ?: kotlin.io.path.createTempDirectory("jxy-browser").toFile()
        val b = com.guixing.jixunying.engine.EdgeBrowser(com.guixing.jixunying.engine.EdgeBrowser.findBrowser() ?: error("找不到 Edge / Chrome"), profile)
        try {
            for (u in urls) {
                val t0 = System.currentTimeMillis()
                val page = runCatching { b.snapshot(u, SocialLinks.EXTRACT_JS) }
                println("==== $u（${System.currentTimeMillis() - t0} ms）")
                page.onFailure { println("出错：$it") }.onSuccess { p ->
                    val plat = SocialLinks.platformOf(u)
                    println("blocked=${SocialLinks.blocked(p)}")
                    println(if (plat != null) SocialLinks.format(plat, p).take(2500) else p.toString().take(2500))
                    if (System.getenv("JXY_LIVE_DEBUG") != null) {
                        val d = b.snapshot(u, "JSON.stringify({t:document.title,u:location.href,len:document.documentElement.outerHTML.length,body:(document.body&&document.body.innerHTML||'').slice(0,1500),txt:(document.body&&document.body.innerText||'').slice(0,800)})", 8_000)
                        println("调试：" + d)
                    }
                    if (System.getenv("JXY_LIVE_LINKS") != null) {
                        val links = b.snapshot(u, "JSON.stringify({l:[...new Set([...document.querySelectorAll('a[href]')].map(a=>a.href))]})", 3_000)
                        println("链接：" + (links["l"] as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }
                            .filter { Regex("/video/|/explore/|/short-video/|/detail/|/status/|BV1|/discovery/item/").containsMatchIn(it) }.take(6))
                    }
                }
            }
            SocialLinks.all.forEach { println("${it.name} cookies: " + b.cookieNames(it.cookieDomain).take(30)) }
        } finally { b.close() }
    }
}
