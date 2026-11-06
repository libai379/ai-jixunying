package com.guixing.jixunying

import com.guixing.jixunying.engine.Updater
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 手机先走国内镜像（Gitee 发行版，2026-11-06）。
 * 假的 Gitee：releases/latest 的字段照 2026-11-06 不登录查 clear-as-daylight/ai-jixunying 实测——assets 只有 name、browser_download_url，
 * 还自动带两个源码包（v1.19.1.zip、v1.19.1.tar.gz）；下载地址 302 跳到 foruda.gitee.com。校验值只在发行说明里。
 */
class GiteeMirrorTest {
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File
    private val apk = ByteArray(250_000) { (it * 13 + 5).toByte() }
    private val giteeHits = AtomicInteger()
    private val githubHits = AtomicInteger()
    @Volatile private var mirrorDown = false
    @Volatile private var mirrorCorrupt = false
    @Volatile private var mirrorNoSha = false

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun base() = "http://127.0.0.1:$port"
    private val name = "AI-Jixunying-9.9.9-20261106-android.apk"

    private fun giteeRelease(): String {
        val body = "**AI集训营 9.9.9**\\n\\n## Download\\n\\nSHA-256：\\n- AI-Jixunying-9.9.9-20261106-windows.exe：${"1".repeat(64)}\\n" +
            (if (mirrorNoSha) "" else "- $name：${sha(apk)}\\n")
        return """{"id":1,"tag_name":"v9.9.9","target_commitish":"master","prerelease":false,"name":"AI集训营 9.9.9","body":"$body",
            "created_at":"2026-11-06T10:00:00+08:00","assets":[
              {"name":"$name","browser_download_url":"${base()}/gitee/download/v9.9.9/$name"},
              {"name":"v9.9.9.zip","browser_download_url":"${base()}/gitee/archive/v9.9.9.zip"},
              {"name":"v9.9.9.tar.gz","browser_download_url":"${base()}/gitee/archive/v9.9.9.tar.gz"}]}"""
    }

    private fun githubRelease() = """{"tag_name":"v9.9.9","name":"AI集训营 9.9.9","published_at":"2026-11-06T02:00:00Z","html_url":"https://github.com/x","body":"gh",
        "assets":[{"name":"$name","size":${apk.size},"digest":"sha256:${sha(apk)}","browser_download_url":"${base()}/gh/download/v9.9.9/$name"}]}"""

    @BeforeTest
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        dir = kotlin.io.path.createTempDirectory("jxy-gitee").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                get("/gitee/latest") {
                    if (mirrorDown) call.respondText("bad gateway", status = HttpStatusCode.BadGateway)
                    else call.respondText(giteeRelease(), ContentType.Application.Json)
                }
                get("/gitee/download/{tag}/{name}") { call.respondRedirect("${base()}/foruda/${call.parameters["name"]}") }
                get("/foruda/{name}") {
                    giteeHits.incrementAndGet()
                    val b = if (mirrorCorrupt) apk.copyOf().also { it[0] = (it[0] + 1).toByte() } else apk
                    call.respondBytes(b, ContentType.parse("application/vnd.android.package-archive"))
                }
                get("/gh/latest") { call.respondText(githubRelease(), ContentType.Application.Json) }
                get("/gh/download/{tag}/{name}") {
                    githubHits.incrementAndGet()
                    call.respondBytes(apk, ContentType.parse("application/vnd.android.package-archive"))
                }
            }
        }.start(wait = false)
        System.setProperty("jxy.update.prefix", "${base()}/gh/download/")
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("jxy.update.prefix")
        fake.stop(100, 500)
        dir.deleteRecursively()
    }

    private fun phone(sub: String) = Updater(File(dir, sub), proxy = { null }, isPhone = true, api = "${base()}/gh/latest",
        current = "1.0.0", mirrorApi = "${base()}/gitee/latest")

    @Test
    fun parsesGiteeReleaseAndReadsShaFromNotes() {
        val info = Updater.parseMirror(Json.parseToJsonElement(giteeRelease()).jsonObject)!!
        assertEquals("9.9.9", info.version)
        assertEquals(name, info.assetName)
        assertEquals(sha(apk), info.sha256)
        assertEquals("apk", info.kind)
        assertEquals("${base()}/gitee/download/v9.9.9/$name", info.mirrorUrl)
        // 电脑代下只认 GitHub 的地址：按 GitHub 的发行版地址拼
        assertEquals("${base()}/gh/download/v9.9.9/$name", info.assetUrl)
        assertTrue(info.pageUrl.startsWith("https://gitee.com/"))
        mirrorNoSha = true
        assertNull(Updater.parseMirror(Json.parseToJsonElement(giteeRelease()).jsonObject), "说明里没有校验值就不用镜像")
    }

    @Test
    fun phoneChecksAndDownloadsFromGiteeFirst() = runBlocking {
        val up = phone("a")
        val info = up.check()
        assertTrue(info.mirrorUrl.isNotEmpty())
        val f = up.download(info) {}
        assertContentEquals(apk, f.readBytes())
        assertEquals(1, giteeHits.get())
        assertEquals(0, githubHits.get())
    }

    @Test
    fun brokenMirrorFallsBackToGithub() = runBlocking {
        // 镜像查不到：照旧查 GitHub
        mirrorDown = true
        val info = phone("b").check()
        assertEquals("", info.mirrorUrl)
        mirrorDown = false
        // 镜像下来的东西校验不对：删掉，接着从 GitHub 下
        mirrorCorrupt = true
        val up = phone("c")
        val i2 = up.check()
        val f = up.download(i2) {}
        assertContentEquals(apk, f.readBytes())
        assertEquals(1, giteeHits.get())
        assertEquals(1, githubHits.get())
        // 手动只走镜像：不对就报错，不偷偷换路
        File(dir, "d").deleteRecursively()
        val up3 = phone("d")
        assertFailsWith<IllegalStateException> { up3.download(up3.check(), only = "gitee") {} }
        // 手动直连 GitHub：不碰镜像
        val before = giteeHits.get()
        up3.download(up3.check(), only = "direct") {}
        assertEquals(before, giteeHits.get())
    }

    /** 真连 Gitee（JXY_LIVE_GITEE=1 才跑）：按手机的默认设置查最新版、从镜像整个下下来并核对，不能碰 GitHub。 */
    @Test
    fun liveGitee() = runBlocking {
        if (System.getenv("JXY_LIVE_GITEE") != "1") return@runBlocking
        System.clearProperty("jxy.update.prefix")
        val logs = mutableListOf<String>()
        val up = Updater(File(dir, "live"), proxy = { null }, isPhone = true, api = Updater.LATEST_API, current = "1.0.0", log = { logs += it })
        val info = up.check()
        println("live gitee: ${info.version} ${info.assetName} mirror=${info.mirrorUrl} sha=${info.sha256}")
        assertTrue(info.mirrorUrl.startsWith("https://gitee.com/"), logs.joinToString())
        val f = up.download(info) {}
        println("live gitee: ${f.length()} 字节；" + logs.joinToString("；"))
        assertTrue(logs.any { "国内镜像" in it && "用了" in it }, logs.joinToString())
    }

    @Test
    fun desktopNeverUsesMirror() = runBlocking {
        val info = Updater(File(dir, "pc"), proxy = { null }, isPhone = false, api = "${base()}/gh/latest", current = "1.0.0",
            mirrorApi = "${base()}/gitee/latest").check()
        assertEquals("", info.mirrorUrl)
    }
}
