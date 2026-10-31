package com.guixing.jixunying.engine

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.WeixinBot
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
 * - 一台电脑连多台手机（2026-10-24）：电脑不接的时候只让一台手机接——电脑状态里的 weixinBackup（默认是最后一台来拿绑定的，
 *   电脑 设置 → 微信 能换）。手机把它记在本机 [backupFile]，电脑关着、手机重启了也知道该不该接。用自己扫码绑的不受这条管。
 * - 电脑挂几个微信（2026-10-30 多微信方案甲）：每个微信分开算。手机只拿它自己遥控电脑时绑的、加上电脑自己绑的；
 *   电脑在接哪个微信，手机那一份就待命；电脑关了，它自己绑的它接，电脑自己绑的归接班手机。
 *   手机自己扫码绑的：电脑没在接同一个微信就照常答（以前电脑在接任何一个都待命，A 手机自己绑的就用不了）。
 */
class WeixinHandover(
    private val hub: Hub,
    private val engine: Engine,
    private val bridge: WeixinBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val backupFile: java.io.File? = null,
) {
    companion object {
        const val STANDBY = "电脑在接微信，这台手机待命（电脑关机或关掉微信助理后，手机自动接上）"
        const val CHECKING = "正在看电脑在不在接微信…"
        const val OTHER_PHONE = "电脑关机时由别的手机接微信，这台待命（在电脑 设置 → 微信 里能换成这台）"

        /** 电脑正在收微信消息。老版本电脑没有 answering 这一项，按「已连接」判断。 */
        fun hostAnswering(wx: WeixinInfo) = wx.answering || (wx.bound && wx.status.startsWith("已连接"))
    }

    /** 电脑上定的接班手机（设备编号；"none" = 都不接；空 = 没定）。 */
    @Volatile private var backup: String = backupFile?.let { f -> runCatching { f.readText().trim() }.getOrNull() }.orEmpty()

    private var lastSync = 0L
    private val reportedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var wasConnected = false
    /** 电脑是老版本、不认 WeixinShareAll：退回一份的 WeixinShare。 */
    private var hostOld = false

    fun start() {
        bridge.standby = ::standbyReason
        bridge.onAnswered = { _, _ -> scope.launch { report() } }
        scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(2_000)
            }
        }
    }

    /** 电脑上挂着的几个微信。老版本电脑没有 bots，只有汇总的一个（算电脑自己绑的）。 */
    private fun hostBots(wx: WeixinInfo): List<WeixinBot> =
        wx.bots.ifEmpty { if (wx.bound && wx.botId.isNotEmpty()) listOf(WeixinBot(wx.botId, answering = hostAnswering(wx))) else emptyList() }

    /** 电脑在接这个微信就待命；还没弄清电脑在不在接时也先等着。返回 null = 手机可以接。 */
    fun standbyReason(bot: WeixinBridge.BotRef): String? {
        val r = hub.remote.value ?: return null
        return when (r.conn.value) {
            is ConnState.Connecting -> CHECKING
            is ConnState.Connected -> {
                val st = r.store.state.value
                when {
                    // 连上了但电脑的状态还没到（weixinCapable 是电脑状态里才有的）
                    !st.weixinCapable -> CHECKING
                    hostAnswers(st.weixin, bot) -> STANDBY
                    else -> notMyTurn(r.host.deviceId, bot)
                }
            }
            else -> notMyTurn(r.host.deviceId, bot)
        }
    }

    /** 电脑在接这个微信。老版本电脑说不清是哪个：在接就算在接（和以前一样）。 */
    private fun hostAnswers(wx: WeixinInfo, bot: WeixinBridge.BotRef): Boolean {
        if (wx.bots.isEmpty()) return hostAnswering(wx)
        return wx.bots.any { it.answering && (it.botId == bot.botId || (bot.userId.isNotEmpty() && it.userId == bot.userId)) }
    }

    /**
     * 电脑不接时：手机自己扫码绑的照常接；同步来的、是这台手机绑的照常接；同步来的、电脑自己绑的看接班的是不是这台
     * （电脑定了别的手机接班、或者都不接，这台就待命）。
     */
    private fun notMyTurn(me: String, bot: WeixinBridge.BotRef): String? {
        if (!bot.shared) return null
        if (bot.boundBy.isNotEmpty()) return if (bot.boundBy == me) null else OTHER_PHONE
        val b = backup
        return if (b == "none" || (b.isNotEmpty() && b != me)) OTHER_PHONE else null
    }

    private suspend fun tick() {
        val remote = hub.remote.value
        val connected = remote != null && remote.conn.value is ConnState.Connected
        val st = remote?.store?.state?.value
        if (!connected || st == null || !st.weixinCapable) { wasConnected = false; return }
        if (st.weixinBackup != backup) {
            backup = st.weixinBackup
            backupFile?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(backup) } }
        }
        val host = hostBots(st.weixin)
        // 电脑在接的那几个：它最后一次说话以前发来的消息都归它
        for (b in host) if (b.answering) bridge.raiseFence(b.botId, remote.lastHeardAt)
        if (!wasConnected) { wasConnected = true; reportedUntil.clear(); report() }
        val me = remote.host.deviceId
        val on = engine.state.settings.weixin.enabled
        // 这台手机该拿的：它自己绑的 + 电脑自己绑的
        val wanted = host.filter { it.boundBy.isEmpty() || it.boundBy == me }
        val mine = bridge.refs()
        // 电脑那边没有了（解除了、或者换了一个）：同步来的那份删掉
        mine.filter { it.shared && host.none { h -> h.botId == it.botId } }.forEach { bridge.forgetShared(it.botId) }
        // 电脑上有、这台还没有的：拿过来。手机自己扫码绑的同一个微信不动
        val missing = wanted.filter { h -> mine.none { it.botId == h.botId || (!it.shared && h.userId.isNotEmpty() && it.userId == h.userId) } }
        if (on && missing.isNotEmpty() && System.currentTimeMillis() - lastSync > 60_000) {
            lastSync = System.currentTimeMillis()
            sync(remote)
        }
    }

    private suspend fun sync(remote: RemoteBackend) {
        if (!hostOld) {
            val r = remote.call(Command.WeixinShareAll)
            if (r.ok) { bridge.importSharedAll(r.data); return }
            // 老版本电脑看不懂这条命令
            hostOld = true
        }
        val r = remote.call(Command.WeixinShare)
        if (r.ok) bridge.importShared(r.data)
    }

    /** 告诉电脑手机答到了哪儿（连上的时候、每答完一条），每个微信分开报。 */
    private suspend fun report() {
        val remote = hub.remote.value ?: return
        if (remote.conn.value !is ConnState.Connected) return
        for (b in bridge.refs().filter { it.shared }) {
            val t = bridge.answeredUntil(b.botId)
            if (t <= (reportedUntil[b.botId] ?: 0)) continue
            if (remote.call(Command.WeixinReport(t, b.botId)).ok) reportedUntil[b.botId] = t
        }
    }
}
