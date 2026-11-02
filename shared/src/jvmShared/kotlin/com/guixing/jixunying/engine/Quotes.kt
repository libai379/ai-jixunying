package com.guixing.jixunying.engine

import java.security.MessageDigest

/**
 * 引用别的消息（2026-11-01）：聊天记录里写成 Markdown 的「> 」行，和界面上点「引用」放进输入框的一样，
 * 模型读得懂，界面也会画成引用块。这里放共用的几样小事。
 */
object Quotes {
    /** 引用的正文最长放多少字（微信一条最长 1800 字，够了；再长的是用户自己发的长文）。 */
    const val MAX_CHARS = 2000

    /** 去掉「> 」引用行，只留这个人自己说的话。判断叫了谁时用：引用里出现的名字不算叫人。 */
    fun spoken(text: String): String =
        text.lineSequence().filterNot { it.trimStart().startsWith(">") }.joinToString("\n").trim()

    /** 把一段话写成引用，后面跟着这个人自己说的。 */
    fun compose(quote: String, said: String): String {
        val q = quote.trim().lineSequence().joinToString("\n") { "> $it".trimEnd() }
        return if (said.isBlank()) q else q + "\n\n" + said.trim()
    }

    fun clip(text: String, max: Int = MAX_CHARS): String = if (text.length <= max) text else text.take(max) + "…"

    /** 微信的「部分引用」：用户在一条消息里只选了一段（ref_msg.partial_text）。 */
    class Partial(val start: String, val end: String, val startIndex: Int, val endIndex: Int, val md5: String)

    /**
     * 照官方插件 @tencent-weixin/openclaw-weixin src/messaging/partial-quote.ts：start、end 是选中那段开头和结尾的字，
     * 两个 index 是第几次出现。endindex 有两种算法（从全文头数、从选中开头往后数），有 quotemd5 就用它认是哪一种；
     * 校验对不上返回 null，不编引用内容。
     */
    fun resolvePartial(full: String, p: Partial): String? {
        if (full.isEmpty() || p.start.isEmpty() || p.end.isEmpty()) return null
        fun nth(value: String, occurrence: Int, from: Int = 0): Int {
            if (occurrence < 0) return -1
            var pos = from
            for (i in 0..occurrence) {
                pos = full.indexOf(value, pos)
                if (pos < 0) return -1
                if (i < occurrence) pos += value.length
            }
            return pos
        }
        fun candidate(global: Boolean): String? {
            val start = nth(p.start, p.startIndex)
            if (start < 0) return null
            val end = if (global) nth(p.end, p.endIndex) else nth(p.end, p.endIndex, start + p.start.length)
            if (end < start) return null
            return full.substring(start, end + p.end.length)
        }
        val candidates = listOfNotNull(candidate(global = true), candidate(global = false)).distinct()
        if (p.md5.isEmpty()) return candidates.firstOrNull()
        val want = p.md5.lowercase()
        return candidates.firstOrNull { md5Hex(it) == want }
    }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
