package com.guixing.jixunying.engine.code

import java.io.File

/**
 * Code 栏改文件之前存原样（docs/Code栏设计说明.md 第 4 节第 4 条，安全核心，写代码的 AI 只调用不改）。
 * 一个会话一个文件夹：<数据目录>/code/<对话 id>/。每处改动按 changeId 存一份：
 * - <changeId>.orig：改之前的内容（原样字节）
 * - <changeId>.new：原来没有这个文件（还原 = 删掉它），里面不放东西
 * 同一个文件在一次回答里被改好几次，每次各存一份，还原时按改动从后往前还原就能回到最早的样子。
 */
class CodeBackup(private val dir: File) {

    /** 改之前调用。返回 true = 原来没有这个文件（是新建的）。已经存过的 changeId 不会覆盖。 */
    fun save(changeId: String, file: File): Boolean {
        require(SAFE_ID.matches(changeId)) { "changeId 不合法" }
        dir.mkdirs()
        val orig = File(dir, "$changeId.orig")
        val created = File(dir, "$changeId.new")
        if (orig.exists() || created.exists()) return created.exists()
        return if (file.isFile) {
            file.copyTo(orig)
            false
        } else {
            created.writeText("")
            true
        }
    }

    /** 改之前的内容；原来没有这个文件返回 null。 */
    fun original(changeId: String): ByteArray? = File(dir, "$changeId.orig").takeIf { it.isFile }?.readBytes()

    fun has(changeId: String): Boolean = File(dir, "$changeId.orig").exists() || File(dir, "$changeId.new").exists()

    /** 还原成改之前的样子：原来没有的文件删掉，有的写回原样。没有这份备份返回 false。 */
    fun restore(changeId: String, file: File): Boolean {
        val orig = File(dir, "$changeId.orig")
        val created = File(dir, "$changeId.new")
        return when {
            orig.isFile -> { file.parentFile?.mkdirs(); orig.copyTo(file, overwrite = true); true }
            created.exists() -> { if (file.isFile) file.delete(); true }
            else -> false
        }
    }

    /** 删会话时一起删。 */
    fun deleteAll() { dir.deleteRecursively() }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
