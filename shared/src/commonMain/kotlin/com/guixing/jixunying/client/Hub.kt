package com.guixing.jixunying.client

import com.guixing.jixunying.model.PairedHost
import com.guixing.jixunying.model.PairingCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 一台设备上的全部「后端」：
 * - local：本机引擎，电脑和手机都有，手机不连电脑也能单独用；
 * - remote：手机配对过电脑时，遥控那台电脑（隔多远都行，走加密中转）。
 * 电脑上只有 local，配对信息由电脑的 RelayHost 管。
 */
class Hub(
    val local: Backend,
    private val linkFactory: ((PairedHost) -> FrameLink)? = null,
    private val pairer: (suspend (PairingCode, String) -> Result<PairedHost>)? = null,
    private val deviceName: String = "",
    private val persist: (PairedHost?) -> Unit = {},
) {
    private val _remote = MutableStateFlow<RemoteBackend?>(null)
    val remote: StateFlow<RemoteBackend?> = _remote

    /** 当前界面操作的是电脑（true）还是本机（false）。 */
    val useRemote = MutableStateFlow(false)

    val canPair: Boolean get() = pairer != null

    /** 和电脑同步记忆、账本、微信对话的情况（engine/DeviceSync 在手机上填；电脑上一直是空的）。 */
    val sync = MutableStateFlow(com.guixing.jixunying.model.SyncInfo())
    /** 点「现在同步」。手机上由 DeviceSyncer 设置；null = 这台设备不同步（电脑）。 */
    var syncNow: (() -> Unit)? = null

    /**
     * 切到电脑（2026-11-04 用户：「电脑没有开机，手机的 APP 是不能直接切过去的」「要提示一下电脑未开机或者未连接」）：
     * 电脑在线才切。返回 null = 切过去了；否则是没切的原因，给界面提示。
     */
    fun switchToRemote(): String? {
        val r = _remote.value ?: return "还没配对电脑：到 设置 → 连接电脑 扫码配对"
        return when (val c = r.conn.value) {
            is ConnState.Connected -> { useRemote.value = true; null }
            is ConnState.Failed -> "没切过去：" + c.reason
            else -> "没切过去：正在找电脑，还没连上（电脑可能没开机，或者 AI集训营 没打开），等几秒再试"
        }
    }

    fun attach(host: PairedHost) {
        val factory = linkFactory ?: return
        _remote.value?.stop()
        _remote.value = RemoteBackend(host, factory(host), deviceName).also { it.start() }
    }

    suspend fun pair(code: PairingCode): Result<PairedHost> {
        val p = pairer ?: return Result.failure(IllegalStateException("这台设备不支持配对"))
        return p(code, deviceName).onSuccess { host ->
            persist(host)
            attach(host)
            useRemote.value = true
        }
    }

    fun unpair() {
        _remote.value?.stop()
        _remote.value = null
        useRemote.value = false
        persist(null)
    }
}
