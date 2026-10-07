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
