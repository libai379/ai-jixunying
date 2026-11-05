package com.guixing.jixunying

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.FrameLink
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.Event
import com.guixing.jixunying.model.PairedHost
import com.guixing.jixunying.model.WireFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 手机切到后台省电（1.5.0）：暂停时断开中转、不发心跳；回前台马上接上、要一份最新状态。 */
class RemotePauseTest {
    private class FakeLink : FrameLink {
        override val online = MutableStateFlow(true)
        override val incoming = MutableSharedFlow<WireFrame>(extraBufferCapacity = 16)
        val hellos = AtomicInteger()
        var paused = 0
        var resumed = 0
        override suspend fun send(frame: WireFrame) {
            if (frame is WireFrame.Hello) {
                hellos.incrementAndGet()
                incoming.emit(WireFrame.Evt(Event.State(AppState(weixinCapable = true))))
            }
        }
        override fun pause() { paused++; online.value = false }
        override fun resume() { resumed++; online.value = true }
        override fun close() {}
    }

    @Test
    fun pauseAndResume() = runBlocking {
        val link = FakeLink()
        val r = RemoteBackend(PairedHost("h", "电脑", "d", "k", emptyList()), link, "手机")
        r.start()
        withTimeout(5_000) { while (r.conn.value !is ConnState.Connected) delay(50) }

        r.pause()
        assertEquals(1, link.paused)
        assertTrue(r.conn.value !is ConnState.Connected, "暂停后不算连着")
        val before = link.hellos.get()
        delay(5_000)
        assertEquals(before, link.hellos.get(), "暂停时不发心跳")
        assertTrue(r.conn.value !is ConnState.Connected)

        r.resume()
        assertEquals(1, link.resumed)
        withTimeout(6_000) { while (r.conn.value !is ConnState.Connected) delay(50) }
        assertTrue(link.hellos.get() > before, "回前台马上打招呼要状态")
        r.stop()
    }

    /**
     * 切出去又马上切回来（不到一次心跳 2 秒）、新连接一下就连上：以前心跳没看见「断过」，又被 resume 清了上线时间，
     * 不再打招呼，状态永远卡在「正在连」（2026-11-05 在 phoneLinksThroughRelay 里偶发查出来）。
     */
    @Test
    fun quickPauseResumeReconnects() = runBlocking {
        repeat(3) { round ->
            val link = FakeLink()
            val r = RemoteBackend(PairedHost("h", "电脑", "d", "k", emptyList()), link, "手机")
            r.start()
            withTimeout(5_000) { while (r.conn.value !is ConnState.Connected) delay(50) }
            delay(300L * round)
            r.pause()
            delay(100)
            r.resume()
            withTimeout(6_000) { while (r.conn.value !is ConnState.Connected) delay(50) }
            r.stop()
        }
    }

    /** 电脑一直不回音的线路（中转连着，电脑关机）；answer() 以后电脑开始回。 */
    private class SilentHostLink : FrameLink {
        override val online = MutableStateFlow(true)
        override val incoming = MutableSharedFlow<WireFrame>(extraBufferCapacity = 16)
        @Volatile var hostOn = false
        override suspend fun send(frame: WireFrame) {
            if (frame is WireFrame.Hello && hostOn) incoming.emit(WireFrame.Evt(Event.State(AppState())))
        }
        override fun close() {}
    }

    /**
     * 2026-11-04 用户：「电脑没有开机，手机的 APP 是不能直接切过去的……要提示一下电脑未开机或者未连接」。
     * 电脑没回音时切不过去、给出原因；电脑回音以后才切得过去。
     */
    @Test
    fun switchNeedsTheComputer() = runBlocking {
        val link = SilentHostLink()
        val host = PairedHost("h", "电脑", "d", "k", emptyList())
        val local = RemoteBackend(host, SilentHostLink(), "本机占位")
        val hub = com.guixing.jixunying.client.Hub(local, linkFactory = { link }, deviceName = "手机")
        hub.attach(host)
        val why = hub.switchToRemote()
        assertTrue(why != null && "没切过去" in why, "电脑没回音不让切：$why")
        assertEquals(false, hub.useRemote.value)

        link.hostOn = true
        link.incoming.emit(WireFrame.Evt(Event.State(AppState())))
        withTimeout(10_000) { while (hub.remote.value!!.conn.value !is ConnState.Connected) { link.incoming.emit(WireFrame.Evt(Event.State(AppState()))); delay(200) } }
        assertEquals(null, hub.switchToRemote(), "电脑回音以后切得过去")
        assertEquals(true, hub.useRemote.value)
        hub.unpair()
    }

    /** 回前台时中转要一会儿才连上：这段时间只算「正在连」，不报「连不上」（不然「掉线自动切回本机」会一回前台就把人踢回本机）。 */
    @Test
    fun resumeIsNotReportedAsOffline() = runBlocking {
        val link = FakeLink()
        val r = RemoteBackend(PairedHost("h", "电脑", "d", "k", emptyList()), link, "手机")
        r.start()
        withTimeout(5_000) { while (r.conn.value !is ConnState.Connected) delay(50) }
        r.pause()
        r.resume()
        link.online.value = false // 中转还在重连
        var sawFailed = false
        repeat(30) { if (r.conn.value is ConnState.Failed) sawFailed = true; delay(100) }
        assertEquals(false, sawFailed, "刚回前台、中转还没连上，不算掉线")
        link.online.value = true
        withTimeout(6_000) { while (r.conn.value !is ConnState.Connected) delay(50) }
        r.stop()
    }
}
