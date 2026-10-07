package com.guixing.jixunying.engine

import com.guixing.jixunying.model.AppState
import com.guixing.jixunying.model.ConfigBundle
import com.guixing.jixunying.model.Member
import com.guixing.jixunying.model.ModelInfo
import com.guixing.jixunying.model.ProviderConfig
import com.guixing.jixunying.model.SearchSettings
import com.guixing.jixunying.model.UserProfile

/**
 * 把电脑上的配置并进手机、以及清理重复的服务商和成员。
 * 判断「是不是同一个」看内容不看编号：电脑和手机各自建的同一个东西编号不一样。
 * - 服务商：接口地址一样，并且 Key 一样（或有一边没填）= 同一个
 * - 成员：名字一样、模型一样、服务商是同一家（接口地址一样）= 同一位
 */
object ConfigMerge {

    fun normUrl(u: String) = u.trim().trimEnd('/').lowercase().removeSuffix("/v1").trimEnd('/')

    private fun sameService(a: ProviderConfig, b: ProviderConfig) = normUrl(a.baseUrl) == normUrl(b.baseUrl)

    private fun sameAccount(a: ProviderConfig, b: ProviderConfig) =
        sameService(a, b) && (a.apiKey.trim() == b.apiKey.trim() || a.apiKey.isBlank() || b.apiKey.isBlank())

    private fun mergeModels(a: List<ModelInfo>, b: List<ModelInfo>) = a + b.filter { m -> a.none { it.id == m.id } }

    class ImportResult(
        val state: AppState,
        /** 被合并掉的成员编号 → 保留的成员编号（聊天记录里的发言人要跟着改）。 */
        val memberMap: Map<String, String>,
        val addedProviders: Int,
        val addedMembers: Int,
        /** 本机已有、只补了空缺的项，写成给人看的话，比如「阿德」补上了定位。 */
        val filled: List<String>,
        val removedDuplicates: Int,
        val addedMemories: Int = 0,
    ) {
        val nothingNew get() = addedProviders == 0 && addedMembers == 0 && filled.isEmpty() && removedDuplicates == 0 && addedMemories == 0
    }

    fun import(s: AppState, b: ConfigBundle, keepLocalProxy: Boolean): ImportResult {
        val providers = s.providers.toMutableList()
        val pMap = mutableMapOf<String, String>()
        var addedP = 0
        val filled = mutableListOf<String>()
        for (p in b.providers) {
            val i = providers.indexOfFirst { it.id == p.id || sameAccount(it, p) }
            if (i >= 0) {
                val e = providers[i]
                pMap[p.id] = e.id
                val newModels = p.models.filter { m -> e.models.none { it.id == m.id } }
                val fillKey = e.apiKey.isBlank() && p.apiKey.isNotBlank()
                if (newModels.isNotEmpty()) filled += "「${e.name}」多了 ${newModels.size} 个模型"
                if (fillKey) filled += "「${e.name}」补上了 Key"
                if (newModels.isNotEmpty() || fillKey) providers[i] = e.copy(models = e.models + newModels, apiKey = if (fillKey) p.apiKey else e.apiKey)
            } else {
                providers += p
                pMap[p.id] = p.id
                addedP++
            }
        }
        fun serviceOf(id: String) = providers.firstOrNull { it.id == id }
        val members = s.members.toMutableList()
        var addedM = 0
        for (m0 in b.members) {
            val m = m0.copy(providerId = pMap[m0.providerId] ?: m0.providerId)
            val i = members.indexOfFirst { e ->
                e.id == m.id || (e.name.trim() == m.name.trim() && e.modelId.trim() == m.modelId.trim() &&
                    serviceOf(e.providerId)?.let { pe -> serviceOf(m.providerId)?.let { sameService(pe, it) } } != false)
            }
            if (i >= 0) {
                // 同一位成员：只补本机没填的，本机已有的设定不覆盖
                val e = members[i]
                val parts = buildList {
                    if (e.bio.isBlank() && m.bio.isNotBlank()) add("定位")
                    if (e.modelId.isBlank() && m.modelId.isNotBlank()) add("模型")
                    if (e.providerId.isBlank() && m.providerId.isNotBlank()) add("服务商")
                }
                if (parts.isNotEmpty()) {
                    members[i] = e.copy(bio = e.bio.ifBlank { m.bio }, providerId = e.providerId.ifBlank { m.providerId }, modelId = e.modelId.ifBlank { m.modelId })
                    filled += "「${e.name}」补上了${parts.joinToString("、")}"
                }
            } else {
                members += m
                addedM++
            }
        }
        val settings = s.settings.copy(
            // 本机已经设过的不覆盖
            search = if (s.settings.search == SearchSettings()) b.search else s.settings.search,
            imageGen = if (s.settings.imageGen.providerId.isBlank()) b.imageGen.copy(providerId = pMap[b.imageGen.providerId] ?: b.imageGen.providerId) else s.settings.imageGen,
            // 电脑上的本地代理地址在手机上没用
            proxy = if (keepLocalProxy) s.settings.proxy else b.proxy,
        )
        // 长期记忆：内容差不多的不重复加
        val memories = s.memories.toMutableList()
        var addedMem = 0
        for (m in b.memories) {
            if (memories.none { it.id == m.id || Recorder.similar(it.text, m.text) }) { memories += m; addedMem++ }
        }
        val merged = s.copy(
            providers = providers,
            members = members,
            profile = if (s.profile == UserProfile()) b.profile else s.profile,
            settings = settings,
            memories = memories,
        )
        val (clean, mMap, removed) = dedupe(merged)
        return ImportResult(clean, mMap, addedP, addedM, filled, removed, addedMem)
    }

    /** 合并重复的服务商和成员。保留对话里正在用的那一份。返回（新状态，成员编号映射，去掉了几项）。 */
    fun dedupe(s: AppState): Triple<AppState, Map<String, String>, Int> {
        val kept = mutableListOf<ProviderConfig>()
        val pMap = mutableMapOf<String, String>()
        for (p in s.providers) {
            val k = kept.indexOfFirst { sameAccount(it, p) }
            if (k >= 0) {
                pMap[p.id] = kept[k].id
                kept[k] = kept[k].copy(models = mergeModels(kept[k].models, p.models), apiKey = kept[k].apiKey.ifBlank { p.apiKey })
            } else kept += p
        }
        val usage = s.conversations.flatMap { it.memberIds }.groupingBy { it }.eachCount()
        val ms = s.members.map { it.copy(providerId = pMap[it.providerId] ?: it.providerId) }
        fun serviceKey(m: Member) = kept.firstOrNull { it.id == m.providerId }?.let { normUrl(it.baseUrl) } ?: m.providerId
        val groups = ms.groupBy { Triple(it.name.trim(), it.modelId.trim(), serviceKey(it)) }
        val mMap = mutableMapOf<String, String>()
        for (g in groups.values) {
            if (g.size < 2) continue
            val keeper = g.maxBy { usage[it.id] ?: 0 }
            g.filter { it.id != keeper.id }.forEach { mMap[it.id] = keeper.id }
        }
        val keptMembers = ms.filter { it.id !in mMap }
        val removed = (s.providers.size - kept.size) + (s.members.size - keptMembers.size)
        if (removed == 0) return Triple(s, emptyMap(), 0)
        val convs = s.conversations.map { c -> c.copy(memberIds = c.memberIds.map { mMap[it] ?: it }.distinct()) }
        val ig = s.settings.imageGen.let { it.copy(providerId = pMap[it.providerId] ?: it.providerId) }
        return Triple(s.copy(providers = kept, members = keptMembers, conversations = convs, settings = s.settings.copy(imageGen = ig)), mMap, removed)
    }
}
