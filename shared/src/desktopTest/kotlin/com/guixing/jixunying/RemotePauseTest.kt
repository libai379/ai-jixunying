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
}
