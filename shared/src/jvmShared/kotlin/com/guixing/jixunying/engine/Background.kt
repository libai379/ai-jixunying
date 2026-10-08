package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.BgJob
import com.guixing.jixunying.model.BgProblem
import java.util.concurrent.ConcurrentHashMap

/**
 * 记录员后台活（压缩聊天、挑记忆、点名、立场档案）出没出错。以前出错就悄悄停掉，用户不知道。
 * - 出错：记进 AppState.bgProblems（界面常驻显示），弹一次提示；同一样活半小时内只弹一次，免得刷屏。
 * - 成功一次：这样活的错清掉。
 */
internal class BackgroundWatch(
    private val stateOf: () -> AppState,
    private val update: ((AppState) -> AppState) -> Unit,
    private val notice: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastNotice = ConcurrentHashMap<BgJob, Long>()

    fun ok(job: BgJob) {
        if (stateOf().bgProblems.none { it.job == job }) return
        update { s -> s.copy(bgProblems = s.bgProblems.filterNot { it.job == job }) }
        lastNotice.remove(job)
    }

    fun failed(job: BgJob, reason: String, convTitle: String = "") {
        val now = clock()
        var problem: BgProblem? = null
        update { s ->
            val old = s.bgProblems.firstOrNull { it.job == job }
            val p = BgProblem(job, reason, now, convTitle, (old?.times ?: 0) + 1)
            problem = p
            s.copy(bgProblems = s.bgProblems.filterNot { it.job == job } + p)
        }
        val last = lastNotice[job]
        if (last == null || now - last > NOTICE_EVERY) {
            lastNotice[job] = now
            problem?.let { notice(it.noticeText()) }
        }
    }

    /** 「知道了」：先不显示，再出错照样记、照样提示。 */
    fun dismiss(job: BgJob) {
        update { s -> s.copy(bgProblems = s.bgProblems.filterNot { it.job == job }) }
        lastNotice.remove(job)
    }

    companion object {
        const val NOTICE_EVERY = 30 * 60_000L
        const val NO_RECORDER = "没有能用的模型当记录员：到 设置 → 模型服务 配一个服务商，或在 设置 → 记忆 里选一个记录员"
        const val UNREADABLE = "记录员的回答看不懂：这个模型可能不适合当记录员，可以在 设置 → 记忆 换一个（mimo-v2.6-flash 这类便宜的就够）"
    }
}
