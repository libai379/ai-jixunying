package com.guixing.jixunying.engine

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.CommandResult
import com.guixing.jixunying.model.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机检查更新的退路（2026-10-30）：手机直连 GitHub 常下不动，配对的电脑开着就请电脑代下（电脑走它的代理），
 * 下好放在电脑的文件区，手机用 GetFilePart 一段 512KB 地取回来（和取大视频同一套，见 RemoteBackend.fileBytes）。
 * 电脑太老（状态里没有 appVersion，不认 HostFetchUpdate，问了也只能干等 10 分钟）就不问，直接说。
 * 核对 SHA-256 在 Updater.adoptBytes 里做（电脑那边下完也核对过一次）。
 *
 * 2026-11-04 华为卡在 50%：电脑 16 秒就下完了，手机进度停在一半、一个字节不再收，按钮一直「正在下载…」。
 * 华为把第三方程序的系统日志屏蔽了，手机这边到底在等哪句话没法确认（教训 163），所以：
 * ①每一步记日志（log，进 jxy.log，「发给电脑」能拿回来）；②电脑下完了 30 秒还没回话就再问（原来要干等 10 分钟）。
 */
class UpdateViaHost(private val hub: Hub, private val log: (String) -> Unit = {}) {
    suspend fun fetch(info: UpdateInfo, onProgress: (Float) -> Unit): ByteArray {
        val r = hub.remote.value ?: throw IllegalStateException("这台手机没配对电脑，没法请电脑代下")
        if (r.conn.value !is ConnState.Connected) throw IllegalStateException("电脑现在没连上，没法请电脑代下")
        val hostVersion = r.store.state.value.appVersion
        if (hostVersion.isEmpty()) throw IllegalStateException("电脑上的 AI集训营 太老，不会代下；先把电脑更新了")
        log("请电脑代下 ${info.assetName}（电脑是 $hostVersion）")
        var id: String? = null
        var why = ""
        // 进度：电脑下（看电脑状态里的 hostFetchProgress）算前一半，传回手机算后一半。10-31 真机上电脑下了六分半，手机一直停在 0%，看着像卡死
        coroutineScope {
            val watch = launch { r.store.state.collect { s -> if (s.hostFetchProgress >= 0f) onProgress(s.hostFetchProgress * 0.5f) } }
            try {
                // 电脑下几十 MB 可能比一次请求等得还久：超时了再问，电脑那边下好的不会重下
                for (i in 1..4) {
                    val t0 = System.currentTimeMillis()
                    val res = askHost(r, info)
                    val secs = (System.currentTimeMillis() - t0) / 1000
                    if (res == null) { log("第 $i 次问：电脑那边下完了，$secs 秒没等到回话，再问一次"); why = "电脑下完了但回话没到手机"; continue }
                    log("第 $i 次问：$secs 秒后电脑回了：" + if (res.ok) "下好了（文件 ${res.data}）" else res.message)
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
        val t0 = System.currentTimeMillis()
        return coroutineScope {
            var logged = -1
            val watch = launch {
                r.downloads.collect { m ->
                    m[fileId]?.let { p ->
                        onProgress(0.5f + p * 0.5f)
                        val tenth = (p * 10).toInt()
                        if (tenth != logged) { logged = tenth; log("从电脑取：${tenth * 10}%（${(System.currentTimeMillis() - t0) / 1000} 秒）") }
                    }
                }
            }
            try {
                val bytes = r.fileBytes(fileId)
                log(if (bytes == null) "从电脑取没成（${(System.currentTimeMillis() - t0) / 1000} 秒，停在 ${maxOf(0, logged) * 10}% 左右）"
                    else "从电脑取完：${bytes.size} 字节，${(System.currentTimeMillis() - t0) / 1000} 秒")
                bytes ?: throw IllegalStateException("电脑下好了，但取回手机没成功（网不好？再试一次，或者在「关于」里换条路）")
            } finally {
                watch.cancel()
            }
        }
    }

    /**
     * 问一次电脑；电脑那边下完了（进度从下载中回到 -1）30 秒还没回话，就当回话在中转上丢了，返回 null 让上面再问
     * （电脑那边已经下好的，再问马上就回）。电脑没在下（早就下好了）就照常等回话。
     */
    private suspend fun askHost(r: RemoteBackend, info: UpdateInfo): CommandResult? = coroutineScope {
        val ask = async { r.call(Command.HostFetchUpdate(info.assetUrl, info.sha256, info.assetSize)) }
        val watchdog = launch {
            r.store.state.first { it.hostFetchProgress >= 0f }
            r.store.state.first { it.hostFetchProgress < 0f }
            delay(hostDoneGraceMs)
            ask.cancel()
        }
        try {
            ask.await()
        } catch (e: CancellationException) {
            if (!isActive) throw e
            null
        } finally {
            watchdog.cancel()
        }
    }

    companion object {
        /** 电脑下完以后等回话的时间（测试里改短）。 */
        @Volatile var hostDoneGraceMs = 30_000L
    }
}
