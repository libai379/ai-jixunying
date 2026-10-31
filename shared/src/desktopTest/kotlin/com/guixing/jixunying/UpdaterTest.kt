package com.guixing.jixunying

import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.engine.Engine
import com.guixing.jixunying.engine.Storage
import com.guixing.jixunying.engine.Updater
import com.guixing.jixunying.engine.UpdateViaHost
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.PairingCode
import com.guixing.jixunying.model.RelaySettings
import com.guixing.jixunying.relay.RelayHost
import com.guixing.jixunying.relay.RelayLink
import com.guixing.jixunying.relay.pairWithHost
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 检查更新（2026-10-30，engine/Updater.kt、DesktopPlatform 的 UpdateSwap、UpdateViaHost）。
 * 假的 GitHub：/repos/.../releases/latest 给发行版 JSON（字段照 2026-10-30 gh api 实测的 v1.12.1：tag_name、assets[].name / size / digest / browser_download_url），
 * 下载地址先 302 跳到别的路径（真 GitHub 跳 release-assets.githubusercontent.com）。
 */
class UpdaterTest {
    private lateinit var fake: EmbeddedServer<*, *>
    private var port = 0
    private lateinit var dir: File
    private val apk = ByteArray(300_000) { (it * 31 + 7).toByte() }
    private val zip = ByteArray(200_000) { (it * 17 + 3).toByte() }
    private val downloads = AtomicInteger()
    @Volatile private var badDigest = false
    @Volatile private var version = "9.9.9"
    /** 下载地址不给下（模拟手机直连 GitHub 下不动）。 */
    @Volatile private var blockDownloads = false

    private fun freePort() = ServerSocket(0).use { it.localPort }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun base() = "http://127.0.0.1:$port"

    private fun release(): String {
        val dl = "${base()}/libai379/ai-jixunying/releases/download/v$version"
        fun asset(name: String, bytes: ByteArray, type: String) =
            """{"name":"$name","size":${bytes.size},"content_type":"$type","digest":"sha256:${if (badDigest) "0".repeat(64) else sha(bytes)}","browser_download_url":"$dl/$name"}"""
        return """{"tag_name":"v$version","name":"AI集训营 $version","draft":false,"prerelease":false,"published_at":"2026-10-31T01:02:03Z",
            "html_url":"https://github.com/libai379/ai-jixunying/releases/tag/v$version","body":"**AI集训营 $version**\n\n## Improvements\n\n- 修了一些东西",
            "assets":[${asset("AI-Jixunying-$version-20261031-android.apk", apk, "application/vnd.android.package-archive")},
                      {"name":"AI-Jixunying-$version-20261031-windows.exe","size":5,"digest":"sha256:${"1".repeat(64)}","browser_download_url":"$dl/x.exe"},
                      ${asset("AI-Jixunying-$version-20261031-windows-update.zip", zip, "application/zip")}]}"""
    }

    @BeforeTest
    fun setUp() {
        port = freePort()
        dir = kotlin.io.path.createTempDirectory("jxy-update").toFile()
        fake = embeddedServer(CIO, port = port) {
            routing {
                get("/repos/libai379/ai-jixunying/releases/latest") { call.respondText(release(), ContentType.Application.Json) }
                // 真 GitHub 的下载地址会 302 跳走
                get("/libai379/ai-jixunying/releases/download/{tag}/{name}") {
                    if (blockDownloads) call.respondText("blocked", status = HttpStatusCode.Forbidden)
                    else call.respondRedirect("${base()}/assets/${call.parameters["name"]}")
                }
                get("/assets/{name}") {
                    downloads.incrementAndGet()
                    val n = call.parameters["name"].orEmpty()
                    when {
                        n.endsWith(".apk") -> call.respondBytes(apk, ContentType.parse("application/vnd.android.package-archive"))
                        n.endsWith(".zip") -> call.respondBytes(zip, ContentType.Application.Zip)
                        else -> call.respondText("no", status = HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
        System.setProperty("jxy.update.api", "${base()}/repos/libai379/ai-jixunying/releases/latest")
        System.setProperty("jxy.update.prefix", "${base()}/libai379/ai-jixunying/releases/download/")
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("jxy.update.api")
        System.clearProperty("jxy.update.prefix")
        System.clearProperty("jxy.version")
        fake.stop(100, 500)
        dir.deleteRecursively()
    }

    @Test
    fun versionsCompare() {
        assertTrue(Updater.newer("1.12.10", "1.12.9"))
        assertTrue(Updater.newer("v1.13.0", "1.12.1"))
        assertTrue(Updater.newer("2.0", "1.99.99"))
        assertFalse(Updater.newer("1.12.1", "1.12.1"))
        assertFalse(Updater.newer("v1.12.1", "1.12.1"))
        assertFalse(Updater.newer("1.12.0", "1.12.1"))
        assertFalse(Updater.newer("1.12", "1.12.0"))
        assertEquals(listOf(1, 12, 1), Updater.parseVersion("v1.12.1-rc1"))
    }

    /** 线路：设了代理先走代理再直连；没设代理只直连（以前没设代理时一条都没有，手机根本不去连）；连本机地址不走代理。 */
    @Test
    fun routesAlwaysIncludeDirect() {
        val f = File(dir, "u")
        assertEquals(listOf<String?>(null), Updater(f, proxy = { null }, isPhone = true).routes("https://api.github.com/x"))
        assertEquals(listOf<String?>(null), Updater(f, proxy = { "  " }, isPhone = true).routes("https://api.github.com/x"))
        assertEquals(listOf("127.0.0.1:10809", null), Updater(f, proxy = { "127.0.0.1:10809" }, isPhone = false).routes("https://api.github.com/x"))
        assertEquals(listOf<String?>(null), Updater(f, proxy = { "127.0.0.1:10809" }, isPhone = false).routes("http://127.0.0.1:8080/latest"))
    }

    /** 设了代理但代理不通：退回直连照样查得到（假服务器在本机，这里用一个不是本机写法的地址指过去）。 */
    @Test
    fun deadProxyFallsBackToDirect() = runBlocking {
        System.setProperty("jxy.update.api", "http://127.0.0.2:$port/repos/libai379/ai-jixunying/releases/latest")
        val up = Updater(File(dir, "u2"), proxy = { "127.0.0.1:1" }, isPhone = true, current = "1.0.0")
        assertEquals(listOf("127.0.0.1:1", null), up.routes("http://127.0.0.2:$port/x"))
        assertEquals("9.9.9", up.check().version)
    }

    /** 挑文件：手机 apk；电脑能原地换要整包，不能换（或没有整包）要安装程序；校验值、大小、地址照 GitHub 给的。 */
    @Test
    fun picksTheRightFile() {
        val json = Json.parseToJsonElement(release()).jsonObject
        val phone = Updater.parse(json, isPhone = true, canSwap = false)
        assertEquals("9.9.9", phone.version)
        assertEquals("apk", phone.kind)
        assertEquals(sha(apk), phone.sha256)
        assertEquals(apk.size.toLong(), phone.assetSize)
        assertTrue(phone.assetUrl.endsWith("android.apk"))
        assertTrue("修了一些东西" in phone.notes)
        val swap = Updater.parse(json, isPhone = false, canSwap = true)
        assertEquals("zip", swap.kind)
        assertTrue(swap.assetName.endsWith("windows-update.zip"))
        assertEquals("exe", Updater.parse(json, isPhone = false, canSwap = false).kind, "程序文件夹写不进去：要安装程序")
        // 这一版没传整包（比如 1.12.1 只有 exe 和 apk）：能原地换的电脑不退回安装程序（会另装一份），没有能自动装的、去网页
        val old = Json.parseToJsonElement(release().replace("windows-update.zip", "windows-update.txt")).jsonObject
        assertEquals("", Updater.parse(old, isPhone = false, canSwap = true).kind)
        assertEquals("exe", Updater.parse(old, isPhone = false, canSwap = false).kind)
    }

    /** 查 → 下（跟着 302 跳）→ 核对 → 再下一次直接用下好的；校验值对不上的删掉、不用。 */
    @Test
    fun downloadsAndVerifies() = runBlocking {
        val up = Updater(File(dir, "updates"), proxy = { null }, isPhone = true, current = "1.12.1")
        val info = up.check()
        assertTrue(Updater.newer(info.version, up.current))
        val seen = mutableListOf<Float>()
        val f = up.download(info) { seen += it }
        assertContentEquals(apk, f.readBytes())
        assertEquals(1f, seen.last())
        assertTrue(seen.size > 3, "有进度：$seen")
        assertEquals(1, downloads.get())
        assertEquals(f, up.download(info) {}, "下好、核对过的直接用")
        assertEquals(1, downloads.get(), "不重下")
        assertEquals(listOf(f.name), File(dir, "updates").list()!!.toList(), "只留一个文件")

        badDigest = true
        File(dir, "updates").deleteRecursively()
        val bad = up.check()
        val e = assertFailsWith<IllegalStateException> { up.download(bad) {} }
        assertTrue("对不上" in e.message!!, e.message)
        assertTrue(File(dir, "updates").listFiles().isNullOrEmpty(), "下坏的删掉了")

        // 已经是最新
        badDigest = false
        version = "1.12.1"
        assertFalse(Updater.newer(up.check().version, up.current))
    }

    /** 引擎：查完写进 state.update（有新版、说法），后台下载完 downloaded 指向核对过的文件；手机直连下不动时走 updateFallback。 */
    @Test
    fun engineChecksAndDownloads() = runBlocking {
        System.setProperty("jxy.version", "1.0.0")
        val e = Engine(Storage(File(dir, "phone")), isPhone = true)
        val r = e.call(Command.UpdateCheck)
        assertTrue(r.ok, r.message)
        assertTrue(e.state.update.newer)
        assertEquals("有新版 9.9.9", e.state.update.status)
        assertTrue(e.call(Command.UpdateDownload).ok)
        withTimeout(10_000) { while (e.state.update.downloaded.isEmpty()) delay(30) }
        assertContentEquals(apk, File(e.state.update.downloaded).readBytes())
        assertEquals(-1f, e.state.update.progress)

        // 直连下不动（下载地址 404）：请电脑代下的退路给了整个文件，核对过当成已下好
        val e2 = Engine(Storage(File(dir, "phone2")), isPhone = true)
        var asked = 0
        e2.updateFallback = { info, prog -> asked++; prog(0.5f); prog(1f); assertEquals(sha(apk), info.sha256); apk }
        e2.call(Command.UpdateCheck)
        blockDownloads = true
        assertTrue(e2.call(Command.UpdateDownload).ok)
        withTimeout(10_000) { while (e2.state.update.downloaded.isEmpty() && !e2.state.update.error) delay(30) }
        assertEquals(1, asked)
        assertFalse(e2.state.update.error, e2.state.update.status)
        assertContentEquals(apk, File(e2.state.update.downloaded).readBytes())
        // 退路给的东西对不上校验值：不用
        val e3 = Engine(Storage(File(dir, "phone3")), isPhone = true)
        e3.updateFallback = { _, _ -> ByteArray(10) }
        e3.call(Command.UpdateCheck)
        e3.call(Command.UpdateDownload)
        withTimeout(10_000) { while (!e3.state.update.error) delay(30) }
        assertTrue("对不上" in e3.state.update.status, e3.state.update.status)
        assertTrue(e3.state.update.downloaded.isEmpty())
    }

    /** 电脑替手机代下：只认本项目发行版地址；下好放进文件区，手机经真中转分段取回来，内容一样。 */
    @Test
    fun phoneGetsItThroughTheComputer() = runBlocking {
        val mqttPort = freePort()
        val broker = io.moquette.broker.Server()
        broker.startServer(io.moquette.broker.config.MemoryConfig(Properties().apply {
            setProperty("port", mqttPort.toString()); setProperty("host", "127.0.0.1"); setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true"); setProperty("persistence_enabled", "false"); setProperty("data_path", File(dir, "mqtt").absolutePath)
        }))
        val pc = Engine(Storage(File(dir, "pc")))
        pc.call(Command.SaveSettings(pc.state.settings.copy(proxy = "", relay = RelaySettings(true, listOf("tcp://127.0.0.1:$mqttPort"), "测试电脑"))))
        val relay = RelayHost(pc)
        relay.restart()
        try {
            withTimeout(15_000) { while (!pc.state.relayStatus.startsWith("已连上")) delay(100) }
            // 别的地址不替手机下
            assertFalse(pc.call(Command.HostFetchUpdate("https://example.com/evil.apk", sha(apk), 10)).ok)
            assertFalse(pc.call(Command.HostFetchUpdate("${base()}/libai379/ai-jixunying/releases/download/v9.9.9/a.apk", "", 10)).ok, "没校验值不下")

            val phone = Engine(Storage(File(dir, "phone")), isPhone = true)
            val hub = Hub(phone, linkFactory = { RelayLink(it) }, pairer = { c, n -> pairWithHost(c, n) }, deviceName = "测试手机")
            assertTrue(hub.pair(PairingCode(pc.state.hostId, pc.state.pairingSecret, "测试电脑", pc.state.settings.relay.brokers)).isSuccess)
            val remote = hub.remote.value!!
            withTimeout(15_000) { while (remote.conn.value !is com.guixing.jixunying.client.ConnState.Connected || remote.store.state.value.appVersion.isEmpty()) delay(50) }
            assertEquals(com.guixing.jixunying.model.APP_VERSION, remote.store.state.value.appVersion, "手机看得到电脑是哪一版")

            val info = Updater.parse(Json.parseToJsonElement(release()).jsonObject, isPhone = true, canSwap = false)
            val seen = mutableListOf<Float>()
            val bytes = UpdateViaHost(hub).fetch(info) { seen += it }
            assertContentEquals(apk, bytes)
            assertTrue(seen.isNotEmpty())
            val cached = File(dir, "pc/files").listFiles()!!.filter { it.name.startsWith("update-") }
            assertEquals(1, cached.size, "电脑文件区只留这一个")
            // 再要一次：电脑用下好的，不重下
            val before = downloads.get()
            assertContentEquals(apk, UpdateViaHost(hub).fetch(info) {})
            assertEquals(before, downloads.get())
        } finally {
            relay.stop(); broker.stopServer()
        }
    }

    /** 电脑整包：解开到程序文件夹旁边、找出新程序文件夹；路径跑出去的整包不解。 */
    @Test
    fun desktopZipPrepare() {
        val appDir = File(dir, "apps/ai-jixunying").apply { mkdirs() }
        File(appDir, "ai-jixunying.exe").writeText("old")
        val good = File(dir, "good.zip")
        java.util.zip.ZipOutputStream(good.outputStream()).use { z ->
            for ((n, t) in listOf("ai-jixunying/ai-jixunying.exe" to "new", "ai-jixunying/app/a.jar" to "jar", "ai-jixunying/runtime/release" to "r")) {
                z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry()
            }
        }
        val newDir = UpdateSwap.prepare(good, appDir, "ai-jixunying.exe")
        assertEquals(File(dir, "apps/ai-jixunying.update/ai-jixunying").canonicalFile, newDir.canonicalFile)
        assertEquals("new", File(newDir, "ai-jixunying.exe").readText())
        assertEquals("old", File(appDir, "ai-jixunying.exe").readText(), "原来的还没动")

        val evil = File(dir, "evil.zip")
        java.util.zip.ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("../../outside.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        assertFailsWith<IllegalStateException> { UpdateSwap.prepare(evil, appDir, "ai-jixunying.exe") }
        assertFalse(File(dir, "outside.txt").exists())
        assertFalse(UpdateSwap.stageOf(appDir).exists(), "解了一半的删掉了")

        val empty = File(dir, "empty.zip")
        java.util.zip.ZipOutputStream(empty.outputStream()).use { z -> z.putNextEntry(java.util.zip.ZipEntry("readme.txt")); z.write("x".toByteArray()); z.closeEntry() }
        val e = assertFailsWith<IllegalStateException> { UpdateSwap.prepare(empty, appDir, "ai-jixunying.exe") }
        assertTrue("没有找到程序" in e.message!!)
    }

    /**
     * 换文件夹的脚本（真跑 PowerShell）：等「程序」进程退出 → 旧文件夹改名 .old、新的改成原名 → 打开新的（这里用一个 .cmd 当程序，打开时写个记号）。
     * 新文件夹不见了的话改回去、打开旧的。
     */
    @Test
    fun swapScriptSwapsAndRollsBack() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        fun app(root: File, tag: String) = File(root, "ai-jixunying").apply {
            mkdirs()
            File(this, "app").mkdirs()
            File(this, "which.txt").writeText(tag)
            File(this, "run.cmd").writeText("@echo off\r\necho %~dp0 > \"${File(dir, "started-$tag.txt").absolutePath}\"\r\n")
        }
        val appDir = app(File(dir, "s1"), "old")
        val stage = UpdateSwap.stageOf(appDir).apply { mkdirs() }
        val newDir = app(stage, "new")
        // 「程序」：一个两秒后自己退出的进程
        val fakeApp = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 2").start()
        val p = UpdateSwap.launch(appDir, newDir, "run.cmd", listOf(fakeApp.pid()))
        assertTrue(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "脚本跑完了")
        val log = File(stage, "swap.log").readText()
        assertTrue("swapped" in log, log)
        assertFalse(fakeApp.isAlive, "等程序退了才换")
        assertEquals("new", File(appDir, "which.txt").readText())
        assertEquals("old", File(UpdateSwap.oldOf(appDir), "which.txt").readText())
        withTimeout10s { File(dir, "started-new.txt").isFile }

        // 新文件夹不见了：改回去、打开旧的
        val appDir2 = app(File(dir, "s2"), "old2")
        val stage2 = UpdateSwap.stageOf(appDir2).apply { mkdirs() }
        val p2 = UpdateSwap.launch(appDir2, File(stage2, "missing"), "run.cmd", emptyList())
        assertTrue(p2.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("rolling back" in File(stage2, "swap.log").readText())
        assertEquals("old2", File(appDir2, "which.txt").readText(), "改回去了")
        withTimeout10s { File(dir, "started-old2.txt").isFile }
    }

    private fun withTimeout10s(ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!ok()) { assertTrue(System.currentTimeMillis() < until, "10 秒内没等到"); Thread.sleep(100) }
    }
}
