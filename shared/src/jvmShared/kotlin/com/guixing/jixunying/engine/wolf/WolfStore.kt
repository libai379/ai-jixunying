package com.guixing.jixunying.engine.wolf

import com.guixing.jixunying.model.WolfGameRecord
import kotlinx.serialization.json.Json
import java.io.File

/** 每局一个文件 <数据目录>/wolf/<局编号>.json（设计说明第 4 节）。先写临时文件再换名，写到一半断电也不会读坏。 */
class WolfStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    fun save(r: WolfGameRecord) = synchronized(lock) {
        dir.mkdirs()
        val tmp = File(dir, r.id + ".json.tmp")
        tmp.writeText(json.encodeToString(WolfGameRecord.serializer(), r), Charsets.UTF_8)
        val out = File(dir, r.id + ".json")
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
    }

    fun load(id: String): WolfGameRecord? = synchronized(lock) {
        val f = File(dir, "$id.json")
        if (!f.exists()) null else runCatching { json.decodeFromString(WolfGameRecord.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** 全部局，新的在前（读坏的跳过）。 */
    fun all(): List<WolfGameRecord> = synchronized(lock) {
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            runCatching { json.decodeFromString(WolfGameRecord.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull()
        }.sortedByDescending { it.startedAt }
    }
}
