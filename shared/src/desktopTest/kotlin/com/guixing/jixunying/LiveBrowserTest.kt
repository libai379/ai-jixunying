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
                    System.getenv("JXY_LIVE_AUDIO")?.let { outDir ->
                        val v = (p["note"] as? kotlinx.serialization.json.JsonObject)?.get("video")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }?.takeIf { it.isNotBlank() }
                            ?: (p["videos"] as? kotlinx.serialization.json.JsonArray)?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }?.firstOrNull { "douyinstatic" !in it }
                        if (v == null) println("没有视频地址") else {
                            val t1 = System.currentTimeMillis()
                            val (pcm, secs) = b.audioPcm(v, 60)
                            val wav = com.guixing.jixunying.engine.SpeechText.wavOf(16000, 1, 16, pcm)
                            val f = File(outDir, "audio-${System.currentTimeMillis()}.wav").also { it.parentFile.mkdirs(); it.writeBytes(wav) }
                            val w = com.guixing.jixunying.engine.SpeechText.parseWav(wav)!!
                            println("声音：整段 ${"%.1f".format(secs)} 秒，取了 ${"%.1f".format(w.seconds)} 秒，静音=${com.guixing.jixunying.engine.SpeechText.isSilent(w)}，" +
                                "用了 ${System.currentTimeMillis() - t1} ms，存在 $f")
                            // JXY_LIVE_ASR=数据文件夹：复制它的 state.json 用真 Key 听写前 30 秒（自动挑的听写服务）
                            System.getenv("JXY_LIVE_ASR")?.let { src ->
                                val tmp = kotlin.io.path.createTempDirectory("jxy-live-asr").toFile()
                                try {
                                    File(src, "state.json").copyTo(File(tmp, "state.json"))
                                    val e = com.guixing.jixunying.engine.Engine(com.guixing.jixunying.engine.Storage(tmp))
                                    val head = com.guixing.jixunying.engine.SpeechText.wavOf(16000, 1, 16, pcm.copyOfRange(0, minOf(pcm.size, 16000 * 2 * 30)))
                                    val t2 = System.currentTimeMillis()
                                    val r = e.call(com.guixing.jixunying.model.Command.Transcribe(java.util.Base64.getEncoder().encodeToString(head)))
                                    println("听写（${System.currentTimeMillis() - t2} ms）：" + if (r.ok) "「${r.data}」" else "失败 ${r.message}")
                                } finally { tmp.deleteRecursively() }
                            }
                        }
                    }
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
