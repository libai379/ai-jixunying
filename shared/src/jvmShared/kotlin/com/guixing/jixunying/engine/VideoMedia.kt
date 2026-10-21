package com.guixing.jixunying.engine

import com.guixing.jixunying.model.VideoMediaUse
import com.guixing.jixunying.model.VideoModel
import com.guixing.jixunying.model.VideoPick

/**
 * 做视频的一份素材：对话里的图 / 视频、电脑上的文件（bytes），或者公开网址（url）。
 * seconds = 视频 / 音频的时长（读不出来是 null，交给服务端去查）。
 */
class VideoInput(
    val use: String,
    val name: String,
    val mime: String,
    val bytes: ByteArray? = null,
    val url: String? = null,
    val seconds: Double? = null,
) {
    val size: Long get() = bytes?.size?.toLong() ?: 0L
}

/**
 * 素材的本地检查（发之前先挡住，免得白等一趟）。限制全是官方原文（docs/参考资料.md「视频生成」）：
 * wan3.0：首帧 / 尾帧各 1 张；参考图 ≤10 张、单张 ≤20MB；参考视频 ≤5 段、总时长 ≤15 秒、单个 ≤100MB；参考音频 ≤5 段、总时长 ≤15 秒、单个 ≤15MB；
 *   文档 / 网页各 ≤1 个且二选一；首尾帧和其余类型互斥；只认「仅首帧」「首帧 + 尾帧」两种帧组合；最多 20 个素材；有视频输入时输入 + 输出 ≤30 秒。
 * MiniMax H3：首帧 / 尾帧各 ≤1；参考图 ≤9 张、单张 ≤30MB；参考视频 ≤3 段、单段 2～15 秒、总 ≤15 秒、单个 ≤50MB、MP4 / MOV；
 *   参考音频 ≤3 段、单段 2～15 秒、总 ≤15 秒、单个 ≤15MB、WAV / MP3；最多 12 个；首尾帧和参考类互斥；请求体 ≤64MB（我们用 Base64 传，放大约 33%）。
 */
object VideoMedia {
    private const val MB = 1024L * 1024

    /** 文档类素材（wan3.0 file）认的扩展名。 */
    val docExts = setOf("docx", "doc", "xlsx", "xls", "pptx", "ppt", "pdf", "txt", "md")

    /** 返回 null = 没问题；否则是给 AI 的说明。duration = 要的输出秒数（-1 = 智能时长）。 */
    fun check(m: VideoModel, inputs: List<VideoInput>, duration: Int?): String? {
        if (inputs.isEmpty()) return null
        val by = inputs.groupBy { it.use }
        fun n(use: String) = by[use].orEmpty().size
        fun secs(use: String) = by[use].orEmpty().sumOf { it.seconds ?: 0.0 }
        val bad = inputs.map { it.use }.filter { it !in m.media }.distinct()
        if (bad.isNotEmpty()) return "${m.id} 收不了${bad.joinToString("、") { VideoMediaUse.label(it) }}"
        val frames = inputs.any { it.use in VideoMediaUse.frames }
        if (frames && inputs.any { it.use !in VideoMediaUse.frames }) return "首帧 / 尾帧不能和参考图、参考视频、参考音频、文档、网页一起用（官方规定互斥）。要么只给首帧（和尾帧），要么全用参考类"
        if (n(VideoMediaUse.FIRST) > 1 || n(VideoMediaUse.LAST) > 1) return "首帧、尾帧各只能 1 张"
        for (i in inputs) {
            if (i.use == VideoMediaUse.LINK) {
                if (i.url == null) return "网页素材要给公开网址（http / https 开头）：${i.name}"
                continue
            }
            if (i.url != null) continue
            val ext = i.name.substringAfterLast('.', "").lowercase()
            when (i.use) {
                VideoMediaUse.FIRST, VideoMediaUse.LAST, VideoMediaUse.IMAGE -> if (!i.mime.startsWith("image/")) return "《${i.name}》不是图片，不能当${VideoMediaUse.label(i.use)}"
                VideoMediaUse.VIDEO -> if (!i.mime.startsWith("video/")) return "《${i.name}》不是视频，不能当参考视频"
                VideoMediaUse.AUDIO -> if (!i.mime.startsWith("audio/")) return "《${i.name}》不是音频，不能当参考音频"
                VideoMediaUse.FILE -> if (ext !in docExts) return "《${i.name}》不能当文档素材：只认 ${docExts.joinToString("、")}"
            }
        }
        return if (m.vendor == VideoPick.MINIMAX) checkMinimax(inputs, ::n, ::secs) else checkWan(inputs, ::n, ::secs, duration)
    }

    private fun checkWan(inputs: List<VideoInput>, n: (String) -> Int, secs: (String) -> Double, duration: Int?): String? {
        if (n(VideoMediaUse.LAST) == 1 && n(VideoMediaUse.FIRST) == 0) return "万相 3.0 不能只给尾帧：要么只给首帧，要么首帧 + 尾帧都给"
        if (n(VideoMediaUse.FILE) + n(VideoMediaUse.LINK) > 1) return "文档和网页只能给一个（二选一，各最多 1 个）"
        if (inputs.size > 20) return "素材太多：最多 20 个，现在 ${inputs.size} 个"
        if (n(VideoMediaUse.IMAGE) > 10) return "参考图最多 10 张，现在 ${n(VideoMediaUse.IMAGE)} 张"
        if (n(VideoMediaUse.VIDEO) > 5) return "参考视频最多 5 段，现在 ${n(VideoMediaUse.VIDEO)} 段"
        if (n(VideoMediaUse.AUDIO) > 5) return "参考音频最多 5 段，现在 ${n(VideoMediaUse.AUDIO)} 段"
        if (secs(VideoMediaUse.VIDEO) > 15.0) return "参考视频加起来最长 15 秒，现在 ${fmt(secs(VideoMediaUse.VIDEO))} 秒"
        if (secs(VideoMediaUse.AUDIO) > 15.0) return "参考音频加起来最长 15 秒，现在 ${fmt(secs(VideoMediaUse.AUDIO))} 秒"
        for (i in inputs) {
            val limit = when (i.use) {
                VideoMediaUse.VIDEO -> 100 * MB
                VideoMediaUse.AUDIO -> 15 * MB
                VideoMediaUse.FIRST, VideoMediaUse.LAST, VideoMediaUse.IMAGE -> 20 * MB
                else -> Long.MAX_VALUE
            }
            if (i.size > limit) return "《${i.name}》太大（${i.size / MB} MB），${VideoMediaUse.label(i.use)}单个最大 ${limit / MB} MB"
        }
        val inSecs = secs(VideoMediaUse.VIDEO)
        if (duration != null && duration > 0 && inSecs > 0 && inSecs + duration > 30.0)
            return "有视频输入时「输入视频 + 输出视频」最长 30 秒：输入 ${fmt(inSecs)} 秒 + 要做 $duration 秒超了。duration 不填（智能时长）或者改小"
        return null
    }

    private fun checkMinimax(inputs: List<VideoInput>, n: (String) -> Int, secs: (String) -> Double): String? {
        if (inputs.size > 12) return "素材太多：MiniMax 最多 12 个，现在 ${inputs.size} 个"
        if (n(VideoMediaUse.IMAGE) > 9) return "参考图最多 9 张，现在 ${n(VideoMediaUse.IMAGE)} 张"
        if (n(VideoMediaUse.VIDEO) > 3) return "参考视频最多 3 段，现在 ${n(VideoMediaUse.VIDEO)} 段"
        if (n(VideoMediaUse.AUDIO) > 3) return "参考音频最多 3 段，现在 ${n(VideoMediaUse.AUDIO)} 段"
        if (secs(VideoMediaUse.VIDEO) > 15.0) return "参考视频加起来最长 15 秒，现在 ${fmt(secs(VideoMediaUse.VIDEO))} 秒"
        if (secs(VideoMediaUse.AUDIO) > 15.0) return "参考音频加起来最长 15 秒，现在 ${fmt(secs(VideoMediaUse.AUDIO))} 秒"
        for (i in inputs) {
            if (i.url != null) continue
            val ext = i.name.substringAfterLast('.', "").lowercase()
            val (limit, exts) = when (i.use) {
                VideoMediaUse.VIDEO -> 50 * MB to setOf("mp4", "mov")
                VideoMediaUse.AUDIO -> 15 * MB to setOf("wav", "mp3")
                else -> 30 * MB to setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
            }
            if (ext.isNotEmpty() && ext !in exts) return "《${i.name}》格式不对：MiniMax 的${VideoMediaUse.label(i.use)}只认 ${exts.joinToString("、")}"
            if (i.size > limit) return "《${i.name}》太大（${i.size / MB} MB），MiniMax 的${VideoMediaUse.label(i.use)}单个最大 ${limit / MB} MB"
            val s = i.seconds
            if (s != null && i.use in setOf(VideoMediaUse.VIDEO, VideoMediaUse.AUDIO) && (s < 2.0 || s > 15.0))
                return "《${i.name}》${fmt(s)} 秒：MiniMax 的${VideoMediaUse.label(i.use)}每段要 2～15 秒"
        }
        val body = inputs.sumOf { it.size } * 4 / 3
        if (body > 60 * MB) return "素材加起来太大（约 ${body / MB} MB），MiniMax 一次请求最多 64 MB，换小一点的视频或少给几个"
        return null
    }

    fun fmt(s: Double) = if (s % 1.0 == 0.0) s.toInt().toString() else "%.1f".format(s)

    /** 按扩展名猜类型（对话里的附件有自己的 mime，电脑上的文件用这个）。 */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }

    /** 时长（秒）：MP4 / MOV 读 mvhd，WAV 读头，MP3 按第一帧的码率估；读不出来返回 null。 */
    fun durationOf(name: String, mime: String, bytes: ByteArray): Double? = runCatching {
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext in setOf("mp4", "mov", "m4v", "m4a") || mime == "video/mp4" || mime == "video/quicktime" -> mp4Duration(bytes)
            ext == "wav" || mime == "audio/wav" -> wavDuration(bytes)
            ext == "mp3" || mime == "audio/mpeg" -> mp3Duration(bytes)
            else -> null
        }
    }.getOrNull()

    private fun u32(b: ByteArray, i: Int) = ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
        ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)
    private fun u64(b: ByteArray, i: Int) = (u32(b, i) shl 32) or u32(b, i + 4)
    private fun le32(b: ByteArray, i: Int) = (b[i].toLong() and 0xff) or ((b[i + 1].toLong() and 0xff) shl 8) or
        ((b[i + 2].toLong() and 0xff) shl 16) or ((b[i + 3].toLong() and 0xff) shl 24)

    /** ISO BMFF：顶层找 moov，里面找 mvhd（版本 0：32 位 timescale / duration；版本 1：64 位 duration）。 */
    fun mp4Duration(b: ByteArray): Double? {
        fun find(from: Int, to: Int, type: String): Int? {
            var i = from
            while (i + 8 <= to) {
                var size = u32(b, i)
                val t = String(b, i + 4, 4, Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) { if (i + 16 > to) return null; size = u64(b, i + 8); header = 16 }
                else if (size == 0L) size = (to - i).toLong()
                if (size < header) return null
                if (t == type) return i + header
                i += size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            return null
        }
        val moovStart = find(0, b.size, "moov") ?: return null
        val moovEnd = (moovStart - 8 + u32(b, moovStart - 8).toInt()).coerceAtMost(b.size)
        val mv = find(moovStart, moovEnd, "mvhd") ?: return null
        val version = b[mv].toInt()
        return if (version == 1) {
            val scale = u32(b, mv + 20); val dur = u64(b, mv + 24)
            if (scale == 0L) null else dur.toDouble() / scale
        } else {
            val scale = u32(b, mv + 12); val dur = u32(b, mv + 16)
            if (scale == 0L) null else dur.toDouble() / scale
        }
    }

    /** RIFF / WAVE：fmt 里的每秒字节数，data 块的长度。 */
    fun wavDuration(b: ByteArray): Double? {
        if (b.size < 12 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        var i = 12
        var byteRate = 0L
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.ISO_8859_1)
            val size = le32(b, i + 4)
            if (id == "fmt " && i + 20 <= b.size) byteRate = le32(b, i + 16)
            if (id == "data") return if (byteRate > 0) minOf(size, (b.size - i - 8).toLong()).toDouble() / byteRate else null
            i += 8 + size.toInt() + (size.toInt() and 1)
        }
        return null
    }

    /** MP3：跳过 ID3，读第一帧的码率，按固定码率估（可变码率会不准，只用来粗查 15 秒上限）。 */
    fun mp3Duration(b: ByteArray): Double? {
        var i = 0
        if (b.size > 10 && String(b, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            val sz = ((b[6].toInt() and 0x7f) shl 21) or ((b[7].toInt() and 0x7f) shl 14) or ((b[8].toInt() and 0x7f) shl 7) or (b[9].toInt() and 0x7f)
            i = 10 + sz
        }
        while (i + 4 <= b.size) {
            if ((b[i].toInt() and 0xff) == 0xff && (b[i + 1].toInt() and 0xe0) == 0xe0) {
                val ver = (b[i + 1].toInt() shr 3) and 3          // 3 = MPEG1
                val layer = (b[i + 1].toInt() shr 1) and 3        // 1 = Layer III
                val idx = (b[i + 2].toInt() shr 4) and 0xf
                if (layer == 1 && idx in 1..14) {
                    val v1 = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
                    val v2 = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
                    val kbps = if (ver == 3) v1[idx - 1] else v2[idx - 1]
                    return (b.size - i).toDouble() * 8 / (kbps * 1000)
                }
            }
            i++
        }
        return null
    }
}
