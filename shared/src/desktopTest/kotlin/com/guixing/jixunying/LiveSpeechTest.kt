package com.guixing.jixunying

import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Ledger
import com.guixing.jixunying.engine.SpeechText
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.Prices
import com.guixing.jixunying.model.SpeechAudio
import com.guixing.jixunying.model.Thinking
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import kotlin.test.Test

/**
 * 用真 Key 试语音：小米、MiniMax、智谱各读一句话，再拿每段录音让这三家各听写一遍（互相听，看字段取得对不对）。
 * 主要确认两件官方页面没直接写的事：小米的音频在 choices[0].message.audio.data；MiniMax 听写返回的文字字段叫 text。
 * 千问不跑（CLAUDE.md：千问余额很少）。花费：小米读出来限时免费，其余加起来几分钱以内。
 * 只在设了 JXY_LIVE_SPEECH=数据文件夹 时跑：把那份数据的 state.json 复制到临时文件夹再用（不动原数据），跑完删掉。不打印 Key。
 * 设了 JXY_LIVE_SPEECH_OUT=文件夹 就把读出来的 wav 存过去（自己听一听）。
 */
class LiveSpeechTest {
    @Test
    fun readAndWriteDownWithRealKeys() = runBlocking {
        val src = System.getenv("JXY_LIVE_SPEECH") ?: return@runBlocking
        val out = System.getenv("JXY_LIVE_SPEECH_OUT")?.let { File(it).apply { mkdirs() } }
        val tmp = kotlin.io.path.createTempDirectory("jxy-live-speech").toFile()
        try {
            File(src, "state.json").copyTo(File(tmp, "state.json"))
            val e = Engine(Storage(tmp))
            val sentence = "你好，这是语音测试。"
            val providers = e.state.providers.filter { it.enabled && it.apiKey.isNotBlank() && Thinking.platformOf(it) in setOf("mimo", "minimax", "zhipu") }
            println("参加的服务商：" + providers.joinToString("、") { "${it.name}（${Thinking.platformOf(it)}）" })

            // —— 1. 读出来 ——
            val clips = mutableListOf<Pair<String, ByteArray>>()
            for (p in providers) {
                val t0 = System.currentTimeMillis()
                val r = e.call(Command.Speak(sentence, providerId = p.id))
                val ms = System.currentTimeMillis() - t0
                if (!r.ok) { println("读出来 失败  ${p.name}：${r.message}"); continue }
                val a = AppJson.decodeFromString(SpeechAudio.serializer(), r.data)
                val wav = Base64.getDecoder().decode(a.segments.first())
                val w = SpeechText.parseWav(wav)
                val info = if (w == null) "认不出 wav 头（前 4 字节：${String(wav, 0, minOf(4, wav.size), Charsets.ISO_8859_1)}）"
                    else "${w.sampleRate} Hz、${w.channels} 声道、${w.bits} 位、${"%.2f".format(w.seconds)} 秒"
                println("读出来 成功  ${a.label}：${wav.size} 字节，$info，$ms 毫秒")
                out?.let { File(it, "${Thinking.platformOf(p)}.wav").writeBytes(wav) }
                if (w != null) clips += Thinking.platformOf(p) to wav
            }

            // —— 2. 听写（每段录音让每家各听一遍） ——
            for ((from, wav) in clips) for (p in providers) {
                val t0 = System.currentTimeMillis()
                val r = e.call(Command.Transcribe(Base64.getEncoder().encodeToString(wav), providerId = p.id))
                val ms = System.currentTimeMillis() - t0
                println("听写 ${if (r.ok) "成功" else "失败"}  ${p.name} 听 $from 的录音：" + (if (r.ok) "「${r.data}」" else r.message) + "，$ms 毫秒")
            }

            // —— 3. 记账 ——
            val recs = Ledger(File(tmp, "usage")).since(0)
            var cny = 0.0
            for (rec in recs) {
                val price = Prices.of(rec.platform, rec.model, baseUrl = e.state.provider(rec.providerId)?.baseUrl.orEmpty())
                val c = Prices.cost(rec, price)
                if (c != null) cny += c
                println("账本  ${rec.kind} ${rec.model}：${"%.2f".format(rec.units)} ${rec.unit} → " + (c?.let { "%.5f 元".format(it) } ?: "没算价"))
            }
            println("这次一共约 ${"%.4f".format(cny)} 元（${recs.size} 条）")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
