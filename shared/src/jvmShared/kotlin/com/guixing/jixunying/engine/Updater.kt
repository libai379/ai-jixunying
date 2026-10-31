package com.guixing.jixunying.engine

import com.guixing.jixunying.model.APP_VERSION
import com.guixing.jixunying.model.UpdateInfo
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest

/**
 * 检查更新（2026-10-30 用户：「如果安装了这个应用，我从关于里边去更新，需不需要服务器？」——不要，查 GitHub 发行版）。
 *
 * - 查：GET https://api.github.com/repos/libai379/ai-jixunying/releases/latest（不登录每小时 60 次，够用），
 *   tag_name 比本机 APP_VERSION 新就算有新版。每个文件 GitHub 都给了 digest（"sha256:…"，2026-10-30 用 gh api 实测 v1.12.1 两个文件都有，
 *   和发版时本地算的一致），下完拿它核对，对不上就删掉不用。
 * - 挑文件：手机要 .apk；电脑能原地换（程序文件夹能写）只要整包 -windows-update.zip（发版时和安装程序一起传，没传就让用户去网页，不退回安装程序），
 *   程序文件夹写不进去才要 .exe 安装程序。
 * - 网：先走设置里的代理，不通再直连（手机上多半没有那个代理）；连本机地址（测试）不走代理。
 * 下载的文件放 <数据目录>/updates/，只留最新的一个。
 */
class Updater(
    private val dir: File,
    private val proxy: () -> String?,
    private val isPhone: Boolean,
    /** 电脑上：程序文件夹能不能原地换（装好的程序、文件夹能写）。 */
    private val canSwap: () -> Boolean = { false },
    private val api: String = System.getProperty("jxy.update.api") ?: LATEST_API,
    /** 本机版本（测试、真机演练时用 -Djxy.version 假装是旧版）。 */
    val current: String = System.getProperty("jxy.version") ?: APP_VERSION,
) {
    companion object {
        const val LATEST_API = "https://api.github.com/repos/libai379/ai-jixunying/releases/latest"
        /** 只认本项目发行版的下载地址（电脑替手机代下时用，免得手机让电脑去下别的东西）。测试里换成假服务器（-Djxy.update.prefix）。 */
        val DOWNLOAD_PREFIX: String get() = System.getProperty("jxy.update.prefix") ?: "https://github.com/libai379/ai-jixunying/releases/download/"
        /** 安装包最大多少（防着拿到奇怪的东西把盘塞满）。 */
        const val MAX_SIZE = 600L * 1024 * 1024

        /** "v1.12.1" → [1, 12, 1]；认不出的段当 0。 */
        fun parseVersion(v: String): List<Int> =
            v.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

        /** a 比 b 新。 */
        fun newer(a: String, b: String): Boolean {
            val x = parseVersion(a)
            val y = parseVersion(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        /** 从 GitHub 的发行版 JSON 里拿这台设备要的东西。 */
        fun parse(json: JsonObject, isPhone: Boolean, canSwap: Boolean): UpdateInfo {
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val tag = json.s("tag_name")
            val assets = (json["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun find(test: (String) -> Boolean) = assets.firstOrNull { test(it.s("name").lowercase()) }
            val (asset, kind) = when {
                isPhone -> find { it.endsWith(".apk") } to "apk"
                // 能原地换的电脑只要整包：这一版没传整包也不退回安装程序——用户那台电脑跑的是复制过去的程序文件夹，跑安装程序会另装一份，
                // 桌面图标、开机自启还指着旧的（10-30）。没有整包就让用户去发行版网页
                canSwap -> find { it.endsWith(".zip") && "windows" in it } to "zip"
                else -> find { it.endsWith(".exe") } to "exe"
            }
            return UpdateInfo(
                version = tag.removePrefix("v"),
                tag = tag,
                name = json.s("name"),
                notes = json.s("body"),
                publishedAt = json.s("published_at"),
                pageUrl = json.s("html_url"),
                assetName = asset?.s("name").orEmpty(),
                assetUrl = asset?.s("browser_download_url").orEmpty(),
                assetSize = (asset?.get("size") as? JsonPrimitive)?.longOrNull ?: 0,
                sha256 = asset?.s("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase().orEmpty(),
                kind = if (asset == null) "" else kind,
            )
        }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** 连不上的原因给人看：有的异常没有说明文字（域名解析不了、连不上），就写是哪一类。 */
        fun why(e: Throwable?): String = when (e) {
            null -> "不知道为什么"
            is java.net.UnknownHostException -> "找不到 ${e.message.orEmpty()}：这台设备的网解析不了域名"
            is java.net.ConnectException -> "连不上：${e.message.orEmpty().take(60)}"
            is java.net.SocketTimeoutException, is io.ktor.client.plugins.HttpRequestTimeoutException -> "等太久没回应"
            is javax.net.ssl.SSLException -> "加密连接没建成：${e.message.orEmpty().take(60)}"
            else -> (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(80)
        }

        private fun loopback(url: String) = Regex("""^https?://(127\.0\.0\.1|localhost|\[::1])[:/]""").containsMatchIn(url)
    }

    /**
     * 先走代理、不通再直连（null = 直连）；连本机地址不走代理。
     * 10-30 模拟器上实测出过错：以前写成 listOfNotNull(代理, null)，把代表直连的 null 也滤掉了——没设代理的手机一条线路都没有、根本不去连。
     */
    internal fun routes(url: String): List<String?> = buildList {
        val p = proxy()?.trim()?.takeIf { it.isNotEmpty() }
        if (p != null && !loopback(url)) add(p)
        add(null)
    }

    /** 查最新的一版。查不到抛异常（说法给人看）。 */
    suspend fun check(): UpdateInfo {
        var last: Throwable? = null
        for (route in routes(api)) {
            try {
                var body = ""
                Http.client(route).prepareGet(api) {
                    timeout { requestTimeoutMillis = 30_000 }
                    header("Accept", "application/vnd.github+json")
                    header("User-Agent", "AIJixunying/$current")
                }.execute { resp ->
                    body = resp.bodyAsText()
                    if (resp.status.value == 404) throw IllegalStateException("GitHub 上还没有发行版")
                    if (resp.status.value == 403 || resp.status.value == 429) throw IllegalStateException("GitHub 说查得太勤了，过一会儿再查（不登录每小时 60 次）")
                    if (resp.status.value !in 200..299) throw ApiException(resp.status.value, body)
                }
                val json = Json.parse(body) as? JsonObject ?: throw IllegalStateException("GitHub 返回的不是发行版信息")
                return parse(json, isPhone, canSwap())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
        }
        throw IllegalStateException("连不上 GitHub（${why(last)}）")
    }

    /** 已经下好、核对过的那个文件（没有返回 null）。 */
    fun ready(info: UpdateInfo): File? =
        File(dir, info.assetName).takeIf { info.assetName.isNotEmpty() && it.isFile && info.sha256.isNotEmpty() && sha256(it) == info.sha256 }

    /**
     * 下载这台设备要的那个文件，核对 SHA-256；对不上删掉、抛异常。onProgress 给 0..1。
     * 发行版没给 SHA-256 的不下（核对不了就不装）。
     */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        require(info.assetUrl.isNotEmpty()) { "这一版没有这台设备能用的文件" }
        if (info.sha256.isEmpty()) throw IllegalStateException("发行版没给这个文件的校验值，不敢装；去发行版网页手动下载吧")
        if (info.assetSize > MAX_SIZE) throw IllegalStateException("文件太大（${info.assetSize / 1024 / 1024} MB），不像是安装包")
        ready(info)?.let { onProgress(1f); return@withContext it }
        dir.mkdirs()
        val target = File(dir, info.assetName)
        val part = File(dir, info.assetName + ".part")
        var last: Throwable? = null
        for (route in routes(info.assetUrl)) {
            try {
                fetch(route, info.assetUrl, part, info.assetSize, onProgress)
                val got = sha256(part)
                if (got != info.sha256) {
                    part.delete()
                    throw IllegalStateException("下下来的文件和 GitHub 给的校验值对不上（可能下坏了），已删掉，再试一次")
                }
                target.delete()
                if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
                // 只留这一个
                dir.listFiles()?.filter { it.isFile && it.name != target.name }?.forEach { it.delete() }
                return@withContext target
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                part.delete()
                last = e
            }
        }
        throw IllegalStateException("下载没成功（${why(last)}）")
    }

    /** 边下边写进文件，带进度。GitHub 的下载地址会跳到 release-assets.githubusercontent.com，客户端自己跟着跳。 */
    private suspend fun fetch(route: String?, url: String, out: File, expected: Long, onProgress: (Float) -> Unit) {
        Http.client(route).prepareGet(url) {
            // 一百多 MB 慢网也得下得完；卡住不动由 socket 超时管
            timeout { requestTimeoutMillis = 2 * 60 * 60_000L }
            header("User-Agent", "AIJixunying/$current")
        }.execute { resp ->
            if (resp.status.value !in 200..299) throw ApiException(resp.status.value, "下载失败")
            val total = resp.headers["Content-Length"]?.toLongOrNull()?.takeIf { it > 0 } ?: expected
            if (total > MAX_SIZE) throw IllegalStateException("文件太大，不像是安装包")
            val buf = ByteArray(1 shl 16)
            var done = 0L
            var shown = -1
            resp.bodyAsChannel().toInputStream().use { input -> out.outputStream().use { os ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    if (done > MAX_SIZE) throw IllegalStateException("文件太大，不像是安装包")
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown) { shown = pct; onProgress((done.toFloat() / total).coerceAtMost(1f)) }
                    }
                }
            } }
        }
    }

    /** 别处拿来的整个文件（手机让电脑代下、分段取回来的）：核对 SHA-256，对上了放进 updates/ 当成已下好。 */
    fun adoptBytes(bytes: ByteArray, info: UpdateInfo): File {
        if (info.sha256.isEmpty()) throw IllegalStateException("没有校验值，不敢用")
        dir.mkdirs()
        val part = File(dir, info.assetName + ".part")
        part.writeBytes(bytes)
        if (sha256(part) != info.sha256) { part.delete(); throw IllegalStateException("从电脑取回来的文件和 GitHub 给的校验值对不上，没用它") }
        val target = File(dir, info.assetName)
        target.delete()
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
        dir.listFiles()?.filter { it.isFile && it.name != target.name }?.forEach { it.delete() }
        return target
    }
}
