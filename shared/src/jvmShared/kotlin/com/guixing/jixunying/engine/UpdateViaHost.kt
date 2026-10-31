package com.guixing.jixunying.engine

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.UpdateInfo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 手机检查更新的退路（2026-10-30）：手机直连 GitHub 常下不动，配对的电脑开着就请电脑代下（电脑走它的代理），
 * 下好放在电脑的文件区，手机用 GetFilePart 一段 512KB 地取回来（和取大视频同一套，见 RemoteBackend.fileBytes）。
 * 电脑太老（状态里没有 appVersion，不认 HostFetchUpdate，问了也只能干等 10 分钟）就不问，直接说。
 * 核对 SHA-256 在 Updater.adoptBytes 里做（电脑那边下完也核对过一次）。
 */
class UpdateViaHost(private val hub: Hub) {
    suspend fun fetch(info: UpdateInfo, onProgress: (Float) -> Unit): ByteArray {
        val r = hub.remote.value ?: throw IllegalStateException("这台手机没配对电脑，没法请电脑代下")
        if (r.conn.value !is ConnState.Connected) throw IllegalStateException("电脑现在没连上，没法请电脑代下")
        if (r.store.state.value.appVersion.isEmpty()) throw IllegalStateException("电脑上的 AI集训营 太老，不会代下；先把电脑更新了")
        var id: String? = null
        var why = ""
        // 进度：电脑下（看电脑状态里的 hostFetchProgress）算前一半，传回手机算后一半。10-31 真机上电脑下了六分半，手机一直停在 0%，看着像卡死
        coroutineScope {
            val watch = launch { r.store.state.collect { s -> if (s.hostFetchProgress >= 0f) onProgress(s.hostFetchProgress * 0.5f) } }
            try {
                // 电脑下几十 MB 可能比一次请求等得还久：超时了再问，电脑那边下好的不会重下
                for (i in 1..3) {
                    val res = r.call(Command.HostFetchUpdate(info.assetUrl, info.sha256, info.assetSize))
                    if (res.ok) { id = res.data; break }
                    why = res.message
                    if ("只替" in why || "校验值" in why || "太大" in why) break
                }
            } finally {
                watch.cancel()
            }
        }
        val fileId = id ?: throw IllegalStateException("电脑也没下成（$why）")
        onProgress(0.5f)
        return coroutineScope {
            val watch = launch { r.downloads.collect { m -> m[fileId]?.let { onProgress(0.5f + it * 0.5f) } } }
            try {
                r.fileBytes(fileId) ?: throw IllegalStateException("电脑下好了，但取回手机没成功（网不好？再试一次）")
            } finally {
                watch.cancel()
            }
        }
    }
}
