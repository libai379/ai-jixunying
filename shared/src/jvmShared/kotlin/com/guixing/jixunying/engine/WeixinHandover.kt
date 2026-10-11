package com.guixing.jixunying.engine

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.WeixinInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机上的微信助理和配对电脑的交接（1.5.0）。一个微信只能绑一台设备（手机再扫码会把电脑的顶掉），
 * 所以两边用同一个绑定，同一时间只让一边答：
 * - 电脑优先：电脑在线、开着微信助理、收得到消息时，手机待命；电脑关机、断网或关掉微信助理，手机接着答。
 *   刚打开、还不知道电脑在不在接时也先等着（最多十几秒），免得和电脑一起答。
 * - 绑定自动同步：电脑绑了微信、手机开着微信助理，就经加密线路把绑定拿过来；电脑换了绑定就重新拿，电脑解除了手机也删掉。
 *   手机自己扫码绑的不动。
 * - 不重复回答：手机只答最后一次听到电脑以后发来的消息；手机答过的告诉电脑，电脑不再答。
 * 没配对电脑的手机不受影响，自己绑、自己答。
 */
class WeixinHandover(
    private val hub: Hub,
    private val engine: Engine,
    private val bridge: WeixinBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    companion object {
        const val STANDBY = "电脑在接微信，这台手机待命（电脑关机或关掉微信助理后，手机自动接上）"
        const val CHECKING = "正在看电脑在不在接微信…"

        /** 电脑正在收微信消息。老版本电脑没有 answering 这一项，按「已连接」判断。 */
        fun hostAnswering(wx: WeixinInfo) = wx.answering || (wx.bound && wx.status.startsWith("已连接"))
    }

    private var lastSync = 0L
    private var reportedUntil = 0L
    private var wasConnected = false

    fun start() {
        bridge.standby = ::standbyReason
        bridge.onAnswered = { scope.launch { report() } }
        scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(2_000)
            }
        }
    }

    /** 电脑在接就待命；还没弄清电脑在不在接时也先等着。返回 null = 手机可以接。 */
    fun standbyReason(): String? {
        val r = hub.remote.value ?: return null
        return when (r.conn.value) {
            is ConnState.Connecting -> CHECKING
            is ConnState.Connected -> {
                val st = r.store.state.value
                when {
                    // 连上了但电脑的状态还没到（weixinCapable 是电脑状态里才有的）
                    !st.weixinCapable -> CHECKING
                    hostAnswering(st.weixin) -> STANDBY
                    else -> null
                }
            }
            else -> null
        }
    }

    private suspend fun tick() {
        val remote = hub.remote.value
        val connected = remote != null && remote.conn.value is ConnState.Connected
        val st = remote?.store?.state?.value
        if (!connected || st == null || !st.weixinCapable) { wasConnected = false; return }
        // 电脑在接：它最后一次说话以前发来的消息都归它
        if (hostAnswering(st.weixin)) bridge.raiseFence(remote.lastHeardAt)
        if (!wasConnected) { wasConnected = true; reportedUntil = 0; report() }
        val host = st.weixin
        val on = engine.state.settings.weixin.enabled
        when {
            // 电脑解除了绑定：同步来的那份也删掉
            !host.bound && bridge.isShared -> bridge.forgetShared()
            // 电脑绑了（或者换了）微信：拿过来。手机自己扫码绑的不动
            on && host.bound && host.botId.isNotEmpty() && (!bridge.isBound || (bridge.isShared && bridge.botId != host.botId)) &&
                System.currentTimeMillis() - lastSync > 60_000 -> {
                lastSync = System.currentTimeMillis()
                sync(remote)
            }
        }
    }

    private suspend fun sync(remote: RemoteBackend) {
        val r = remote.call(Command.WeixinShare)
        if (r.ok) bridge.importShared(r.data)
    }

    /** 告诉电脑手机答到了哪儿（连上的时候、每答完一条）。 */
    private suspend fun report() {
        val remote = hub.remote.value ?: return
        if (remote.conn.value !is ConnState.Connected) return
        val t = bridge.answeredUntil
        if (t <= reportedUntil) return
        if (remote.call(Command.WeixinReport(t)).ok) reportedUntil = t
    }
}
