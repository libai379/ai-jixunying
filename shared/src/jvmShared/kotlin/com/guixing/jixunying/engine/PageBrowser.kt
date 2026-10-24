package com.guixing.jixunying.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * 读平台链接用的本机浏览器（2026-10-23 用户：「各个平台爬虫公开内容」「不能靠云端，只能把源码扒下来才行」）。
 * 抖音、小红书、快手不登录、或者用无界面浏览器，拿到的是登录页 / 验证码页（10-23 实测，docs/平台链接设计说明.md），
 * 所以用一个真的浏览器：电脑上是 Edge，单独一个资料夹（<数据>/browser），平时窗口放在屏幕外面；
 * 用户在里面扫码登录一次，以后程序在这个浏览器里打开链接、读页面。手机上没有（返回 null）。
 */
interface PageBrowser {
    /** 打开网址，等页面加载完再等 [settleMs]，在页面里执行 [script]（返回 JSON 字符串），关掉这个标签页。 */
    suspend fun snapshot(url: String, script: String, settleMs: Long = 15_000): JsonObject

    /** 这个浏览器里某个域名下有哪些 cookie 名字（只看名字，判断登没登录）。 */
    suspend fun cookieNames(domain: String): Set<String>

    /** 把浏览器窗口挪到屏幕中间并打开 [url]，让用户登录。 */
    suspend fun showForLogin(url: String)

    /** 登好了：窗口挪回屏幕外面。 */
    suspend fun hide()

    /**
     * 把视频 / 音频地址里的声音解码成 16k 单声道 16 位 PCM（最多 [maxSeconds] 秒），返回 (PCM, 整段多少秒)。
     * 在浏览器里解码（它自带 AAC / MP4 解码），所以程序不用带 ffmpeg。
     */
    suspend fun audioPcm(mediaUrl: String, maxSeconds: Int): Pair<ByteArray, Double>

    /** 浏览器已经开着（查登录状态时没开就不去开，免得一打开设置页就冒出一个 Edge）。 */
    fun running(): Boolean

    fun close()
}

/** 电脑上返回 Edge 实现，找不到 Edge 或者在手机上返回 null；自动测试里（-Djxy.browser=off）也是 null。 */
expect fun createPageBrowser(profileDir: File): PageBrowser?

/** 认得的平台：域名、登录 cookie、登录页。 */
object SocialLinks {
    data class Platform(val id: String, val name: String, val hosts: List<String>, val cookieDomain: String, val loginCookies: List<String>, val loginUrl: String)

    // 登录 cookie 名字：抖音 sessionid、小红书 web_session、快手 userId、微博 SUB、B站 SESSDATA（各家网页版登录后才有；未确认会不会改名，改了就显示「没登录」但照样能读）
    val all = listOf(
        Platform("douyin", "抖音", listOf("douyin.com", "iesdouyin.com"), "douyin.com", listOf("sessionid", "sessionid_ss"), "https://www.douyin.com/"),
        // 小红书没登录也发 web_session（游客的，10-23 实测），看 cookie 判断不了登没登录：空着 = 「看不出来」
        Platform("xhs", "小红书", listOf("xiaohongshu.com", "xhslink.com"), "xiaohongshu.com", emptyList(), "https://www.xiaohongshu.com/explore"),
        Platform("kuaishou", "快手", listOf("kuaishou.com", "chenzhongtech.com", "kwai.com"), "kuaishou.com", listOf("userId", "kuaishou.server.web_st"), "https://www.kuaishou.com/"),
        Platform("weibo", "微博", listOf("weibo.com", "weibo.cn"), "weibo.com", listOf("SUB"), "https://weibo.com/"),
        Platform("bilibili", "B站", listOf("bilibili.com", "b23.tv"), "bilibili.com", listOf("SESSDATA"), "https://www.bilibili.com/"),
    )

    fun platformOf(url: String): Platform? {
        val host = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull() ?: return null
        return all.firstOrNull { p -> p.hosts.any { host == it || host.endsWith(".$it") } }
    }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /**
     * 在页面里执行：通用的（标题、简介、正文文字、大图、视频地址）+ 小红书页面自带的数据（window.__INITIAL_STATE__ 里的笔记）。
     * 只读页面上已经显示给用户的东西，不调平台的接口。
     */
    val EXTRACT_JS = """
        (() => {
          const q = s => document.querySelector(s);
          const meta = n => (q('meta[property="' + n + '"]') || q('meta[name="' + n + '"]') || {}).content || '';
          const out = {
            url: location.href, title: document.title,
            desc: meta('og:description') || meta('description'),
            ogImage: meta('og:image'),
            text: ((document.body && document.body.innerText) || '').slice(0, 30000),
          };
          const vids = [...document.querySelectorAll('video')].map(v => v.currentSrc || v.src || ((v.querySelector('source') || {}).src)).filter(s => s && !s.startsWith('blob:'));
          out.videos = [...new Set(vids)].slice(0, 5);
          out.images = [...new Set([...document.images].filter(i => i.naturalWidth >= 300 && i.naturalHeight >= 300).map(i => i.currentSrc || i.src))].slice(0, 20);
          try {
            const st = window.__INITIAL_STATE__;
            const m = st && st.note && (st.note.noteDetailMap || (st.note._value && st.note._value.noteDetailMap));
            if (m) {
              const k = Object.keys(m)[0];
              const n = m[k] && (m[k].note || (m[k]._value && m[k]._value.note));
              if (n) {
                const s = JSON.stringify((n.video && n.video.media) || {});
                const mu = s.match(/"masterUrl":"([^"]+)"/);
                out.note = {
                  title: n.title || '', desc: n.desc || '', type: n.type || '',
                  user: (n.user && n.user.nickname) || '',
                  images: (n.imageList || []).map(i => i.urlDefault || i.url || '').filter(Boolean),
                  likes: n.interactInfo ? String(n.interactInfo.likedCount || '') : '',
                  tags: (n.tagList || []).map(t => t.name),
                  video: mu ? mu[1] : '',
                };
              }
            }
          } catch (e) {}
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /** 这个页面的视频地址（小红书笔记里的优先；去掉抖音的占位视频）。没有返回 null。 */
    fun videoOf(page: JsonObject): String? =
        (page["note"] as? JsonObject)?.str("video")?.takeIf { it.isNotBlank() }
            ?: (page["videos"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.firstOrNull { "douyinstatic.com" !in it }

    /** 页面是登录页、验证码页（没拿到内容）。 */
    fun blocked(page: JsonObject): Boolean {
        // 小红书的笔记数据读到了就算读到（页面上同时有个登录框，文字又短）
        if (page["note"] is JsonObject) return false
        val title = page.str("title").orEmpty()
        val url = page.str("url").orEmpty()
        val text = page.str("text").orEmpty()
        return "验证码" in title || "出错啦" in title || "/login" in url || "passport" in url || "captcha" in url.lowercase() ||
            "安全风控" in text || "访问请求被拒绝" in text ||
            (text.length < 400 && ("登录" in text || "扫码" in text))
    }

    /** 整理成给 AI 读的文字。 */
    fun format(p: Platform, page: JsonObject): String = buildString {
        appendLine("【${p.name}】${page.str("url")}")
        val note = page["note"] as? JsonObject
        if (note != null) {
            note.str("title")?.takeIf { it.isNotBlank() }?.let { appendLine("标题：$it") }
            note.str("user")?.takeIf { it.isNotBlank() }?.let { appendLine("作者：$it") }
            note.str("likes")?.takeIf { it.isNotBlank() }?.let { appendLine("点赞：$it") }
            (note["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotEmpty() }?.let { appendLine("话题：" + it.joinToString("、")) }
            note.str("desc")?.takeIf { it.isNotBlank() }?.let { appendLine("正文：\n$it") }
            (note["images"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.takeIf { it.isNotEmpty() }?.let { appendLine("图片 ${it.size} 张：" + it.take(9).joinToString(" ")) }
            note.str("video")?.takeIf { it.isNotBlank() }?.let { appendLine("视频地址：$it") }
            appendLine()
        } else {
            page.str("title")?.takeIf { it.isNotBlank() }?.let { appendLine("标题：$it") }
            page.str("desc")?.takeIf { it.isNotBlank() }?.let { appendLine("简介：$it") }
        }
        // 抖音页面上先放一个占位视频（douyinstatic.com/…/uuu_265.mp4），不是这个作品的
        (page["videos"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.filterNot { "douyinstatic.com" in it }
            ?.takeIf { it.isNotEmpty() && note == null }?.let { appendLine("视频地址：" + it.first()) }
        // 小红书笔记数据读到了就够了（页面文字里多是登录框和推荐流）
        if (note == null) {
            appendLine("页面上的文字（含评论区，可能有导航栏杂字）：")
            append(page.str("text").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(12_000))
        }
    }
}
