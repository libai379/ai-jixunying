package com.guixing.jixunying

import com.guixing.jixunying.engine.Embeddings
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.model.EmbedModels
import com.guixing.jixunying.model.Thinking
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 用真 Key 量两件事（智谱 embedding-3；千问不跑，CLAUDE.md：千问余额很少）：
 * 1. 一个汉字大约算几个 token（估算里先按 1:1 粗估，EmbedModels.TOKEN_NOTE）；
 * 2. 「意思相近」和「不相关」的句子，余弦相似度各在什么范围（定按意思搜的门槛用）。
 * 只在设了 JXY_LIVE_SEMANTIC=数据文件夹 时跑：复制那份数据的 state.json 到临时文件夹再用（不动原数据），跑完删掉。不打印 Key。
 * 花费：几十句话，不到一分钱。
 */
class LiveSemanticTest {
    private fun cos(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var x = 0.0; var y = 0.0
        for (i in a.indices) { d += a[i] * b[i]; x += a[i] * a[i]; y += b[i] * b[i] }
        return d / (Math.sqrt(x) * Math.sqrt(y))
    }

    /**
     * 拿一份已经算好向量的数据（比如从模拟器导出来的 files/data）直接搜几句话，看按意思搜搜不搜得到、门槛挡不挡得住不相关的。
     * 只在设了 JXY_LIVE_SEMANTIC_DATA=数据文件夹 时跑：整个文件夹复制到临时文件夹再用。每句问话算一次向量（几个 token）。
     */
    @Test
    fun searchWithRealVectors() = runBlocking {
        val src = System.getenv("JXY_LIVE_SEMANTIC_DATA") ?: return@runBlocking
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-semantic-data").toFile()
        try {
            File(src).copyRecursively(tmp)
            val e = Engine(Storage(tmp))
            println("进度：${e.state.semanticProgress.history.text}")
            // 每句问话对库里每一段的相似度（定门槛用：短问句对长段落，和两句短句子之间不一样）
            val store = com.guixing.jixunying.engine.VectorStore(File(tmp, "vectors"))
            val p = e.state.providers.first { Thinking.platformOf(it) == "zhipu" && it.enabled && it.apiKey.isNotBlank() }
            val em = Embeddings { null }
            val qs = listOf("保证金怎么退回来", "押金条款要注意什么", "房东扣钱有什么规定", "孩子发烧怎么办", "红烧肉怎么做", "how to get my deposit back")
            val qv = em.embed(p, EmbedModels.ZHIPU, qs).vectors
            qs.forEachIndexed { i, q ->
                val hits = store.search(com.guixing.jixunying.engine.VectorStore.HISTORY, qv[i], 10)
                println("相似度「$q」：" + hits.joinToString("、") { "%.3f".format(it.score) })
            }
            for (q in listOf("保证金怎么退回来", "房东扣钱有什么规定", "孩子发烧怎么办", "红烧肉怎么做", "how to get my deposit back")) {
                val r = e.call(com.guixing.jixunying.model.Command.SearchHistory(q, 8))
                val hits = com.guixing.jixunying.model.AppJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(com.guixing.jixunying.model.HistoryHit.serializer()), r.data)
                println("搜「$q」：${hits.size} 条" + hits.joinToString("") { "\n    · ${it.convTitle} · ${it.sender}：${it.snippet.take(40)}" })
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun measureTokensAndSimilarity() = runBlocking {
        val src = System.getenv("JXY_LIVE_SEMANTIC") ?: return@runBlocking
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-semantic").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp))
            val p = e.state.providers.firstOrNull { Thinking.platformOf(it) == "zhipu" && it.enabled && it.apiKey.isNotBlank() } ?: error("没有智谱")
            val em = Embeddings { null }
            var spent = 0

            // —— 1. token 换算 ——
            val zh = "先说结论：这份合同能签，但押金条款要改。退房验收时没有欠费和人为损坏的，房东应在十五日内全额退还押金；" +
                "如果要扣，需要逐项书面说明并提供凭证。另外维修责任、提前退租的违约金、能不能转租养宠物，这几条也建议写清楚。"
            val en = "Semantic search finds documents by meaning rather than exact keyword matching. Text embeddings map sentences into vectors."
            val mixed = "下周三下午 3 点在 A 座 302 会议室开 Q4 预算评审会，记得带上 Excel 表和 PPT。"
            for ((name, t) in listOf("纯中文" to zh, "英文" to en, "中英数字混排" to mixed)) {
                val r = em.embed(p, EmbedModels.ZHIPU, listOf(t))
                spent += r.promptTokens
                println("token 换算  $name：${t.length} 个字符 → ${r.promptTokens} token，每字符 ${"%.2f".format(r.promptTokens.toDouble() / t.length)}，维度 ${r.vectors.first().size}")
            }

            // —— 2. 相似度 ——
            val groups = listOf(
                "什么时候开会" to listOf(
                    "近" to "咱们下周三碰个头聊一下新方案", "近" to "下周二下午两点在三楼会议室讨论预算",
                    "远" to "这家餐厅的红烧肉很好吃", "远" to "Python 的列表推导式怎么写", "远" to "猫为什么喜欢晒太阳"),
                "押金能退回来吗" to listOf(
                    "近" to "退房的时候房东说要扣一部分保证金", "近" to "租房合同里押金条款要写清楚，验收后十五天内退还",
                    "远" to "下周三碰个头聊一下新方案", "远" to "怎么给手机换壁纸", "远" to "今天北京天气晴"),
                "孩子发烧了怎么办" to listOf(
                    "近" to "小朋友体温 38 度 5，要不要去医院", "近" to "宝宝退烧药怎么吃",
                    "远" to "公司年会节目怎么排", "远" to "红烧肉的做法", "远" to "押金条款要写清楚"),
            )
            val near = mutableListOf<Double>()
            val far = mutableListOf<Double>()
            for ((q, cands) in groups) {
                val r = em.embed(p, EmbedModels.ZHIPU, listOf(q) + cands.map { it.second })
                spent += r.promptTokens
                val qv = r.vectors.first()
                println("问：$q")
                cands.forEachIndexed { i, (kind, text) ->
                    val s = cos(qv, r.vectors[i + 1])
                    (if (kind == "近") near else far) += s
                    println("   $kind ${"%.3f".format(s)}  $text")
                }
            }
            println("意思相近：最低 ${"%.3f".format(near.min())}、最高 ${"%.3f".format(near.max())}；不相关：最低 ${"%.3f".format(far.min())}、最高 ${"%.3f".format(far.max())}")
            println("一共 $spent token，按 0.5 元 / 百万约 ${"%.6f".format(spent * 0.5 / 1_000_000)} 元")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
