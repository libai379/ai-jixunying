package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.DocHit
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class DocEntry(
    val path: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    /** 抽出的文字有多少字；0 = 没抽出来（只能按文件名找）。 */
    val chars: Int = 0,
    val note: String = "",
)

@Serializable
private data class DocIndexFile(val entries: List<DocEntry> = emptyList(), val scannedAt: Long = 0)

/**
 * 本机文档库：把文件夹里的文档（PDF、Word、Excel、PPT、文本……）抽成文字存起来，AI 能搜、能读。
 * 存在 <数据目录>\docindex\：index.json 是清单，text\<哈希>.txt 是抽出来的文字。
 * 只看文件修改时间和大小，没变的不重抽。搜索时逐个文档比对（几千个文档以内够快），常用的文字留在内存里。
 */
class DocLibrary(private val dir: File) {

    companion object {
        /** 收录的文档类型。老版 Office（doc、xls、ppt）抽不出文字，但能按文件名找到。 */
        val docExt = setOf(
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "wps", "et", "dps",
            "txt", "md", "markdown", "csv", "tsv", "rtf", "html", "htm",
        )
        // json、xml、log 大多是程序的配置和日志，收进来全是噪音（手机上实测 /sdcard 根目录就有这类文件）

        /** 不进去的文件夹：程序、缓存、代码仓库的生成物，以及微信的文件夹（要单独打开开关才收录）。 */
        private val skipDirs = setOf(
            "node_modules", ".git", ".svn", ".hg", ".gradle", ".idea", ".vscode", "build", "dist", "target", "out",
            "__pycache__", ".cache", "cache", "caches", "appdata", "\$recycle.bin", "system volume information",
            "windows", "program files", "program files (x86)", "programdata", ".thumbnails", "thumbnails",
            "android", "wechat files", "xwechat_files", "tencent files", "my games",
        )
        const val MAX_FILES = 20_000
        const val MAX_EXTRACT_BYTES = 30L * 1024 * 1024
        const val MAX_TEXT_CHARS = 400_000

        fun folderOf(path: String): String {
            val i = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            return if (i > 0) path.substring(0, i) else ""
        }
    }

    private val textDir = File(dir, "text").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    @Volatile private var entries: Map<String, DocEntry> = load()
    @Volatile var scannedAt: Long = 0
        private set
    private val scanning = AtomicBoolean(false)
    val isScanning get() = scanning.get()

    /** 内存里留着的文字，总量有上限（电脑约 64MB，手机按可用内存的八分之一）。 */
    private val cacheLimit = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)
    private val cache = LinkedHashMap<String, String>(256, 0.75f, true)
    private var cacheBytes = 0L
    private val cacheLock = Any()

    private fun cachePut(path: String, t: String) = synchronized(cacheLock) {
        cache.put(path, t)?.let { cacheBytes -= it.length * 2L }
        cacheBytes += t.length * 2L
        val it = cache.entries.iterator()
        while (cacheBytes > cacheLimit && it.hasNext()) {
            val e = it.next()
            if (e.key == path) continue
            cacheBytes -= e.value.length * 2L
            it.remove()
        }
    }

    private fun cacheRemove(path: String) = synchronized(cacheLock) { cache.remove(path)?.let { cacheBytes -= it.length * 2L } }

    private fun load(): Map<String, DocEntry> = runCatching {
        val f = AppJson.decodeFromString(DocIndexFile.serializer(), indexFile.readText(Charsets.UTF_8))
        scannedAt = f.scannedAt
        f.entries.associateBy { it.path }
    }.getOrDefault(emptyMap())

    private fun save() = runCatching {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(AppJson.encodeToString(DocIndexFile.serializer(), DocIndexFile(entries.values.toList(), scannedAt)), Charsets.UTF_8)
        java.nio.file.Files.move(tmp.toPath(), indexFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    val count: Int get() = entries.size

    private fun textFile(path: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return File(textDir, "$h.txt")
    }

    fun text(path: String): String? {
        synchronized(cacheLock) { cache[path]?.let { return it } }
        val t = textFile(path).takeIf { it.isFile }?.readText(Charsets.UTF_8) ?: return null
        cachePut(path, t)
        return t
    }

    /**
     * 扫一遍：新文件和改过的抽文字，删掉的移出索引。onProgress 大约每秒报一次。
     * 已经在扫就直接返回 false。
     */
    fun scan(roots: List<File>, onProgress: (String, Int) -> Unit): Boolean {
        if (!scanning.compareAndSet(false, true)) return false
        try {
            val seen = HashSet<String>()
            val next = ConcurrentHashMap(entries)
            var lastReport = 0L
            var changed = 0
            fun report(text: String) {
                val now = System.currentTimeMillis()
                if (now - lastReport > 1000) { lastReport = now; onProgress(text, next.size) }
            }
            fun walk(d: File, depth: Int) {
                if (depth > 14 || seen.size >= MAX_FILES) return
                val kids = d.listFiles() ?: return
                report("正在扫描 ${d.path}")
                for (f in kids) {
                    if (seen.size >= MAX_FILES) return
                    val name = f.name
                    if (name.startsWith(".") || name.startsWith("~$")) continue
                    if (f.isDirectory) {
                        if (name.lowercase() in skipDirs) continue
                        if (runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)) continue
                        walk(f, depth + 1)
                        continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in docExt) continue
                    val path = f.path
                    if (!seen.add(path)) continue
                    val size = f.length()
                    val mtime = f.lastModified()
                    val old = next[path]
                    if (old != null && old.size == size && old.mtime == mtime) continue
                    next[path] = extractOne(f, ext, size, mtime)
                    changed++
                    report("正在读 ${f.name}")
                }
            }
            for (r in roots.distinctBy { it.path }) if (r.isDirectory) walk(r, 0)
            // 不在任何收录文件夹里、或者已经删掉的，移出索引
            val gone = next.keys.filter { it !in seen }
            gone.forEach { p -> next.remove(p); textFile(p).delete(); cacheRemove(p) }
            entries = HashMap(next)
            scannedAt = System.currentTimeMillis()
            if (changed > 0 || gone.isNotEmpty() || !indexFile.exists()) save()
            onProgress("", entries.size)
            return true
        } finally {
            scanning.set(false)
        }
    }

    private fun extractOne(f: File, ext: String, size: Long, mtime: Long): DocEntry {
        val base = DocEntry(f.path, f.name, size, mtime)
        if (size > MAX_EXTRACT_BYTES) return base.copy(note = "文件太大（${size / 1024 / 1024}MB），只能按文件名找")
        if (ext in setOf("doc", "xls", "ppt", "wps", "et", "dps")) return base.copy(note = "老格式，读不出文字，只能按文件名找")
        return try {
            val (text, note) = DocExtract.extract(f.name, f.readBytes())
            if (text.isNullOrBlank()) base.copy(note = note.ifBlank { "读不出文字" })
            else {
                val t = text.take(MAX_TEXT_CHARS)
                textFile(f.path).writeText(t, Charsets.UTF_8)
                cacheRemove(f.path)
                base.copy(chars = t.length, note = if (text.length > MAX_TEXT_CHARS) "很长，只收了前 ${MAX_TEXT_CHARS / 10_000} 万字" else "")
            }
        } catch (e: Throwable) {
            base.copy(note = "读取失败：${e.message?.take(60)}")
        }
    }

    private fun hit(e: DocEntry, snippet: String) = DocHit(e.path, e.name, folderOf(e.path), e.size, e.mtime, e.chars, snippet, e.note)

    /** 最近改过的文档。 */
    fun recent(limit: Int): List<DocHit> = entries.values.sortedByDescending { it.mtime }.take(limit).map { hit(it, "") }

    /** 按文件名和内容搜。文件名命中的分数高；同分的新文件在前。 */
    fun search(query: String, limit: Int): List<DocHit> {
        val terms = Recorder.terms(query)
        if (terms.isEmpty()) return recent(limit)
        val need = if (terms.size >= 4) 2 else 1
        val scored = ArrayList<Pair<Int, DocHit>>()
        for (e in entries.values) {
            val nameScore = Recorder.score(e.name, terms) * 4 + Recorder.score(folderOf(e.path), terms)
            var textScore = 0
            var snippet = ""
            if (e.chars > 0) {
                val t = text(e.path)
                if (t != null) {
                    textScore = Recorder.score(t, terms)
                    if (textScore > 0) snippet = Recorder.snippet(t, terms, 140)
                }
            }
            val total = nameScore + textScore
            if (total >= need) scored += total to hit(e, snippet)
        }
        return scored.sortedWith(compareByDescending<Pair<Int, DocHit>> { it.first }.thenByDescending { it.second.mtime }).take(limit).map { it.second }
    }

    /** 找文档：完整路径，或者只给文件名（重名取最新的）。 */
    fun find(pathOrName: String): DocEntry? {
        val key = pathOrName.trim().trim('"', '《', '》')
        return entries[key] ?: entries.values.filter { it.name == key || it.path.endsWith(key) }.maxByOrNull { it.mtime }
    }

    fun isIndexed(path: String) = entries.containsKey(path)
}
