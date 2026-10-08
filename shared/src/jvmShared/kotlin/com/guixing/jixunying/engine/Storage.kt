package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Attachment
import com.guixing.jixunying.model.Message
import com.guixing.jixunying.model.StanceTopic
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 全部数据存本机：%APPDATA%\ai-jixunying\
 *   state.json            服务商、成员、设置、会话列表
 *   convs\<id>.json       每个会话的聊天记录
 *   files\<id>            附件和生成的图片，旁边 <id>.json 是元数据
 *   stances.json          立场档案（群聊里各位成员的立场和改口）
 * 读坏了的文件不覆盖，改名成 .broken-时间 留着，再从空白开始。
 */
class Storage(val root: File) {
    private val convDir = File(root, "convs")
    val fileDir = File(root, "files")
    val brokenNotes = mutableListOf<String>()

    init {
        convDir.mkdirs()
        fileDir.mkdirs()
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun quarantine(f: File, e: Throwable) {
        val bak = File(f.parentFile, f.name + ".broken-" + System.currentTimeMillis())
        f.renameTo(bak)
        brokenNotes += "${f.name} 读不出来（${e.message?.take(80)}），已改名为 ${bak.name} 保留，没有覆盖。"
    }

    fun loadState(): AppState? {
        val f = File(root, "state.json")
        if (!f.exists()) return null
        return try {
            AppJson.decodeFromString(AppState.serializer(), f.readText(Charsets.UTF_8))
        } catch (e: Throwable) {
            quarantine(f, e); null
        }
    }

    @Synchronized
    fun saveState(state: AppState) = writeAtomic(File(root, "state.json"), AppJson.encodeToString(AppState.serializer(), state))

    private val listSer = ListSerializer(Message.serializer())

    fun loadMessages(convId: String): MutableList<Message> {
        val f = File(convDir, "$convId.json")
        if (!f.exists()) return mutableListOf()
        return try {
            AppJson.decodeFromString(listSer, f.readText(Charsets.UTF_8)).toMutableList()
        } catch (e: Throwable) {
            quarantine(f, e); mutableListOf()
        }
    }

    fun saveMessages(convId: String, list: List<Message>) = synchronized(this) {
        writeAtomic(File(convDir, "$convId.json"), AppJson.encodeToString(listSer, list))
    }

    fun deleteConversation(convId: String, attachmentIds: List<String>) {
        File(convDir, "$convId.json").delete()
        File(convDir, "$convId.memo.json").delete()
        attachmentIds.forEach { deleteFile(it) }
    }

    /** 记录员给这个对话写的摘要（没有就是空的）。 */
    fun loadMemo(convId: String): ConvMemo {
        val f = File(convDir, "${safe(convId)}.memo.json")
        if (!f.exists()) return ConvMemo()
        return runCatching { AppJson.decodeFromString(ConvMemo.serializer(), f.readText(Charsets.UTF_8)) }.getOrElse { ConvMemo() }
    }

    fun saveMemo(convId: String, memo: ConvMemo) = synchronized(this) {
        writeAtomic(File(convDir, "${safe(convId)}.memo.json"), AppJson.encodeToString(ConvMemo.serializer(), memo))
    }

    private val stanceSer = ListSerializer(StanceTopic.serializer())

    /** 立场档案（全部对话的议题）。读坏了改名留着，从空白开始。 */
    fun loadStances(): MutableList<StanceTopic> {
        val f = File(root, "stances.json")
        if (!f.exists()) return mutableListOf()
        return try {
            AppJson.decodeFromString(stanceSer, f.readText(Charsets.UTF_8)).toMutableList()
        } catch (e: Throwable) {
            quarantine(f, e); mutableListOf()
        }
    }

    fun saveStances(list: List<StanceTopic>) = synchronized(this) {
        writeAtomic(File(root, "stances.json"), AppJson.encodeToString(stanceSer, list))
    }

    fun putFile(att: Attachment, bytes: ByteArray, extractedText: String?) {
        File(fileDir, att.id).writeBytes(bytes)
        writeAtomic(File(fileDir, att.id + ".json"), AppJson.encodeToString(Attachment.serializer(), att))
        if (extractedText != null) File(fileDir, att.id + ".txt").writeText(extractedText, Charsets.UTF_8)
    }

    fun fileMeta(id: String): Attachment? = runCatching {
        AppJson.decodeFromString(Attachment.serializer(), File(fileDir, "$id.json").readText(Charsets.UTF_8))
    }.getOrNull()

    fun fileBytes(id: String): ByteArray? = File(fileDir, safe(id)).takeIf { it.isFile }?.readBytes()

    fun fileText(id: String): String? = File(fileDir, safe(id) + ".txt").takeIf { it.isFile }?.readText(Charsets.UTF_8)

    fun deleteFile(id: String) {
        listOf("", ".json", ".txt").forEach { File(fileDir, safe(id) + it).delete() }
    }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }

    companion object {
        fun defaultRoot(): File {
            System.getProperty("jxy.data")?.let { return File(it) }
            val appData = System.getenv("APPDATA") ?: (System.getProperty("user.home") + "/.config")
            return File(appData, "ai-jixunying")
        }
    }
}
