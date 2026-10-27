package com.guixing.jixunying.engine

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.client.RemoteBackend
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.ChannelBatch
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.LedgerBatch
import com.guixing.jixunying.model.PeerConfig
import com.guixing.jixunying.model.SyncInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 手机上跑：连着配对的电脑时，两边弄成一样（2026-10-27 用户：「除了本地模型用不了，没法同步之外，其他的都一样」）：
 * 模型服务、成员（[ConfigSync]，谁改得晚听谁的、删了跟着删）→ 花费账本 → 微信助理的对话（用户 10-27：「聊天记录只同步微信助手」）。
 * 界面、个性化、各种设置不同步，各管各的（用户 10-27：「别把 UI 设计也跟着过去……都同步了，那是 bug」）。
 * 记忆另由 [MemorySyncer] 每 3 秒对一次（小）。一条规矩：连上那一下（电脑开机、手机打开应用）、之后每 2 分钟、点「现在同步」
 * 或电脑上点「让手机现在同步」，都是同一遍。
 * 避让（用户：「后台占的带宽或者流量太大……有其他运行的东西的话，就要学会避让，小一点慢慢的更新」）：
 * - 每批之前量一次别的应用上网多忙（[networkLoad]）：每秒超过 [BUSY_BYTES] 就停下来等，最多等 [MAX_YIELD_MS]，之后用最小的批次接着走；
 * - 用手机流量（按流量计费的网）时批次压到最小、批和批之间多歇一会儿；
 * - 一批来回超过 5 秒就把批次减半，很快就慢慢加回去。
 * 进度（上次发到哪、收到哪）存在 <数据目录>/sync/<电脑编号>.json；断了下次从那里接着，重发的两边都会去重。
 */
class DeviceSyncer(
    private val hub: Hub,
    private val engine: Engine,
    /** 这台手机的名字（电脑上的账本、记录里写「手机 xxx」）。 */
    private val phoneName: String,
    private val root: File,
    private val load: () -> NetLoad = ::networkLoad,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
    /** 测试里调小。 */
    private val pauseMs: Long = 400,
) {
    @Serializable
    data class Cursor(val push: Long = 0, val pull: Long = 0, val seen: Long = 0)

    @Serializable
    data class Progress(val ledger: Cursor = Cursor(), val channels: Map<String, Cursor> = emptyMap())

    private val kick = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        hub.syncNow = { kick.trySend(Unit) }
        scope.launch {
            var wasConnected = false
            var lastRun = 0L
            var handledRequest = -1L
            while (isActive) {
                val remote = hub.remote.value
                val connected = remote != null && remote.conn.value is ConnState.Connected
                var manual = withTimeoutOrNull(if (connected && !wasConnected) 1 else 5_000L) { kick.receive() } != null
                // 电脑上点了「让手机现在同步」（第一次看到的不算，那是以前点的）
                val req = remote?.store?.state?.value?.syncRequestAt ?: 0L
                if (connected) { if (handledRequest >= 0 && req > handledRequest) manual = true; handledRequest = req }
                if (connected && remote != null && (manual || !wasConnected || clock() - lastRun > EVERY_MS)) {
                    runCatching { runOnce(remote, firstThisSession = !wasConnected) }
                        .onFailure { e -> hub.sync.value = hub.sync.value.copy(running = false, yielding = "", error = (e.message ?: e.toString()).take(120)) }
                    lastRun = clock()
                }
                wasConnected = connected
            }
        }
    }

    private fun file(hostId: String) = File(root, "sync/$hostId.json")

    private fun loadProgress(hostId: String): Progress =
        runCatching { AppJson.decodeFromString(Progress.serializer(), file(hostId).readText()) }.getOrDefault(Progress())

    private fun saveProgress(hostId: String, p: Progress) = runCatching {
        file(hostId).parentFile.mkdirs()
        file(hostId).writeText(AppJson.encodeToString(Progress.serializer(), p))
    }

    /** 跑一遍；返回 false = 没跑完（原因在 hub.sync）。 */
    internal suspend fun runOnce(remote: RemoteBackend, firstThisSession: Boolean = true): Boolean {
        hub.sync.value = hub.sync.value.copy(running = true, error = "", yielding = "")
        val hostId = remote.host.hostId
        val hostLabel = "电脑 " + remote.host.hostName.ifBlank { "电脑" }
        var p = loadProgress(hostId)
        // 刚连上：往回多对一段（调用开始时记的时间，可能比上次的进度早），重复的会被去重
        fun back(t: Long) = if (firstThisSession) (t - OVERLAP_MS).coerceAtLeast(0) else t
        var sent = 0
        var got = 0
        val pacer = Pacer()

        // 配置：先取电脑的，把两边各自建的同一个东西对成同一个编号，合好，自己换上，再交给电脑换上（电脑打平时听手机合好的）
        val pcR = remote.call(Command.SyncConfig(PeerConfig()))
        if (!pcR.ok) return finish(pcR.message, 0, 0)
        val pc = AppJson.decodeFromString(PeerConfig.serializer(), pcR.data)
        engine.alignToPeer(pc)
        val merged = engine.mergePeerConfig { mine -> ConfigSync.merge(mine, pc) }
        val cfgBack = remote.call(Command.SyncConfig(merged))
        if (!cfgBack.ok) return finish(cfgBack.message, 0, 0)

        // 账本
        var cur = p.ledger.let { Cursor(back(it.push), back(it.pull)) }
        while (true) {
            val limit = pacer.limit(LEDGER_MAX, LEDGER_MIN)
            val push = engine.exportLedger(cur.push, limit)
            val t0 = clock()
            val r = remote.call(Command.SyncLedger(push, phoneName, cur.pull, limit))
            if (!r.ok) return finish(r.message, sent, got)
            val back = AppJson.decodeFromString(LedgerBatch.serializer(), r.data)
            got += engine.importLedger(back, hostLabel)
            sent += back.added
            cur = Cursor(advance(cur.push, push.until, push.more), advance(cur.pull, back.until, back.more))
            p = p.copy(ledger = cur); saveProgress(hostId, p)
            if (!push.more && !back.more) break
            pacer.after(clock() - t0)
        }

        // 对话：两边有的都对一遍；上次对过以后两边都没动过的跳过（不用每个都来回一趟）
        val all = engine.state.conversations + remote.store.state.value.conversations
        val changed = all.mapNotNull { c -> engine.syncKeyOf(c)?.let { it to engine.convChangedAt(c) } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.max() }
        for ((ch, at) in changed) {
            val old = p.channels[ch]
            if (old != null && at <= old.seen) continue
            var cc = (old ?: Cursor()).let { Cursor(back(it.push), back(it.pull)) }
            while (true) {
                val limit = pacer.limit(CHANNEL_MAX, CHANNEL_MIN)
                val push = engine.exportChannel(ch, cc.push, limit)
                val t0 = clock()
                val r = remote.call(Command.SyncChannel(push, phoneName, cc.pull, limit))
                if (!r.ok) return finish(r.message, sent, got)
                val back = AppJson.decodeFromString(ChannelBatch.serializer(), r.data)
                got += engine.importChannel(back, hostLabel)
                sent += back.added
                cc = Cursor(advance(cc.push, push.until, push.more), advance(cc.pull, back.until, back.more))
                val done = !push.more && !back.more
                // 对完了才记「看到这个时间了」；两边合完自己这边的时间可能又往后挪了，下次再看一眼也没事
                p = p.copy(channels = p.channels + (ch to if (done) cc.copy(seen = at) else cc)); saveProgress(hostId, p)
                if (done) break
                pacer.after(clock() - t0)
            }
        }
        finish("", sent, got)
        runCatching { remote.call(Command.SyncDone(hub.sync.value.summary)) }
        return true
    }

    /** 下一批从哪要：这批最后一条的时间；整批都在同一毫秒、还有更多时往后挪 1 毫秒，免得原地打转。 */
    private fun advance(from: Long, until: Long, more: Boolean) = if (more && until <= from) from + 1 else maxOf(from, until)

    private fun finish(error: String, sent: Int, got: Int): Boolean {
        val old = error.contains("不认识") || error.contains("Unknown", ignoreCase = true) || error.contains("unknown", ignoreCase = true)
        hub.sync.value = SyncInfo(
            running = false, lastAt = if (error.isBlank()) clock() else hub.sync.value.lastAt,
            summary = if (sent + got == 0) "两边已经一样" else "发过去 $sent 条、收到 $got 条（重复的不算）",
            error = when { error.isBlank() -> ""; old -> "电脑上的 AI集训营 版本太老，不认识两端同步，换新版后自动同步"; else -> error.take(120) },
        )
        return error.isBlank()
    }

    /** 批次大小和节奏。 */
    private inner class Pacer {
        private var scale = 1.0

        fun limit(max: Int, min: Int): Int {
            val l = load()
            if (l.metered) return min
            return (max * scale).toInt().coerceIn(min, max)
        }

        suspend fun after(tookMs: Long) {
            scale = if (tookMs > SLOW_MS) (scale / 2).coerceAtLeast(0.05) else (scale * 1.5).coerceAtMost(1.0)
            var l = load()
            var waited = 0L
            while (l.otherBytesPerSec > BUSY_BYTES && waited < MAX_YIELD_MS) {
                hub.sync.value = hub.sync.value.copy(yielding = "别的应用正在大量上网（每秒约 ${l.otherBytesPerSec / 1024} KB），先让一让")
                scale = 0.05
                delay(5_000); waited += 5_000
                l = load()
            }
            hub.sync.value = hub.sync.value.copy(yielding = if (l.metered) "用的是手机流量，慢慢传" else "")
            delay(if (l.metered) pauseMs * 5 else maxOf(pauseMs, tookMs))
        }
    }

    companion object {
        const val EVERY_MS = 2 * 60_000L
        const val OVERLAP_MS = 15 * 60_000L
        const val BUSY_BYTES = 256 * 1024L
        const val MAX_YIELD_MS = 2 * 60_000L
        const val SLOW_MS = 5_000L
        const val LEDGER_MAX = 200
        const val LEDGER_MIN = 20
        const val CHANNEL_MAX = 40
        const val CHANNEL_MIN = 5
    }
}

/**
 * 两端的配置怎么合（2026-10-27 用户：「除了本地模型用不了，没法同步之外，其他的都一样」）。和 [MemorySync] 一个规矩：
 * - 同一个编号：改得晚的为准；一样晚时 preferB = true 听 b 的（电脑收手机合好的那份用），服务商再打平就留有 Key 的。
 * - 删除：删的时间不早于最后一次修改就算删了；删除记录留 90 天。
 * 编号不同的同一个东西（以前两边各自建的）由手机在合之前先对齐编号（Engine.alignToPeer）。
 */
object ConfigSync {
    private fun tombs(a: List<com.guixing.jixunying.model.MemoryTombstone>, b: List<com.guixing.jixunying.model.MemoryTombstone>, now: Long) =
        (a + b).groupBy { it.id }.map { (_, l) -> l.maxBy { it.deletedAt } }.filter { now - it.deletedAt < MemorySync.KEEP_DELETED_MS }.sortedBy { it.id }

    private fun <T> pick(a: List<T>, b: List<T>, id: (T) -> String, at: (T) -> Long, preferB: Boolean, tie: (T, T) -> T): List<T> {
        val out = LinkedHashMap<String, T>()
        for (x in a) out[id(x)] = x
        for (y in b) {
            val x = out[id(y)]
            out[id(y)] = when {
                x == null -> y
                at(y) > at(x) -> y
                at(y) < at(x) -> x
                else -> if (preferB) y else tie(x, y)
            }
        }
        return out.values.toList()
    }

    fun merge(a: com.guixing.jixunying.model.PeerConfig, b: com.guixing.jixunying.model.PeerConfig, preferB: Boolean = false,
              now: Long = System.currentTimeMillis()): com.guixing.jixunying.model.PeerConfig {
        val deleted = tombs(a.deleted, b.deleted, now)
        val delAt = deleted.associate { it.id to it.deletedAt }
        fun alive(id: String, at: Long) = delAt[id]?.let { it < at } ?: true
        val providers = pick(a.providers, b.providers, { it.id }, { it.updatedAt }, preferB) { x, y -> if (x.apiKey.isBlank() && y.apiKey.isNotBlank()) y else x }
            .filter { alive(it.id, it.updatedAt) }
        val members = pick(a.members, b.members, { it.id }, { it.updatedAt }, preferB) { x, _ -> x }.filter { alive(it.id, it.updatedAt) }
        return com.guixing.jixunying.model.PeerConfig(providers, members, deleted, tombs(a.convDeleted, b.convDeleted, now))
    }
}
