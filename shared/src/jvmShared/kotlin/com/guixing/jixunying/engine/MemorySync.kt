package com.guixing.jixunying.engine

import com.guixing.jixunying.client.ConnState
import com.guixing.jixunying.client.Hub
import com.guixing.jixunying.model.AppJson
import com.guixing.jixunying.model.Command
import com.guixing.jixunying.model.MemoryBundle
import com.guixing.jixunying.model.MemoryItem
import com.guixing.jixunying.model.MemoryTombstone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 手机和电脑的长期记忆互相同步（2026-10-24 用户：「两端之间的超级记忆是互相同步的」）。
 * 以前只有手机上点「同步电脑上的模型服务和成员」时把电脑的记忆单向补到手机，手机记下的、删掉的都不回电脑。
 *
 * 合并规则（两边用同一个 [merge]，谁先谁后结果一样）：
 * - 同一个编号：以后改的（updatedAt 大的）为准。
 * - 删除：记一条「编号 + 删的时间」；删的时间不早于最后一次修改就算删了（删了以后另一边又改过，就留着改过的）。删除记录留 90 天。
 * - 编号不同但字一模一样（老版本各记各的）：留改得晚的，另一条记成删除。字差不多的不动——那是记录员的事，同步不替人判断。
 */
object MemorySync {
    const val KEEP_DELETED_MS = 90L * 24 * 3600 * 1000

    fun merge(a: MemoryBundle, b: MemoryBundle, now: Long = System.currentTimeMillis()): MemoryBundle {
        val deleted = (a.deleted + b.deleted).groupBy { it.id }.map { (_, l) -> l.maxBy { it.deletedAt } }
            .filter { now - it.deletedAt < KEEP_DELETED_MS }.toMutableList()
        val byId = LinkedHashMap<String, MemoryItem>()
        for (m in a.items + b.items) {
            val cur = byId[m.id]
            if (cur == null || m.updatedAt > cur.updatedAt) byId[m.id] = m
        }
        val delAt = deleted.associate { it.id to it.deletedAt }
        val alive = byId.values.filter { m -> delAt[m.id]?.let { it < m.updatedAt } ?: true }
        val kept = mutableListOf<MemoryItem>()
        val seen = HashMap<String, Int>()
        for (m in alive) {
            val key = m.text.trim()
            val i = seen[key]
            if (i == null) { seen[key] = kept.size; kept += m; continue }
            val other = kept[i]
            val (win, lose) = if (m.updatedAt > other.updatedAt || (m.updatedAt == other.updatedAt && m.id > other.id)) m to other else other to m
            kept[i] = win
            deleted.removeAll { it.id == lose.id }
            deleted += MemoryTombstone(lose.id, lose.updatedAt)
        }
        return MemoryBundle(kept, deleted.sortedBy { it.id })
    }

    /** 两边是不是已经一样了（不看顺序）。 */
    fun same(a: MemoryBundle, b: MemoryBundle) =
        a.items.associate { it.id to it.updatedAt } == b.items.associate { it.id to it.updatedAt } &&
            a.deleted.associate { it.id to it.deletedAt } == b.deleted.associate { it.id to it.deletedAt }
}

/**
 * 手机上跑：配对的电脑连着时，两边的记忆一变就同步一次（先把手机的发给电脑合，再把合好的合进手机）。
 * 手机在遥控电脑时也一样同步，断开以后手机自己用的也是最新的。电脑版本太老、不认识这条指令就不同步，也不报错。
 */
class MemorySyncer(
    private val hub: Hub,
    private val engine: Engine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Volatile var lastError: String = ""
        private set

    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { lastError = it.message ?: it.toString() }
                delay(3_000)
            }
        }
    }

    internal suspend fun tick() {
        val remote = hub.remote.value ?: return
        if (remote.conn.value !is ConnState.Connected) return
        val host = remote.store.state.value
        val mine = engine.state.let { MemoryBundle(it.memories, it.memoryDeleted) }
        val theirs = MemoryBundle(host.memories, host.memoryDeleted)
        if (MemorySync.same(mine, theirs)) return
        val r = remote.call(Command.SyncMemories(mine))
        if (!r.ok) { lastError = r.message; return }
        val merged = AppJson.decodeFromString(MemoryBundle.serializer(), r.data)
        engine.call(Command.SyncMemories(merged))
        lastError = ""
    }
}
