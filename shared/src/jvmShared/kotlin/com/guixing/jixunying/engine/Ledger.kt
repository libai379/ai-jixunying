package com.guixing.jixunying.engine

import com.guixing.jixunying.model.UsageRecord
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 这次调用算谁的账：放进协程上下文，LlmClient 和画图记账时取出来。 */
class UsageTag(val kind: String, val memberId: String = "", val convId: String = "") : AbstractCoroutineContextElement(UsageTag) {
    companion object Key : CoroutineContext.Key<UsageTag>
}

/**
 * 账本：usage\年-月.jsonl，一行一条 UsageRecord，只追加不改。月份按北京时间算。
 * 读坏的行跳过（不影响别的行）。
 */
class Ledger(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    init { dir.mkdirs() }

    fun add(r: UsageRecord) = synchronized(lock) {
        File(dir, monthOf(r.at) + ".jsonl").appendText(json.encodeToString(UsageRecord.serializer(), r) + "\n", Charsets.UTF_8)
    }

    fun addAll(list: List<UsageRecord>) = synchronized(lock) { list.forEach(::add) }

    /** since 以后（含）的全部记录，按时间排。 */
    fun since(since: Long = 0): List<UsageRecord> = synchronized(lock) {
        val firstMonth = if (since <= 0) "" else monthOf(since)
        (dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray())
            .filter { it.name.removeSuffix(".jsonl") >= firstMonth }
            .sortedBy { it.name }
            .flatMap { f -> f.readLines(Charsets.UTF_8).mapNotNull { line -> runCatching { json.decodeFromString(UsageRecord.serializer(), line) }.getOrNull() } }
            .filter { it.at >= since }
            .sortedBy { it.at }
    }

    /** 服务商、成员改了编号（两端同步对齐编号，2026-10-27）：账本里的旧编号一起换掉，「手填余额」的估算才对得上。 */
    fun remap(ids: Map<String, String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        for (f in dir.listFiles { x -> x.name.endsWith(".jsonl") } ?: emptyArray()) {
            val lines = f.readLines(Charsets.UTF_8)
            var changed = false
            val out = lines.map { line ->
                val r = runCatching { json.decodeFromString(UsageRecord.serializer(), line) }.getOrNull() ?: return@map line
                val n = r.copy(providerId = ids[r.providerId] ?: r.providerId, memberId = ids[r.memberId] ?: r.memberId)
                if (n == r) line else { changed = true; json.encodeToString(UsageRecord.serializer(), n) }
            }
            if (changed) {
                val tmp = File(dir, f.name + ".tmp")
                tmp.writeText(out.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** 以前的聊天记录补记过没有（只补一次）。 */
    var backfilled: Boolean
        get() = File(dir, ".backfilled").exists()
        set(v) { if (v) File(dir, ".backfilled").writeText("1") else File(dir, ".backfilled").delete() }

    companion object {
        val BEIJING: ZoneId = ZoneId.of("Asia/Shanghai")
        fun monthOf(millis: Long): String = Instant.ofEpochMilli(millis).atZone(BEIJING).let { "%04d-%02d".format(it.year, it.monthValue) }
    }
}
