package com.guixing.jixunying.engine.code

import java.io.File

/**
 * Code 栏连 GitHub（2026-10-18，用户：「能不能连接 github，开发完帮忙上传」）。
 *
 * 照 Claude 桌面 App：开发完点「Create PR」，它 push 分支、用 GitHub 官方命令行 gh 开 PR；要求本机装了 gh 并登录
 * （https://code.claude.com/docs/en/desktop ——「PR monitoring requires the GitHub CLI (gh) to be installed and authenticated」）。
 * 我们多一样：项目还不是 Git 仓库的，一键 git init + 在 GitHub 上建仓库并上传（gh repo create --source . --push）。
 *
 * 命令都用 Code 栏的执行器在项目文件夹里跑。执行器不给子进程代理变量（防 Key 泄露），连 GitHub 要代理的话
 * 在命令前面临时设上设置里的代理（只设 HTTP(S)_PROXY，不带任何 Key）。
 */
class GitHub(private val runner: CodeRunner, private val shell: String, private val proxy: String, private val ghPath: String) {

    class Result(val ok: Boolean, val text: String)

    private val ps = shell.trim().lowercase().let { it.isEmpty() || it == "powershell" }

    private fun withProxy(cmd: String): String {
        val px = proxy.trim().ifBlank { return cmd }.let { if (it.startsWith("http")) it else "http://$it" }
        return if (ps) "\$env:HTTPS_PROXY='$px'; \$env:HTTP_PROXY='$px'; $cmd" else "set HTTPS_PROXY=$px&& set HTTP_PROXY=$px&& $cmd"
    }

    /** gh 怎么叫：设置里填了路径就用它，否则靠 PATH 找 gh。 */
    private fun gh(args: String): String = ghPath.trim().let { p ->
        if (p.isEmpty()) "gh $args" else if (ps) "& '${p.replace("'", "''")}' $args" else "\"$p\" $args"
    }

    suspend fun run(cmd: String, dir: File, timeoutSec: Int = 120): Result {
        val r = runner.run(withProxy(cmd), dir, shell, timeoutSec, 20_000)
        val tail = if (r.timedOut) "\n（超时了）" else ""
        return Result(r.exitCode == 0, r.output.trim() + tail)
    }

    /** git、gh 装没装、登没登录。返回给人看的几行字。 */
    suspend fun status(dir: File): Pair<Boolean, String> {
        val git = run("git --version", dir, 30)
        val ghV = run(gh("--version"), dir, 30)
        val auth = if (ghV.ok) run(gh("auth status"), dir, 60) else null
        val user = auth?.text?.let { Regex("account (\\S+)").find(it)?.groupValues?.get(1) ?: Regex("as (\\S+)").find(it)?.groupValues?.get(1) }
        val lines = buildList {
            add(if (git.ok) "Git：${git.text.lineSequence().first()}" else "Git：没找到（先装 Git：https://git-scm.com/downloads）")
            add(if (ghV.ok) "GitHub 命令行（gh）：${ghV.text.lineSequence().first()}" else "GitHub 命令行（gh）：没找到（装一个：https://cli.github.com/ ，或者在下面填 gh.exe 的位置）")
            if (auth != null) add(if (auth.ok) "已登录 GitHub" + (user?.let { "：$it" } ?: "") else "还没登录 GitHub：点「登录 GitHub」")
        }
        return (git.ok && ghV.ok && auth?.ok == true) to lines.joinToString("\n")
    }

    /** 项目文件夹的 Git 状态：是不是仓库、分支、改了哪些文件、远端。 */
    suspend fun repo(dir: File): Map<String, String> {
        val inside = run("git rev-parse --is-inside-work-tree", dir, 30)
        if (!inside.ok || "true" !in inside.text) return mapOf("repo" to "false")
        val branch = run("git branch --show-current", dir, 30).text.trim()
        val changes = run("git status --porcelain", dir, 30).text.trim()
        val remote = run("git remote get-url origin", dir, 30).let { if (it.ok) it.text.trim() else "" }
        val ahead = run("git rev-list --count '@{u}..HEAD'", dir, 30).let { if (it.ok) it.text.trim() else "" }
        return mapOf("repo" to "true", "branch" to branch, "changes" to changes, "remote" to remote, "ahead" to ahead)
    }

    /** 提交全部改动（信息写进临时文件再 -F，免得引号、换行在外壳里出问题）。没改动就说没改动。 */
    suspend fun commit(dir: File, message: String): Result {
        val add = run("git add -A", dir)
        if (!add.ok) return add
        if (run("git status --porcelain", dir, 30).text.isBlank()) return Result(true, "没有要提交的改动")
        val f = File.createTempFile("jxy-commit", ".txt").apply { writeText(message.ifBlank { "更新" }, Charsets.UTF_8) }
        return try { run("git commit -F \"${f.absolutePath}\"", dir) } finally { f.delete() }
    }

    suspend fun push(dir: File): Result = run("git push -u origin HEAD", dir, 300)

    /** 开 PR：标题、说明用提交记录自动填（gh pr create --fill）。 */
    suspend fun pr(dir: File): Result = run(gh("pr create --fill"), dir, 300)

    /** 还不是仓库的：git init、提交、在 GitHub 上建仓库并上传。 */
    suspend fun create(dir: File, name: String, private: Boolean, message: String): Result {
        val out = StringBuilder()
        if (!(run("git rev-parse --is-inside-work-tree", dir, 30).text.contains("true"))) {
            val init = run("git init -b main", dir)
            out.appendLine(init.text)
            if (!init.ok) return Result(false, out.toString())
        }
        val c = commit(dir, message)
        out.appendLine(c.text)
        if (!c.ok) return Result(false, out.toString())
        val safe = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-').ifBlank { "my-project" }
        val r = run(gh("repo create $safe ${if (private) "--private" else "--public"} --source . --remote origin --push"), dir, 300)
        out.appendLine(r.text)
        return Result(r.ok, out.toString().trim())
    }

    /**
     * PR 的检查状态（2026-10-18，开发补齐第 9 样；Claude 桌面 App 的 CI status bar：「Claude Code uses the GitHub CLI to poll check results」）。
     * 返回（PR 网址, 状态：OPEN / MERGED / CLOSED, 每项检查「名字：结果」）；没有 PR 返回 null。
     */
    suspend fun prChecks(dir: File): Triple<String, String, List<Pair<String, String>>>? {
        val r = run(gh("pr view --json url,state,statusCheckRollup"), dir, 60)
        if (!r.ok) return null
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(r.text.substring(r.text.indexOf('{'))) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return null
        fun s(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val checks = (o["statusCheckRollup"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val name = s(c["name"]).ifBlank { s(c["context"]) }
            // CheckRun：status 没完就是 IN_PROGRESS / QUEUED，完了看 conclusion；StatusContext 看 state
            val result = s(c["conclusion"]).ifBlank { s(c["state"]) }.ifBlank { s(c["status"]) }.uppercase()
            name to result
        }
        return Triple(s(o["url"]), s(o["state"]), checks)
    }

    /** 最近一次失败的检查日志（只要失败的那几步，最后 15000 字）。 */
    suspend fun failedLog(dir: File): String {
        val list = run(gh("run list --limit 5 --json databaseId,conclusion,headBranch"), dir, 60)
        val branch = run("git branch --show-current", dir, 30).text.trim()
        val arr = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(list.text.substring(list.text.indexOf('['))) as kotlinx.serialization.json.JsonArray }.getOrNull()
            ?: return "取不到检查记录：" + list.text.take(300)
        val id = arr.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.firstOrNull {
            (it["conclusion"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "failure" &&
                (branch.isEmpty() || (it["headBranch"] as? kotlinx.serialization.json.JsonPrimitive)?.content == branch)
        }?.get("databaseId")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: return "最近没有失败的检查。"
        return run(gh("run view $id --log-failed"), dir, 120).text.takeLast(15_000)
    }

    /** 全过了自动合并（squash；仓库设置里要先打开自动合并）。 */
    suspend fun autoMerge(dir: File): Result = run(gh("pr merge --auto --squash"), dir, 120)

    /** 开一个控制台窗口跑 gh auth login --web（要用户在浏览器里点同意，程序替不了）。 */
    fun openLoginWindow(dir: File): Boolean = runCatching {
        val cmd = withProxy(gh("auth login --web --git-protocol https"))
        val pb = if (ps) ProcessBuilder("cmd.exe", "/c", "start", "\"登录 GitHub\"", "powershell", "-NoExit", "-Command", cmd)
        else ProcessBuilder("cmd.exe", "/c", "start", "\"登录 GitHub\"", "cmd", "/k", cmd)
        pb.directory(dir).start()
        true
    }.getOrDefault(false)
}
