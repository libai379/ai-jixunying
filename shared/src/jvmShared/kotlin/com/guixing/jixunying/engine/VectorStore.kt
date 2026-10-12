package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppJson
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 切段（docs/向量和语音设计说明.md 第 2 节）：一段最多 PART 字，相邻两段重叠 OVERLAP 字。
 * 一条消息、一个文档都走这里：短的自然就是一段；长了的在句号、换行这些地方断，断不了就硬切。
 * 重叠是为了「一句话正好横跨两段」时按意思搜也不会漏。
 */
object VectorText {
    /** 一段最多多少字。智谱单条最多 3072 token、千问 8192，500 字一段离上限很远，也够一句完整的话。 */
    const val PART = 500
    /** 相邻两段的重复部分。 */
    const val OVERLAP = 50

    private val think = Regex("<think>[\\s\\S]*?</think>")
    private val sentenceEnd = charArrayOf('。', '！', '？', '；', '!', '?', ';', '\n')

    /** 去掉思考过程（<think>…</think>，成员回答里常有）和首尾空白。 */
    fun clean(text: String) = think.replace(text, "").trim()

    /** 把一段文字切成几段。全是空的返回空表。 */
    fun chunks(text: String): List<String> {
        val t = clean(text)
        if (t.isBlank()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < t.length) {
            var end = minOf(t.length, start + PART)
            if (end < t.length) {
                // 尽量在句子之间断：往前找最后一个句末标点，但别切得太碎（至少要 PART 的一半）
                for (i in end - 1 downTo start + PART / 2) {
                    if (t[i] in sentenceEnd) { end = i + 1; break }
                }
            }
            val part = t.substring(start, end).trim()
            if (part.isNotEmpty()) out += part
            if (end >= t.length) break
            // 下一段往回退 OVERLAP 个字：相邻两段有重复的内容
            start = maxOf(end - OVERLAP, start + 1)
        }
        return out
    }
}

/** 一段向量的来历：聊天记录是「哪个对话的哪条消息的第几段」，文档是「哪个文件的第几段」。 */
@Serializable
data class VecSource(
    val convId: String = "",
    val messageId: String = "",
    val path: String = "",
    /** 这一段是这条消息 / 这个文件的第几段（从 0 开始）。 */
    val part: Int = 0,
    /** 文档：抽文字时的文件修改时间（文件改了，这一份要重算）。 */
    val mtime: Long = 0,
    /** 文档：这个文件一共切成几段（段数对得上、修改时间没变，补算时就不用再读文字，见 Engine.docsPlan）。 */
    val of: Int = 0,
) {
    /** 对号入座用的键（同一段重算时覆盖它）。 */
    fun key(): String = if (path.isEmpty()) "c\u0000$convId\u0000$messageId\u0000$part" else "d\u0000$path\u0000$part"
}

/** 一段向量：来历 + 这段文字的指纹（文字变了要重算）。 */
@Serializable
data class VecEntry(val source: VecSource, val hash: String)

/** 一份向量（history / docs）的清单：哪个平台、哪个模型、多少维、什么时候算的。 */
@Serializable
data class VecIndex(
    val platform: String = "",
    val model: String = "",
    val dim: Int = 0,
    val builtAt: Long = 0,
    val entries: List<VecEntry> = emptyList(),
    /** 向量存在哪个文件（每次写一个新名字，见 VectorStore.write）。空 = 老版本的 <份>.bin。 */
    val bin: String = "",
)

/** 一次命中的一段。 */
class VecHit(val entry: VecEntry, val score: Double)

/** 读出来的一份：清单和向量一一对应。 */
class VecData(
    val entries: List<VecEntry>,
    val vectors: List<FloatArray>,
    val platform: String,
    val model: String,
    val dim: Int,
    val builtAt: Long,
) {
    val isEmpty get() = entries.isEmpty()

    /**
     * 这份是不是现在这个模型（平台 + 模型名 + 维度都对得上）算出来的。
     * 换了模型或者维度就不能混着比（不同模型算出来的向量不在一个空间里），要整份作废重算。
     */
    fun matches(platform: String, model: String, dim: Int) =
        !isEmpty && this.platform == platform && this.model == model && (dim <= 0 || this.dim == dim)
}

/**
 * 本机向量库（docs/向量和语音设计说明.md 第 4 节）：存在数据目录 vectors\ 下，聊天记录和文档各一份。
 * - <份>.json 是清单（来历、指纹、平台模型维度、向量在哪个文件），<份>-<编号>.bin 是向量本身（float32、小端，按清单顺序排）。
 * - 每次写一个新名字的 bin，再写 json 指向它（都走「写临时文件再改名」），最后删掉旧的 bin。
 *   崩在中间时 json 还是上一版、指着上一版的 bin，两边一致。以前只有一个 <份>.bin：崩在两步之间时，
 *   旧清单会对上新 bin，而新 bin 里的顺序可能已经变了（删过消息），向量和段悄悄对错号（主会话审查 10-12 改）。
 * - 查的时候暴力算余弦相似度、取最接近的前几段就够（设计说明第 6 节：量不大，不值得上索引）。
 */
class VectorStore(private val dir: File) {

    companion object {
        /** 聊天记录。 */
        const val HISTORY = "history"
        /** 我的文档。 */
        const val DOCS = "docs"
    }

    private val lock = Any()
    private val loaded = HashMap<String, VecData>()

    private fun jsonFile(kind: String) = File(dir, "$kind.json")
    private fun legacyBin(kind: String) = File(dir, "$kind.bin")

    /** 这一份所有的 bin（新名字的和老版本的）。 */
    private fun allBins(kind: String): List<File> =
        dir.listFiles { f -> f.name == "$kind.bin" || (f.name.startsWith("$kind-") && f.name.endsWith(".bin")) }?.toList().orEmpty()

    private var seq = 0L

    /** 一份的清单和向量（第一次用到时从盘上读）。 */
    fun data(kind: String): VecData = synchronized(lock) { loaded.getOrPut(kind) { load(kind) } }

    fun index(kind: String): VecIndex {
        val d = data(kind)
        return VecIndex(d.platform, d.model, d.dim, d.builtAt, d.entries)
    }

    /** 整份作废（换了模型、用户点了「重新算一遍」）：两份文件都删掉，已算的向量不留。 */
    fun reset(kind: String) = synchronized(lock) {
        loaded.remove(kind)
        jsonFile(kind).delete()
        allBins(kind).forEach { it.delete() }
        Unit
    }

    /**
     * 整份重写（算完一批就写一次，中途停了也已经存下算好的那些）。
     * dim 是所有向量的长度（智谱、千问是 1024；「自定义」是它自己返回的长度）。
     */
    fun write(kind: String, platform: String, model: String, dim: Int, entries: List<VecEntry>, vectors: List<FloatArray>, at: Long) {
        if (entries.isNotEmpty() && entries.size != vectors.size) throw IllegalStateException("向量清单和向量条数对不上：${entries.size} / ${vectors.size}")
        vectors.forEachIndexed { i, v ->
            if (v.size != dim) throw IllegalStateException("第 ${i + 1} 条向量是 ${v.size} 维，其余是 $dim 维")
        }
        synchronized(lock) {
            dir.mkdirs()
            // 先写一个新名字的 bin，再写 json 指向它，最后删旧 bin：崩在哪一步，json 指的那个 bin 都和它一致
            val buf = ByteBuffer.allocate(vectors.size * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
            vectors.forEach { v -> v.forEach { buf.putFloat(it) } }
            val name = "$kind-${System.currentTimeMillis()}-${seq++}.bin"
            val tmpBin = File(dir, "$name.tmp")
            tmpBin.writeBytes(buf.array())
            Files.move(tmpBin.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            val tmpJson = File(dir, "$kind.json.tmp")
            tmpJson.writeText(AppJson.encodeToString(VecIndex.serializer(), VecIndex(platform, model, dim, at, entries, name)), Charsets.UTF_8)
            Files.move(tmpJson.toPath(), jsonFile(kind).toPath(), StandardCopyOption.REPLACE_EXISTING)
            allBins(kind).filter { it.name != name }.forEach { it.delete() }
            // 上次崩在写 bin 的中途留下的临时文件
            dir.listFiles { f -> f.name.startsWith("$kind-") && f.name.endsWith(".bin.tmp") }?.forEach { it.delete() }
            loaded[kind] = VecData(entries.toList(), vectors.map { it.copyOf() }, platform, model, dim, at)
        }
    }

    /** 按余弦相似度取最接近的前 top 段。 */
    fun search(kind: String, query: FloatArray, top: Int): List<VecHit> {
        val d = data(kind)
        if (d.entries.isEmpty() || query.size != d.dim) return emptyList()
        val scored = ArrayList<VecHit>(d.entries.size)
        for (i in d.entries.indices) {
            val s = cosine(query, d.vectors[i]) ?: continue
            scored += VecHit(d.entries[i], s)
        }
        return scored.sortedByDescending { it.score }.take(top)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double? {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na <= 0.0 || nb <= 0.0) return null
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    private fun load(kind: String): VecData {
        val idx = runCatching { AppJson.decodeFromString(VecIndex.serializer(), jsonFile(kind).readText(Charsets.UTF_8)) }.getOrNull()
        if (idx == null || idx.dim <= 0) return VecData(emptyList(), emptyList(), "", "", 0, 0)
        val bin = if (idx.bin.isNotBlank()) File(dir, idx.bin) else legacyBin(kind)
        val bytes = runCatching { bin.readBytes() }.getOrDefault(ByteArray(0))
        val per = idx.dim * 4
        // bin 被截短了（磁盘出过问题）：前面那一截和清单的前面是同一次写的，按对得上的条数用
        val count = minOf(idx.entries.size, bytes.size / per)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vectors = ArrayList<FloatArray>(count)
        for (i in 0 until count) {
            val v = FloatArray(idx.dim)
            for (j in 0 until idx.dim) v[j] = buf.float
            vectors += v
        }
        return VecData(idx.entries.take(count), vectors, idx.platform, idx.model, idx.dim, idx.builtAt)
    }
}
