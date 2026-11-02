package com.guixing.jixunying.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 一张图的像素：ARGB，一行接一行。 */
class Pixels(val w: Int, val h: Int, val argb: IntArray) {
    fun copy() = Pixels(w, h, argb.copyOf())
}

/**
 * P 图（2026-11-02 用户：「现在的接口都是画图，我需要就是 P 图的……它不可能画准的」）。
 * 起因：微信里让 AI 去掉截图上的一行时间，AI 调画图工具（只收文字，原图根本没传过去）连画四张假截图。
 * 就算把原图传给改图模型也不行：千问官方文档写的是重新出一张总像素约 1024×1024 的图，没有圈范围的参数（docs/参考资料.md「P 图」）。
 *
 * 所以这里不走任何模型：程序直接改原图的像素，没动到的地方一个像素都不变，不花钱，手机电脑一样跑。
 * AI 只负责看图、说改哪里；改完程序自己比对改了多少、在哪，结果图再给 AI 看一眼，对了才算做好。
 * 坐标用「AI 看到的那张图」的像素（发给模型的图长边不超过 VIEW_SIDE，见 Engine.imageDataUrl），这里换算回原图。
 */
object PhotoEdit {
    const val VIEW_SIDE = 1600
    const val MAX_STEPS = 20

    /** 回给模型的话（参数不对、位置不对之类），不是程序出错。 */
    class Problem(message: String) : Exception(message)

    val OPS = listOf("erase", "mosaic", "blur", "fill", "crop", "rotate", "flip", "resize", "text", "mark", "adjust", "paste")

    // ———————————————— 读图 ————————————————

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 255
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun be32(b: ByteArray, i: Int) = (be16(b, i) shl 16) or be16(b, i + 2)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

    /** 文件头里的宽高（按 EXIF 方向转正以后的）；认不出返回 null。不解码，便宜。 */
    fun sizeOf(b: ByteArray): Pair<Int, Int>? = runCatching {
        val raw = rawSize(b) ?: return null
        if (orientationOf(b) in 5..8) raw.second to raw.first else raw
    }.getOrNull()

    private fun rawSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 26) return null
        if (u8(b, 0) == 0x89 && b[1] == 'P'.code.toByte()) return be32(b, 16) to be32(b, 20)
        if (b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) return le16(b, 6) to le16(b, 8)
        if (b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()) return le32(b, 18) to abs(le32(b, 22))
        if (b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() && b.size >= 30) {
            return when (String(b, 12, 4, Charsets.ISO_8859_1)) {
                "VP8 " -> (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
                "VP8L" -> { val v = le32(b, 21); ((v and 0x3FFF) + 1) to (((v shr 14) and 0x3FFF) + 1) }
                "VP8X" -> ((u8(b, 24) or (u8(b, 25) shl 8) or (u8(b, 26) shl 16)) + 1) to ((u8(b, 27) or (u8(b, 28) shl 8) or (u8(b, 29) shl 16)) + 1)
                else -> null
            }
        }
        if (u8(b, 0) == 0xFF && u8(b, 1) == 0xD8) {
            var i = 2
            while (i + 9 < b.size) {
                if (u8(b, i) != 0xFF) { i++; continue }
                val m = u8(b, i + 1)
                if (m == 0xFF) { i++; continue }
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return be16(b, i + 7) to be16(b, i + 5)
                if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) { i += 2; continue }
                i += 2 + be16(b, i + 2)
            }
        }
        return null
    }

    /** JPEG 里 EXIF 记的方向（1 = 正的）；手机拍的照片常带，解码库不会替你转。 */
    fun orientationOf(b: ByteArray): Int = runCatching {
        if (b.size < 12 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return 1
        var i = 2
        while (i + 4 < b.size && u8(b, i) == 0xFF) {
            val m = u8(b, i + 1)
            val len = be16(b, i + 2)
            if (m == 0xE1 && String(b, i + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                val t = i + 10
                val big = b[t] == 'M'.code.toByte()
                fun s(p: Int) = if (big) be16(b, p) else le16(b, p)
                fun l(p: Int) = if (big) be32(b, p) else le32(b, p)
                val ifd = t + l(t + 4)
                for (n in 0 until s(ifd)) {
                    val e = ifd + 2 + n * 12
                    if (s(e) == 0x0112) return s(e + 8).takeIf { it in 1..8 } ?: 1
                }
                return 1
            }
            if (m == 0xDA) return 1
            i += 2 + len
        }
        1
    }.getOrDefault(1)

    /** 解码，并按 EXIF 转正（AI 和用户看到的都是转正的）。 */
    fun load(bytes: ByteArray): Pixels? {
        val p = decodePixels(bytes) ?: return null
        return when (orientationOf(bytes)) {
            2 -> flip(p, horizontal = true)
            3 -> rotate(p, 180)
            4 -> flip(p, horizontal = false)
            5 -> flip(rotate(p, 90), horizontal = true)
            6 -> rotate(p, 90)
            7 -> flip(rotate(p, 270), horizontal = true)
            8 -> rotate(p, 270)
            else -> p
        }
    }

    /** AI 看到的尺寸（长边不超过 VIEW_SIDE）。 */
    fun viewSize(w: Int, h: Int): Pair<Int, Int> {
        val side = max(w, h)
        if (side <= VIEW_SIDE) return w to h
        val s = VIEW_SIDE.toDouble() / side
        return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
    }

    /** 带 EXIF 方向的照片转正再发给模型：不转的话模型看到的是躺着的，写的坐标也对不上。没有方向要转的返回 null。 */
    fun uprightJpeg(bytes: ByteArray): ByteArray? {
        if (orientationOf(bytes) == 1) return null
        val p = runCatching { load(bytes) }.getOrNull() ?: return null
        return runCatching { encodePixels(p, 90) }.getOrNull()
    }

    // ———————————————— 参数 ————————————————

    private fun JsonObject.txt(vararg keys: String): String? = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
    private fun JsonObject.num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.contentOrNull?.trim()?.removeSuffix("%")?.toDoubleOrNull() } }
    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "true") } == true

    /** steps 可能是数组，也可能被模型写成字符串（一段 JSON）。 */
    fun steps(el: JsonElement?): List<JsonObject> {
        val arr = when (el) {
            null -> return emptyList()
            is JsonArray -> el
            is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
                runCatching { Json.parse(s) }.getOrNull() ?: throw Problem("steps 要写成数组：[{\"op\":\"erase\",\"box\":[左,上,右,下]}]")
            }?.let { it as? JsonArray ?: JsonArray(listOf(it)) } ?: return emptyList()
            is JsonObject -> JsonArray(listOf(el))
        }
        return arr.map { it as? JsonObject ?: throw Problem("steps 里每一步要写成 {\"op\": …}") }
    }

    /** [左, 上, 右, 下] → 原图坐标；也认字符串「x1,y1,x2,y2」。 */
    private fun boxOf(s: JsonObject, key: String, p: Pixels, scale: Double, op: String, required: Boolean = true): IntArray? {
        val v = s[key]
        val nums: List<Double>? = when (v) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.let { n -> n.doubleOrNull ?: n.contentOrNull?.trim()?.toDoubleOrNull() } }
            is JsonPrimitive -> v.contentOrNull?.split(Regex("[^0-9.]+"))?.filter { it.isNotEmpty() }?.mapNotNull { it.toDoubleOrNull() }
            is JsonObject -> listOf(v.jsonObject.num("x1", "left", "x"), v.jsonObject.num("y1", "top", "y"), v.jsonObject.num("x2", "right"), v.jsonObject.num("y2", "bottom")).takeIf { it.all { n -> n != null } }?.map { it!! }
            else -> null
        }
        if (nums == null || nums.size != 4) {
            if (!required && v == null) return null
            throw Problem("$op 要给 $key：[左, 上, 右, 下]，四个数，单位是像素。")
        }
        // 只有全是 0～1 的小数才当比例（有的模型习惯这么写）
        val frac = nums.all { it in 0.0..1.0 } && nums.any { it != 0.0 && it != 1.0 }
        val xs = if (frac) p.w.toDouble() else scale
        val ys = if (frac) p.h.toDouble() else scale
        val x1 = (min(nums[0], nums[2]) * xs).toInt().coerceIn(0, p.w)
        val x2 = (max(nums[0], nums[2]) * xs).roundToInt().coerceIn(0, p.w)
        val y1 = (min(nums[1], nums[3]) * ys).toInt().coerceIn(0, p.h)
        val y2 = (max(nums[1], nums[3]) * ys).roundToInt().coerceIn(0, p.h)
        if (x2 - x1 < 1 || y2 - y1 < 1) {
            val (vw, vh) = viewSize(p.w, p.h)
            throw Problem("$op 的 $key 在图外面或者是空的：这张图是 $vw×$vh，给的是 ${nums.joinToString(",") { it.toInt().toString() }}。")
        }
        return intArrayOf(x1, y1, x2, y2)
    }

    private val NAMED = mapOf(
        "红" to 0xE53935, "red" to 0xE53935, "橙" to 0xFB8C00, "orange" to 0xFB8C00, "黄" to 0xFDD835, "yellow" to 0xFDD835,
        "绿" to 0x43A047, "green" to 0x43A047, "青" to 0x00ACC1, "cyan" to 0x00ACC1, "蓝" to 0x1E88E5, "blue" to 0x1E88E5,
        "紫" to 0x8E24AA, "purple" to 0x8E24AA, "粉" to 0xEC407A, "pink" to 0xEC407A,
        "黑" to 0x000000, "black" to 0x000000, "白" to 0xFFFFFF, "white" to 0xFFFFFF, "灰" to 0x9E9E9E, "gray" to 0x9E9E9E, "grey" to 0x9E9E9E,
    )

    /** 「红色」「red」「#FF3B30」→ 0xRRGGBB。 */
    private fun colorOf(s: String?): Int? {
        val t = s?.trim()?.lowercase()?.removeSuffix("色") ?: return null
        if (t.isEmpty()) return null
        NAMED[t]?.let { return it }
        val hex = t.removePrefix("#").removePrefix("0x")
        if (hex.length == 6) hex.toIntOrNull(16)?.let { return it }
        if (hex.length == 3) hex.toIntOrNull(16)?.let { v -> return ((v shr 8 and 15) * 17 shl 16) or ((v shr 4 and 15) * 17 shl 8) or ((v and 15) * 17) }
        throw Problem("不认识的颜色「$s」：写 #RRGGBB，或者 红 橙 黄 绿 青 蓝 紫 粉 黑 白 灰。")
    }

    private fun dist(a: Int, b: Int) = max(max(abs((a shr 16 and 255) - (b shr 16 and 255)), abs((a shr 8 and 255) - (b shr 8 and 255))), abs((a and 255) - (b and 255)))

    // ———————————————— 擦除 ————————————————

    /** 框外面一圈的主色；flat = 这一圈基本就是这一个颜色（截图、纯色背景）。 */
    private class Ring(val color: Int, val flat: Boolean)

    private fun ringOf(p: Pixels, b: IntArray): Ring? {
        val counts = HashMap<Int, Int>()
        var total = 0
        val samples = ArrayList<Int>()
        fun add(x: Int, y: Int) {
            if (x !in 0 until p.w || y !in 0 until p.h) return
            val c = p.argb[y * p.w + x] or (0xFF shl 24)
            counts[c] = (counts[c] ?: 0) + 1
            samples += c
            total++
        }
        for (d in 3..6) {
            for (x in b[0] - d until b[2] + d) { add(x, b[1] - d); add(x, b[3] + d - 1) }
            for (y in b[1] - d + 1 until b[3] + d - 1) { add(b[0] - d, y); add(b[2] + d - 1, y) }
        }
        if (total == 0) return null
        val mode = counts.maxBy { it.value }.key
        val near = samples.count { dist(it, mode) <= 10 }
        // 六成就算：框碰到旁边的气泡时，外面一圈有一边是别的颜色，但框里还是在这个背景上
        return Ring(mode, near >= total * 0.6)
    }

    /** 擦掉一块的结果：真正动的范围、动了多少，以及被擦的东西的颜色和高度（紧接着写字时用）。 */
    private class Erased(val box: IntArray?, val count: Int, val fg: Int?, val textH: Int, val note: String)

    /**
     * 纯色背景：不是把框涂掉，是找出框里和背景不一样的一块块东西，整块擦掉。这样框画得不准也不要紧：
     * 字被框切了一半，整个字照样擦干净；旁边的气泡、头像只是被框碰到一点（大部分在框外），不动。
     */
    private fun eraseFlat(p: Pixels, b: IntArray, bg: Int): Erased {
        val margin = max(24, min(b[3] - b[1], 80))
        val wx1 = max(0, b[0] - margin); val wy1 = max(0, b[1] - margin)
        val wx2 = min(p.w, b[2] + margin); val wy2 = min(p.h, b[3] + margin)
        val ww = wx2 - wx1; val wh = wy2 - wy1
        val tol = 8
        val isFg = BooleanArray(ww * wh) { i -> dist(p.argb[(wy1 + i / ww) * p.w + wx1 + i % ww], bg) > tol }
        val seen = BooleanArray(ww * wh)
        val mask = BooleanArray(ww * wh)
        var kept = 0
        var skippedBig = 0
        val stack = IntArray(ww * wh)
        for (start in 0 until ww * wh) {
            if (!isFg[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            var size = 0; var inside = 0; var touches = false
            val members = ArrayList<Int>()
            while (sp > 0) {
                val i = stack[--sp]
                members += i; size++
                val x = i % ww; val y = i / ww
                val ax = wx1 + x; val ay = wy1 + y
                if (ax in b[0] until b[2] && ay in b[1] until b[3]) inside++
                // 碰到窗口边、而那边还有图：这块伸到框外很远，不是框里的东西
                if ((x == 0 && wx1 > 0) || (y == 0 && wy1 > 0) || (x == ww - 1 && wx2 < p.w) || (y == wh - 1 && wy2 < p.h)) touches = true
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until ww || ny !in 0 until wh) continue
                    val n = ny * ww + nx
                    if (isFg[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
            }
            if (inside == 0) continue
            if (touches) { if (inside * 4 >= size || inside > 200) skippedBig++; continue }
            if (inside * 10 < size * 3) continue
            for (i in members) mask[i] = true
            kept += size
        }
        var note = ""
        if (skippedBig > 0) note = "框碰到了伸到框外很远的东西（比如气泡、头像）$skippedBig 处，那些没动：要整块去掉就把框画大，整个圈进去；要擦气泡里的字，把框画在气泡里面。"
        if (kept == 0) return Erased(null, 0, null, 0, if (note.isNotEmpty()) note else "这个框里没有和背景不一样的东西，什么也没动：位置可能不对，对着图再看看。")
        // 被擦的东西的颜色（离背景最远的那些像素）和范围
        var bx1 = Int.MAX_VALUE; var by1 = Int.MAX_VALUE; var bx2 = -1; var by2 = -1
        val far = ArrayList<Int>()
        for (i in mask.indices) if (mask[i]) {
            val x = wx1 + i % ww; val y = wy1 + i / ww
            bx1 = min(bx1, x); by1 = min(by1, y); bx2 = max(bx2, x + 1); by2 = max(by2, y + 1)
            far += p.argb[y * p.w + x]
        }
        val fg = far.sortedByDescending { dist(it, bg) }.take(max(1, far.size / 5)).let { top ->
            ((top.map { it shr 16 and 255 }.average().toInt()) shl 16) or ((top.map { it shr 8 and 255 }.average().toInt()) shl 8) or top.map { it and 255 }.average().toInt()
        }
        // 字边上压缩留下的淡影也一起擦（往外 3 像素，只擦接近背景的，碰到别的东西不擦）
        val grown = mask.copyOf()
        for (i in mask.indices) if (mask[i]) {
            val x = i % ww; val y = i / ww
            for (dy in -3..3) for (dx in -3..3) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until ww || ny !in 0 until wh) continue
                val n = ny * ww + nx
                if (!mask[n] && !isFg[n]) grown[n] = true
                else if (!mask[n] && dist(p.argb[(wy1 + ny) * p.w + wx1 + nx], bg) <= 30) grown[n] = true
            }
        }
        // JPEG 一个块 8 像素，字周围的块里还有很淡的振铃，留着看得出字的影子：再往外一点，只贴几乎就是背景色的
        val ringing = dilate(mask, ww, wh, 8)
        for (i in ringing.indices) if (ringing[i] && !grown[i] && dist(p.argb[(wy1 + i / ww) * p.w + wx1 + i % ww], bg) <= 14) grown[i] = true
        var count = 0
        for (i in grown.indices) if (grown[i]) {
            val k = (wy1 + i / ww) * p.w + wx1 + i % ww
            val alpha = p.argb[k] and (0xFF shl 24)
            val nv = alpha or (bg and 0xFFFFFF)
            if (p.argb[k] != nv) { p.argb[k] = nv; count++ }
        }
        return Erased(intArrayOf(bx1, by1, bx2, by2), count, fg, by2 - by1, note)
    }

    /** 颜色对不对得上（有彩色按色相比，黑白灰按距离比）。 */
    private fun matcher(color: Int): (Int) -> Boolean {
        val hsv = hsv(color)
        return if (hsv[1] >= 0.25f) { c ->
            val h = hsv(c)
            h[1] >= 0.35f && h[2] >= 0.3f && min(abs(h[0] - hsv[0]), 360f - abs(h[0] - hsv[0])) <= 16f
        } else { c -> dist(c, color) <= 40 }
    }

    private fun hsv(c: Int): FloatArray {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val mx = max(max(r, g), b); val mn = min(min(r, g), b); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d + 6f) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    /** 只擦框里某个颜色的笔迹（用户拿红笔画的圈、荧光笔）：别的颜色一点不动。只按颜色找，旁边橙黄色的表情也会被当成红的（11-02 试过），所以按色相比、还要成块。 */
    private fun eraseColor(p: Pixels, b: IntArray, color: Int): Erased {
        val pad = 20
        val wx1 = max(0, b[0] - pad); val wy1 = max(0, b[1] - pad)
        val wx2 = min(p.w, b[2] + pad); val wy2 = min(p.h, b[3] + pad)
        val ww = wx2 - wx1; val wh = wy2 - wy1
        val same = matcher(color)
        val hit = BooleanArray(ww * wh) { i ->
            val x = wx1 + i % ww; val y = wy1 + i / ww
            x in b[0] until b[2] && y in b[1] until b[3] && same(p.argb[y * p.w + x])
        }
        // 零星几个点不算笔迹（压缩噪点）
        val seen = BooleanArray(ww * wh)
        val blobs = ArrayList<IntArray>()
        val stack = IntArray(ww * wh)
        for (start in hit.indices) {
            if (!hit[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start; seen[start] = true
            val members = ArrayList<Int>()
            while (sp > 0) {
                val i = stack[--sp]
                members += i
                val x = i % ww; val y = i / ww
                for (dy in -2..2) for (dx in -2..2) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until ww || ny !in 0 until wh) continue
                    val n = ny * ww + nx
                    if (hit[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
            }
            blobs += members.toIntArray()
        }
        val biggest = blobs.maxOfOrNull { it.size } ?: 0
        if (biggest < 12) return Erased(null, 0, null, 0, "这个框里没找到这个颜色的笔迹，什么也没动。")
        val core = BooleanArray(ww * wh)
        for (m in blobs) if (m.size >= max(12, biggest / 50)) for (i in m) core[i] = true
        // 笔迹周围压缩留下的色晕：往外 6 像素算一圈，比周围干净的颜色差得不多的一起擦；差得多的是别的东西（字、气泡），不动
        val zone = dilate(core, ww, wh, 6)
        // 每个要擦的点从最近的干净点取色（一圈一圈往里推）
        val from = IntArray(ww * wh) { -1 }
        var frontier = ArrayList<Int>()
        for (i in zone.indices) if (!zone[i]) from[i] = i
        for (i in zone.indices) if (zone[i]) {
            val x = i % ww; val y = i / ww
            var near = -1
            for ((dx, dy) in NEIGHBORS) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until ww && ny in 0 until wh && !zone[ny * ww + nx]) { near = ny * ww + nx; break }
            }
            if (near >= 0) { from[i] = near; frontier.add(i) }
        }
        while (frontier.isNotEmpty()) {
            val next = ArrayList<Int>()
            for (i in frontier) {
                val x = i % ww; val y = i / ww
                for ((dx, dy) in NEIGHBORS) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until ww || ny !in 0 until wh) continue
                    val n = ny * ww + nx
                    if (from[n] < 0) { from[n] = from[i]; next.add(n) }
                }
            }
            frontier = next
        }
        fun at(i: Int) = p.argb[(wy1 + i / ww) * p.w + wx1 + i % ww]
        // 笔迹边上半透不透的那些点：颜色还偏笔迹的色相，只是淡（压缩把红色晕开了）
        val tint = hsv(color)
        fun tinted(c: Int): Boolean {
            if (tint[1] < 0.25f) return false
            val h = hsv(c)
            return h[1] >= 0.08f && min(abs(h[0] - tint[0]), 360f - abs(h[0] - tint[0])) <= 30f
        }
        val mask = BooleanArray(ww * wh) { i -> core[i] || (zone[i] && from[i] >= 0 && (dist(at(i), at(from[i])) <= 70 || tinted(at(i)))) }
        // 背景是不是平的：平的（一块块纯色）就近取色；有纹理的（照片）从周围找相似的一小块补
        var rough = 0L; var n = 0
        for (i in zone.indices) if (zone[i] && from[i] >= 0 && from[i] != i) {
            val s = from[i]; val sx = s % ww; val sy = s / ww
            if (sx + 1 < ww && !zone[sy * ww + sx + 1]) { rough += dist(at(s), at(sy * ww + sx + 1)); n++ }
            if (sy + 1 < wh && !zone[(sy + 1) * ww + sx]) { rough += dist(at(s), at((sy + 1) * ww + sx)); n++ }
        }
        var count = 0
        var bx1 = Int.MAX_VALUE; var by1 = Int.MAX_VALUE; var bx2 = -1; var by2 = -1
        for (i in mask.indices) if (mask[i]) { val x = wx1 + i % ww; val y = wy1 + i / ww; bx1 = min(bx1, x); by1 = min(by1, y); bx2 = max(bx2, x + 1); by2 = max(by2, y + 1); count++ }
        if (n > 0 && rough.toDouble() / n > 6.0) {
            val full = BooleanArray(p.w * p.h)
            for (i in mask.indices) if (mask[i]) full[(wy1 + i / ww) * p.w + wx1 + i % ww] = true
            synth(p, full, intArrayOf(bx1, by1, bx2, by2))
        } else {
            val snapshot = IntArray(mask.size) { at(it) }
            // 这一带的几个主色（纯色背景、气泡）：11-02 真图上试过，直接抄最近的点，会把压缩留下的杂色一路抄进去，原来的圈的位置留一圈淡影
            val counts = HashMap<Int, Int>()
            for (i in zone.indices) if (!zone[i]) { val c = snapshot[i] and 0xFFFFFF; counts[c] = (counts[c] ?: 0) + 1 }
            val palette = ArrayList<Int>()
            for (e in counts.entries.sortedByDescending { it.value }) {
                if (palette.size >= 8 || e.value * 500 < zone.size) break
                if (palette.none { dist(it, e.key) <= 8 }) palette += e.key
            }
            fun snap(c: Int, tol: Int): Int = palette.minByOrNull { dist(it, c) }?.takeIf { dist(it, c) <= tol } ?: (c and 0xFFFFFF)
            for (i in mask.indices) if (mask[i] && from[i] >= 0) {
                val k = (wy1 + i / ww) * p.w + wx1 + i % ww
                p.argb[k] = (p.argb[k] and (0xFF shl 24)) or snap(snapshot[from[i]], 24)
            }
            // 压缩把颜色晕开得比笔迹宽（JPEG 的色度块 16 像素），周围一圈接近主色的也贴回去；差得多的是别的东西，不动
            val halo = dilate(core, ww, wh, 16)
            for (i in halo.indices) if (halo[i] && !mask[i]) {
                val k = (wy1 + i / ww) * p.w + wx1 + i % ww
                val c = snap(snapshot[i], 24)
                if (c != snapshot[i] and 0xFFFFFF) { p.argb[k] = (p.argb[k] and (0xFF shl 24)) or c; count++ }
            }
        }
        var cy1 = Int.MAX_VALUE; var cy2 = -1
        for (i in core.indices) if (core[i]) { val y = wy1 + i / ww; cy1 = min(cy1, y); cy2 = max(cy2, y + 1) }
        return Erased(intArrayOf(bx1, by1, bx2, by2), count, color, if (cy2 > cy1) cy2 - cy1 else 0, "")
    }

    private val NEIGHBORS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** 往外扩 r 像素（方形，先横后竖）。 */
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val a = BooleanArray(src.size)
        for (y in 0 until h) {
            var d = r + 1
            for (x in 0 until w) { if (src[y * w + x]) d = 0 else d++; if (d <= r) a[y * w + x] = true }
            d = r + 1
            for (x in w - 1 downTo 0) { if (src[y * w + x]) d = 0 else d++; if (d <= r) a[y * w + x] = true }
        }
        val b = BooleanArray(src.size)
        for (x in 0 until w) {
            var d = r + 1
            for (y in 0 until h) { if (a[y * w + x]) d = 0 else d++; if (d <= r) b[y * w + x] = true }
            d = r + 1
            for (y in h - 1 downTo 0) { if (a[y * w + x]) d = 0 else d++; if (d <= r) b[y * w + x] = true }
        }
        return b
    }

    /**
     * 补纹理（框周围不是纯色，比如照片上的日期水印）：一圈一圈从外往里，每个点在周围找一小块最像的，把它对应的颜色抄过来。
     * 补小东西可以；太大、后面有结构的（抠掉一个人）补不像，结果图 AI 看得到，得照实说。
     */
    private fun synth(p: Pixels, mask: BooleanArray, bbox: IntArray) {
        val area = mask.count { it }
        // 太大的补不像，还会算很久（11-02 训练里有模型想把整个背景擦掉）
        if (area > 200_000) throw Problem("要补的这块太大（$area 个像素），从周围抄补不像，这个工具做不了。换背景、去掉大东西这种要凭空造内容的，照实告诉用户。")
        val r = 3
        val band = max(24, min(max(bbox[2] - bbox[0], bbox[3] - bbox[1]), 240))
        val x1 = max(0, bbox[0] - band); val y1 = max(0, bbox[1] - band)
        val x2 = min(p.w, bbox[2] + band); val y2 = min(p.h, bbox[3] + band)
        val rnd = Random(12345)
        fun clean(x: Int, y: Int): Boolean {
            if (x - r < 0 || y - r < 0 || x + r >= p.w || y + r >= p.h) return false
            for (dy in -r..r) for (dx in -r..r) if (mask[(y + dy) * p.w + x + dx]) return false
            return true
        }
        val pool = ArrayList<Int>()
        var tries = 0
        while (pool.size < 4000 && tries < 200_000) {
            tries++
            val x = x1 + rnd.nextInt(max(1, x2 - x1)); val y = y1 + rnd.nextInt(max(1, y2 - y1))
            if (clean(x, y)) pool += y * p.w + x
        }
        if (pool.isEmpty()) throw Problem("这块周围没有能抄的地方，补不了。")
        val known = BooleanArray(mask.size) { !mask[it] }
        val src = IntArray(mask.size) { -1 }
        val tryCount = if (area > 60_000) 30 else 70
        var frontier = ArrayList<Int>()
        val queued = BooleanArray(mask.size)
        for (i in mask.indices) if (mask[i]) {
            val x = i % p.w; val y = i / p.w
            if (NEIGHBORS.any { (dx, dy) -> val nx = x + dx; val ny = y + dy; nx in 0 until p.w && ny in 0 until p.h && known[ny * p.w + nx] }) { frontier.add(i); queued[i] = true }
        }
        while (frontier.isNotEmpty()) {
            val next = ArrayList<Int>()
            for (t in frontier) {
                val tx = t % p.w; val ty = t / p.w
                var best = -1
                var bestScore = Long.MAX_VALUE
                fun score(s: Int) {
                    val sx = s % p.w; val sy = s / p.w
                    var sum = 0L; var cnt = 0
                    for (dy in -r..r) for (dx in -r..r) {
                        val ax = tx + dx; val ay = ty + dy
                        val bx = sx + dx; val by = sy + dy
                        if (ax !in 0 until p.w || ay !in 0 until p.h || bx !in 0 until p.w || by !in 0 until p.h) continue
                        val ka = ay * p.w + ax
                        if (!known[ka]) continue
                        val kb = by * p.w + bx
                        if (!known[kb]) continue
                        val c1 = p.argb[ka]; val c2 = p.argb[kb]
                        val d1 = (c1 shr 16 and 255) - (c2 shr 16 and 255)
                        val d2 = (c1 shr 8 and 255) - (c2 shr 8 and 255)
                        val d3 = (c1 and 255) - (c2 and 255)
                        sum += (d1 * d1 + d2 * d2 + d3 * d3).toLong(); cnt++
                    }
                    if (cnt == 0) return
                    val v = sum * 64 / cnt
                    if (v < bestScore) { bestScore = v; best = s }
                }
                // 先试邻居抄的地方接着往下抄（这样抄出来是连贯的一片），再随机试一批
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = tx + dx; val ny = ty + dy
                    if (nx !in 0 until p.w || ny !in 0 until p.h) continue
                    val sn = src[ny * p.w + nx]
                    if (sn < 0) continue
                    val cx = sn % p.w - dx; val cy = sn / p.w - dy
                    if (cx in 0 until p.w && cy in 0 until p.h && !mask[cy * p.w + cx]) score(cy * p.w + cx)
                }
                repeat(tryCount) { score(pool[rnd.nextInt(pool.size)]) }
                if (best < 0) best = pool[rnd.nextInt(pool.size)]
                p.argb[t] = (p.argb[t] and (0xFF shl 24)) or (p.argb[best] and 0xFFFFFF)
                src[t] = best
                known[t] = true
            }
            for (t in frontier) {
                val x = t % p.w; val y = t / p.w
                for ((dx, dy) in NEIGHBORS) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until p.w || ny !in 0 until p.h) continue
                    val n = ny * p.w + nx
                    if (mask[n] && !known[n] && !queued[n]) { queued[n] = true; next.add(n) }
                }
            }
            frontier = next
        }
    }

    private fun erase(p: Pixels, b: IntArray, color: Int?): Erased {
        if (color != null) return eraseColor(p, b, color)
        val ring = ringOf(p, b) ?: throw Problem("这个框把整张图都圈进去了，擦不了；要整张重来就直接说做不到。")
        if (ring.flat) return eraseFlat(p, b, ring.color)
        val mask = BooleanArray(p.w * p.h)
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) mask[y * p.w + x] = true
        val count = (b[2] - b[0]) * (b[3] - b[1])
        synth(p, mask, b)
        return Erased(b, count, null, 0, "这块背景不是纯色，整个框是从周围抄来补的：看一眼补得自然不自然。")
    }

    // ———————————————— 别的操作 ————————————————

    private fun mosaic(p: Pixels, b: IntArray, size: Int) {
        var y = b[1]
        while (y < b[3]) {
            val ye = min(y + size, b[3])
            var x = b[0]
            while (x < b[2]) {
                val xe = min(x + size, b[2])
                var r = 0L; var g = 0L; var bl = 0L
                val n = (ye - y) * (xe - x)
                for (yy in y until ye) for (xx in x until xe) { val c = p.argb[yy * p.w + xx]; r += c shr 16 and 255; g += c shr 8 and 255; bl += c and 255 }
                val v = ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
                for (yy in y until ye) for (xx in x until xe) { val k = yy * p.w + xx; p.argb[k] = (p.argb[k] and (0xFF shl 24)) or v }
                x = xe
            }
            y = ye
        }
    }

    private fun blur(p: Pixels, b: IntArray, radius: Int) {
        val w = b[2] - b[0]; val h = b[3] - b[1]
        var cur = IntArray(w * h) { p.argb[(b[1] + it / w) * p.w + b[0] + it % w] }
        repeat(3) {
            val horiz = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0; var g = 0; var bl = 0; var n = 0
                for (d in -radius..radius) { val xx = x + d; if (xx !in 0 until w) continue; val c = cur[y * w + xx]; r += c shr 16 and 255; g += c shr 8 and 255; bl += c and 255; n++ }
                horiz[y * w + x] = ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
            }
            val vert = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0; var g = 0; var bl = 0; var n = 0
                for (d in -radius..radius) { val yy = y + d; if (yy !in 0 until h) continue; val c = horiz[yy * w + x]; r += c shr 16 and 255; g += c shr 8 and 255; bl += c and 255; n++ }
                vert[y * w + x] = ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
            }
            cur = vert
        }
        for (i in cur.indices) { val k = (b[1] + i / w) * p.w + b[0] + i % w; p.argb[k] = (p.argb[k] and (0xFF shl 24)) or (cur[i] and 0xFFFFFF) }
    }

    private fun fill(p: Pixels, b: IntArray, color: Int) {
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) p.argb[y * p.w + x] = (0xFF shl 24) or color
    }

    private fun crop(p: Pixels, b: IntArray): Pixels {
        val w = b[2] - b[0]; val h = b[3] - b[1]
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(p.argb, (b[1] + y) * p.w + b[0], out, y * w, w)
        return Pixels(w, h, out)
    }

    /** 顺时针转 90、180、270 度。 */
    fun rotate(p: Pixels, angle: Int): Pixels = when (angle) {
        90 -> Pixels(p.h, p.w, IntArray(p.w * p.h)).also { o -> for (y in 0 until p.h) for (x in 0 until p.w) o.argb[x * o.w + (p.h - 1 - y)] = p.argb[y * p.w + x] }
        180 -> Pixels(p.w, p.h, IntArray(p.w * p.h) { p.argb[p.argb.size - 1 - it] })
        270 -> Pixels(p.h, p.w, IntArray(p.w * p.h)).also { o -> for (y in 0 until p.h) for (x in 0 until p.w) o.argb[(p.w - 1 - x) * o.w + y] = p.argb[y * p.w + x] }
        else -> throw Problem("rotate 的 angle 只能是 90、180、270（顺时针）；别的角度做不了。")
    }

    fun flip(p: Pixels, horizontal: Boolean): Pixels {
        val o = IntArray(p.w * p.h)
        for (y in 0 until p.h) for (x in 0 until p.w) {
            o[y * p.w + x] = if (horizontal) p.argb[y * p.w + (p.w - 1 - x)] else p.argb[(p.h - 1 - y) * p.w + x]
        }
        return Pixels(p.w, p.h, o)
    }

    /** 缩放：缩小先对半平均（不糊出锯齿），最后一步双线性。 */
    fun resize(src: Pixels, w: Int, h: Int): Pixels {
        var p = src
        while (p.w >= w * 2 && p.h >= h * 2) {
            val nw = p.w / 2; val nh = p.h / 2
            val o = IntArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) { val c = p.argb[(y * 2 + dy) * p.w + x * 2 + dx]; a += c ushr 24; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
                o[y * nw + x] = ((a / 4) shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
            }
            p = Pixels(nw, nh, o)
        }
        if (p.w == w && p.h == h) return p
        val o = IntArray(w * h)
        for (y in 0 until h) {
            val fy = if (h > 1) y * (p.h - 1.0) / (h - 1) else 0.0
            val y0 = fy.toInt(); val y1 = min(y0 + 1, p.h - 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = if (w > 1) x * (p.w - 1.0) / (w - 1) else 0.0
                val x0 = fx.toInt(); val x1 = min(x0 + 1, p.w - 1); val tx = fx - x0
                var v = 0
                for (s in 0..24 step 8) {
                    val a = (p.argb[y0 * p.w + x0] ushr s and 255) * (1 - tx) + (p.argb[y0 * p.w + x1] ushr s and 255) * tx
                    val b = (p.argb[y1 * p.w + x0] ushr s and 255) * (1 - tx) + (p.argb[y1 * p.w + x1] ushr s and 255) * tx
                    v = v or ((a * (1 - ty) + b * ty).roundToInt().coerceIn(0, 255) shl s)
                }
                o[y * w + x] = v
            }
        }
        return Pixels(w, h, o)
    }

    private fun adjust(p: Pixels, b: IntArray, brightness: Double, contrast: Double, saturation: Double) {
        val bright = brightness * 2.55
        val c = (100.0 + contrast) / 100.0
        val s = (100.0 + saturation) / 100.0
        for (y in b[1] until b[3]) for (x in b[0] until b[2]) {
            val k = y * p.w + x
            val v = p.argb[k]
            var r = (v shr 16 and 255).toDouble(); var g = (v shr 8 and 255).toDouble(); var bl = (v and 255).toDouble()
            val gray = 0.299 * r + 0.587 * g + 0.114 * bl
            r = gray + (r - gray) * s; g = gray + (g - gray) * s; bl = gray + (bl - gray) * s
            r = (r - 128) * c + 128 + bright; g = (g - 128) * c + 128 + bright; bl = (bl - 128) * c + 128 + bright
            p.argb[k] = (v and (0xFF shl 24)) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or bl.roundToInt().coerceIn(0, 255)
        }
    }

    // ———————————————— 跑一串 ————————————————

    /** summary 带坐标，写进记录给 AI 看；label 是界面上那一步显示的人话（「擦除、写字」）。 */
    class Outcome(val out: Pixels, val notes: List<String>, val summary: String, val label: String)

    private val OP_NAMES = mapOf(
        "erase" to "擦除", "mosaic" to "打码", "blur" to "模糊", "fill" to "盖色块", "crop" to "裁剪", "rotate" to "转方向",
        "flip" to "翻转", "resize" to "缩放", "text" to "写字", "mark" to "标注", "adjust" to "调色", "paste" to "贴图",
    )

    /**
     * 按顺序做 steps。other：按文件名找对话里的另一张图（paste 用）。
     * 坐标按 AI 看到的尺寸写；裁剪以后从裁剪后的左上角算起；resize 以后按新尺寸算。
     */
    fun run(src: Pixels, steps: List<JsonObject>, other: (String) -> Pixels?): Outcome {
        if (steps.size > MAX_STEPS) throw Problem("一次最多 $MAX_STEPS 步，这次写了 ${steps.size} 步。这个工具不是画板，一块块拼画面拼不像；真做不了就照实告诉用户。")
        var p = src.copy()
        var scale = max(src.w, src.h).toDouble().let { if (it > VIEW_SIDE) it / VIEW_SIDE else 1.0 }
        val notes = mutableListOf<String>()
        val did = mutableListOf<String>()
        fun view(b: IntArray) = "[" + b.joinToString(",") { (it / scale).roundToInt().toString() } + "]"
        var lastErase: Erased? = null
        for (s in steps) {
            val op = s.txt("op", "do", "type", "action").orEmpty().lowercase()
            when (op) {
                "erase" -> {
                    val b = boxOf(s, "box", p, scale, op)!!
                    val e = erase(p, b, colorOf(s.txt("color", "only_color")))
                    lastErase = e
                    if (e.note.isNotEmpty()) notes += e.note
                    if (e.box != null) { notes += "擦掉的东西实际在 ${view(e.box)}，动了 ${e.count} 个像素。"; did += "擦掉 ${view(e.box)}" + (s.txt("color", "only_color")?.let { "（只擦$it）" } ?: "") }
                    else did += "擦除没动到东西"
                }
                "mosaic" -> {
                    val b = boxOf(s, "box", p, scale, op)!!
                    val size = s.num("size")?.let { (it * scale).toInt() } ?: max(10, (b[3] - b[1]) / 4)
                    mosaic(p, b, size.coerceIn(2, 200))
                    did += "打码 ${view(b)}"
                }
                "blur" -> {
                    val b = boxOf(s, "box", p, scale, op)!!
                    blur(p, b, (s.num("radius", "size")?.let { (it * scale).toInt() } ?: max(6, min(b[2] - b[0], b[3] - b[1]) / 5)).coerceIn(1, 80))
                    did += "模糊 ${view(b)}"
                }
                "fill" -> {
                    val b = boxOf(s, "box", p, scale, op)!!
                    fill(p, b, colorOf(s.txt("color")) ?: throw Problem("fill 要给 color。"))
                    did += "盖色块 ${view(b)}"
                }
                "crop" -> {
                    val b = boxOf(s, "box", p, scale, op)!!
                    p = crop(p, b)
                    did += "裁剪 ${view(b)}"
                }
                "rotate" -> {
                    val a = ((s.num("angle", "degrees") ?: throw Problem("rotate 要给 angle：90、180、270（顺时针）。")).toInt() % 360 + 360) % 360
                    if (a != 0) p = rotate(p, a)
                    did += "顺时针转 $a 度"
                }
                "flip" -> {
                    val d = s.txt("direction", "axis").orEmpty().lowercase()
                    val horizontal = d.isEmpty() || d.startsWith("h") || "左右" in d || "水平" in d
                    p = flip(p, horizontal)
                    did += if (horizontal) "左右翻转" else "上下翻转"
                }
                "resize" -> {
                    val w = s.num("width")?.toInt()
                    val h = s.num("height")?.toInt()
                    val pct = s.num("percent")
                    val (nw, nh) = when {
                        pct != null -> (p.w * pct / 100).toInt() to (p.h * pct / 100).toInt()
                        w != null && h != null -> w to h
                        w != null -> w to (p.h * w.toDouble() / p.w).toInt()
                        h != null -> (p.w * h.toDouble() / p.h).toInt() to h
                        else -> throw Problem("resize 要给 width、height 或 percent。")
                    }
                    if (nw !in 1..8000 || nh !in 1..8000) throw Problem("resize 的尺寸要在 1 到 8000 之间，给的是 $nw×$nh。")
                    p = resize(p, nw, nh)
                    scale = 1.0
                    did += "缩放到 $nw×$nh"
                }
                "text" -> {
                    val text = s.txt("text", "content") ?: throw Problem("text 要给 text（写什么字）。")
                    val given = boxOf(s, "box", p, scale, op, required = false)
                    // 刚擦掉一段字：写回擦掉的那个位置，比模型估的框准（除非它明确写在别处）
                    val erased = lastErase?.box?.takeIf { e -> given == null || (given[0] < e[2] && e[0] < given[2] && given[1] < e[3] && e[1] < given[3]) }
                    val b = erased ?: given ?: throw Problem("text 要给 box：字写在哪。")
                    // 刚擦掉一段字接着写：颜色、大小照擦掉的那段（模型猜不准）
                    val color = colorOf(s.txt("color")) ?: lastErase?.fg ?: 0x222222
                    val cjk = text.any { it.code > 0x2E7F }
                    val h = (lastErase?.textH ?: 0).takeIf { it > 0 } ?: (b[3] - b[1])
                    val size = s.num("size")?.let { (it * scale).toInt() } ?: (h / (if (cjk) 0.90 else 0.74)).toInt()
                    drawTextOn(p, text, b, color, size.coerceIn(6, 2000), s.flag("bold"), s.txt("align").orEmpty().lowercase())
                    did += "写字「${text.take(30)}」在 ${view(b)}"
                }
                "mark" -> {
                    val b = boxOf(s, "box", p, scale, op)!!
                    val shape = s.txt("shape").orEmpty().lowercase().let { if (it.startsWith("e") || it.startsWith("c") || "圆" in it || "圈" in it) "ellipse" else "rect" }
                    val thick = s.num("thickness")?.let { (it * scale).toInt() } ?: max(3, max(p.w, p.h) / 300)
                    drawShapeOn(p, shape, b, colorOf(s.txt("color")) ?: 0xE53935, thick.coerceIn(1, 100))
                    did += (if (shape == "ellipse") "画圈 " else "画框 ") + view(b)
                }
                "adjust" -> {
                    val b = boxOf(s, "box", p, scale, op, required = false) ?: intArrayOf(0, 0, p.w, p.h)
                    val br = s.num("brightness") ?: 0.0; val co = s.num("contrast") ?: 0.0; val sa = s.num("saturation") ?: 0.0
                    if (br == 0.0 && co == 0.0 && sa == 0.0) throw Problem("adjust 要给 brightness、contrast、saturation 里至少一个（-100 到 100）。")
                    adjust(p, b, br.coerceIn(-100.0, 100.0), co.coerceIn(-100.0, 100.0), sa.coerceIn(-100.0, 100.0))
                    did += "调色（亮度 ${br.toInt()} 对比度 ${co.toInt()} 饱和度 ${sa.toInt()}）"
                }
                "paste" -> {
                    val name = s.txt("from") ?: throw Problem("paste 要给 from：另一张图的文件名。")
                    val o = other(name) ?: throw Problem("找不到「$name」这张图。")
                    val oScale = max(o.w, o.h).toDouble().let { if (it > VIEW_SIDE) it / VIEW_SIDE else 1.0 }
                    val fb = boxOf(s, "from_box", o, oScale, op, required = false) ?: intArrayOf(0, 0, o.w, o.h)
                    val b = boxOf(s, "box", p, scale, op)!!
                    val piece = resize(crop(o, fb), b[2] - b[0], b[3] - b[1])
                    for (y in 0 until piece.h) for (x in 0 until piece.w) {
                        val c = piece.argb[y * piece.w + x]
                        val a = c ushr 24
                        val k = (b[1] + y) * p.w + b[0] + x
                        p.argb[k] = when (a) {
                            255 -> c
                            0 -> p.argb[k]
                            else -> {
                                val d = p.argb[k]
                                fun mix(sh: Int) = (((c ushr sh and 255) * a + (d ushr sh and 255) * (255 - a)) / 255) shl sh
                                (0xFF shl 24) or mix(16) or mix(8) or mix(0)
                            }
                        }
                    }
                    did += "贴上《$name》到 ${view(b)}"
                }
                "" -> throw Problem("这一步没写 op：${s.toString().take(80)}。op 只能是 ${OPS.joinToString("、")}。")
                else -> throw Problem("不认识的操作「$op」，只能是 ${OPS.joinToString("、")}。")
            }
            if (op != "erase" && op != "text") lastErase = null
        }
        val label = steps.mapNotNull { OP_NAMES[it.txt("op", "do", "type", "action").orEmpty().lowercase()] }.distinct().joinToString("、")
        return Outcome(p, notes, did.joinToString("；").ifEmpty { "原样" }, label.ifEmpty { "原样" })
    }

    // ———————————————— 比对 ————————————————

    /** 改了多少像素、都在哪（程序量的，不是 AI 说的）。尺寸变了返回 null。 */
    class Diff(val changed: Int, val total: Int, val box: IntArray?)

    fun diff(a: Pixels, b: Pixels): Diff? {
        if (a.w != b.w || a.h != b.h) return null
        var n = 0
        var x1 = Int.MAX_VALUE; var y1 = Int.MAX_VALUE; var x2 = -1; var y2 = -1
        for (y in 0 until a.h) for (x in 0 until a.w) {
            val k = y * a.w + x
            if (a.argb[k] != b.argb[k]) { n++; x1 = min(x1, x); y1 = min(y1, y); x2 = max(x2, x + 1); y2 = max(y2, y + 1) }
        }
        return Diff(n, a.w * a.h, if (n > 0) intArrayOf(x1, y1, x2, y2) else null)
    }
}
