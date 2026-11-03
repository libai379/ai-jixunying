package com.guixing.jixunying.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

actual fun createPageBrowser(profileDir: File): PageBrowser? =
    if (System.getProperty("jxy.browser") == "off") null else EdgeBrowser.findBrowser()?.let { EdgeBrowser(it, profileDir) }

/**
 * 电脑上的 Edge（Windows 10 / 11 自带；没有就找 Chrome），用它自己的调试接口（Chrome DevTools Protocol）控制。
 * 单独的资料夹，和用户自己平时用的 Edge 互不相干；窗口平时在屏幕外面（任务栏上会有一个 Edge 图标）。
 * 不用无界面模式：抖音对无界面浏览器直接给验证码页（10-23 实测）。
 */
class EdgeBrowser(private val exe: File, private val profile: File) : PageBrowser {
    private val lock = Mutex()
    private var proc: Process? = null
    @Volatile private var cdp: Cdp? = null

    override suspend fun snapshot(url: String, script: String, settleMs: Long): JsonObject {
        val c = ensure()
        val target = c.send("Target.createTarget", buildJsonObject { put("url", "about:blank"); put("background", true) }).str("targetId")!!
        try {
            val session = c.send("Target.attachToTarget", buildJsonObject { put("targetId", target); put("flatten", true) }).str("sessionId")!!
            c.send("Page.enable", session = session)
            val loaded = c.waitEvent("Page.loadEventFired", session)
            c.send("Page.navigate", buildJsonObject { put("url", url) }, session)
            withTimeoutOrNull(25_000) { loaded.await() }
            // 抖音这类页面加载完事件之后还要好几秒才把内容画出来（10-23：等 3 秒一个字没有，等 8 秒全有）：
            // 隔一会儿看一次正文有多长，连着两次不再变长（而且有点内容了）就算好了，最多再等 settleMs
            val deadline = System.currentTimeMillis() + settleMs
            var last = -1
            var stable = 0
            while (System.currentTimeMillis() < deadline) {
                delay(800)
                val len = runCatching {
                    ((c.send("Runtime.evaluate", buildJsonObject {
                        put("expression", "document.body ? document.body.innerText.length : 0"); put("returnByValue", true)
                    }, session)["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.intOrNull ?: 0
                }.getOrDefault(0)
                stable = if (len == last && len > 200) stable + 1 else 0
                last = len
                if (stable >= 2) break
            }
            val r = c.send("Runtime.evaluate", buildJsonObject {
                put("expression", script); put("returnByValue", true); put("awaitPromise", true)
            }, session)
            val v = ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalStateException("页面里读不出内容：" + r.toString().take(200))
            return Json.parse(v).jsonObject
        } finally {
            runCatching { c.send("Target.closeTarget", buildJsonObject { put("targetId", target) }) }
        }
    }

    /**
     * 新开一个后台标签页直接打开媒体地址（同源，fetch 不受跨域限制），在页面里 fetch 回来、用 OfflineAudioContext 解码
     * （按 16000 的采样率解码会自动重采样），混成单声道转 16 位，存在页面变量里，再分块用 base64 取回来。
     */
    override suspend fun audioPcm(mediaUrl: String, maxSeconds: Int): Pair<ByteArray, Double> = decodePcm(mediaUrl, "location.href", maxSeconds)

    /** 本机文件：程序临时开一个只认 127.0.0.1 的小服务把它递给浏览器（file:// 不能 fetch，画面也取不出来）。 */
    override suspend fun fileAudioPcm(file: File, maxSeconds: Int): Pair<ByteArray, Double> =
        serving(file) { page -> decodePcm(page, "'v'", maxSeconds) }

    /**
     * 取画面：在页面里 fetch 成 Blob（整段都在内存里，想跳到哪都行，不用服务支持分段）、放进一个不插进页面的 video，
     * 跳到每个时间点画到 canvas 上存成 JPEG。声音关着。解不开（比如这台电脑没有 HEVC 解码）时报错，由调用处说明。
     */
    override suspend fun fileFrames(file: File, count: Int, maxSide: Int): Pair<List<ByteArray>, Double> = serving(file) { page ->
        val c = ensure()
        val target = c.send("Target.createTarget", buildJsonObject { put("url", "about:blank"); put("background", true) }).str("targetId")!!
        try {
            val session = c.send("Target.attachToTarget", buildJsonObject { put("targetId", target); put("flatten", true) }).str("sessionId")!!
            c.send("Page.enable", session = session)
            // 后台标签页不加载视频（loadeddata 等 30 秒也不来，11-01 实测）：让这个页面当自己是可见的，不用把窗口拉到前面抢焦点
            c.send("Emulation.setFocusEmulationEnabled", buildJsonObject { put("enabled", true) }, session)
            val loaded = c.waitEvent("Page.loadEventFired", session)
            c.send("Page.navigate", buildJsonObject { put("url", page) }, session)
            withTimeoutOrNull(15_000) { loaded.await() }
            val grab = """
                (async () => {
                  const r = await fetch('v');
                  if (!r.ok) return JSON.stringify({error: 'HTTP ' + r.status});
                  const v = document.createElement('video');
                  v.muted = true; v.playsInline = true; v.preload = 'auto';
                  v.src = URL.createObjectURL(await r.blob());
                  try {
                    await new Promise((ok, no) => {
                      v.onloadeddata = ok;
                      v.onerror = () => no(new Error(v.error ? v.error.message : '解不开'));
                      setTimeout(() => no(new Error('等了 30 秒没解出来')), 30000);
                    });
                  } catch (e) { return JSON.stringify({error: String(e.message || e)}); }
                  if (!v.videoWidth) return JSON.stringify({error: '没有画面', seconds: v.duration});
                  const scale = Math.min(1, $maxSide / Math.max(v.videoWidth, v.videoHeight));
                  const cv = document.createElement('canvas');
                  cv.width = Math.max(1, Math.round(v.videoWidth * scale));
                  cv.height = Math.max(1, Math.round(v.videoHeight * scale));
                  const g = cv.getContext('2d');
                  const out = [];
                  for (let i = 0; i < $count; i++) {
                    await new Promise(ok => { v.onseeked = ok; setTimeout(ok, 8000); v.currentTime = v.duration * (i + 0.5) / $count; });
                    g.drawImage(v, 0, 0, cv.width, cv.height);
                    out.push(cv.toDataURL('image/jpeg', 0.8).split(',')[1]);
                  }
                  window.__jxyFrames = out;
                  return JSON.stringify({frames: out.length, seconds: v.duration});
                })()
            """.trimIndent()
            val r = c.send("Runtime.evaluate", buildJsonObject {
                put("expression", grab); put("returnByValue", true); put("awaitPromise", true)
            }, session, timeoutMs = 180_000)
            val v = ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalStateException("视频的画面取不出来：" + r.toString().take(200))
            val info = Json.parse(v).jsonObject
            info.str("error")?.let { throw IllegalStateException(it) }
            val n = (info["frames"] as? JsonPrimitive)?.intOrNull ?: 0
            val seconds = (info["seconds"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val frames = (0 until n).map { i ->
                val b64 = ((c.send("Runtime.evaluate", buildJsonObject { put("expression", "window.__jxyFrames[$i]"); put("returnByValue", true) }, session)["result"] as? JsonObject)
                    ?.get("value") as? JsonPrimitive)?.contentOrNull ?: throw IllegalStateException("取画面失败")
                java.util.Base64.getDecoder().decode(b64)
            }
            frames to seconds
        } finally {
            runCatching { c.send("Target.closeTarget", buildJsonObject { put("targetId", target) }) }
        }
    }

    /**
     * 只认 127.0.0.1、带随机路径的小服务（只用 java.base：打包的运行时不带 jdk.httpserver）：
     * 路径「…/」是一张空白页（不直接打开视频，免得浏览器自己放出声音），「…/v」是这个文件。用完就关。
     */
    private suspend fun <T> serving(file: File, block: suspend (pageUrl: String) -> T): T {
        val token = java.util.UUID.randomUUID().toString()
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        kotlin.concurrent.thread(isDaemon = true, name = "jxy-video-serve") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                kotlin.concurrent.thread(isDaemon = true) {
                    runCatching {
                        s.use { sock ->
                            val r = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val path = r.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                            while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
                            val out = sock.getOutputStream()
                            fun head(status: String, type: String, len: Long) =
                                out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: $len\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                            when (path) {
                                "/$token/" -> { val html = "<!doctype html><title>jxy</title>".toByteArray(); head("200 OK", "text/html", html.size.toLong()); out.write(html) }
                                "/$token/v" -> { head("200 OK", "video/mp4", file.length()); file.inputStream().use { it.copyTo(out) } }
                                else -> head("404 Not Found", "text/plain", 0)
                            }
                            out.flush()
                        }
                    }
                }
            }
        }
        try {
            return block("http://127.0.0.1:${server.localPort}/$token/")
        } finally {
            runCatching { server.close() }
        }
    }

    private suspend fun decodePcm(pageUrl: String, fetchExpr: String, maxSeconds: Int): Pair<ByteArray, Double> {
        val c = ensure()
        val target = c.send("Target.createTarget", buildJsonObject { put("url", "about:blank"); put("background", true) }).str("targetId")!!
        try {
            val session = c.send("Target.attachToTarget", buildJsonObject { put("targetId", target); put("flatten", true) }).str("sessionId")!!
            c.send("Page.enable", session = session)
            val loaded = c.waitEvent("Page.loadEventFired", session)
            c.send("Page.navigate", buildJsonObject { put("url", pageUrl) }, session)
            withTimeoutOrNull(30_000) { loaded.await() }
            val decode = """
                (async () => {
                  const r = await fetch($fetchExpr);
                  if (!r.ok) return JSON.stringify({error: 'HTTP ' + r.status});
                  const buf = await r.arrayBuffer();
                  const ctx = new OfflineAudioContext(1, 1, 16000);
                  let audio;
                  try { audio = await ctx.decodeAudioData(buf); } catch (e) { return JSON.stringify({error: '解码失败：' + e}); }
                  const n = Math.min(audio.length, $maxSeconds * 16000);
                  const chs = [];
                  for (let c = 0; c < audio.numberOfChannels; c++) chs.push(audio.getChannelData(c));
                  const out = new Int16Array(n);
                  for (let i = 0; i < n; i++) {
                    let s = 0;
                    for (const d of chs) s += d[i];
                    s = s / chs.length;
                    out[i] = Math.max(-32768, Math.min(32767, Math.round(s * 32767)));
                  }
                  window.__jxyPcm = new Uint8Array(out.buffer);
                  return JSON.stringify({bytes: window.__jxyPcm.length, seconds: audio.duration});
                })()
            """.trimIndent()
            val r = c.send("Runtime.evaluate", buildJsonObject {
                put("expression", decode); put("returnByValue", true); put("awaitPromise", true)
            }, session, timeoutMs = 180_000)
            val v = ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
                ?: throw IllegalStateException("视频的声音读不出来：" + r.toString().take(200))
            val info = Json.parse(v).jsonObject
            info.str("error")?.let { throw IllegalStateException("视频的声音读不出来：$it") }
            val total = (info["bytes"] as? JsonPrimitive)?.intOrNull ?: 0
            val seconds = (info["seconds"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val out = java.io.ByteArrayOutputStream(total)
            var off = 0
            val step = 1_000_000
            while (off < total) {
                val js = "(() => { const b = window.__jxyPcm.subarray($off, ${off + step}); let s = ''; " +
                    "for (let i = 0; i < b.length; i += 8192) s += String.fromCharCode.apply(null, b.subarray(i, i + 8192)); return btoa(s); })()"
                val part = ((c.send("Runtime.evaluate", buildJsonObject { put("expression", js); put("returnByValue", true) }, session)["result"] as? JsonObject)
                    ?.get("value") as? JsonPrimitive)?.contentOrNull ?: throw IllegalStateException("取声音数据失败")
                out.write(java.util.Base64.getDecoder().decode(part))
                off += step
            }
            return out.toByteArray() to seconds
        } finally {
            runCatching { c.send("Target.closeTarget", buildJsonObject { put("targetId", target) }) }
        }
    }

    override suspend fun cookieNames(domain: String): Set<String> {
        val r = ensure().send("Storage.getCookies")
        return (r["cookies"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it.str("domain") ?: "").trimStart('.').let { d -> d == domain || d.endsWith(".$domain") } }
            .mapNotNull { it.str("name") }.toSet()
    }

    override suspend fun showForLogin(url: String) {
        val c = ensure()
        val target = c.send("Target.createTarget", buildJsonObject { put("url", url) }).str("targetId")!!
        setBounds(c, target, onScreen = true)
        runCatching { c.send("Target.activateTarget", buildJsonObject { put("targetId", target) }) }
    }

    override suspend fun hide() {
        val c = cdp ?: return
        val targets = (c.send("Target.getTargets")["targetInfos"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        targets.firstOrNull { it.str("type") == "page" }?.str("targetId")?.let { setBounds(c, it, onScreen = false) }
    }

    private suspend fun setBounds(c: Cdp, target: String, onScreen: Boolean) {
        val win = c.send("Browser.getWindowForTarget", buildJsonObject { put("targetId", target) })["windowId"]
        val id = (win as? JsonPrimitive)?.intOrNull ?: return
        // 先回到普通状态才能改位置（最小化 / 最大化时改不了）
        c.send("Browser.setWindowBounds", buildJsonObject { put("windowId", id); put("bounds", buildJsonObject { put("windowState", "normal") }) })
        c.send("Browser.setWindowBounds", buildJsonObject {
            put("windowId", id)
            put("bounds", buildJsonObject {
                if (onScreen) { put("left", 120); put("top", 60) } else { put("left", OFF); put("top", OFF) }
                put("width", 1200); put("height", 860)
            })
        })
    }

    init {
        // AI集训营 关掉时把这个浏览器也关掉（不然它在屏幕外面一直开着）
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    override fun running(): Boolean = cdp?.open == true ||
        (connectExisting(File(profile, "DevToolsActivePort"))?.also { cdp = it } != null)

    override fun close() {
        cdp?.let { c -> runCatching { kotlinx.coroutines.runBlocking { withTimeoutOrNull(2_000) { c.send("Browser.close") } } } }
        runCatching { cdp?.close() }
        cdp = null
        proc?.let { p -> runCatching { p.descendants().forEach { it.destroy() }; p.destroy() } }
        proc = null
    }

    /** 已经开着的（上次程序开的也算，靠 DevToolsActivePort 找）就接上，没有就开一个。 */
    private suspend fun ensure(): Cdp = lock.withLock {
        cdp?.takeIf { it.open }?.let { return it }
        val portFile = File(profile, "DevToolsActivePort")
        connectExisting(portFile)?.let { cdp = it; return it }
        profile.mkdirs()
        portFile.delete()
        proc = ProcessBuilder(
            exe.absolutePath,
            "--user-data-dir=${profile.absolutePath}",
            "--remote-debugging-port=0",
            "--no-first-run", "--no-default-browser-check", "--disable-sync",
            // 国内平台直连：不跟系统代理走（10-23 走了代理，B站看到国外 IP 直接 412 风控）
            "--no-proxy-server",
            "--window-position=$OFF,$OFF", "--window-size=1200,860",
            // 窗口在屏幕外面时浏览器会当成「被挡住了」，降速或不加载视频：关掉这些省电行为
            "--disable-features=CalculateNativeWinOcclusion",
            // 微信发来的视频是 HEVC（H.265，11-03 ffprobe 查的），Edge 默认解不了（画面全黑、videoWidth = 0）：打开平台的 HEVC 硬件解码
            "--enable-features=PlatformHEVCDecoderSupport",
            "--disable-background-timer-throttling", "--disable-renderer-backgrounding", "--disable-backgrounding-occluded-windows",
            "about:blank",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        val c = withTimeout(20_000) {
            while (true) { connectExisting(portFile)?.let { return@withTimeout it }; delay(200) }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        cdp = c
        c
    }

    private fun connectExisting(portFile: File): Cdp? {
        val lines = runCatching { portFile.readLines() }.getOrNull() ?: return null
        val port = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val path = lines.getOrNull(1)?.trim() ?: return null
        return runCatching { Cdp("ws://127.0.0.1:$port$path") }.getOrNull()
    }

    companion object {
        private const val OFF = -10000

        fun findBrowser(): File? {
            val env = { k: String -> System.getenv(k).orEmpty() }
            return listOf(
                "${env("ProgramFiles(x86)")}\\Microsoft\\Edge\\Application\\msedge.exe",
                "${env("ProgramFiles")}\\Microsoft\\Edge\\Application\\msedge.exe",
                "${env("ProgramFiles")}\\Google\\Chrome\\Application\\chrome.exe",
                "${env("LOCALAPPDATA")}\\Google\\Chrome\\Application\\chrome.exe",
            ).map(::File).firstOrNull { it.isFile }
        }
    }

    /** 一条调试连接：发指令等回执，按 sessionId 收事件。 */
    private class Cdp(wsUrl: String) {
        private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        private val nextId = AtomicInteger(1)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val waiters = CopyOnWriteArrayList<Triple<String, String?, CompletableDeferred<JsonObject>>>()
        @Volatile var open = false
        private val ready = CompletableDeferred<Unit>()
        private val ws: WebSocket = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { open = true; ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { Json.parse(text).jsonObject }.getOrNull() ?: return
                val id = (o["id"] as? JsonPrimitive)?.intOrNull
                if (id != null) { pending.remove(id)?.complete(o); return }
                val method = o.str("method") ?: return
                val session = o.str("sessionId")
                waiters.filter { it.first == method && (it.second == null || it.second == session) }.forEach { w ->
                    waiters.remove(w); w.third.complete(o)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(IllegalStateException("浏览器连接断了：$reason")) }
        })

        init {
            // 连不上（端口是旧的）就在这里失败，让上面重开一个浏览器
            kotlinx.coroutines.runBlocking { withTimeout(3_000) { ready.await() } }
        }

        private fun fail(t: Throwable) {
            open = false
            if (!ready.isCompleted) ready.completeExceptionally(t)
            pending.values.forEach { it.completeExceptionally(t) }
            pending.clear()
        }

        fun waitEvent(method: String, session: String?): CompletableDeferred<JsonObject> =
            CompletableDeferred<JsonObject>().also { waiters += Triple(method, session, it) }

        suspend fun send(method: String, params: JsonObject = JsonObject(emptyMap()), session: String? = null, timeoutMs: Long = 30_000): JsonObject {
            val id = nextId.getAndIncrement()
            val d = CompletableDeferred<JsonObject>()
            pending[id] = d
            val msg = buildJsonObject {
                put("id", id); put("method", method); put("params", params)
                if (session != null) put("sessionId", session)
            }
            if (!ws.send(msg.toString())) { pending.remove(id); throw IllegalStateException("浏览器连接断了") }
            val r = withTimeout(timeoutMs) { d.await() }
            (r["error"] as? JsonObject)?.let { throw IllegalStateException("浏览器报错（$method）：${it.str("message")}") }
            return (r["result"] as? JsonObject) ?: JsonObject(emptyMap())
        }

        fun close() { open = false; runCatching { ws.close(1000, null) }; http.dispatcher.executorService.shutdown() }
    }
}
